/*
 *   Copyright 2019 Jeffrey Liu (scubajeffrey@protonmail.ch)
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package site.leos.apps.lespas.photo

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.database.ContentObserver
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.SharedElementCallback
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.Fade
import androidx.transition.Slide
import androidx.transition.TransitionManager
import androidx.viewpager2.widget.ViewPager2
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.github.chrisbanes.photoview.PhotoView
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.transition.MaterialContainerTransform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.Album
import site.leos.apps.lespas.album.AlbumViewModel
import site.leos.apps.lespas.album.Cover
import site.leos.apps.lespas.helper.ConfirmDialogFragment
import site.leos.apps.lespas.helper.MediaSliderTransitionListener
import site.leos.apps.lespas.helper.MetaDataDialogFragment
import site.leos.apps.lespas.helper.RemoveOriginalBroadcastReceiver
import site.leos.apps.lespas.helper.SeamlessMediaSliderAdapter
import site.leos.apps.lespas.helper.ShareOutDialogFragment
import site.leos.apps.lespas.helper.SnapseedResultWorker
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.helper.Tools.parcelable
import site.leos.apps.lespas.helper.VideoPlayerViewModel
import site.leos.apps.lespas.helper.VideoPlayerViewModelFactory
import site.leos.apps.lespas.publication.NCShareViewModel
import site.leos.apps.lespas.settings.SettingsFragment
import site.leos.apps.lespas.sync.AcquiringDialogFragment
import site.leos.apps.lespas.sync.ActionViewModel
import site.leos.apps.lespas.sync.ShareReceiverActivity
import java.io.File
import java.util.UUID

class PhotoSlideFragment : Fragment() {
    private lateinit var album: Album

    private lateinit var window: Window
    //private var previousNavBarColor = 0
    private var previousOrientationSetting = 0
    //private var previousTitleBarDisplayOption = 0

    private lateinit var controlsContainer: LinearLayout
    private lateinit var removeButton: Button
    private lateinit var coverButton: Button
    private lateinit var useAsButton: Button
    private lateinit var snapseedButton: Button
    private lateinit var captionTextView: TextView

    private lateinit var slider: ViewPager2
    private lateinit var pAdapter: PhotoSlideAdapter

    private val albumModel: AlbumViewModel by activityViewModels()
    private val actionModel: ActionViewModel by viewModels()
    private val imageLoaderModel: NCShareViewModel by activityViewModels()
    private val currentPhotoModel: CurrentPhotoViewModel by activityViewModels()
    private lateinit var playerViewModel: VideoPlayerViewModel

    private var autoRotate = false
    private var shareOutType = GENERAL_SHARE
    private var shareOutMimeType = ""
    private var waitingMsg: Snackbar? = null
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var snapseedCatcher: BroadcastReceiver
    private lateinit var snapseedOutputObserver: ContentObserver
    private lateinit var deleteMediaLauncher: ActivityResultLauncher<IntentSenderRequest>
    private val snapseedFileUris = mutableSetOf<Uri>()

    private lateinit var removeOriginalBroadcastReceiver: RemoveOriginalBroadcastReceiver

    private var isRemote: Boolean = false
    private lateinit var serverPath: String
    private lateinit var serverFullPath: String
    private lateinit var rootPath: String

    private lateinit var shareOutBackPressedCallback: OnBackPressedCallback

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        album = requireArguments().parcelable(KEY_ALBUM)!!
        // Album meta won't change during this fragment lifecycle
        isRemote = Tools.isRemoteAlbum(album)
        rootPath = Tools.getLocalRoot(requireContext())
        serverPath = "${Tools.getRemoteHome(requireContext())}/${album.name}"
        serverFullPath = "${imageLoaderModel.getResourceRoot()}${serverPath}"
        // Player model should have callFactory setting so that it can play both local and remote video, because even in remote album, there are not yet uploaded local video item too
        playerViewModel = ViewModelProvider(this, VideoPlayerViewModelFactory(requireActivity(), imageLoaderModel.getCallFactory(), imageLoaderModel.getPlayerCache()))[VideoPlayerViewModel::class.java]
        //playerViewModel = ViewModelProvider(this, VideoPlayerViewModelFactory(requireActivity().application, if (isRemote) imageLoaderModel.getCallFactory() else null))[VideoPlayerViewModel::class.java]

        pAdapter = PhotoSlideAdapter(
            requireContext(),
            Tools.getDisplayDimension(requireActivity()).first,
            playerViewModel,
            { photo->
                with(photo) {
                    if (isRemote && photo.eTag != Photo.ETAG_NOT_YET_UPLOADED) SeamlessMediaSliderAdapter.VideoItem("${serverFullPath}/${name}".toUri(), mimeType, width, height, id)
                    else {
                        var fileName = "${rootPath}/${id}"
                        if (!(File(fileName).exists())) fileName = "${rootPath}/${name}"
                        SeamlessMediaSliderAdapter.VideoItem("file:///$fileName".toUri(), mimeType, width, height, id)
                    }
                }
            },
            { state-> toggleBottomControl(state) },
            { photo, imageView, type ->
                if (type == NCShareViewModel.TYPE_NULL) startPostponedEnterTransition()
                else imageLoaderModel.setImagePhoto(NCShareViewModel.RemotePhoto(photo, if (isRemote && photo.eTag != Photo.ETAG_NOT_YET_UPLOADED) serverPath else ""), imageView!!, type) { startPostponedEnterTransition() }
            },
            { view -> imageLoaderModel.cancelSetImagePhoto(view) }
        ).apply { stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY }

        // Adjusting the shared element mapping
        setEnterSharedElementCallback(object : SharedElementCallback() {
            override fun onMapSharedElements(names: MutableList<String>?, sharedElements: MutableMap<String, View>?) {
                if (names?.isNotEmpty() == true) slider.getChildAt(0)?.findViewById<View>(R.id.media)?.apply { sharedElements?.put(names[0], this) }
            }
        })

        previousOrientationSetting = requireActivity().requestedOrientation
        with(PreferenceManager.getDefaultSharedPreferences(requireContext())) {
            autoRotate = getBoolean(requireContext().getString(R.string.auto_rotate_perf_key), false)
        }

        // Broadcast receiver listening on share destination
        snapseedCatcher = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent!!.parcelable<ComponentName>(Intent.EXTRA_CHOSEN_COMPONENT)?.packageName!!.substringAfterLast('.') == "snapseed") {
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

        // Content observer looking for Snapseed output
        snapseedOutputObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            private var lastId = ""

            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)

                // ContentObserver got called twice, once for itself, once for it's descendant, all with same last path segment
                uri?.lastPathSegment?.let {
                    if (it != lastId) {
                        lastId = it

                        val workerId = UUID.randomUUID()
                        with(WorkManager.getInstance(requireContext())) {
                            enqueue(OneTimeWorkRequestBuilder<SnapseedResultWorker>()
                                .setInputData(workDataOf(SnapseedResultWorker.KEY_IMAGE_URI to uri.toString(), SnapseedResultWorker.KEY_SHARED_PHOTO to pAdapter.getPhotoAt(slider.currentItem).id, SnapseedResultWorker.KEY_ALBUM to album.id))
                                .setId(workerId)
                                .build()
                            )

                            viewLifecycleOwner.lifecycleScope.launch {
                                getWorkInfoByIdFlow(workerId).flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED).collect { workInfo ->
                                    if (workInfo?.state == WorkInfo.State.SUCCEEDED) {
                                        if (PreferenceManager.getDefaultSharedPreferences(requireContext()).getBoolean(requireContext().getString(R.string.snapseed_replace_pref_key), false)) {
                                            // When replacing original with Snapseed result, refresh image cache of all size
                                            val photoId = pAdapter.getPhotoAt(slider.currentItem).id
                                            imageLoaderModel.invalidPhoto(photoId)
                                            if (Tools.isRemoteAlbum(album)) lifecycleScope.launch(Dispatchers.IO) { File(Tools.getLocalRoot(requireContext()), photoId).delete() }
                                        }

                                        // Removing snapseed file from MediaStore for Android 12 or above, actual removal happen during onStop()
                                        snapseedFileUris.add(uri)
                                    }
                                }
                            }
                        }
                        requireContext().contentResolver.unregisterContentObserver(this)
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) deleteMediaLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {}

        removeOriginalBroadcastReceiver = RemoveOriginalBroadcastReceiver { if (it && pAdapter.getPhotoAt(slider.currentItem).id != album.cover) removePhoto() }

        shareOutBackPressedCallback = object: OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                // Cancel share out job if it's running
                waitingMsg?.let {
                    if (it.isShownOrQueued) {
                        imageLoaderModel.cancelShareOut()
                        it.dismiss()
                    }
                }
                isEnabled = false
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(this, shareOutBackPressedCallback)

        this.window = requireActivity().window
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        (activity as AppCompatActivity).supportActionBar?.hide()
        Tools.setImmersive(window, true)

        return inflater.inflate(R.layout.fragment_photoslide, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        postponeEnterTransition()

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
                    if (state == ViewPager2.SCROLL_STATE_SETTLING) handlerBottomControl.post(hideBottomControls)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && state == ViewPager2.SCROLL_STATE_IDLE) slider.getChildAt(0)?.findViewById<View>(R.id.media)?.apply {
                        window.colorMode = if (this is PhotoView && getTag(R.id.HDR_TAG) as Boolean? == true) ActivityInfo.COLOR_MODE_HDR else ActivityInfo.COLOR_MODE_DEFAULT
                    }
                }

                override fun onPageSelected(position: Int) {
                    super.onPageSelected(position)

                    try {
                        pAdapter.getPhotoAt(position).run {
                            currentPhotoModel.setCurrentPosition(position + 1)
                            if (autoRotate) requireActivity().requestedOrientation = if (this.width > this.height) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

                            // Can't delete cover
                            removeButton.isEnabled = this.id != album.cover
                            with(!(Tools.isMediaPlayable(this.mimeType) || this.mimeType == Tools.PANORAMA_MIMETYPE)) {
                                // Can't set video as avatar
                                useAsButton.isEnabled = this
                                // Can't Snapseed video
                                snapseedButton.isEnabled = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(getString(R.string.snapseed_pref_key), false) && this
                            }

                            // Can't set panorama as cover TODO make it possible
                            coverButton.isEnabled = this.mimeType != Tools.PANORAMA_MIMETYPE

                            // Update caption
                            captionTextView.text = caption
                        }
                    } catch (_: IndexOutOfBoundsException) {}
                }
            })
        }

        sharedElementEnterTransition = MaterialContainerTransform().apply {
            duration = resources.getInteger(android.R.integer.config_mediumAnimTime).toLong()
            scrimColor = Color.TRANSPARENT
            fadeMode = MaterialContainerTransform.FADE_MODE_CROSS
        }.apply {
            addListener(MediaSliderTransitionListener(slider))
        }

        // Controls
        controlsContainer = view.findViewById(R.id.bottom_controls_container)
        ViewCompat.setOnApplyWindowInsetsListener(controlsContainer) { v, insets->
            @Suppress("DEPRECATION")
            if (insets.isVisible(WindowInsetsCompat.Type.navigationBars()) || window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION == 0) {
                val systemBar = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                val displayCutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
                v.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    bottomMargin = systemBar.bottom
                    rightMargin = systemBar.right + displayCutout.right
                    leftMargin = systemBar.left + displayCutout.left
                }
            }
            insets
        }

        removeButton = view.findViewById(R.id.remove_button)
        coverButton = view.findViewById(R.id.cover_button)
        useAsButton = view.findViewById(R.id.set_as_button)
        snapseedButton = view.findViewById(R.id.snapseed_button)
        captionTextView = view.findViewById(R.id.caption)

        coverButton.run {
            //setOnTouchListener(delayHideTouchListener)
            setOnClickListener {
                handlerBottomControl.post(hideBottomControls)

                val currentMedia = pAdapter.getPhotoAt(slider.currentItem)
                if (Tools.isMediaPlayable(currentMedia.mimeType) || currentMedia.mimeType == "image/gif") {
                    actionModel.updateCover(album.id, album.name, Cover(currentMedia.id, Album.SPECIAL_COVER_BASELINE, currentMedia.width, currentMedia.height, currentMedia.name, currentMedia.mimeType, currentMedia.orientation))
                    showCoverAppliedStatus(true)
                } else {
                    exitTransition = Fade().apply { duration = 80 }
                    parentFragmentManager.beginTransaction().setReorderingAllowed(true).add(R.id.container_overlay, CoverSettingFragment.newInstance(currentMedia), CoverSettingFragment::class.java.canonicalName).addToBackStack(null).commit()
                }
            }
        }
        view.findViewById<Button>(R.id.share_button).run {
            //setOnTouchListener(delayHideTouchListener)
            setOnClickListener {
                val mimeType = pAdapter.getPhotoAt(slider.currentItem).mimeType
                if (mimeType.startsWith("video")) playerViewModel.pause(Uri.EMPTY)
                handlerBottomControl.post(hideBottomControls)
                if (parentFragmentManager.findFragmentByTag(SHARE_OUT_DIALOG) == null) ShareOutDialogFragment.newInstance(mimeTypes = listOf(mimeType))?.show(parentFragmentManager, SHARE_OUT_DIALOG) ?: run { shareOut(strip = false, lowResolution = false, shareType = GENERAL_SHARE) }
            }
        }
        useAsButton.run {
            //setOnTouchListener(delayHideTouchListener)
            setOnClickListener {
                shareOut(strip = true, lowResolution = false, shareType = SHARE_TO_WALLPAPER)
            }
        }
        view.findViewById<Button>(R.id.info_button).run {
            //setOnTouchListener(delayHideTouchListener)
            setOnClickListener {
                handlerBottomControl.post(hideBottomControls)

                if (parentFragmentManager.findFragmentByTag(INFO_DIALOG) == null) with(pAdapter.getPhotoAt(slider.currentItem)) {
                    if (isRemote && eTag != Photo.ETAG_NOT_YET_UPLOADED)
                        MetaDataDialogFragment.newInstance(NCShareViewModel.RemotePhoto(this, serverPath)).show(parentFragmentManager, INFO_DIALOG)
                    else
                        MetaDataDialogFragment.newInstance(this).show(parentFragmentManager, INFO_DIALOG)
                }
            }
        }
        snapseedButton.run {
            // Snapseed edit replace/add preference can't be changed in this screen, safe to fix the action icon
            if (PreferenceManager.getDefaultSharedPreferences(requireContext()).getBoolean(getString(R.string.snapseed_replace_pref_key), false)) {
                setCompoundDrawablesWithIntrinsicBounds(null, ContextCompat.getDrawable(requireContext(),  R.drawable.ic_baseline_snapseed_24), null, null)
                text = getString(R.string.button_text_edit_in_snapseed_replace)
            }
            else {
                setCompoundDrawablesWithIntrinsicBounds(null, ContextCompat.getDrawable(requireContext(), R.drawable.ic_baseline_snapseed_add_24), null, null)
                text = getString(R.string.button_text_edit_in_snapseed_add)
            }
            //setOnTouchListener(delayHideTouchListener)
            setOnClickListener {
                handlerBottomControl.post(hideBottomControls)
                shareOut(strip = false, lowResolution = false, shareType = SHARE_TO_SNAPSEED)
            }
        }
        removeButton.run {
            //setOnTouchListener(delayHideTouchListener)
            setOnClickListener {
                handlerBottomControl.post(hideBottomControls)

                if (parentFragmentManager.findFragmentByTag(REMOVE_DIALOG) == null)
                    ConfirmDialogFragment.newInstance(getString(R.string.confirm_delete), positiveButtonText = getString(R.string.yes_delete), individualKey = DELETE_REQUEST_KEY, requestKey = PHOTO_SLIDE_REQUEST_KEY).show(parentFragmentManager, REMOVE_DIALOG)
            }
        }
        captionTextView.run {
            //setOnTouchListener(delayHideTouchListener)
            setOnClickListener {
                handlerBottomControl.post(hideBottomControls)

                parentFragmentManager.findFragmentByTag(CAPTION_DIALOG) ?: run { CaptionEditDialogFragment.newInstance(captionTextView.text.toString()).show(parentFragmentManager, CAPTION_DIALOG) }
            }
            movementMethod = ScrollingMovementMethod()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    albumModel.getAllPhotoInAlbum(album.id).collect { photos ->
                        pAdapter.setPhotos(if (currentPhotoModel.getCurrentQuery().isEmpty()) photos else photos.filter { it.name.contains(currentPhotoModel.getCurrentQuery()) }, album.sortOrder)
                        slider.setCurrentItem(currentPhotoModel.getCurrentPosition() - 1, false)
                    }
                }
                launch {
                    imageLoaderModel.shareOutUris.collect { uris ->
                        // Dismiss snackbar before showing system share chooser, avoid unpleasant screen flicker
                        handler.removeCallbacksAndMessages(null)
                        if (waitingMsg?.isShownOrQueued == true) {
                            waitingMsg?.dismiss()
                            shareOutBackPressedCallback.isEnabled = false
                        }

                        // Call system share chooser
                        if (uris.isNotEmpty()) when (shareOutType) {
                            GENERAL_SHARE -> {
                                startActivity(Intent.createChooser(Intent().apply {
                                    action = Intent.ACTION_SEND
                                    type = shareOutMimeType
                                    putExtra(Intent.EXTRA_STREAM, uris[0])
                                    clipData = ClipData.newUri(requireContext().contentResolver, "", uris[0])
                                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    putExtra(ShareReceiverActivity.KEY_SHOW_REMOVE_OPTION, true)
                                    putExtra(ShareReceiverActivity.KEY_CURRENT_ALBUM_ID, album.id)
                                }, null))
                            }
                            SHARE_TO_SNAPSEED -> {
                                startActivity(Intent().apply {
                                    action = Intent.ACTION_SEND
                                    setDataAndType(uris[0], shareOutMimeType)
                                    putExtra(Intent.EXTRA_STREAM, uris[0])
                                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    setClassName(SettingsFragment.SNAPSEED_PACKAGE_NAME, SettingsFragment.SNAPSEED_MAIN_ACTIVITY_CLASS_NAME)
                                })

                                // Send broadcast just like system share does when user chooses Snapseed, so that PhotoSliderFragment can catch editing result
                                view.context.sendBroadcast(Intent().apply {
                                    action = CHOOSER_SPY_ACTION
                                    putExtra(Intent.EXTRA_CHOSEN_COMPONENT, ComponentName(SettingsFragment.SNAPSEED_PACKAGE_NAME, SettingsFragment.SNAPSEED_MAIN_ACTIVITY_CLASS_NAME))
                                })
                            }
                            SHARE_TO_WALLPAPER -> {
                                startActivity(Intent.createChooser(Intent().apply {
                                    action = Intent.ACTION_ATTACH_DATA
                                    addCategory(Intent.CATEGORY_DEFAULT)
                                    setDataAndType(uris[0], shareOutMimeType)
                                    putExtra("mimeType", shareOutMimeType)
                                    //clipData = ClipData.newUri(requireActivity().contentResolver, "", it.first)
                                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                                }, getString(R.string.button_text_use_as)))
                            }
                        }
                    }
                }.invokeOnCompletion {
                    handler.removeCallbacksAndMessages(null)
                    if (waitingMsg?.isShownOrQueued == true) {
                        waitingMsg?.dismiss()
                        shareOutBackPressedCallback.isEnabled = false
                    }
                }
            }
        }

        ContextCompat.registerReceiver(requireContext(), snapseedCatcher, IntentFilter(CHOOSER_SPY_ACTION), ContextCompat.RECEIVER_EXPORTED)
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(removeOriginalBroadcastReceiver, IntentFilter(AcquiringDialogFragment.BROADCAST_REMOVE_ORIGINAL))

        // Remove photo confirm dialog result handler
        parentFragmentManager.setFragmentResultListener(PHOTO_SLIDE_REQUEST_KEY, viewLifecycleOwner) { _, bundle ->
            when(bundle.getString(ConfirmDialogFragment.INDIVIDUAL_REQUEST_KEY)) {
                DELETE_REQUEST_KEY -> if (bundle.getBoolean(ConfirmDialogFragment.CONFIRM_DIALOG_RESULT_KEY, false)) removePhoto()
            }
        }

        // Caption dialog result handler
        parentFragmentManager.setFragmentResultListener(CaptionEditDialogFragment.RESULT_KEY_NEW_CAPTION, viewLifecycleOwner) { _, bundle ->
            bundle.getString(CaptionEditDialogFragment.RESULT_KEY_NEW_CAPTION)?.let { newCaption ->
                captionTextView.text = newCaption
                actionModel.updatePhotoCaption(pAdapter.getPhotoAt(slider.currentItem).id, newCaption, album.name)
            }
        }

        // Share out dialog result handler
        parentFragmentManager.setFragmentResultListener(ShareOutDialogFragment.SHARE_OUT_DIALOG_RESULT_KEY, viewLifecycleOwner) { _, bundle ->
            if (bundle.getBoolean(ShareOutDialogFragment.SHARE_OUT_DIALOG_RESULT_KEY, true)) shareOut(bundle.getBoolean(ShareOutDialogFragment.STRIP_RESULT_KEY, false), bundle.getBoolean(ShareOutDialogFragment.LOW_RESOLUTION_RESULT_KEY, false), GENERAL_SHARE)
        }

        // Cover setting result handler
        parentFragmentManager.setFragmentResultListener(CoverSettingFragment.KEY_COVER_SETTING_RESULT, viewLifecycleOwner) { _, bundle ->
            bundle.parcelable<Cover>(CoverSettingFragment.KEY_NEW_COVER)?.let { newCover ->
                showCoverAppliedStatus(true)
                actionModel.updateCover(album.id, album.name, newCover)
            } ?: run { showCoverAppliedStatus(false) }

            // Clear transition when coming back from CoverSettingFragment
            exitTransition = null
        }

        savedInstanceState?.let {
            if (it.getBoolean(KEY_SHAREOUT_RUNNING)) {
                shareOutMimeType = it.getString(KEY_SHAREOUT_MIMETYPE, "image/*")!!
                shareOutType = it.getInt(KEY_SHAREOUT_TYPE)
                waitingMsg = Tools.getPreparingSharesSnackBar(slider) {
                    imageLoaderModel.cancelShareOut()
                    shareOutBackPressedCallback.isEnabled = false
                }
                waitingMsg?.run {
                    show()
                    shareOutBackPressedCallback.isEnabled = true
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        pAdapter.setPauseVideo(true)
    }

    override fun onStop() {
        super.onStop()

        // Pause video playing
        try { if (pAdapter.getPhotoAt(slider.currentItem).mimeType.startsWith("video")) handler.postDelayed({ playerViewModel.pause(Uri.EMPTY) }, 300) } catch (_: IndexOutOfBoundsException) {}

        // Remove snapshot work file if running on Android 12 or above and Manager Media role has been assigned
        if (snapseedFileUris.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && MediaStore.canManageMedia(requireContext()))
            deleteMediaLauncher.launch(IntentSenderRequest.Builder(MediaStore.createDeleteRequest(requireContext().contentResolver, snapseedFileUris)).setFillInIntent(null).build())
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        //outState.putInt(KEY_DISPLAY_OPTION, previousTitleBarDisplayOption)
        outState.putBoolean(KEY_SHAREOUT_RUNNING, waitingMsg?.isShownOrQueued == true)
        outState.putInt(KEY_SHAREOUT_TYPE, shareOutType)
        outState.putString(KEY_SHAREOUT_MIMETYPE, shareOutMimeType)

        pAdapter.setPauseVideo(false)
    }

    override fun onDestroyView() {
        handler.removeCallbacksAndMessages(null)

        requireContext().unregisterReceiver(snapseedCatcher)
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(removeOriginalBroadcastReceiver)
        slider.adapter = null

        if (waitingMsg?.isShownOrQueued == true) waitingMsg?.dismiss()

        // Quit immersive
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            @Suppress("DEPRECATION")
            window.decorView.setOnSystemUiVisibilityChangeListener(null)
        }
        Tools.setImmersive(window, false)
        (requireActivity() as AppCompatActivity).apply {
            requestedOrientation = previousOrientationSetting
            supportActionBar?.show()
        }

        super.onDestroyView()
    }

    override fun onDestroy() {
        handlerBottomControl.removeCallbacksAndMessages(null)

        requireContext().contentResolver.unregisterContentObserver(snapseedOutputObserver)

        super.onDestroy()
    }

    // Toggle visibility of bottom controls and system decoView
    private val handlerBottomControl = Handler(Looper.getMainLooper())
    private fun toggleBottomControl(state: Boolean?) {
        handlerBottomControl.removeCallbacksAndMessages(null)
        handlerBottomControl.post(if (state ?: !controlsContainer.isVisible) showBottomControls else hideBottomControls)
    }

    private val hideBottomControls = Runnable {
        WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())

        TransitionManager.beginDelayedTransition(controlsContainer, Slide(Gravity.BOTTOM).apply { duration = 200 })
        controlsContainer.isVisible = false
        handlerBottomControl.removeCallbacksAndMessages(null)
    }
    private val showBottomControls = Runnable {
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())

        TransitionManager.beginDelayedTransition(controlsContainer, Slide(Gravity.BOTTOM).apply { duration = 200 })
        controlsContainer.isVisible = true
        // Trigger auto hide only if there is no caption
        if (captionTextView.text.isEmpty()) handlerBottomControl.postDelayed(hideBottomControls, AUTO_HIDE_DELAY_MILLIS)
    }

/*
    // Delay hiding the system UI while interacting with controls, preventing the jarring behavior of controls going away
    @SuppressLint("ClickableViewAccessibility")
    private val delayHideTouchListener = View.OnTouchListener { _, _ ->
        handlerBottomControl.removeCallbacks(hideSystemUI)
        handlerBottomControl.postDelayed(hideSystemUI, AUTO_HIDE_DELAY_MILLIS)
        false
    }
*/

    private fun showCoverAppliedStatus(appliedStatus: Boolean) {
        Snackbar.make(window.decorView.rootView, getString(if (appliedStatus) R.string.toast_cover_applied else R.string.toast_cover_set_canceled), Snackbar.LENGTH_SHORT)
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

    private fun removePhoto() { actionModel.deletePhotos(listOf(pAdapter.getPhotoAt(slider.currentItem)), album) }

    private fun shareOut(strip: Boolean, lowResolution: Boolean, shareType: Int) {
        shareOutType = shareType
        waitingMsg = Tools.getPreparingSharesSnackBar(slider) {
            imageLoaderModel.cancelShareOut()
            shareOutBackPressedCallback.isEnabled = false
        }

        // Show a SnackBar if it takes too long (more than 500ms) preparing shares
        handler.postDelayed({
            waitingMsg?.show()
            shareOutBackPressedCallback.isEnabled = true
        }, 500)

        // Prepare media files for sharing
        imageLoaderModel.prepareFileForShareOut(listOf(NCShareViewModel.RemotePhoto(pAdapter.getPhotoAt(slider.currentItem).apply { shareOutMimeType = mimeType }, if (Tools.isRemoteAlbum(album)) serverPath else "")), strip, lowResolution)
    }

    class PhotoSlideAdapter(
        context: Context,
        displayWidth: Int, playerViewModel: VideoPlayerViewModel, private val videoItemLoader: (Photo) -> VideoItem,
        clickListener: (Boolean?) -> Unit, imageLoader: (Photo, ImageView?, String) -> Unit, cancelLoader: (View) -> Unit
    ): SeamlessMediaSliderAdapter<Photo>(context, displayWidth, PhotoDiffCallback(), playerViewModel, clickListener, imageLoader, cancelLoader) {
        override fun getVideoItem(position: Int): VideoItem = videoItemLoader(getItem(position))
        override fun getItemTransitionName(position: Int): String = getItem(position).id
        override fun getItemMimeType(position: Int): String = getItem(position).mimeType

        fun setPhotos(collection: List<Photo>, sortOrder: Int) {
            val photos = Tools.sortPhotos(collection, sortOrder)
            submitList(photos.toMutableList())
        }

        fun getPhotoAt(position: Int): Photo = currentList[position]
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

        private var currentQuery = ""
        fun setCurrentQuery(query: String) { currentQuery = query }
        fun getCurrentQuery(): String = currentQuery
    }

    companion object {
        private const val AUTO_HIDE_DELAY_MILLIS = 3000L // The number of milliseconds to wait after user interaction before hiding the system UI.

        private const val INFO_DIALOG = "INFO_DIALOG"
        private const val REMOVE_DIALOG = "REMOVE_DIALOG"
        private const val CAPTION_DIALOG = "CAPTION_DIALOG"
        private const val SHARE_OUT_DIALOG = "SHARE_OUT_DIALOG"
        private const val PHOTO_SLIDE_REQUEST_KEY = "PHOTO_SLIDE_REQUEST_KEY"
        private const val DELETE_REQUEST_KEY = "PHOTO_SLIDER_DELETE_REQUEST_KEY"

        private const val GENERAL_SHARE = 0
        private const val SHARE_TO_SNAPSEED = 1
        private const val SHARE_TO_WALLPAPER = 2

        //private const val KEY_DISPLAY_OPTION = "KEY_DISPLAY_OPTION"
        private const val KEY_SHAREOUT_RUNNING = "KEY_SHAREOUT_RUNNING"
        private const val KEY_SHAREOUT_MIMETYPE = "KEY_SHAREOUT_MIMETYPE"
        private const val KEY_SHAREOUT_TYPE = "KEY_SHAREOUT_TYPE"

        const val CHOOSER_SPY_ACTION = "site.leos.apps.lespas.CHOOSER_PHOTOSLIDER"
        const val KEY_ALBUM = "ALBUM"

        @JvmStatic
        fun newInstance(album: Album) = PhotoSlideFragment().apply { arguments = Bundle().apply { putParcelable(KEY_ALBUM, album) }}
    }
}