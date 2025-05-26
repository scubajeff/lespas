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

package site.leos.apps.lespas.tv

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.panoramagl.PLManager
import com.panoramagl.PLSphericalPanorama
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.Album
import site.leos.apps.lespas.album.AlbumViewModel
import site.leos.apps.lespas.helper.SeamlessMediaSliderAdapter
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.helper.Tools.parcelable
import site.leos.apps.lespas.helper.VideoPlayerViewModel
import site.leos.apps.lespas.helper.VideoPlayerViewModelFactory
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.publication.NCShareViewModel
import site.leos.apps.lespas.publication.RemoteMediaFragment.PhotoDiffCallback

class TVSliderFragment: Fragment() {
    private lateinit var mediaAdapter: RemoteMediaAdapter
    private lateinit var slider: ViewPager2

    private val albumModel: AlbumViewModel by activityViewModels()
    private val imageLoaderModel: NCShareViewModel by activityViewModels()
    private lateinit var playerViewModel: VideoPlayerViewModel

    private val handler = Handler(Looper.getMainLooper())
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        playerViewModel = ViewModelProvider(this, VideoPlayerViewModelFactory(requireActivity(), imageLoaderModel.getCallFactory(), imageLoaderModel.getPlayerCache(), imageLoaderModel.getSessionVolumePercentage()))[VideoPlayerViewModel::class.java]

        mediaAdapter= RemoteMediaAdapter(
            requireContext(),
            Tools.getDisplayDimension(requireActivity()).first,
            imageLoaderModel.getResourceRoot(),
            playerViewModel,
            { state ->  },
            { media, imageView, type -> if (type != NCShareViewModel.TYPE_NULL) imageLoaderModel.setImagePhoto(media, imageView!!, NCShareViewModel.TYPE_FULL) },
            { media, imageView, plManager, panorama -> imageLoaderModel.setImagePhoto(media, imageView!!, NCShareViewModel.TYPE_PANORAMA, plManager, panorama) },
            { view -> imageLoaderModel.cancelSetImagePhoto(view) },
            { delta ->
                slider.beginFakeDrag()
                slider.fakeDragBy(delta * slider.width)
                slider.endFakeDrag()
            }
        )

        var isShared = false
        requireArguments().parcelable<NCShareViewModel.ShareWithMe>(KEY_SHARED)?.let { shared ->
            lifecycleScope.launch(Dispatchers.IO) { imageLoaderModel.getRemotePhotoList(shared, true) }
            isShared = true
        }
        setFragmentResult(RESULT_REQUEST_KEY, bundleOf(KEY_SHARED to isShared))
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        (activity as AppCompatActivity).supportActionBar?.hide()
        Tools.setImmersive(requireActivity().window, true)

        return inflater.inflate(R.layout.fragment_tv_slider, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        slider = view.findViewById<ViewPager2>(R.id.slider).apply {
            adapter = mediaAdapter
            requestFocus()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                requireArguments().parcelable<Album>(KEY_ALBUM)?.let { album -> launch {
                    albumModel.getAllPhotoInAlbum(album.id).collect { photos ->
                        val serverPath = if (album.isRemote()) "${Tools.getRemoteHome(requireContext())}/${album.name}" else ""

                        // Panorama photo need focus to play with, filter them now
                        mediaAdapter.submitList(Tools.sortPhotos(photos.filter { it.mimeType != Tools.PANORAMA_MIMETYPE }, album.sortOrder).map { NCShareViewModel.RemotePhoto(it, serverPath) })
                    }
                }}
                requireArguments().parcelable<NCShareViewModel.ShareWithMe>(KEY_SHARED)?.let { launch { imageLoaderModel.publicationContentMeta.collect { mediaAdapter.submitList(it) }}}
            }
        }
    }

    override fun onResume() {
        super.onResume()
        mediaAdapter.setPauseVideo(true)
    }

    override fun onStop() {
        try { if (mediaAdapter.getPhotoAt(slider.currentItem).mimeType.startsWith("video")) handler.postDelayed({ playerViewModel.pause(Uri.EMPTY) }, 300) } catch (_: IndexOutOfBoundsException) {}

        super.onStop()
    }

    class RemoteMediaAdapter(context: Context, displayWidth: Int, private val basePath: String, playerViewModel: VideoPlayerViewModel,
        clickListener: (Boolean?) -> Unit, imageLoader: (NCShareViewModel.RemotePhoto, ImageView?, type: String) -> Unit, panoLoader: (NCShareViewModel.RemotePhoto, ImageView?, PLManager, PLSphericalPanorama) -> Unit, cancelLoader: (View) -> Unit,
        private val scrollListener: (Float) -> Unit,
    ): SeamlessMediaSliderAdapter<NCShareViewModel.RemotePhoto>(context, displayWidth, PhotoDiffCallback(), playerViewModel, clickListener, imageLoader, panoLoader, cancelLoader) {
        fun getPhotoAt(position: Int): Photo = currentList[position].photo

        override fun getVideoItem(position: Int): VideoItem = with(getItem(position) as NCShareViewModel.RemotePhoto) { VideoItem("$basePath$remotePath/${photo.name}".toUri(), photo.mimeType, photo.width, photo.height, photo.id) }
        override fun getItemTransitionName(position: Int): String = (getItem(position) as NCShareViewModel.RemotePhoto).photo.id
        override fun getItemMimeType(position: Int): String = (getItem(position) as NCShareViewModel.RemotePhoto).photo.mimeType

        override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
            super.onAttachedToRecyclerView(recyclerView)

            recyclerView.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            recyclerView.setOnKeyListener(object : View.OnKeyListener {
                val lm = recyclerView.layoutManager as LinearLayoutManager
                override fun onKey(v: View?, keyCode: Int, event: KeyEvent?): Boolean {
                    if (event?.action == KeyEvent.ACTION_DOWN) {
                        when(keyCode) {
                            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_BUTTON_R1 -> {
                                scrollListener(if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_BUTTON_L1) 1f else -1f)
                                return true
                            }
                            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_A -> {
                                recyclerView.findViewHolderForLayoutPosition(lm.findFirstVisibleItemPosition())?.let { viewHolder ->
                                    if (viewHolder is SeamlessMediaSliderAdapter<*>.VideoViewHolder) { viewHolder.playOrPauseOnTV() }
                                    return true
                                }
                            }
                        }
                    }

                    return false
                }
            })
        }

        override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
            recyclerView.setOnKeyListener(null)
            super.onDetachedFromRecyclerView(recyclerView)
        }
    }

    companion object {
        const val RESULT_REQUEST_KEY = "RESULT_REQUEST_KEY"

        private const val KEY_ALBUM = "KEY_ALBUM"
        const val KEY_SHARED = "KEY_SHARED"

        @JvmStatic
        fun newInstance(album: Album?, shared: NCShareViewModel.ShareWithMe?) = TVSliderFragment().apply {
            arguments = Bundle().apply {
                putParcelable(KEY_ALBUM, album)
                putParcelable(KEY_SHARED, shared)
            }
        }
    }
}