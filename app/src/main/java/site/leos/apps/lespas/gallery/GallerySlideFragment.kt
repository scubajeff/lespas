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
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.transition.Slide
import android.transition.TransitionManager
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.SharedElementCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.transition.MaterialContainerTransform
import kotlinx.coroutines.launch
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.ConfirmDialogFragment
import site.leos.apps.lespas.helper.MediaSliderTransitionListener
import site.leos.apps.lespas.helper.MetaDataDialogFragment
import site.leos.apps.lespas.helper.SeamlessMediaSliderAdapter
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.helper.VideoPlayerViewModel
import site.leos.apps.lespas.helper.VideoPlayerViewModelFactory
import site.leos.apps.lespas.publication.NCShareViewModel
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

class GallerySlideFragment : Fragment() {
    private lateinit var mediaAdapter: MediaSlideAdapter
    private lateinit var mediaList: ViewPager2
    private lateinit var controlsContainer: ConstraintLayout
    private lateinit var tvDate: TextView
    private lateinit var tvSize: TextView
    private lateinit var removeButton: ImageButton

    private val galleryModel: GalleryFragment.GalleryViewModel by viewModels(ownerProducer = { requireParentFragment() })
    private val imageLoaderModel: NCShareViewModel by activityViewModels()
    private lateinit var playerViewModel: VideoPlayerViewModel

    private lateinit var window: Window

    private var previousOrientationSetting = 0
    private var previousTitleBarDisplayOption = 0
    private var stripExif = "2"
    private var autoRotate = false
    private var nextInLine = ""

    private lateinit var remoteArchiveBaseFolder: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        remoteArchiveBaseFolder = Tools.getCameraArchiveHome(requireContext())
        playerViewModel = ViewModelProvider(this, VideoPlayerViewModelFactory(requireActivity(), imageLoaderModel.getCallFactory(), imageLoaderModel.getPlayerCache()))[VideoPlayerViewModel::class.java]

        mediaAdapter = MediaSlideAdapter(
            requireContext(),
            "${imageLoaderModel.getResourceRoot()}${remoteArchiveBaseFolder}",
            Tools.getDisplayDimension(requireActivity()).first,
            playerViewModel,
            { state -> toggleSystemUI(state) },
            { remotePhoto, imageView, type ->
                if (type == NCShareViewModel.TYPE_NULL) startPostponedEnterTransition()
                else imageLoaderModel.setImagePhoto(remotePhoto, imageView!!, type) { startPostponedEnterTransition() }
            },
            { view -> imageLoaderModel.cancelSetImagePhoto(view) },
        ).apply { stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY }

        previousOrientationSetting = requireActivity().requestedOrientation
        PreferenceManager.getDefaultSharedPreferences(requireContext()).apply {
            autoRotate = getBoolean(requireContext().getString(R.string.auto_rotate_perf_key), false)
            stripExif = getString(getString(R.string.strip_exif_pref_key), getString(R.string.strip_ask_value))!!
        }

        // Listener for our UI controls to show/hide with System UI
        this.window = requireActivity().window
        @Suppress("DEPRECATION")
        window.decorView.setOnSystemUiVisibilityChangeListener { visibility -> followSystemBar(visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) }


        // Adjusting the shared element mapping
        setEnterSharedElementCallback(object : SharedElementCallback() {
            override fun onMapSharedElements(names: MutableList<String>?, sharedElements: MutableMap<String, View>?) {
                if (names?.isNotEmpty() == true) mediaList.getChildAt(0)?.findViewById<View>(R.id.media)?.run { sharedElements?.put(names[0], this) }
            }
        })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) requireActivity().window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        postponeEnterTransition()

        // Wipe ActionBar
        (requireActivity() as AppCompatActivity).supportActionBar?.run {
            previousTitleBarDisplayOption = savedInstanceState?.run {
                // During fragment recreate, wipe actionbar to avoid flash
                wipeActionBar()

                getInt(KEY_DISPLAY_OPTION)
            } ?: displayOptions
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? = inflater.inflate(R.layout.fragment_gallery_slide, container, false)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

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
                    if (state == ViewPager2.SCROLL_STATE_SETTLING) handlerBottomControl.post(hideSystemUI)
                }

                @SuppressLint("SetTextI18n")
                override fun onPageSelected(position: Int) {
                    super.onPageSelected(position)

                    try {
                        mediaAdapter.getPhotoAt(position).run {
                            if (autoRotate) requireActivity().requestedOrientation = if (this.photo.width > this.photo.height) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            galleryModel.setCurrentPhotoId(photo.id)
                            tvDate.text = "${photo.dateTaken.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())}, ${photo.dateTaken.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))}"
                            tvSize.text = Tools.humanReadableByteCountSI(photo.shareId.toLong())
                            removeButton.isEnabled = photo.lastModified != LocalDateTime.MAX
                        }
                    } catch (_: IndexOutOfBoundsException) {}

                }
            })
        }

        mediaList.doOnLayout {
            // Get into immersive mode
            Tools.goImmersive(window, savedInstanceState == null)
        }

        sharedElementEnterTransition = MaterialContainerTransform().apply {
            duration = resources.getInteger(android.R.integer.config_mediumAnimTime).toLong()
            scrimColor = Color.TRANSPARENT
            fadeMode = MaterialContainerTransform.FADE_MODE_CROSS
        }.apply { addListener(MediaSliderTransitionListener(mediaList)) }

        // Controls
        controlsContainer = view.findViewById<ConstraintLayout>(R.id.bottom_controls_container).apply {
            ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets->
                @Suppress("DEPRECATION")
                v.updatePadding(bottom = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) window.decorView.rootWindowInsets.stableInsetBottom else with(window.windowManager.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars())) { bottom - top })
                insets
            }
        }
        view.findViewById<ImageButton>(R.id.info_button).setOnClickListener {
            if (parentFragmentManager.findFragmentByTag(INFO_DIALOG) == null) mediaAdapter.getPhotoAt(mediaList.currentItem).photo.let { photo ->
                (if (photo.albumId == GalleryFragment.FROM_DEVICE_GALLERY) MetaDataDialogFragment.newInstance(photo) else MetaDataDialogFragment.newInstance(NCShareViewModel.RemotePhoto(photo, remoteArchiveBaseFolder))).show(parentFragmentManager, INFO_DIALOG)
            }
        }
        removeButton = view.findViewById<ImageButton>(R.id.remove_button).apply {
            setOnClickListener {
                nextInLine = when {
                    mediaList.currentItem < mediaAdapter.currentList.size - 1 -> mediaAdapter.getPhotoAt(mediaList.currentItem + 1).photo.id    // Item to be deleted is not the last one in the list, next in line will be the next one
                    mediaList.currentItem == 0 -> ""                                                                                                    // Item to be deleted is the last one in the list and the only one in list, next in line is ""
                    else -> mediaAdapter.getPhotoAt(mediaList.currentItem - 1).photo.id                                                         // Item to be deleted is the last one in the list and there are more than one left after deletion, next in line will be the previous one
                }
                mediaAdapter.getPhotoAt(mediaList.currentItem).photo.let { photo ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) galleryModel.remove(listOf(photo.id), nextInLine)
                    else if (parentFragmentManager.findFragmentByTag(CONFIRM_DIALOG) == null) ConfirmDialogFragment.newInstance(getString(R.string.confirm_delete), positiveButtonText = getString(R.string.yes_delete), requestKey = DELETE_REQUEST_KEY).show(parentFragmentManager, CONFIRM_DIALOG)
                }
            }
        }
        view.findViewById<ImageButton>(R.id.share_button).setOnClickListener {
            mediaAdapter.getPhotoAt(mediaList.currentItem).photo.let { photo ->
                if (stripExif == getString(R.string.strip_ask_value)) {
                    if (Tools.hasExif(photo.mimeType)) {
                        if (parentFragmentManager.findFragmentByTag(CONFIRM_DIALOG) == null) ConfirmDialogFragment.newInstance(getString(R.string.strip_exif_msg, getString(R.string.strip_exif_title)), requestKey = STRIP_REQUEST_KEY, positiveButtonText = getString(R.string.strip_exif_yes), negativeButtonText = getString(R.string.strip_exif_no), cancelable = true).show(parentFragmentManager, CONFIRM_DIALOG)
                    } else galleryModel.shareOut(listOf(photo.id), false,)
                } else galleryModel.shareOut(listOf(photo.id), stripExif == getString(R.string.strip_on_value),)
            }
        }
        view.findViewById<ImageButton>(R.id.lespas_button).setOnClickListener { galleryModel.add(listOf(mediaAdapter.getPhotoAt(mediaList.currentItem).photo.id)) }

        parentFragmentManager.setFragmentResultListener(ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY, viewLifecycleOwner) { _, bundle ->
            when(bundle.getString(ConfirmDialogFragment.INDIVIDUAL_REQUEST_KEY)) {
                DELETE_REQUEST_KEY -> if (bundle.getBoolean(ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY, false)) {
                    galleryModel.remove(listOf(mediaAdapter.getPhotoAt(mediaList.currentItem).photo.id), nextInLine)
                }
                STRIP_REQUEST_KEY -> galleryModel.shareOut(listOf(mediaAdapter.getPhotoAt(mediaList.currentItem).photo.id), bundle.getBoolean(ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY, false), false)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            galleryModel.medias.collect { localMedias ->
                localMedias?.let {
                    val folderArgument = requireArguments().getString(ARGUMENT_FOLDER) ?: ""
                    val photos = mutableListOf<NCShareViewModel.RemotePhoto>().apply {
                        (when {
                            folderArgument.isEmpty() -> localMedias.sortedByDescending { it.media.photo.dateTaken }
                            folderArgument.contains('/') -> localMedias.filter { it.fullPath == folderArgument }
                            else -> localMedias.filter { it.folder == folderArgument }
                        }).forEach { add(it.media) }
                    }

                    if (photos.isEmpty()) parentFragmentManager.popBackStack() else mediaAdapter.submitList(photos) { mediaList.setCurrentItem(mediaAdapter.getPhotoPosition(galleryModel.getCurrentPhotoId()), false) }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        playerViewModel.pause(Uri.EMPTY)
    }

    override fun onDestroyView() {
        mediaList.adapter = null

        super.onDestroyView()
    }

    override fun onDestroy() {
        // BACK TO NORMAL UI
        handlerBottomControl.removeCallbacksAndMessages(null)

        Tools.quitImmersive(window)

        (requireActivity() as AppCompatActivity).run {
            supportActionBar?.run {
                displayOptions = previousTitleBarDisplayOption
                setBackgroundDrawable(ColorDrawable(Tools.getAttributeColor(requireContext(), android.R.attr.colorPrimary)))
            }
            requestedOrientation = previousOrientationSetting
        }

        super.onDestroy()
    }

    // Toggle visibility of bottom controls and system decoView
    private val handlerBottomControl = Handler(Looper.getMainLooper())
    private fun toggleSystemUI(state: Boolean?) {
        handlerBottomControl.removeCallbacksAndMessages(null)
        handlerBottomControl.post(if (state ?: !controlsContainer.isVisible) showSystemUI else hideSystemUI)
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
    }

    // Delay hiding the system UI while interacting with controls, preventing the jarring behavior of controls going away
    @SuppressLint("ClickableViewAccessibility")
    private val delayHideTouchListener = View.OnTouchListener { _, _ ->
        handlerBottomControl.removeCallbacks(hideSystemUI)
        handlerBottomControl.postDelayed(hideSystemUI, AUTO_HIDE_DELAY_MILLIS)
        false
    }

    private fun followSystemBar(show: Boolean) {
        // TODO: Nasty exception handling here, but Android doesn't provide method to unregister System UI/Insets changes listener
        try {
            TransitionManager.beginDelayedTransition(controlsContainer, if (Build.VERSION.SDK_INT > Build.VERSION_CODES.R) android.transition.Fade() else Slide(Gravity.BOTTOM).apply { duration = 50 })
            controlsContainer.visibility = if (show) View.VISIBLE else View.GONE
        } catch (e: UninitializedPropertyAccessException) { e.printStackTrace() }

        // Although it seems like repeating this everytime when showing system UI, wiping actionbar here rather than when fragment creating will prevent action bar flashing
        wipeActionBar()
    }

    private fun wipeActionBar() {
        (requireActivity() as AppCompatActivity).supportActionBar?.run {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            displayOptions = 0
        }
    }

    class MediaSlideAdapter(
        context: Context, private val basePath: String, displayWidth: Int, playerViewModel: VideoPlayerViewModel,
        clickListener: (Boolean?) -> Unit, imageLoader: (NCShareViewModel.RemotePhoto, ImageView?, String) -> Unit, cancelLoader: (View) -> Unit
    ): SeamlessMediaSliderAdapter<NCShareViewModel.RemotePhoto>(context, displayWidth, GalleryFolderViewFragment.MediaDiffCallback(), playerViewModel, clickListener, imageLoader, cancelLoader) {
        override fun getItemTransitionName(position: Int): String = getItem(position).photo.id
        override fun getItemMimeType(position: Int): String = getItem(position).photo.mimeType
        override fun getVideoItem(position: Int): VideoItem = with((getItem(position) as NCShareViewModel.RemotePhoto).photo) {
            if (albumId == GalleryFragment.FROM_DEVICE_GALLERY) VideoItem(Uri.parse(id), mimeType, width, height, id.substringAfterLast('/'))
            else VideoItem(Uri.parse("${basePath}/${name}"), mimeType, width, height, id)
        }

        fun getPhotoAt(position: Int): NCShareViewModel.RemotePhoto = currentList[position]
        fun getPhotoPosition(photoId: String): Int = photoId.substringAfterLast('/').let { id -> currentList.indexOfFirst { it.photo.id.substringAfterLast('/') == id }}
    }

    class MediaDiffCallback : DiffUtil.ItemCallback<NCShareViewModel.RemotePhoto>() {
        override fun areItemsTheSame(oldItem: NCShareViewModel.RemotePhoto, newItem: NCShareViewModel.RemotePhoto): Boolean = oldItem.photo.id == newItem.photo.id
        override fun areContentsTheSame(oldItem: NCShareViewModel.RemotePhoto, newItem: NCShareViewModel.RemotePhoto): Boolean = true
    }

    companion object {
        private const val AUTO_HIDE_DELAY_MILLIS = 3000L // The number of milliseconds to wait after user interaction before hiding the system UI.

        private const val CONFIRM_DIALOG = "CONFIRM_DIALOG"
        private const val INFO_DIALOG = "INFO_DIALOG"
        private const val STRIP_REQUEST_KEY = "GALLERY_STRIP_REQUEST_KEY"
        private const val DELETE_REQUEST_KEY = "GALLERY_DELETE_REQUEST_KEY"

        private const val KEY_DISPLAY_OPTION = "KEY_DISPLAY_OPTION"

        private const val ARGUMENT_FOLDER = "ARGUMENT_FOLDER"

        @JvmStatic
        fun newInstance(folder: String) = GallerySlideFragment().apply { arguments = Bundle().apply { putString(ARGUMENT_FOLDER, folder) }}
    }
}