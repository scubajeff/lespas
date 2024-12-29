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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.SharedElementCallback
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.github.chrisbanes.photoview.PhotoView
import com.google.android.material.transition.MaterialContainerTransform
import kotlinx.coroutines.launch
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.MediaSliderTransitionListener
import site.leos.apps.lespas.helper.SeamlessMediaSliderAdapter
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.publication.NCShareViewModel

class ObjectSearchSlideFragment : Fragment() {
    private lateinit var window: Window
    private var previousOrientationSetting = 0
    private var autoRotate = false
    private lateinit var currentPhotoId: String

    private lateinit var slider: ViewPager2
    private lateinit var pAdapter: PhotoSlideAdapter

    private val imageLoaderModel: NCShareViewModel by activityViewModels()
    private val searchModel: SearchFragment.SearchModel by viewModels(ownerProducer = { requireParentFragment() }) { SearchFragment.SearchModelFactory(requireActivity().application, imageLoaderModel)}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        currentPhotoId = requireArguments().getString(KEY_SCROLL_TO).toString()
        pAdapter = PhotoSlideAdapter(
            requireContext(),
            Tools.getDisplayDimension(requireActivity()).first,
            { state -> },
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

        return inflater.inflate(R.layout.fragment_photoslide, container, false)
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
                        pAdapter.getPhotoAt(position).run {
                            if (autoRotate) requireActivity().requestedOrientation = if (this.width > this.height) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            currentPhotoId = this.id
                            //captionTextView.text = caption
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

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    searchModel.objectDetectResult.collect { photos -> pAdapter.submitList(photos.map { it.remotePhoto }.toMutableList()) { slider.setCurrentItem(pAdapter.getPhotoPosition(currentPhotoId), false) }}
                }
            }
        }
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

    class PhotoSlideAdapter(context: Context, displayWidth: Int, clickListener: (Boolean?) -> Unit, imageLoader: (NCShareViewModel.RemotePhoto, ImageView?, String) -> Unit, cancelLoader: (View) -> Unit
    ) : SeamlessMediaSliderAdapter<NCShareViewModel.RemotePhoto>(context, displayWidth, PhotoDiffCallback(), null, clickListener, imageLoader, cancelLoader) {
        override fun getVideoItem(position: Int): VideoItem = VideoItem(Uri.EMPTY, "", 0, 0, "")
        override fun getItemTransitionName(position: Int): String = (getItem(position) as NCShareViewModel.RemotePhoto).photo.id
        override fun getItemMimeType(position: Int): String  = (getItem(position) as NCShareViewModel.RemotePhoto).photo.mimeType

        fun getPhotoAt(position: Int): Photo = currentList[position].photo
        fun getPhotoPosition(photoId: String): Int = currentList.indexOfFirst { it.photo.id == photoId }
    }

    class PhotoDiffCallback: DiffUtil.ItemCallback<NCShareViewModel.RemotePhoto>() {
        override fun areItemsTheSame(oldItem: NCShareViewModel.RemotePhoto, newItem: NCShareViewModel.RemotePhoto): Boolean = oldItem.photo.id == newItem.photo.id
        override fun areContentsTheSame(oldItem: NCShareViewModel.RemotePhoto, newItem: NCShareViewModel.RemotePhoto): Boolean = true
    }

    companion object {
        private const val KEY_SCROLL_TO = "KEY_SCROLL_TO"

        @JvmStatic
        fun newInstance(scrollToId: String) = ObjectSearchSlideFragment().apply { arguments = Bundle().apply { putString(KEY_SCROLL_TO, scrollToId) } }
    }
}