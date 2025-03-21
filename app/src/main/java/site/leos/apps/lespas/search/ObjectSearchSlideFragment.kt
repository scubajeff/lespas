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

package site.leos.apps.lespas.search

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.SharedElementCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
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
import site.leos.apps.lespas.gallery.GalleryFragment
import site.leos.apps.lespas.helper.ConfirmDialogFragment
import site.leos.apps.lespas.helper.MediaSliderTransitionListener
import site.leos.apps.lespas.helper.MetaDataDialogFragment
import site.leos.apps.lespas.helper.SeamlessMediaSliderAdapter
import site.leos.apps.lespas.helper.ShareOutDialogFragment
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.publication.NCShareViewModel
import site.leos.apps.lespas.sync.ActionViewModel

class ObjectSearchSlideFragment : Fragment() {
    private lateinit var window: Window
    private var previousOrientationSetting = 0
    private var autoRotate = false

    private lateinit var controlsContainer: LinearLayout
    private lateinit var captionArea: LinearLayout
    private lateinit var captionTextView: TextView
    private lateinit var slider: ViewPager2
    private lateinit var pAdapter: PhotoSlideAdapter

    private val imageLoaderModel: NCShareViewModel by activityViewModels()
    private val actionModel: ActionViewModel by viewModels()
    private val searchModel: SearchFragment.SearchModel by viewModels(ownerProducer = { requireParentFragment() }) { SearchFragment.SearchModelFactory(requireActivity().application, imageLoaderModel, actionModel)}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        pAdapter = PhotoSlideAdapter(
            requireContext(),
            Tools.getDisplayDimension(requireActivity()).first,
            { state -> toggleBottomControl(state) },
            { photo, imageView, type -> imageLoaderModel.setImagePhoto(photo, imageView!!, type) { startPostponedEnterTransition() }},
            { imageView -> imageLoaderModel.cancelSetImagePhoto(imageView) },
        ).apply { stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY }

        // Adjusting the shared element mapping
        setEnterSharedElementCallback(object : SharedElementCallback() {
            override fun onMapSharedElements(names: MutableList<String>?, sharedElements: MutableMap<String, View>?) {
                if (names?.isNotEmpty() == true) slider.getChildAt(0)?.findViewById<View>(R.id.media)?.apply { sharedElements?.put(names[0], this) }
            }
        })

        this.window = requireActivity().window

        previousOrientationSetting = requireActivity().requestedOrientation
        autoRotate = PreferenceManager.getDefaultSharedPreferences(requireContext()).getBoolean(requireContext().getString(R.string.auto_rotate_perf_key), false)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        (activity as AppCompatActivity).supportActionBar?.hide()
        Tools.setImmersive(window, true)

        return inflater.inflate(R.layout.fragment_object_search_slide, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        postponeEnterTransition()

        slider = view.findViewById<ViewPager2>(R.id.pager).apply {
            adapter = pAdapter

            // Use reflection to reduce Viewpager2 slide sensitivity, so that PhotoView inside can zoom presently
            val recyclerView = (ViewPager2::class.java.getDeclaredField("mRecyclerView").apply { isAccessible = true }).get(this) as RecyclerView
            (RecyclerView::class.java.getDeclaredField("mTouchSlop")).apply {
                isAccessible = true
                set(recyclerView, (get(recyclerView) as Int) * 4)
            }

            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageScrollStateChanged(state: Int) {
                    super.onPageScrollStateChanged(state)
                    //if (state == ViewPager2.SCROLL_STATE_SETTLING) handlerBottomControl.post(hideBottomControls)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && state == ViewPager2.SCROLL_STATE_IDLE) slider.getChildAt(0)?.findViewById<View>(R.id.media)?.apply {
                        window.colorMode = if (this is PhotoView && getTag(R.id.HDR_TAG) as Boolean? == true) ActivityInfo.COLOR_MODE_HDR else ActivityInfo.COLOR_MODE_DEFAULT
                    }
                }
                override fun onPageSelected(position: Int) {
                    try {
                        pAdapter.getPhotoAt(position).photo.run {
                            if (autoRotate) requireActivity().requestedOrientation = if (this.width > this.height) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
/*
                            if (albumId != GalleryFragment.FROM_DEVICE_GALLERY && albumId != GalleryFragment.FROM_ARCHIVE && caption.isNotEmpty()) {
                                captionTextView.text = caption
                                captionArea.isVisible = true
                            } else captionArea.isVisible = false
*/
                            captionTextView.text = when(albumId) {
                                GalleryFragment.FROM_DEVICE_GALLERY -> "From Gallery"
                                GalleryFragment.FROM_ARCHIVE -> "From archive"
                                else -> "From album"
                            }
                        }
                    } catch (_: IndexOutOfBoundsException) {}

                    searchModel.setCurrentSlideItem(position)
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
        captionArea = view.findViewById(R.id.caption_area)
        captionTextView = view.findViewById(R.id.caption)
        view.findViewById<Button>(R.id.info_button).run {
            setOnClickListener {
                handlerBottomControl.post(hideBottomControls)
                if (parentFragmentManager.findFragmentByTag(INFO_DIALOG) == null) MetaDataDialogFragment.newInstance(pAdapter.getPhotoAt(slider.currentItem)).show(parentFragmentManager, INFO_DIALOG)
            }
        }
        view.findViewById<Button>(R.id.remove_button).run {
            setOnClickListener {
                handlerBottomControl.post(hideBottomControls)
                if (parentFragmentManager.findFragmentByTag(REMOVE_DIALOG) == null) ConfirmDialogFragment.newInstance(getString(R.string.confirm_delete), positiveButtonText = getString(R.string.yes_delete), individualKey = DELETE_REQUEST_KEY, requestKey = OBJECT_SEARCH_SLIDE_REQUEST_KEY).show(parentFragmentManager, REMOVE_DIALOG)
            }
        }
        view.findViewById<Button>(R.id.share_button).run {
            setOnClickListener {
                pAdapter.getPhotoAt(slider.currentItem).let { remotePhoto ->
                    handlerBottomControl.post(hideBottomControls)
                    if (parentFragmentManager.findFragmentByTag(SHARE_OUT_DIALOG) == null) ShareOutDialogFragment.newInstance(mimeTypes = listOf(remotePhoto.photo.mimeType), showRemoveAfterwards = false)!!.show(parentFragmentManager, SHARE_OUT_DIALOG)    // ?: run { searchModel.shareOut(listOf(remotePhoto), strip = false, lowResolution = false, removeAfterwards = false) }
                }
            }
        }

        // Remove photo confirm dialog result handler
        parentFragmentManager.setFragmentResultListener(OBJECT_SEARCH_SLIDE_REQUEST_KEY, viewLifecycleOwner) { _, bundle ->
            when(bundle.getString(ConfirmDialogFragment.INDIVIDUAL_REQUEST_KEY)) {
                DELETE_REQUEST_KEY -> if (bundle.getBoolean(ConfirmDialogFragment.CONFIRM_DIALOG_RESULT_KEY, false)) searchModel.delete(listOf(pAdapter.getPhotoAt(slider.currentItem)))
            }
        }

        // Share out dialog result handler
        parentFragmentManager.setFragmentResultListener(ShareOutDialogFragment.SHARE_OUT_DIALOG_RESULT_KEY, viewLifecycleOwner) { _, bundle ->
            if (bundle.getBoolean(ShareOutDialogFragment.SHARE_OUT_DIALOG_RESULT_KEY, true))
                searchModel.shareOut(
                    photos = listOf(pAdapter.getPhotoAt(slider.currentItem)),
                    strip = bundle.getBoolean(ShareOutDialogFragment.STRIP_RESULT_KEY, false),
                    lowResolution = bundle.getBoolean(ShareOutDialogFragment.LOW_RESOLUTION_RESULT_KEY, false),
                    removeAfterwards = bundle.getBoolean(ShareOutDialogFragment.REMOVE_AFTERWARDS_RESULT_KEY, false),
                )
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    searchModel.objectDetectResult.collect { photos -> pAdapter.submitList(photos.map { it.remotePhoto }.toMutableList()) { slider.setCurrentItem(searchModel.getCurrentSlideItem(), false) }}
                }
            }
        }
    }

    override fun onStop() {
        searchModel.setCurrentSlideItem(slider.currentItem)
        super.onStop()
    }

    override fun onDestroyView() {
        slider.adapter = null

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

    class PhotoSlideAdapter(context: Context, displayWidth: Int, clickListener: (Boolean?) -> Unit, imageLoader: (NCShareViewModel.RemotePhoto, ImageView?, String) -> Unit, cancelLoader: (View) -> Unit
    ) : SeamlessMediaSliderAdapter<NCShareViewModel.RemotePhoto>(context, displayWidth, PhotoDiffCallback(), null, clickListener, imageLoader, cancelLoader) {
        override fun getVideoItem(position: Int): VideoItem = VideoItem(Uri.EMPTY, "", 0, 0, "")
        override fun getItemTransitionName(position: Int): String = (getItem(position) as NCShareViewModel.RemotePhoto).photo.id
        override fun getItemMimeType(position: Int): String  = (getItem(position) as NCShareViewModel.RemotePhoto).photo.mimeType

        fun getPhotoAt(position: Int): NCShareViewModel.RemotePhoto = currentList[position]
    }

    class PhotoDiffCallback: DiffUtil.ItemCallback<NCShareViewModel.RemotePhoto>() {
        override fun areItemsTheSame(oldItem: NCShareViewModel.RemotePhoto, newItem: NCShareViewModel.RemotePhoto): Boolean = oldItem.photo.id == newItem.photo.id
        override fun areContentsTheSame(oldItem: NCShareViewModel.RemotePhoto, newItem: NCShareViewModel.RemotePhoto): Boolean = true
    }

    companion object {
        private const val AUTO_HIDE_DELAY_MILLIS = 3000L // The number of milliseconds to wait after user interaction before hiding the system UI.

        private const val INFO_DIALOG = "INFO_DIALOG"
        private const val REMOVE_DIALOG = "REMOVE_DIALOG"
        private const val SHARE_OUT_DIALOG = "SHARE_OUT_DIALOG"
        private const val DELETE_REQUEST_KEY = "DELETE_REQUEST_KEY"
        private const val OBJECT_SEARCH_SLIDE_REQUEST_KEY = "OBJECT_SEARCH_SLIDE_REQUEST_KEY"

        @JvmStatic
        fun newInstance() = ObjectSearchSlideFragment()
    }
}