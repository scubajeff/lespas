package site.leos.apps.lespas.publication

import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.SharedElementCallback
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.Transition
import androidx.viewpager2.widget.ViewPager2
import com.google.android.exoplayer2.ui.PlayerView
import com.google.android.material.transition.MaterialContainerTransform
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.MediaSliderAdapter
import java.io.File

class RemoteMediaFragment: Fragment() {
    private lateinit var window: Window
    private lateinit var slider: ViewPager2
    private lateinit var pAdapter: RemoteMediaAdapter

    private val shareModel: NCShareViewModel by activityViewModels()

    private var previousOrientationSetting = 0
    private var previousNavBarColor = 0

    private var viewReCreated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        this.window = requireActivity().window

        pAdapter = RemoteMediaAdapter(
            "${requireContext().cacheDir}/${getString(R.string.lespas_base_folder_name)}",
            { state-> toggleSystemUI(state) },
            { media, view, type-> shareModel.getPhoto(media, view, type) { startPostponedEnterTransition() }},
            { view-> shareModel.cancelGetPhoto(view) }
        ).apply {
            stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            @Suppress("UNCHECKED_CAST")
            (arguments?.getParcelableArray(REMOTE_MEDIA)!! as Array<NCShareViewModel.RemotePhoto>).run {
                submitList(toMutableList())

                previousOrientationSetting = requireActivity().requestedOrientation
                if (PreferenceManager.getDefaultSharedPreferences(context).getBoolean(context?.getString(R.string.auto_rotate_perf_key), false))
                    requireActivity().requestedOrientation = if (this[0].width > this[0].height) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        }

        sharedElementEnterTransition = MaterialContainerTransform().apply {
            duration = resources.getInteger(android.R.integer.config_mediumAnimTime).toLong()
            scrimColor = Color.TRANSPARENT
            fadeMode = MaterialContainerTransform.FADE_MODE_CROSS
        }.also {
            // Prevent ViewPager from showing content before transition finished, without this, Android 11 will show it right at the beginning
            // Also we can transit to video thumbnail before starting playing it
            it.addListener(object : Transition.TransitionListener {
                var isVideo = false
                override fun onTransitionStart(transition: Transition) {
                    slider.getChildAt(0)?.apply {
                        findViewById<ImageView>(R.id.media)?.let { mediaView->
                            // media imageview in exoplayer item view is always in invisible state
                            if (mediaView.visibility != View.VISIBLE) {
                                isVideo = true
                                findViewById<PlayerView>(R.id.player_view)?.visibility = View.INVISIBLE
                            }
                            else {
                                mediaView.visibility = View.INVISIBLE
                                pAdapter.setAutoStart(true)
                            }
                        }
                    }
                }
                override fun onTransitionEnd(transition: Transition) {
                    (slider.getChildAt(0) as RecyclerView).apply {
                        if (isVideo) (findViewHolderForAdapterPosition(slider.currentItem) as MediaSliderAdapter<*>.VideoViewHolder).startOver()
                        else findViewById<ImageView>(R.id.media)?.visibility = View.VISIBLE
                    }
                }
                override fun onTransitionCancel(transition: Transition) {
                    (slider.getChildAt(0) as RecyclerView).apply {
                        if (isVideo) (findViewHolderForAdapterPosition(slider.currentItem) as MediaSliderAdapter<*>.VideoViewHolder).startOver()
                        else findViewById<ImageView>(R.id.media)?.visibility = View.VISIBLE
                    }
                }
                override fun onTransitionPause(transition: Transition) {}
                override fun onTransitionResume(transition: Transition) {}
            })
        }


        // Adjusting the shared element mapping
        setEnterSharedElementCallback(object : SharedElementCallback() {
            override fun onMapSharedElements(names: MutableList<String>?, sharedElements: MutableMap<String, View>?) {
                try {
                    sharedElements?.put(names?.get(0)!!, slider.getChildAt(0).findViewById(R.id.media))
                } catch (e: IndexOutOfBoundsException) { e.printStackTrace() }
            }
        })

        savedInstanceState?.getParcelable<MediaSliderAdapter.PlayerState>(PLAYER_STATE)?.apply {
            pAdapter.setPlayerState(this)
            pAdapter.setAutoStart(true)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_photoslide, container, false)

        postponeEnterTransition()

        slider = view.findViewById<ViewPager2>(R.id.pager).apply {
            adapter = pAdapter

            // Use reflection to reduce Viewpager2 slide sensitivity, so that PhotoView inside can zoom presently
            val recyclerView = (ViewPager2::class.java.getDeclaredField("mRecyclerView").apply { isAccessible = true }).get(this) as RecyclerView
            (RecyclerView::class.java.getDeclaredField("mTouchSlop")).apply {
                isAccessible = true
                set(recyclerView, (get(recyclerView) as Int) * 4)
            }
        }

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewReCreated = true

        val systemBarBackground = ContextCompat.getColor(requireContext(), R.color.dark_gray_overlay_background)
        (requireActivity() as AppCompatActivity).supportActionBar!!.hide()

        @Suppress("DEPRECATION")
        window.run {
/*
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
        toggleSystemUI(null)
    }

    override fun onStart() {
        super.onStart()
        pAdapter.initializePlayer(requireContext())
    }

    override fun onResume() {
        super.onResume()
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)

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
        super.onDestroy()
    }

    private var visible: Boolean = true
    private val hideHandler = Handler(Looper.getMainLooper())
    private fun toggleSystemUI(state: Boolean?) {
        hideHandler.removeCallbacksAndMessages(null)
        val hide = state?.let { !state } ?: visible
        hideHandler.post(if (hide) hideSystemUI else showSystemUI)
    }

    @Suppress("DEPRECATION")
    private val hideSystemUI = Runnable {
        window.run {
/*
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE
                    // Set the content to appear under the system bars so that the
                    // content doesn't resize when the system bars hide and show.
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    // Hide the nav bar and status bar
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                )
            } else {
                insetsController?.apply {
                    systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsets.Type.systemBars())
                }
            }
*/
            decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE
                // Set the content to appear under the system bars so that the
                // content doesn't resize when the system bars hide and show.
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                // Hide the nav bar and status bar
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
            )
        }

        visible = false
    }

    @Suppress("DEPRECATION")
    private val showSystemUI = Runnable {
        window.run {
/*
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
            else insetsController?.show(WindowInsets.Type.systemBars())
*/
            decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }

        visible = true

        // auto hide
        hideHandler.postDelayed(hideSystemUI, AUTO_HIDE_DELAY_MILLIS)
    }

    class RemoteMediaAdapter(private val cachePath: String, val clickListener: (Boolean?) -> Unit, val imageLoader: (NCShareViewModel.RemotePhoto, ImageView, type: String) -> Unit, val cancelLoader: (View) -> Unit
    ): MediaSliderAdapter<NCShareViewModel.RemotePhoto>(PhotoDiffCallback(), clickListener, imageLoader, cancelLoader) {
        override fun getVideoItem(position: Int): VideoItem = with(getItem(position) as NCShareViewModel.RemotePhoto) {
            VideoItem(Uri.fromFile(File("$cachePath/videos/${path.substringAfterLast('/')}")), mimeType, width, height, fileId)
        }
        override fun getItemTransitionName(position: Int): String = (getItem(position) as NCShareViewModel.RemotePhoto).fileId
        override fun getItemMimeType(position: Int): String = (getItem(position) as NCShareViewModel.RemotePhoto).mimeType
    }

    class PhotoDiffCallback: DiffUtil.ItemCallback<NCShareViewModel.RemotePhoto>() {
        override fun areItemsTheSame(oldItem: NCShareViewModel.RemotePhoto, newItem: NCShareViewModel.RemotePhoto): Boolean = oldItem.fileId == newItem.fileId
        override fun areContentsTheSame(oldItem: NCShareViewModel.RemotePhoto, newItem: NCShareViewModel.RemotePhoto): Boolean = oldItem.fileId == newItem.fileId
    }

    companion object {
        private const val REMOTE_MEDIA = "REMOTE_MEDIA"
        private const val PLAYER_STATE = "PLAYER_STATE"
        private const val AUTO_HIDE_DELAY_MILLIS = 3000L // The number of milliseconds to wait after user interaction before hiding the system UI.

        @JvmStatic
        fun newInstance(media: List<NCShareViewModel.RemotePhoto>) = RemoteMediaFragment().apply { arguments = Bundle().apply { putParcelableArray(REMOTE_MEDIA, media.toTypedArray()) } }
    }
}