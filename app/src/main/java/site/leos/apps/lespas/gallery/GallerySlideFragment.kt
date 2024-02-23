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

package site.leos.apps.lespas.gallery

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.SharedElementCallback
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.Slide
import androidx.transition.TransitionManager
import androidx.viewpager2.widget.ViewPager2
import com.github.chrisbanes.photoview.PhotoView
import com.google.android.material.transition.MaterialContainerTransform
import kotlinx.coroutines.launch
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.ConfirmDialogFragment
import site.leos.apps.lespas.helper.MediaSliderTransitionListener
import site.leos.apps.lespas.helper.MetaDataDialogFragment
import site.leos.apps.lespas.helper.SeamlessMediaSliderAdapter
import site.leos.apps.lespas.helper.ShareOutDialogFragment
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.helper.VideoPlayerViewModel
import site.leos.apps.lespas.helper.VideoPlayerViewModelFactory
import site.leos.apps.lespas.publication.NCShareViewModel
import site.leos.apps.lespas.sync.ActionViewModel
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

class GallerySlideFragment : Fragment() {
    private lateinit var mediaAdapter: MediaSlideAdapter
    private lateinit var mediaList: ViewPager2
    private lateinit var controlsContainer: ConstraintLayout
    private lateinit var tvPath: TextView
    private lateinit var tvDate: TextView
    private lateinit var tvSize: TextView
    private lateinit var removeButton: ImageButton
    private lateinit var useAsButton: ImageButton
    private lateinit var folderArgument: String

    private val actionModel: ActionViewModel by viewModels(ownerProducer = { requireParentFragment() })
    private val imageLoaderModel: NCShareViewModel by activityViewModels()
    //private val galleryModel: GalleryFragment.GalleryViewModel by viewModels(ownerProducer = { requireParentFragment() })
    private val galleryModel: GalleryFragment.GalleryViewModel by viewModels(ownerProducer = { requireParentFragment() }) { GalleryFragment.GalleryViewModelFactory(requireActivity(), imageLoaderModel, actionModel) }
    private lateinit var playerViewModel: VideoPlayerViewModel

    private lateinit var window: Window

    private var previousOrientationSetting = 0
    private var previousTitleBarDisplayOption = 0
    private var autoRotate = false
    private var nextInLine = ""
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var remoteArchiveBaseFolder: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        folderArgument = requireArguments().getString(ARGUMENT_FOLDER) ?: ""
        remoteArchiveBaseFolder = Tools.getCameraArchiveHome(requireContext())
        playerViewModel = ViewModelProvider(this, VideoPlayerViewModelFactory(requireActivity(), imageLoaderModel.getCallFactory(), imageLoaderModel.getPlayerCache()))[VideoPlayerViewModel::class.java]

        mediaAdapter = MediaSlideAdapter(
            requireContext(),
            imageLoaderModel.getResourceRoot(),
            Tools.getDisplayDimension(requireActivity()).first,
            playerViewModel,
            { state -> toggleBottomControls(state) },
            { localMedia, imageView, type ->
                if (type == NCShareViewModel.TYPE_NULL) startPostponedEnterTransition()
                else imageLoaderModel.setImagePhoto(localMedia.media, imageView!!, type) { startPostponedEnterTransition() }
            },
            { view -> imageLoaderModel.cancelSetImagePhoto(view) },
        ).apply { stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY }

        previousOrientationSetting = requireActivity().requestedOrientation
        PreferenceManager.getDefaultSharedPreferences(requireContext()).apply {
            autoRotate = getBoolean(requireContext().getString(R.string.auto_rotate_perf_key), false)
        }


        // Adjusting the shared element mapping
        setEnterSharedElementCallback(object : SharedElementCallback() {
            override fun onMapSharedElements(names: MutableList<String>?, sharedElements: MutableMap<String, View>?) {
                if (names?.isNotEmpty() == true) mediaList.getChildAt(0)?.findViewById<View>(R.id.media)?.run { sharedElements?.put(names[0], this) }
            }
        })

        this.window = requireActivity().window
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        (activity as AppCompatActivity).supportActionBar?.hide()
        Tools.setImmersive(window, true)

        return inflater.inflate(R.layout.fragment_gallery_slide, container, false)
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        postponeEnterTransition()

        tvPath = view.findViewById(R.id.path)
        tvDate = view.findViewById(R.id.date)
        tvSize = view.findViewById(R.id.size)
        mediaList = view.findViewById<ViewPager2>(R.id.pager).apply {
            adapter = mediaAdapter

            // Use reflection to reduce Viewpager2 slide sensitivity, so that PhotoView inside can zoom presently
            val recyclerView = (ViewPager2::class.java.getDeclaredField("mRecyclerView").apply{ isAccessible = true }).get(this) as RecyclerView
            (RecyclerView::class.java.getDeclaredField("mTouchSlop")).apply {
                isAccessible = true
                set(recyclerView, (get(recyclerView) as Int) * 4)
            }

            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageScrollStateChanged(state: Int) {
                    super.onPageScrollStateChanged(state)
                    if (state == ViewPager2.SCROLL_STATE_SETTLING) handler.post(hideBottomControls)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && state == ViewPager2.SCROLL_STATE_IDLE) mediaList.getChildAt(0)?.findViewById<View>(R.id.media)?.apply {
                        window.colorMode = if (this is PhotoView && getTag(R.id.HDR_TAG) as Boolean? == true) ActivityInfo.COLOR_MODE_HDR else ActivityInfo.COLOR_MODE_DEFAULT
                    }
                }

                @SuppressLint("SetTextI18n")
                override fun onPageSelected(position: Int) {
                    super.onPageSelected(position)

                    try {
                        mediaAdapter.getPhotoAt(position).run {
                            if (autoRotate) requireActivity().requestedOrientation = if (this.photo.width > this.photo.height) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            galleryModel.setCurrentPhotoId(photo.id)
                            tvPath.text = "${galleryModel.getFullPath(photo.id)}${photo.name}"
                            tvDate.text = "${photo.dateTaken.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())}, ${photo.dateTaken.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))}"
                            tvSize.text = Tools.humanReadableByteCountSI(photo.caption.toLong()) + if (photo.width > 0) ",  ${photo.width} × ${photo.height}" else ""
                            removeButton.isEnabled = photo.lastModified != LocalDateTime.MAX
                            useAsButton.isEnabled = photo.mimeType.startsWith("image")
                        }
                    } catch (_: IndexOutOfBoundsException) {}

                }
            })
        }

        sharedElementEnterTransition = MaterialContainerTransform().apply {
            duration = resources.getInteger(android.R.integer.config_mediumAnimTime).toLong()
            scrimColor = Color.TRANSPARENT
            fadeMode = MaterialContainerTransform.FADE_MODE_CROSS
        }.apply { addListener(MediaSliderTransitionListener(mediaList)) }

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

        view.findViewById<ImageButton>(R.id.info_button).setOnClickListener {
            if (parentFragmentManager.findFragmentByTag(INFO_DIALOG) == null) mediaAdapter.getPhotoAt(mediaList.currentItem).let { remotePhoto ->
                (if (mediaAdapter.isPhotoAtLocal(mediaList.currentItem)) MetaDataDialogFragment.newInstance(remotePhoto.photo) else MetaDataDialogFragment.newInstance(remotePhoto)).show(parentFragmentManager, INFO_DIALOG)
            }
        }
        removeButton = view.findViewById<ImageButton>(R.id.remove_button).apply {
            if (folderArgument == GalleryFragment.TRASH_FOLDER) {
                setImageDrawable(ContextCompat.getDrawable(requireContext(), R.drawable.ic_baseline_restore_from_trash_24))
                getString(R.string.action_undelete).let { buttonText ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) tooltipText = buttonText
                    contentDescription = buttonText
                }
            }
            setOnClickListener {
                galleryModel.registerNextInLine(getNextInLine())
                mediaAdapter.getPhotoAt(mediaList.currentItem).photo.let { photo ->
                    val defaultSyncDeletionSetting = PreferenceManager.getDefaultSharedPreferences(requireContext()).getBoolean(getString(R.string.sync_deletion_perf_key), false)
                    when {
                        folderArgument == GalleryFragment.TRASH_FOLDER -> galleryModel.restore(listOf(photo.id), nextInLine)
                        Build.VERSION.SDK_INT == Build.VERSION_CODES.R || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !MediaStore.canManageMedia(requireContext())) -> galleryModel.remove(listOf(photo.id), removeArchive = defaultSyncDeletionSetting)
                        parentFragmentManager.findFragmentByTag(CONFIRM_DIALOG) == null -> ConfirmDialogFragment.newInstance(
                            getString(R.string.confirm_delete), positiveButtonText = getString(R.string.yes_delete), individualKey = DELETE_REQUEST_KEY, requestKey = GALLERY_SLIDE_REQUEST_KEY,
                            checkBoxText = getString(R.string.checkbox_text_remove_archive_copy), checkBoxChecked = defaultSyncDeletionSetting
                        ).show(parentFragmentManager, CONFIRM_DIALOG)
                    }
                }
            }
        }
        useAsButton = view.findViewById<ImageButton>(R.id.use_as_button).apply {
            setOnClickListener { galleryModel.shareOut(listOf(mediaAdapter.getPhotoAt(mediaList.currentItem).photo.id), strip = true, lowResolution = false, removeAfterwards = false, shareType = GalleryFragment.GalleryViewModel.SHARE_USE_AS) }
        }
        view.findViewById<ImageButton>(R.id.share_button).setOnClickListener {
            mediaAdapter.getPhotoAt(mediaList.currentItem).photo.let { photo ->
                if (photo.mimeType.startsWith("video")) playerViewModel.pause(Uri.EMPTY)

                val photoIds = listOf(photo.id)
                if (parentFragmentManager.findFragmentByTag(SHARE_OUT_DIALOG) == null)
                    ShareOutDialogFragment.newInstance(mimeTypes = galleryModel.getMimeTypes(photoIds), showRemoveAfterwards = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaStore.canManageMedia(requireContext()) else false)
                        ?.show(parentFragmentManager, SHARE_OUT_DIALOG) ?: run { galleryModel.shareOut(photoIds, strip = false, lowResolution = false, removeAfterwards = false) }
            }
        }
        view.findViewById<ImageButton>(R.id.lespas_button).setOnClickListener {
            galleryModel.registerNextInLine(getNextInLine())
            galleryModel.add(listOf(mediaAdapter.getPhotoAt(mediaList.currentItem).photo.id))
        }

        parentFragmentManager.setFragmentResultListener(GALLERY_SLIDE_REQUEST_KEY, viewLifecycleOwner) { _, bundle ->
            when(bundle.getString(ConfirmDialogFragment.INDIVIDUAL_REQUEST_KEY)) {
                DELETE_REQUEST_KEY -> if (bundle.getBoolean(ConfirmDialogFragment.CONFIRM_DIALOG_RESULT_KEY, false)) galleryModel.remove(listOf(mediaAdapter.getPhotoAt(mediaList.currentItem).photo.id), removeArchive = bundle.getBoolean(ConfirmDialogFragment.CHECKBOX_RESULT_KEY))
            }
        }

        // Share out dialog result handler
        parentFragmentManager.setFragmentResultListener(ShareOutDialogFragment.SHARE_OUT_DIALOG_RESULT_KEY, viewLifecycleOwner) { _, bundle ->
            if (bundle.getBoolean(ShareOutDialogFragment.SHARE_OUT_DIALOG_RESULT_KEY, true))
                galleryModel.shareOut(
                    photoIds = listOf(mediaAdapter.getPhotoAt(mediaList.currentItem).photo.id),
                    strip = bundle.getBoolean(ShareOutDialogFragment.STRIP_RESULT_KEY, false),
                    lowResolution = bundle.getBoolean(ShareOutDialogFragment.LOW_RESOLUTION_RESULT_KEY, false),
                    removeAfterwards = bundle.getBoolean(ShareOutDialogFragment.REMOVE_AFTERWARDS_RESULT_KEY, false),
                )
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                when {
                    folderArgument == GalleryFragment.TRASH_FOLDER -> galleryModel.trash.collect { setList(it) }                    // Trash view
                    folderArgument == GalleryFragment.ALL_FOLDER -> galleryModel.medias.collect { setList(it) }                     // All folder view
                    folderArgument.indexOf('/') == -1 -> galleryModel.mediasInFolder(folderArgument).collect { setList(it) }   // Single main folder view
                    else -> galleryModel.medias.collect { setList(it?.filter {item -> item.fullPath == folderArgument}) }           // Launched as viewer in a folder
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        mediaAdapter.setPauseVideo(true)
    }

    override fun onStop() {
        super.onStop()
        try {
            if (mediaAdapter.currentList.isNotEmpty() && mediaAdapter.getPhotoAt(mediaList.currentItem).photo.mimeType.startsWith("video")) handler.postDelayed({ playerViewModel.pause(Uri.EMPTY) }, 300)
        } catch (_: IndexOutOfBoundsException) {}
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        outState.putInt(KEY_DISPLAY_OPTION, previousTitleBarDisplayOption)
        mediaAdapter.setPauseVideo(false)
    }

    override fun onDestroyView() {
        handler.removeCallbacksAndMessages(null)
        mediaList.adapter = null

        // Quick immersive
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
        // BACK TO NORMAL UI
        handlerBottomControl.removeCallbacksAndMessages(null)

        super.onDestroy()
    }

    private fun getNextInLine(): String = when {
        mediaList.currentItem < mediaAdapter.currentList.size - 1 -> mediaAdapter.getPhotoAt(mediaList.currentItem + 1).photo.id    // Item to be deleted is not the last one in the list, next in line will be the next one
        mediaList.currentItem == 0 -> ""                                                                                                    // Item to be deleted is the last one in the list and the only one in list, next in line is ""
        else -> mediaAdapter.getPhotoAt(mediaList.currentItem - 1).photo.id                                                         // Item to be deleted is the last one in the list and there are more than one left after deletion, next in line will be the previous one
    }

    private fun setList(localMedias: List<GalleryFragment.LocalMedia>?) {
        if (localMedias == null) return
        if (localMedias.isEmpty()) parentFragmentManager.popBackStack()
        else {
            requireArguments().getString(ARGUMENT_SUBFOLDER, "").let { subFolder ->
                when {
                    subFolder.isEmpty() -> localMedias
                    subFolder == GalleryFolderViewFragment.CHIP_FOR_ALL_TAG -> localMedias
                    folderArgument == GalleryFragment.ALL_FOLDER -> localMedias.filter { it.appName == subFolder }
                    else -> localMedias.filter { it.fullPath == subFolder }
            }}.let { filtered ->
                if (filtered.isEmpty()) parentFragmentManager.popBackStack()
                else mediaAdapter.submitList(filtered) { mediaList.setCurrentItem(mediaAdapter.getPhotoPosition(galleryModel.getCurrentPhotoId()), false) }
            }
        }
    }

    // Toggle visibility of bottom controls and system decoView
    private val handlerBottomControl = Handler(Looper.getMainLooper())
    private fun toggleBottomControls(state: Boolean?) {
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
        handlerBottomControl.postDelayed(hideBottomControls, AUTO_HIDE_DELAY_MILLIS)
    }

    class MediaSlideAdapter(
        context: Context, private val basePath: String, displayWidth: Int, playerViewModel: VideoPlayerViewModel,
        clickListener: (Boolean?) -> Unit, imageLoader: (GalleryFragment.LocalMedia, ImageView?, String) -> Unit, cancelLoader: (View) -> Unit
    ): SeamlessMediaSliderAdapter<GalleryFragment.LocalMedia>(context, displayWidth, SliderMediaDiffCallback(), playerViewModel, clickListener, imageLoader, cancelLoader) {
        override fun getItemTransitionName(position: Int): String = getItem(position).media.photo.id
        override fun getItemMimeType(position: Int): String = getItem(position).media.photo.mimeType
        override fun getVideoItem(position: Int): VideoItem = with((getItem(position) as GalleryFragment.LocalMedia).media) {
            if (photo.albumId == GalleryFragment.FROM_DEVICE_GALLERY) VideoItem(Uri.parse(photo.id), photo.mimeType, photo.width, photo.height, photo.id.substringAfterLast('/'))
            else VideoItem(Uri.parse("${basePath}/${remotePath}/${photo.name}"), photo.mimeType, photo.width, photo.height, photo.id)
        }

        fun isPhotoAtLocal(position: Int): Boolean = currentList[position].location != GalleryFragment.LocalMedia.IS_REMOTE
        fun getPhotoAt(position: Int): NCShareViewModel.RemotePhoto = currentList[position].media
        fun getPhotoPosition(photoId: String): Int = photoId.substringAfterLast('/').let { id -> currentList.indexOfFirst { it.media.photo.id.substringAfterLast('/') == id }}
    }

    class SliderMediaDiffCallback : DiffUtil.ItemCallback<GalleryFragment.LocalMedia>() {
        override fun areItemsTheSame(oldItem: GalleryFragment.LocalMedia, newItem: GalleryFragment.LocalMedia): Boolean = oldItem.media.photo.id == newItem.media.photo.id
        override fun areContentsTheSame(oldItem: GalleryFragment.LocalMedia, newItem: GalleryFragment.LocalMedia): Boolean = true
    }

    companion object {
        private const val AUTO_HIDE_DELAY_MILLIS = 3000L // The number of milliseconds to wait after user interaction before hiding the system UI.

        private const val CONFIRM_DIALOG = "CONFIRM_DIALOG"
        private const val INFO_DIALOG = "INFO_DIALOG"
        private const val SHARE_OUT_DIALOG = "SHARE_OUT_DIALOG"
        private const val GALLERY_SLIDE_REQUEST_KEY = "GALLERY_SLIDE_REQUEST_KEY"
        private const val DELETE_REQUEST_KEY = "GALLERY_DELETE_REQUEST_KEY"

        private const val KEY_DISPLAY_OPTION = "KEY_DISPLAY_OPTION"

        private const val ARGUMENT_FOLDER = "ARGUMENT_FOLDER"
        private const val ARGUMENT_SUBFOLDER = "ARGUMENT_SUBFOLDER"

        @JvmStatic
        fun newInstance(folder: String, subFolder: String = "") = GallerySlideFragment().apply {
            arguments = Bundle().apply {
                putString(ARGUMENT_FOLDER, folder)
                putString(ARGUMENT_SUBFOLDER, subFolder)
            }
        }
    }
}