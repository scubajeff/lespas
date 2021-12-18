package site.leos.apps.lespas.photo

import android.annotation.SuppressLint
import android.content.*
import android.content.pm.ActivityInfo
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.transition.Slide
import android.transition.TransitionManager
import android.view.*
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.ActionBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.SharedElementCallback
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.Fade
import androidx.viewpager2.widget.ViewPager2
import androidx.work.*
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.transition.MaterialContainerTransform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import site.leos.apps.lespas.MainActivity
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.Album
import site.leos.apps.lespas.album.AlbumViewModel
import site.leos.apps.lespas.album.Cover
import site.leos.apps.lespas.helper.*
import site.leos.apps.lespas.settings.SettingsFragment
import site.leos.apps.lespas.sync.AcquiringDialogFragment
import site.leos.apps.lespas.sync.ActionViewModel
import site.leos.apps.lespas.sync.ShareReceiverActivity
import java.io.File
import java.util.*
import kotlin.math.atan2

class PhotoSlideFragment : Fragment(), MainActivity.OnWindowFocusChangedListener {
    private lateinit var album: Album

    private lateinit var window: Window
    //private var previousNavBarColor = 0
    private var previousOrientationSetting = 0

    private lateinit var controlsContainer: LinearLayout
    private lateinit var removeButton: Button
    private lateinit var coverButton: Button
    private lateinit var setAsButton: Button
    private lateinit var snapseedButton: Button

    private lateinit var slider: ViewPager2
    private lateinit var pAdapter: PhotoSlideAdapter

    private val albumModel: AlbumViewModel by activityViewModels()
    private val actionModel: ActionViewModel by viewModels()
    private val imageLoaderModel: ImageLoaderViewModel by activityViewModels()
    private val currentPhotoModel: CurrentPhotoViewModel by activityViewModels()
    private val playerViewModel: VideoPlayerViewModel by viewModels { VideoPlayerViewModelFactory(requireActivity().application, null) }

    private var autoRotate = false
    private var stripExif = "1"

    private lateinit var snapseedCatcher: BroadcastReceiver
    private lateinit var snapseedOutputObserver: ContentObserver

    private lateinit var removeOriginalBroadcastReceiver: RemoveOriginalBroadcastReceiver
    private lateinit var gestureDetector: GestureDetectorCompat

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        album = arguments?.getParcelable(KEY_ALBUM)!!

        pAdapter = PhotoSlideAdapter(
            Tools.getDisplayWidth(requireActivity().windowManager),
            Tools.getLocalRoot(requireContext()),
            playerViewModel,
            { state-> toggleSystemUI(state) },
            { photo, imageView, type -> imageLoaderModel.loadPhoto(photo, imageView, type) { startPostponedEnterTransition() }},
            { view -> imageLoaderModel.cancelLoading(view as ImageView) }
        ).apply { stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY }

        // Adjusting the shared element mapping
        setEnterSharedElementCallback(object : SharedElementCallback() {
            override fun onMapSharedElements(names: MutableList<String>?, sharedElements: MutableMap<String, View>?) {
                if (names?.isNotEmpty() == true) slider.getChildAt(0).findViewById<View>(R.id.media)?.apply { sharedElements?.put(names[0], this) }
            }
        })

        previousOrientationSetting = requireActivity().requestedOrientation
        with(PreferenceManager.getDefaultSharedPreferences(requireContext())) {
            autoRotate = getBoolean(context?.getString(R.string.auto_rotate_perf_key), false)
            stripExif = getString(getString(R.string.strip_exif_pref_key), getString(R.string.strip_on_value)) ?: "0"
        }

        // Broadcast receiver listening on share destination
        snapseedCatcher = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent!!.getParcelableExtra<ComponentName>(Intent.EXTRA_CHOSEN_COMPONENT)?.packageName!!.substringAfterLast('.') == "snapseed") {
                    // Register content observer if integration with snapseed setting is on
                    if (PreferenceManager.getDefaultSharedPreferences(context).getBoolean(getString(R.string.snapseed_pref_key), false)) {
                        context!!.contentResolver.apply {
                            unregisterContentObserver(snapseedOutputObserver)
                            registerContentObserver(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) else MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, snapseedOutputObserver)
                        }
                    }
                }
            }
        }
        context?.registerReceiver(snapseedCatcher, IntentFilter(CHOOSER_SPY_ACTION))

        // Content observer looking for Snapseed output
        snapseedOutputObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            private val workerName = "${PhotoSlideFragment::class.java.canonicalName}.SNAPSEED_WORKER"
            private var lastId = ""
            private lateinit var snapseedWorker: OneTimeWorkRequest

            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)

                // ContentObserver got called twice, once for itself, once for it's descendant, all with same last path segment
                if (uri?.lastPathSegment!! != lastId) {
                    lastId = uri.lastPathSegment!!

                    snapseedWorker = OneTimeWorkRequestBuilder<SnapseedResultWorker>().setInputData(
                        workDataOf(SnapseedResultWorker.KEY_IMAGE_URI to uri.toString(), SnapseedResultWorker.KEY_SHARED_PHOTO to pAdapter.getPhotoAt(slider.currentItem).id, SnapseedResultWorker.KEY_ALBUM to album.id)).build()
                    WorkManager.getInstance(requireContext()).enqueueUniqueWork(workerName, ExistingWorkPolicy.KEEP, snapseedWorker)

                    WorkManager.getInstance(requireContext()).getWorkInfosForUniqueWorkLiveData(workerName).observe(parentFragmentManager.findFragmentById(R.id.container_root)!!, { workInfo->
                        if (workInfo != null) {
                            if (PreferenceManager.getDefaultSharedPreferences(requireContext()).getBoolean(requireContext().getString(R.string.snapseed_replace_pref_key), false)) {
                                // When replacing original with Snapseed result, refresh image cache of all size
                                imageLoaderModel.invalid(pAdapter.getPhotoAt(slider.currentItem).id)
                            }
                        }
                    })

                    requireContext().contentResolver.unregisterContentObserver(this)
                }
            }
        }

        // Listener for our UI controls to show/hide with System UI
        this.window = requireActivity().window
        playerViewModel.setWindow(this.window)
        @Suppress("DEPRECATION")
        window.decorView.setOnSystemUiVisibilityChangeListener { visibility -> followSystemBar(visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) }
/*
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            window.decorView.setOnSystemUiVisibilityChangeListener { visibility ->
                followSystemBar(visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0, window.decorView.rootWindowInsets.stableInsetBottom)
            }
        } else {
            window.decorView.setOnApplyWindowInsetsListener { _, insets ->
                followSystemBar(insets.isVisible(WindowInsets.Type.navigationBars()), insets.getInsets(WindowInsets.Type.navigationBars()).bottom)
                insets
            }
        }
*/
        removeOriginalBroadcastReceiver = RemoveOriginalBroadcastReceiver { if (it && pAdapter.getPhotoAt(slider.currentItem).id != album.cover) removePhoto() }

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

        postponeEnterTransition()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? = inflater.inflate(R.layout.fragment_photoslide, container, false)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        slider = view.findViewById<ViewPager2>(R.id.pager).apply {
            adapter = pAdapter

            // Use reflection to reduce Viewpager2 slide sensitivity, so that PhotoView inside can zoom presently
            val recyclerView = (ViewPager2::class.java.getDeclaredField("mRecyclerView").apply{ isAccessible = true }).get(this) as RecyclerView
            (RecyclerView::class.java.getDeclaredField("mTouchSlop")).apply {
                isAccessible = true
                set(recyclerView, (get(recyclerView) as Int) * 4)
            }

            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageScrollStateChanged(state: Int) {
                    super.onPageScrollStateChanged(state)
                    if (state == ViewPager2.SCROLL_STATE_SETTLING) hideHandler.post(hideSystemUI)
                }

                override fun onPageSelected(position: Int) {
                    super.onPageSelected(position)

                    pAdapter.getPhotoAt(position).run {
                        currentPhotoModel.setCurrentPosition(position + 1)
                        if (autoRotate) requireActivity().requestedOrientation = if (this.width > this.height) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

                        // Can't delete cover
                        removeButton.isEnabled = this.id != album.cover
                        with(!(Tools.isMediaPlayable(this.mimeType))) {
                            // Can't set video as avatar
                            setAsButton.isEnabled = this
                            // Can't Snapseed video
                            snapseedButton.isEnabled = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(getString(R.string.snapseed_pref_key), false) && this
                        }
                    }
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

        slider.doOnLayout {
            // Get into immersive mode
            Tools.goImmersive(window, savedInstanceState == null)
        }

        sharedElementEnterTransition = MaterialContainerTransform().apply {
            duration = resources.getInteger(android.R.integer.config_mediumAnimTime).toLong()
            scrimColor = Color.TRANSPARENT
            fadeMode = MaterialContainerTransform.FADE_MODE_CROSS
        }.also {
            // Prevent ViewPager from showing content before transition finished, without this, Android 11 will show it right at the beginning
            // Also we can transit to video thumbnail before player start playing
            it.addListener(MediaSliderTransitionListener(slider))
        }

        // Controls
        controlsContainer = view.findViewById<LinearLayout>(R.id.bottom_controls_container).apply {
            ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets->
                @Suppress("DEPRECATION")
                v.updatePadding(bottom = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) window.decorView.rootWindowInsets.stableInsetBottom else with(window.windowManager.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars())) { bottom - top })
                insets
            }
        }
        removeButton = view.findViewById(R.id.remove_button)
        coverButton = view.findViewById(R.id.cover_button)
        setAsButton = view.findViewById(R.id.set_as_button)
        snapseedButton = view.findViewById(R.id.snapseed_button)

        coverButton.run {
            setOnTouchListener(delayHideTouchListener)
            setOnClickListener {
                hideHandler.post(hideSystemUI)

                val currentMedia = pAdapter.getPhotoAt(slider.currentItem)
                if (Tools.isMediaPlayable(currentMedia.mimeType)) {
                    albumModel.setCover(album.id, Cover(currentMedia.id, 0, currentMedia.width, currentMedia.height))
                    // If album has not been uploaded yet, update the cover id in action table too
                    actionModel.updateCover(album.id, currentMedia.id)
                    showCoverAppliedStatus(true)
                } else {
                    exitTransition = Fade().apply { duration = 80 }
                    parentFragmentManager.beginTransaction().setReorderingAllowed(true).add(R.id.container_overlay, CoverSettingFragment.newInstance(album.id, currentMedia), CoverSettingFragment::class.java.canonicalName).addToBackStack(null).commit()
                }
            }
        }
        view.findViewById<Button>(R.id.share_button).run {
            setOnTouchListener(delayHideTouchListener)
            setOnClickListener {
                if (stripExif == getString(R.string.strip_ask_value)) {
                    hideHandler.post(hideSystemUI)

                    if (Tools.hasExif(pAdapter.getPhotoAt(slider.currentItem).mimeType)) {
                        if (parentFragmentManager.findFragmentByTag(CONFIRM_DIALOG) == null) YesNoDialogFragment.newInstance(getString(R.string.strip_exif_msg, getString(R.string.strip_exif_title)), STRIP_REQUEST_KEY).show(parentFragmentManager, CONFIRM_DIALOG)
                    } else shareOut(false)
                }
                else shareOut(stripExif == getString(R.string.strip_on_value))
            }
        }
        setAsButton.run {
            setOnTouchListener(delayHideTouchListener)
            setOnClickListener {
                // TODO should call with strip true??
                prepareShares(pAdapter.getPhotoAt(slider.currentItem), false)?.let {
                    startActivity(Intent.createChooser(Intent().apply {
                        action = Intent.ACTION_ATTACH_DATA
                        setDataAndType(it.first, it.second)
                        putExtra("mimeType", it.second)
                        //clipData = ClipData.newUri(requireActivity().contentResolver, "", it.first)
                        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    }, null))
                }
            }
        }
        view.findViewById<Button>(R.id.info_button).run {
            setOnTouchListener(delayHideTouchListener)
            setOnClickListener {
                hideHandler.post(hideSystemUI)

                if (parentFragmentManager.findFragmentByTag(INFO_DIALOG) == null) MetaDataDialogFragment.newInstance(pAdapter.getPhotoAt(slider.currentItem)).show(parentFragmentManager, INFO_DIALOG)
            }
        }
        snapseedButton.run {
            setCompoundDrawablesWithIntrinsicBounds(null, ContextCompat.getDrawable(requireContext(), if (PreferenceManager.getDefaultSharedPreferences(requireContext()).getBoolean(getString(R.string.snapseed_replace_pref_key), false)) R.drawable.ic_baseline_snapseed_24 else R.drawable.ic_baseline_snapseed_add_24), null, null)
            setOnTouchListener(delayHideTouchListener)
            setOnClickListener { view->
                prepareShares(pAdapter.getPhotoAt(slider.currentItem), false)?.let {
                    startActivity(Intent().apply {
                        action = Intent.ACTION_SEND
                        data = it.first
                        type = it.second
                        putExtra(Intent.EXTRA_STREAM, it.first)
                        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                        setClassName(SettingsFragment.SNAPSEED_PACKAGE_NAME, SettingsFragment.SNAPSEED_MAIN_ACTIVITY_CLASS_NAME)
                    })

                    // Send broadcast just like system share does when user chooses Snapseed, so that PhotoSliderFragment can catch editing result
                    view.context.sendBroadcast(Intent().apply {
                        action = CHOOSER_SPY_ACTION
                        putExtra(Intent.EXTRA_CHOSEN_COMPONENT, ComponentName(SettingsFragment.SNAPSEED_PACKAGE_NAME, SettingsFragment.SNAPSEED_MAIN_ACTIVITY_CLASS_NAME))
                    })
                }
            }
        }
        removeButton.run {
            setOnTouchListener(delayHideTouchListener)
            setOnClickListener {
                hideHandler.post(hideSystemUI)

                if (parentFragmentManager.findFragmentByTag(REMOVE_DIALOG) == null)
                    ConfirmDialogFragment.newInstance(getString(R.string.confirm_delete), getString(R.string.yes_delete), true, DELETE_REQUEST_KEY).show(parentFragmentManager, REMOVE_DIALOG)
            }
        }

        albumModel.getAllPhotoInAlbum(album.id).observe(viewLifecycleOwner, { photos->
            pAdapter.setPhotos(photos, album.sortOrder)
            slider.setCurrentItem(currentPhotoModel.getCurrentPosition() - 1, false)
        })

        currentPhotoModel.getCoverAppliedStatus().observe(viewLifecycleOwner, { appliedStatus ->
            showCoverAppliedStatus(appliedStatus == true)

            // Clear transition when coming back from CoverSettingFragment
            exitTransition = null
        })

        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(removeOriginalBroadcastReceiver, IntentFilter(AcquiringDialogFragment.BROADCAST_REMOVE_ORIGINAL))

        // Remove photo confirm dialog result handler
        parentFragmentManager.setFragmentResultListener(ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY, viewLifecycleOwner) { key, bundle ->
            if (key == ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY) {
                when(bundle.getString(ConfirmDialogFragment.INDIVIDUAL_REQUEST_KEY)) {
                    DELETE_REQUEST_KEY -> if (bundle.getBoolean(ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY, false)) removePhoto()
                    STRIP_REQUEST_KEY -> shareOut(bundle.getBoolean(ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY, false))
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        if (!hasFocus) playerViewModel.pause(Uri.EMPTY)
    }

    override fun onDestroyView() {
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(removeOriginalBroadcastReceiver)

        super.onDestroyView()
    }

    override fun onDestroy() {
        // BACK TO NORMAL UI
        hideHandler.removeCallbacksAndMessages(null)

        @Suppress("DEPRECATION")
        requireActivity().window.run {
/*
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
                decorView.setOnSystemUiVisibilityChangeListener(null)
            } else {
                insetsController?.apply {
                    show(WindowInsets.Type.systemBars())
                    systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_BARS_BY_TOUCH
                }
                statusBarColor = resources.getColor(R.color.color_primary)
                setDecorFitsSystemWindows(true)
                decorView.setOnApplyWindowInsetsListener(null)
            }
*/
            clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
            clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
            //statusBarColor = resources.getColor(R.color.color_primary)
            decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            decorView.setOnSystemUiVisibilityChangeListener(null)
            //navigationBarColor = previousNavBarColor
        }

        (requireActivity() as AppCompatActivity).run {
            supportActionBar?.run {
                displayOptions = ActionBar.DISPLAY_HOME_AS_UP or ActionBar.DISPLAY_SHOW_TITLE
                setBackgroundDrawable(ColorDrawable(ContextCompat.getColor(requireContext(), R.color.color_primary)))
            }
            requestedOrientation = previousOrientationSetting
        }

        requireContext().apply {
            unregisterReceiver(snapseedCatcher)
            contentResolver.unregisterContentObserver(snapseedOutputObserver)
        }

        super.onDestroy()
    }

    // Toggle visibility of bottom controls and system decoView
    private val hideHandler = Handler(Looper.getMainLooper())
    private fun toggleSystemUI(state: Boolean?) {
        hideHandler.removeCallbacksAndMessages(null)
        hideHandler.post(if (state ?: !controlsContainer.isVisible) showSystemUI else hideSystemUI)
    }

    private val hideSystemUI = Runnable { Tools.goImmersive(window) }
    private val showSystemUI = Runnable {
        @Suppress("DEPRECATION")
/*
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R)
        // Shows the system bars by removing all the flags except for the ones that make the content appear under the system bars.
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        else window.insetsController?.show(WindowInsets.Type.systemBars())
*/
        // Shows the system bars by removing all the flags except for the ones that make the content appear under the system bars.
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)

        // trigger auto hide
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
        // Wipe ActionBar
        (requireActivity() as AppCompatActivity).supportActionBar?.run {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            displayOptions = 0
        }

        // TODO: Nasty exception handling here, but Android doesn't provide method to unregister System UI/Insets changes listener
        try {
            TransitionManager.beginDelayedTransition(controlsContainer, if (Build.VERSION.SDK_INT > Build.VERSION_CODES.R) android.transition.Fade() else Slide(Gravity.BOTTOM).apply { duration = 50 })
            controlsContainer.visibility = if (show) View.VISIBLE else View.GONE
        } catch (e: UninitializedPropertyAccessException) { e.printStackTrace() }

        // auto hide
        if (show) hideHandler.postDelayed(hideSystemUI, AUTO_HIDE_DELAY_MILLIS)
    }

    private fun showCoverAppliedStatus(appliedStatus: Boolean) {
        Snackbar.make(window.decorView.rootView, getString(if (appliedStatus) R.string.toast_cover_applied else R.string.toast_cover_set_canceled), Snackbar.LENGTH_SHORT)
            .setAnimationMode(Snackbar.ANIMATION_MODE_FADE)
            //.setAnchorView(window.decorView.rootView)
            .setBackgroundTint(ContextCompat.getColor(requireContext(), R.color.color_primary))
            .setTextColor(ContextCompat.getColor(requireContext(), R.color.color_text_light))
/*
            .addCallback(object: Snackbar.Callback() {
                override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                    super.onDismissed(transientBottomBar, event)
                    hideHandler.post(showSystemUI)
                }
            })
*/
            .show()
    }

    private fun prepareShares(photo: Photo, strip: Boolean): Pair<Uri, String>? {
        try {
            // Synced file is named after id, not yet synced file is named after file's name
            val sourceFile = File(Tools.getLocalRoot(requireContext()), if (photo.eTag.isNotEmpty()) photo.id else photo.name)
            val destFile = File("${requireContext().cacheDir}${MainActivity.TEMP_CACHE_FOLDER}", if (strip) "${UUID.randomUUID()}.${photo.name.substringAfterLast('.')}" else photo.name)

            // Copy the file from fileDir/id to cacheDir/name, strip EXIF base on setting
            val mimeType = if (strip && Tools.hasExif(photo.mimeType)) {
                BitmapFactory.decodeFile(sourceFile.canonicalPath)?.compress(Bitmap.CompressFormat.JPEG, 95, destFile.outputStream())
                "image/jpeg"
            } else {
                sourceFile.copyTo(destFile, true, 4096)
                photo.mimeType
            }
            val uri = FileProvider.getUriForFile(requireContext(), getString(R.string.file_authority), destFile)

            return Pair(uri, mimeType)
        } catch (e: Exception) { return null }
    }

    private fun shareOut(strip: Boolean) {
        val handler = Handler(Looper.getMainLooper())
        val waitingMsg = Tools.getPreparingSharesSnackBar(slider, strip, null)

        lifecycleScope.launch(Dispatchers.IO) {
            // Temporarily prevent screen rotation
            if (!autoRotate) requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED

            // Show a SnackBar if it takes too long (more than 500ms) preparing shares
            withContext(Dispatchers.Main) {
                handler.removeCallbacksAndMessages(null)
                handler.postDelayed({ waitingMsg.show() }, 500)
            }

            with(pAdapter.getPhotoAt(slider.currentItem)) {
                prepareShares(this, strip)?.let {
                    withContext(Dispatchers.Main) {
                        // Dismiss snackbar before showing system share chooser, avoid unpleasant screen flicker
                        if (waitingMsg.isShownOrQueued) waitingMsg.dismiss()

                        // Call system share chooser
                        startActivity(Intent.createChooser(Intent().apply {
                            action = Intent.ACTION_SEND
                            type = it.second
                            putExtra(Intent.EXTRA_STREAM, it.first)
                            clipData = ClipData.newUri(requireContext().contentResolver, "", it.first)
                            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                            putExtra(ShareReceiverActivity.KEY_SHOW_REMOVE_OPTION, true)
                        }, null))
                    }
                }
            }
        }.invokeOnCompletion {
            // Dismiss waiting SnackBar
            handler.removeCallbacksAndMessages(null)
            if (waitingMsg.isShownOrQueued) waitingMsg.dismiss()

            if (!autoRotate) requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun removePhoto() { actionModel.deletePhotos(listOf(pAdapter.getPhotoAt(slider.currentItem)), album.name) }

    class PhotoSlideAdapter(
        displayWidth: Int, private val rootPath: String, playerViewModel: VideoPlayerViewModel,
        clickListener: (Boolean?) -> Unit, imageLoader: (Photo, ImageView, String) -> Unit, cancelLoader: (View) -> Unit
    ): SeamlessMediaSliderAdapter<Photo>(displayWidth, PhotoDiffCallback(), playerViewModel, clickListener, imageLoader, cancelLoader) {
        override fun getVideoItem(position: Int): VideoItem = with(getItem(position) as Photo) {
            var fileName = "$rootPath/${id}"
            if (!(File(fileName).exists())) fileName = "$rootPath/${name}"
            VideoItem(Uri.parse("file:///$fileName"), mimeType, width, height, id)
        }
        override fun getItemTransitionName(position: Int): String = (getItem(position) as Photo).id
        override fun getItemMimeType(position: Int): String = (getItem(position) as Photo).mimeType

        fun setPhotos(collection: List<Photo>, sortOrder: Int) {
            val photos = when(sortOrder) {
                Album.BY_DATE_TAKEN_ASC-> collection.sortedWith(compareBy { it.dateTaken })
                Album.BY_DATE_TAKEN_DESC-> collection.sortedWith(compareByDescending { it.dateTaken })
                Album.BY_DATE_MODIFIED_ASC-> collection.sortedWith(compareBy { it.lastModified })
                Album.BY_DATE_MODIFIED_DESC-> collection.sortedWith(compareByDescending { it.lastModified })
                Album.BY_NAME_ASC-> collection.sortedWith(compareBy { it.name })
                Album.BY_NAME_DESC-> collection.sortedWith(compareByDescending { it.name })
                else-> collection
            }

            submitList(photos.toMutableList())
        }

        fun getPhotoAt(position: Int): Photo = currentList[position] as Photo
    }

    class PhotoDiffCallback: DiffUtil.ItemCallback<Photo>() {
        override fun areItemsTheSame(oldItem: Photo, newItem: Photo): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Photo, newItem: Photo): Boolean = oldItem.lastModified == newItem.lastModified && oldItem.name == newItem.name
    }

    class CurrentPhotoViewModel : ViewModel() {
        // AlbumDetail fragment grid item positions shared with AlbumDetailFragment
        private var currentPosition = 0
        private var lastPosition = 0
        fun setCurrentPosition(position: Int) { currentPosition = position }
        fun getCurrentPosition(): Int = currentPosition
        fun setLastPosition(position: Int) { lastPosition = position }
        fun getLastPosition(): Int = lastPosition

        // Cover applied status shared with CoverSettingFragment
        private val coverAppliedStatus = SingleLiveEvent<Boolean>()
        fun coverApplied(applied: Boolean) { coverAppliedStatus.value = applied}
        fun getCoverAppliedStatus(): SingleLiveEvent<Boolean> = coverAppliedStatus
    }

    companion object {
        private const val AUTO_HIDE_DELAY_MILLIS = 3000L // The number of milliseconds to wait after user interaction before hiding the system UI.

        private const val INFO_DIALOG = "INFO_DIALOG"
        private const val REMOVE_DIALOG = "REMOVE_DIALOG"
        private const val CONFIRM_DIALOG = "CONFIRM_DIALOG"
        private const val DELETE_REQUEST_KEY = "PHOTO_SLIDER_DELETE_REQUEST_KEY"
        private const val STRIP_REQUEST_KEY = "PHOTO_SLIDER_STRIP_REQUEST_KEY"

        const val CHOOSER_SPY_ACTION = "site.leos.apps.lespas.CHOOSER_PHOTOSLIDER"
        const val KEY_ALBUM = "ALBUM"

        @JvmStatic
        fun newInstance(album: Album) = PhotoSlideFragment().apply { arguments = Bundle().apply { putParcelable(KEY_ALBUM, album) }}
    }
}