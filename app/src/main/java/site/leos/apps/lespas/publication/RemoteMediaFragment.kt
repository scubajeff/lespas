package site.leos.apps.lespas.publication

import android.accounts.AccountManager
import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.transition.Slide
import android.transition.TransitionManager
import android.view.*
import android.widget.Button
import android.widget.ImageView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.core.app.SharedElementCallback
import androidx.core.content.ContextCompat
import androidx.core.view.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.transition.MaterialContainerTransform
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.MediaSliderAdapter
import site.leos.apps.lespas.helper.MediaSliderTransitionListener
import site.leos.apps.lespas.helper.MetaDataDialogFragment
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.sync.DestinationDialogFragment
import site.leos.apps.lespas.sync.SyncAdapter
import kotlin.math.atan2

class RemoteMediaFragment: Fragment() {
    private lateinit var window: Window
    private lateinit var controlsContainer: LinearLayoutCompat
    private lateinit var slider: ViewPager2
    private lateinit var pAdapter: RemoteMediaAdapter

    private val shareModel: NCShareViewModel by activityViewModels()
    private val currentPositionModel: PublicationDetailFragment.CurrentPublicationViewModel by activityViewModels()
    private val destinationModel: DestinationDialogFragment.DestinationViewModel by activityViewModels()

    private var previousOrientationSetting = 0
    //private var previousNavBarColor = 0

    private var viewReCreated = false

    private var acquired = false
    private var albumId = ""

    private lateinit var storagePermissionRequestLauncher: ActivityResultLauncher<String>

    private lateinit var gestureDetector: GestureDetectorCompat

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        albumId = requireArguments().getString(KEY_ALBUM_ID) ?: ""

        pAdapter = RemoteMediaAdapter(
            shareModel.getResourceRoot(),
            { state-> toggleSystemUI(state) },
            { media, view, type-> shareModel.getPhoto(media, view, type) { startPostponedEnterTransition() }},
            { view-> shareModel.cancelGetPhoto(view) }
        ).apply {
            stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            @Suppress("UNCHECKED_CAST")
            (arguments?.getParcelableArray(KEY_REMOTE_MEDIA)!! as Array<NCShareViewModel.RemotePhoto>).run {
                submitList(toMutableList())

                previousOrientationSetting = requireActivity().requestedOrientation
                if (PreferenceManager.getDefaultSharedPreferences(context).getBoolean(context?.getString(R.string.auto_rotate_perf_key), false))
                    requireActivity().requestedOrientation = if (this[0].width > this[0].height) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        }

        // Adjusting the shared element mapping
        setEnterSharedElementCallback(object : SharedElementCallback() {
            override fun onMapSharedElements(names: MutableList<String>?, sharedElements: MutableMap<String, View>?) {
                if (names?.isNotEmpty() == true) slider.getChildAt(0).findViewById<View>(R.id.media)?.apply { sharedElements?.put(names[0], this) }
            }
        })

        savedInstanceState?.getParcelable<MediaSliderAdapter.PlayerState>(PLAYER_STATE)?.apply {
            pAdapter.setPlayerState(this)
            pAdapter.setAutoStart(true)
        }

        @Suppress("DEPRECATION")
        requireActivity().window.decorView.setOnSystemUiVisibilityChangeListener { visibility -> followSystemBar(visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) }

        storagePermissionRequestLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted->
            requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

            if (isGranted) saveMedia()
        }

        // Detect swipe up gesture and show bottom controls
        gestureDetector = GestureDetectorCompat(requireContext(), object: GestureDetector.SimpleOnGestureListener() {
            // Overwrite onFling rather than onScroll, since onScroll will be called multiple times during one scroll
            override fun onFling(e1: MotionEvent?, e2: MotionEvent?, velocityX: Float, velocityY: Float): Boolean {
                if (e1 != null && e2 != null) {
                    when(Math.toDegrees(atan2(e1.y - e2.y, e2.x - e1.x).toDouble())) {
                        in 55.0..125.0-> {
                            hideHandler.post(showSystemUI)
                            return true
                        }
                        in -125.0..-55.0-> {
                            hideHandler.post(hideSystemUI)
                            return true
                        }
                    }
                }

                return super.onFling(e1, e2, velocityX, velocityY)
            }
        })

        this.window = requireActivity().window
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_remote_media, container, false)

        postponeEnterTransition()

        slider = view.findViewById<ViewPager2>(R.id.pager).apply {
            adapter = pAdapter
            arguments?.getInt(KEY_SCROLL_TO)?.let {
                setCurrentItem(it, false)
                currentPositionModel.setCurrentPosition(it)
            }

            // Use reflection to reduce Viewpager2 slide sensitivity, so that PhotoView inside can zoom presently
            val recyclerView = (ViewPager2::class.java.getDeclaredField("mRecyclerView").apply { isAccessible = true }).get(this) as RecyclerView
            (RecyclerView::class.java.getDeclaredField("mTouchSlop")).apply {
                isAccessible = true
                set(recyclerView, (get(recyclerView) as Int) * 4)
            }

            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    super.onPageSelected(position)
                    currentPositionModel.setCurrentPosition(position)
                }
            })

            // Detect swipe up gesture and show bottom controls
            (getChildAt(0) as RecyclerView).addOnItemTouchListener(object: RecyclerView.SimpleOnItemTouchListener() {
                override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                    gestureDetector.onTouchEvent(e)
                    return super.onInterceptTouchEvent(rv, e)
                }
            })
        }

        controlsContainer = view.findViewById<LinearLayoutCompat>(R.id.bottom_controls_container).apply {
            ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets->
                @Suppress("DEPRECATION")
                v.updatePadding(bottom = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) window.decorView.rootWindowInsets.stableInsetBottom else with(window.windowManager.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars())) { bottom - top })
                insets
            }
        }
        view.findViewById<Button>(R.id.download_button).run {
            setOnTouchListener(delayHideTouchListener)
            setOnClickListener {
                hideHandler.post(hideSystemUI)
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_NOSENSOR

                    storagePermissionRequestLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
                else saveMedia()
            }
        }
        view.findViewById<Button>(R.id.lespas_button).run {
            setOnTouchListener(delayHideTouchListener)
            setOnClickListener {
                hideHandler.post(hideSystemUI)
                if (parentFragmentManager.findFragmentByTag(TAG_DESTINATION_DIALOG) == null)
                    DestinationDialogFragment.newInstance(arrayListOf(pAdapter.currentList[currentPositionModel.getCurrentPositionValue()]), albumId).show(parentFragmentManager, TAG_DESTINATION_DIALOG)
            }
        }
        view.findViewById<Button>(R.id.info_button).run {
            setOnTouchListener(delayHideTouchListener)
            setOnClickListener {
                hideHandler.post(hideSystemUI)
                if (parentFragmentManager.findFragmentByTag(TAG_INFO_DIALOG) == null) {
                    MetaDataDialogFragment.newInstance(pAdapter.currentList[currentPositionModel.getCurrentPositionValue()]).show(parentFragmentManager, TAG_INFO_DIALOG)
                }
            }
        }

        sharedElementEnterTransition = MaterialContainerTransform().apply {
            duration = resources.getInteger(android.R.integer.config_mediumAnimTime).toLong()
            scrimColor = Color.TRANSPARENT
            fadeMode = MaterialContainerTransform.FADE_MODE_CROSS
        }.also {
            // Prevent ViewPager from showing content before transition finished, without this, Android 11 will show it right at the beginning
            // Also we can transit to video thumbnail before starting playing it
            it.addListener(MediaSliderTransitionListener(slider))
        }

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewReCreated = true

        (requireActivity() as AppCompatActivity).supportActionBar!!.hide()

        @Suppress("DEPRECATION")
        window.run {
/*
            val systemBarBackground = ContextCompat.getColor(requireContext(), R.color.dark_gray_overlay_background)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                previousNavBarColor = navigationBarColor
                navigationBarColor = systemBarBackground
                statusBarColor = systemBarBackground
                insetsController?.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                setDecorFitsSystemWindows(false)
            } else {
                addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
                addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
            }
*/
            statusBarColor = Color.TRANSPARENT
            addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
            addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        }

        // When DestinationDialog returns
        destinationModel.getDestination().observe(viewLifecycleOwner, { album ->
            album?.let {
                shareModel.acquireMediaFromShare(pAdapter.currentList[currentPositionModel.getCurrentPositionValue()], it)

                // If destination is user's own album, trigger sync with server
                if (album.id != PublicationDetailFragment.JOINT_ALBUM_ID) acquired = true
            }
        })
    }

    override fun onStart() {
        super.onStart()
        pAdapter.initializePlayer(requireContext(), shareModel.getCallFactory())
    }

    override fun onResume() {
        super.onResume()
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)

        // Get into immersive mode
        Tools.goImmersive(window)

        (slider.getChildAt(0) as RecyclerView).findViewHolderForAdapterPosition(slider.currentItem).apply {
            if (!viewReCreated && pAdapter.currentList[slider.currentItem].mimeType.startsWith("video")) {
                (this as MediaSliderAdapter<*>.VideoViewHolder).apply {
                    pAdapter.setAutoStart(true)
                    resume()
                }
            }
        }
    }

    override fun onPause() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)

        (slider.getChildAt(0) as RecyclerView).findViewHolderForAdapterPosition(slider.currentItem).apply {
            if (this is MediaSliderAdapter<*>.VideoViewHolder) this.pause()
        }

        viewReCreated = false

        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putParcelable(PLAYER_STATE, pAdapter.getPlayerState())
    }

    override fun onStop() {
        pAdapter.cleanUp()
        super.onStop()
    }

    @Suppress("DEPRECATION")
    override fun onDestroy() {
        // BACK TO NORMAL UI
        hideHandler.removeCallbacksAndMessages(null)

        requireActivity().window.run {
/*
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
                clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)

                decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
                //decorView.setOnSystemUiVisibilityChangeListener(null)
            } else {
                insetsController?.apply {
                    show(WindowInsets.Type.systemBars())
                    systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_BARS_BY_TOUCH
                }
                statusBarColor = resources.getColor(R.color.color_primary)
                navigationBarColor = previousNavBarColor
                setDecorFitsSystemWindows(true)
                //decorView.setOnApplyWindowInsetsListener(null)
            }
*/
            clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
            clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
            statusBarColor = resources.getColor(R.color.color_primary)
            decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            decorView.setOnSystemUiVisibilityChangeListener(null)
        }

        (requireActivity() as AppCompatActivity).run {
            supportActionBar!!.show()
            requestedOrientation = previousOrientationSetting
        }

        // If new acquisition happened, sync with server at once
        if (acquired) ContentResolver.requestSync(AccountManager.get(requireContext()).getAccountsByType(getString(R.string.account_type_nc))[0], getString(R.string.sync_authority), Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putInt(SyncAdapter.ACTION, SyncAdapter.SYNC_REMOTE_CHANGES)
        })

        super.onDestroy()
    }

    private val hideHandler = Handler(Looper.getMainLooper())
    private fun toggleSystemUI(state: Boolean?) {
        hideHandler.removeCallbacksAndMessages(null)
        hideHandler.post(if (state ?: !controlsContainer.isVisible) showSystemUI else hideSystemUI)
    }

    private val hideSystemUI = Runnable { Tools.goImmersive(window) }
    @Suppress("DEPRECATION")
    private val showSystemUI = Runnable {
        window.run {
/*
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
            else insetsController?.show(WindowInsets.Type.systemBars())
*/
            decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }

        // auto hide
        hideHandler.postDelayed(hideSystemUI, AUTO_HIDE_DELAY_MILLIS)
    }

    // Delay hiding the system UI while interacting with controls, preventing the jarring behavior of controls going away
    @SuppressLint("ClickableViewAccessibility")
    private val delayHideTouchListener = View.OnTouchListener { _, _ ->
        hideHandler.removeCallbacks(hideSystemUI)
        hideHandler.postDelayed(hideSystemUI, AUTO_HIDE_DELAY_MILLIS)
        false
    }

    private fun followSystemBar(show: Boolean) {
        // TODO: Nasty exception handling here, but Android doesn't provide method to unregister System UI/Insets changes listener
        try {
            TransitionManager.beginDelayedTransition(controlsContainer, if (Build.VERSION.SDK_INT > Build.VERSION_CODES.R) android.transition.Fade() else Slide(Gravity.BOTTOM).apply { duration = 50 })
            controlsContainer.visibility = if (show) View.VISIBLE else View.GONE
        } catch (e: UninitializedPropertyAccessException) { e.printStackTrace() }

        // auto hide
        if (show) hideHandler.postDelayed(hideSystemUI, AUTO_HIDE_DELAY_MILLIS)
    }

    private fun saveMedia() {
        pAdapter.currentList[currentPositionModel.getCurrentPositionValue()].apply {
            shareModel.savePhoto(requireContext(), this)
            hideHandler.postDelayed({
                Snackbar.make(window.decorView.rootView, getString(R.string.downloading_message, this.path.substringAfterLast('/')), Snackbar.LENGTH_LONG)
                    .setAnimationMode(Snackbar.ANIMATION_MODE_FADE)
                    .setBackgroundTint(resources.getColor(R.color.color_primary, null))
                    .setTextColor(resources.getColor(R.color.color_text_light, null))
                    .show()
            }, 400L)
        }
    }

    class RemoteMediaAdapter(private val basePath: String, val clickListener: (Boolean?) -> Unit, val imageLoader: (NCShareViewModel.RemotePhoto, ImageView, type: String) -> Unit, val cancelLoader: (View) -> Unit
    ): MediaSliderAdapter<NCShareViewModel.RemotePhoto>(PhotoDiffCallback(), clickListener, imageLoader, cancelLoader) {
        override fun getVideoItem(position: Int): VideoItem = with(getItem(position) as NCShareViewModel.RemotePhoto) { VideoItem(Uri.parse("$basePath$path"), mimeType, width, height, fileId) }
        override fun getItemTransitionName(position: Int): String  = (getItem(position) as NCShareViewModel.RemotePhoto).fileId
        override fun getItemMimeType(position: Int): String = (getItem(position) as NCShareViewModel.RemotePhoto).mimeType
    }

    class PhotoDiffCallback: DiffUtil.ItemCallback<NCShareViewModel.RemotePhoto>() {
        override fun areItemsTheSame(oldItem: NCShareViewModel.RemotePhoto, newItem: NCShareViewModel.RemotePhoto): Boolean = oldItem.fileId == newItem.fileId
        override fun areContentsTheSame(oldItem: NCShareViewModel.RemotePhoto, newItem: NCShareViewModel.RemotePhoto): Boolean = oldItem.fileId == newItem.fileId
    }

    companion object {
        private const val KEY_REMOTE_MEDIA = "REMOTE_MEDIA"
        private const val KEY_SCROLL_TO = "SCROLL_TO"
        private const val KEY_ALBUM_ID = "KEY_ALBUM_ID"
        private const val PLAYER_STATE = "PLAYER_STATE"
        private const val AUTO_HIDE_DELAY_MILLIS = 3000L // The number of milliseconds to wait after user interaction before hiding the system UI.

        private const val TAG_DESTINATION_DIALOG = "REMOTEMEDIA_DESTINATION_DIALOG"
        private const val TAG_INFO_DIALOG = "REMOTEMEDIA_INFO_DIALOG"

        @JvmStatic
        fun newInstance(media: List<NCShareViewModel.RemotePhoto>, position: Int, albumId: String) = RemoteMediaFragment().apply {
            arguments = Bundle().apply {
                putParcelableArray(KEY_REMOTE_MEDIA, media.toTypedArray())
                putInt(KEY_SCROLL_TO, position)
                putString(KEY_ALBUM_ID, albumId)
            }
        }
    }
}