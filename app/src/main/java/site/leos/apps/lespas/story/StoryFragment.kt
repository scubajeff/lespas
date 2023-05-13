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

package site.leos.apps.lespas.story

import android.animation.Animator
import android.animation.ValueAnimator
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.drawable.Animatable2
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.asLiveData
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.ViewHolder
import androidx.viewpager2.widget.ViewPager2
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.Album
import site.leos.apps.lespas.album.AlbumViewModel
import site.leos.apps.lespas.album.BGMDialogFragment
import site.leos.apps.lespas.helper.SeamlessMediaSliderAdapter
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.helper.Tools.parcelable
import site.leos.apps.lespas.helper.VideoPlayerViewModel
import site.leos.apps.lespas.helper.VideoPlayerViewModelFactory
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.publication.NCShareViewModel
import java.io.File

class StoryFragment : Fragment() {
    private lateinit var album: Album
    private var isRemote: Boolean = false
    private var isPublication: Boolean = false
    private lateinit var serverPath: String
    private lateinit var serverFullPath: String
    private lateinit var publicationPath: String
    private lateinit var rootPath: String
    private var total = 0
    private var startAt = 0

    private val animationHandler = Handler(Looper.getMainLooper())
    private var slowSwipeAnimator: ValueAnimator? = null

    private lateinit var slider: ViewPager2
    private lateinit var pAdapter: StoryAdapter
    private lateinit var endSign: TextView

    private val albumModel: AlbumViewModel by activityViewModels()
    private val imageLoaderModel: NCShareViewModel by activityViewModels()
    private lateinit var playerViewModel: VideoPlayerViewModel

    private var previousTitleBarDisplayOption = 0

    private var hasBGM = false
    private lateinit var bgmPlayer: ExoPlayer
    private var fadingJob: Job? = null
    private lateinit var localPath: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        album = requireArguments().parcelable(KEY_ALBUM)!!
        isRemote = Tools.isRemoteAlbum(album)
        isPublication = album.eTag == Photo.ETAG_FAKE
        rootPath = Tools.getLocalRoot(requireContext())
        publicationPath = imageLoaderModel.getResourceRoot()
        serverPath = "${Tools.getRemoteHome(requireContext())}/${album.name}"
        serverFullPath = "${publicationPath}${serverPath}"

        startAt = savedInstanceState?.getInt(KEY_START_AT) ?: 0

        playerViewModel = ViewModelProvider(this, VideoPlayerViewModelFactory(requireActivity(), imageLoaderModel.getCallFactory(), imageLoaderModel.getPlayerCache(), slideshowMode = true))[VideoPlayerViewModel::class.java]
        // Advance to next slide after video playback end
        playerViewModel.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                super.onPlaybackStateChanged(playbackState)
                if (playbackState == Player.STATE_ENDED) {
                    advanceSlide(300)
                    if (slider.currentItem < total && !pAdapter.isSlideVideo(slider.currentItem + 1)) fadeInBGM()
                }
            }
        })

        pAdapter = StoryAdapter(
            requireContext(),
            Tools.getDisplayDimension(requireActivity()).first,
            playerViewModel,
            { rp ->
                with(rp.photo) {
                    val uri = when {
                        isPublication -> Uri.parse("${publicationPath}${rp.remotePath}/${name}")
                        isRemote && eTag != Photo.ETAG_NOT_YET_UPLOADED -> Uri.parse("${serverFullPath}/${name}")
                        else -> {
                            var fileName = "${rootPath}/${id}"
                            if (!(File(fileName).exists())) fileName = "${rootPath}/${name}"
                            Uri.parse("file:///$fileName")
                        }
                    }
                    SeamlessMediaSliderAdapter.VideoItem(uri, mimeType, width, height, id)
                }
            },
            { state -> },
            { photo, imageView, type -> if (type != NCShareViewModel.TYPE_NULL) imageLoaderModel.setImagePhoto(photo, imageView!!, type) },
            { view -> imageLoaderModel.cancelSetImagePhoto(view) },
        ).apply { stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY }

        // Prepare display
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) requireActivity().window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        // Wipe ActionBar
        (requireActivity() as AppCompatActivity).supportActionBar?.run {
            previousTitleBarDisplayOption = savedInstanceState?.run {
                // During fragment recreate, wipe actionbar to avoid flash
                wipeActionBar()

                getInt(KEY_DISPLAY_OPTION)
            } ?: displayOptions
        }
        Tools.goImmersive(requireActivity().window)
        @Suppress("DEPRECATION")
        requireActivity().window.decorView.setOnSystemUiVisibilityChangeListener { wipeActionBar() }

        // Prepare BGM playing
        localPath = Tools.getLocalRoot(requireContext())
        bgmPlayer = ExoPlayer.Builder(requireContext()).build()
        bgmPlayer.run {
            repeatMode = ExoPlayer.REPEAT_MODE_ONE
            playWhenReady = false

            var bgmFile = "$localPath/${album.id}${BGMDialogFragment.BGM_FILE_SUFFIX}"
            if (File(bgmFile).exists()) setBGM(bgmFile)
            else {
                // BGM for publication downloaded in cache folder in PublicationDetailFragment
                bgmFile = "${requireContext().cacheDir}/${album.id}${BGMDialogFragment.BGM_FILE_SUFFIX}"
                if (File(bgmFile).exists()) setBGM(bgmFile)
            }

            if (hasBGM) {
                bgmPlayer.volume = 0f
                bgmPlayer.prepare()
            }
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? = inflater.inflate(R.layout.fragment_story, container, false)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Prevent screen rotation during slideshow playback
        requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED

        slider = view.findViewById<ViewPager2>(R.id.pager).apply {
            adapter = pAdapter
            setPageTransformer(ZoomInPageTransformer())
            //setPageTransformer(MarginPageTransformer(320))
            isUserInputEnabled = false
        }

        slider.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageScrollStateChanged(state: Int) {
                super.onPageScrollStateChanged(state)

                // Activate slide animation here, rather than in onPageSelected, because onPageSelected is called before page transformer animation ends
                if (state == ViewPager2.SCROLL_STATE_IDLE) { animateSlide() }
            }

            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)

                startAt = position
            }
        })

        endSign = view.findViewById(R.id.the_end)

        if (isPublication) {
            imageLoaderModel.publicationContentMeta.asLiveData().observe(viewLifecycleOwner) { startSlideshow(it, startAt) }
        } else {
            albumModel.getAllPhotoInAlbum(album.id).observe(viewLifecycleOwner) { photos ->
                Tools.sortPhotos(photos, album.sortOrder).run {
                    val rpList = mutableListOf<NCShareViewModel.RemotePhoto>()
                    forEach { rpList.add(NCShareViewModel.RemotePhoto(it, if (isRemote && it.eTag != Photo.ETAG_NOT_YET_UPLOADED) serverPath else "")) }
                    startSlideshow(rpList, startAt)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        slider.beginFakeDrag()
        slider.fakeDragBy(1f)
        slider.endFakeDrag()
    }

    override fun onPause() {
        stopSlideshow(endOfSlideshow = false)
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_DISPLAY_OPTION, previousTitleBarDisplayOption)
        outState.putInt(KEY_START_AT, startAt)
    }

    override fun onDestroyView() {
        requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

        // Make sure onViewDetachedFromWindow called in SeamlessMediaSliderAdapter
        slider.adapter = null

        super.onDestroyView()
    }

    override fun onDestroy() {
        animationHandler.removeCallbacksAndMessages(null)

        bgmPlayer.release()

        Tools.quitImmersive(requireActivity().window)

        (requireActivity() as AppCompatActivity).run {
            supportActionBar?.run {
                displayOptions = previousTitleBarDisplayOption
                setBackgroundDrawable(ColorDrawable(Tools.getAttributeColor(requireContext(), android.R.attr.colorPrimary)))
            }
        }

        super.onDestroy()
    }

    private fun startSlideshow(photos: List<NCShareViewModel.RemotePhoto>, startAt: Int) {
        total = photos.size - 1
        pAdapter.setPhotos(photos) {
            checkSlide(startAt)
            //slider.setCurrentItem(startAt, true)
            animateSlide()
        }
    }

    private fun stopSlideshow(endOfSlideshow: Boolean) {
        animationHandler.removeCallbacksAndMessages(null)
        // Stop animations
        pAdapter.getViewHolderByPosition(slider.currentItem)?.apply {
            when(this) {
                is SeamlessMediaSliderAdapter<*>.PhotoViewHolder -> getPhotoView().run {
                    animate().cancel()
                    clearAnimation()
                }
                is SeamlessMediaSliderAdapter<*>.AnimatedViewHolder -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) getAnimatedDrawable().run {
                    clearAnimationCallbacks()
                    stop()
                }
                is SeamlessMediaSliderAdapter<*>.VideoViewHolder -> pause()
            }
        }
        slowSwipeAnimator?.let { if (it.isStarted) it.cancel() }

        if (endOfSlideshow) {
            animationHandler.postDelayed({
                endSign.isVisible = true
                // Shows the system bars by removing all the flags except for the ones that make the content appear under the system bars.
                @Suppress("DEPRECATION")
                requireActivity().window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
            }, 500)
            if (hasBGM) animationHandler.postDelayed({ fadeOutBGM() }, 1500)
        }
        else fadeOutBGM()
    }

    private fun animateSlide() {
        animationHandler.removeCallbacksAndMessages(null)
        // Delayed runnable is necessary because it takes a little bit of time for RecyclerView to return correct ViewHolder at the current position
        animationHandler.postDelayed({
            pAdapter.getViewHolderByPosition(slider.currentItem)?.apply {
                when (this) {
                    is SeamlessMediaSliderAdapter<*>.PhotoViewHolder -> {
                        fadeInBGM()
                        getPhotoView().let { photoView ->
                            // Stop any existing animation
                            photoView.animation?.cancel()
                            photoView.animation?.reset()
                            photoView.clearAnimation()

                            // Start a dreamy animation by scaling image a little by 5% in a long period of time of 5s
                            photoView.animate().setDuration(5000).scaleX(DREAMY_SCALE_FACTOR).scaleY(DREAMY_SCALE_FACTOR).setListener(object : Animator.AnimatorListener {
                                var finished = true
                                override fun onAnimationStart(animation: Animator) {}
                                override fun onAnimationRepeat(animation: Animator) {}
                                override fun onAnimationCancel(animation: Animator) { finished = false }

                                // Programmatically advance to the next slide after animation end
                                override fun onAnimationEnd(animation: Animator) { if (finished) advanceSlide() }
                            })
                        }
                    }

                    is SeamlessMediaSliderAdapter<*>.AnimatedViewHolder -> {
                        fadeInBGM()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            getAnimatedDrawable().let {
                                it.repeatCount = 1

                                // This callback is unregistered when this AnimatedViewHolder is detached from window in SeamlessMediaSliderAdapter
                                it.registerAnimationCallback(object : Animatable2.AnimationCallback() {
                                    override fun onAnimationEnd(drawable: Drawable?) {
                                        super.onAnimationEnd(drawable)
                                        advanceSlide()
                                    }
                                })

                                it.start()
                            }
                        }
                    }

                    is SeamlessMediaSliderAdapter<*>.VideoViewHolder -> {
                        // For video item, auto advance to next slide is handled by player's listener
                        play()
                    }
                }
            }
        }, 300)
    }

    private fun advanceSlide(delay: Long = 0) {
        if (slider.currentItem < total) {
            checkSlide(slider.currentItem + 1)
            // Slow down the default page transformation speed
            slowSwipeAnimator = ValueAnimator.ofInt(0, slider.width).apply {
                var prevValue = 0
                duration = 800
                //interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener {
                    (it.animatedValue as Int).run {
                        slider.fakeDragBy((prevValue - this).toFloat())
                        prevValue = this
                    }
                }
                addListener(object : Animator.AnimatorListener {
                    override fun onAnimationStart(animation: Animator) { slider.beginFakeDrag() }
                    override fun onAnimationEnd(animation: Animator) { slider.endFakeDrag() }
                    override fun onAnimationCancel(animation: Animator) { slider.endFakeDrag() }
                    override fun onAnimationRepeat(animation: Animator) {}
                })
                if (delay > 0) startDelay = delay
            }
            slowSwipeAnimator?.start()
        }
        else stopSlideshow(endOfSlideshow = true)
    }

    private fun checkSlide(position: Int) {
        // fade out BGM if next slide is video, do it here to prevent audio mix up
        if (pAdapter.isSlideVideo(position)) fadeOutBGM() else fadeInBGM()

        // With offscreenPageLimit greater than 0, the next slide will be preloaded, however if two consecutive slides are both video, pre-fectch of the 2nd one will ruin the playback of the 1st one
        slider.offscreenPageLimit = if (position < total && pAdapter.isSlideVideo(position) && pAdapter.isSlideVideo(position + 1)) ViewPager2.OFFSCREEN_PAGE_LIMIT_DEFAULT else 1
    }

    private fun setBGM(bgmFile: String) {
        bgmPlayer.setMediaItem(MediaItem.fromUri("file://${bgmFile}"))
        hasBGM = true
    }

    private fun fadeInBGM() {
        if (hasBGM) {
            fadingJob?.cancel()

            if (bgmPlayer.volume < 1f) fadingJob = lifecycleScope.launch {
                bgmPlayer.play()
                while (isActive) {
                    delay(75)

                    if (bgmPlayer.volume < 1f) bgmPlayer.volume += 0.05f
                    else {
                        bgmPlayer.volume = 1f
                        break
                    }
                }
            }
        }
    }

    private fun fadeOutBGM() {
        if (hasBGM) {
            fadingJob?.cancel()

            if (bgmPlayer.volume > 0f) fadingJob = lifecycleScope.launch {
                while (isActive) {
                    delay(75)

                    if (bgmPlayer.volume > 0f) bgmPlayer.volume -= 0.05f
                    else {
                        bgmPlayer.volume = 0f
                        bgmPlayer.pause()
                        break
                    }
                }
            }
        }
    }

    private fun wipeActionBar() {
        (requireActivity() as AppCompatActivity).supportActionBar?.run {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            displayOptions = 0
        }
    }

    // ViewPager2 PageTransformer for zooming out current slide and zooming in the next
    class ZoomInPageTransformer: ViewPager2.PageTransformer {
        override fun transformPage(page: View, position: Float) {
            page.apply {
                when(page) {
                    is ConstraintLayout -> {
                        // viewpager_item_exoplayer's first element is a ConstraintLayout, means viewpager_item_photo and viewpager_item_gif can't have the 1st element as ConstraintLayout
                    }
                    else -> {
                        when {
                            position <= -1f -> { // [-Infinity, -1)
                                // This page is way off-screen to the left
                                alpha = 0f
                                scaleX = 1f + DREAMY_SCALE_FACTOR
                                scaleY = 1f + DREAMY_SCALE_FACTOR
                            }
                            position < 0f -> { // [-1, 0)
                                // This page is moving off-screen

                                alpha = 1f + position
                                // Counteract the default slide transition
                                translationX = width * -position
                                // Move it above the page on the right, which is the one becoming the center piece
                                translationZ = 1f
                                (DREAMY_SCALE_FACTOR - position).run {
                                    scaleX = this
                                    scaleY = this
                                }
                            }
                            position == 0f -> {
                                alpha = 1f
                                translationX = 0f
                                translationZ = 0f
                                // keep scale factor intact, remove glitch between dreamy zoom and slide zoom out
                            }
                            position <= 1f -> { // (0, 1]
                                // This page is moving into screen

                                alpha = 1f - position
                                // Counteract the default slide transition
                                translationX = width * -position
                                translationZ = 0f
                                //(0.5f * (1 - position) + 0.5f).run {
                                //(0.5f * (2f - position)).run {
                                (1f - position * 0.5f).run {
                                    scaleX = this
                                    scaleY = this
                                }
                            }
                            else -> { // (1, +Infinity]
                                // This page is way off-screen to the right
                                alpha = 0f
                                translationZ = -1f
                                scaleX = 0.5f
                                scaleY = 0.5f
                            }
                        }
                    }
                }
            }
        }
    }

    class StoryAdapter(context: Context, displayWidth: Int, playerViewModel: VideoPlayerViewModel, private val videoItemLoader: (NCShareViewModel.RemotePhoto) -> VideoItem, clickListener: (Boolean?) -> Unit, imageLoader: (NCShareViewModel.RemotePhoto, ImageView?, String) -> Unit, cancelLoader: (View) -> Unit
    ): SeamlessMediaSliderAdapter<NCShareViewModel.RemotePhoto>(context, displayWidth, PhotoDiffCallback(), playerViewModel, clickListener, imageLoader, cancelLoader) {
        override fun getVideoItem(position: Int): VideoItem = videoItemLoader(getItem(position))
        override fun getItemTransitionName(position: Int): String = getItem(position).photo.id
        override fun getItemMimeType(position: Int): String = getItem(position).photo.mimeType

        fun setPhotos(photos: List<NCShareViewModel.RemotePhoto>, callback: () -> Unit) { submitList(photos.toMutableList()) { callback() }}

        fun isSlideVideo(position: Int): Boolean = currentList[position].photo.mimeType.startsWith("video")

        // Maintaining a map between adapter position and it's ViewHolder
        private val vhMap = HashMap<ViewHolder, Int>()
        fun getViewHolderByPosition(position: Int): ViewHolder? {
            vhMap.entries.forEach { if (it.value == position) return it.key }
            return null
        }
        override fun onViewAttachedToWindow(holder: ViewHolder) {
            super.onViewAttachedToWindow(holder)
            vhMap[holder] = holder.bindingAdapterPosition
        }

        override fun onViewDetachedFromWindow(holder: ViewHolder) {
            vhMap.remove(holder)
            super.onViewDetachedFromWindow(holder)
        }

        override fun onViewRecycled(holder: ViewHolder) {
            vhMap.remove(holder)
            super.onViewRecycled(holder)
        }

        override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
            vhMap.clear()
            super.onDetachedFromRecyclerView(recyclerView)
        }
    }

    class PhotoDiffCallback: DiffUtil.ItemCallback<NCShareViewModel.RemotePhoto>() {
        override fun areItemsTheSame(oldItem: NCShareViewModel.RemotePhoto, newItem: NCShareViewModel.RemotePhoto): Boolean = oldItem.photo.id == newItem.photo.id
        override fun areContentsTheSame(oldItem: NCShareViewModel.RemotePhoto, newItem: NCShareViewModel.RemotePhoto): Boolean = oldItem.photo.id == newItem.photo.id
    }

    companion object {
        private const val DREAMY_SCALE_FACTOR = 1.05f
        private const val KEY_DISPLAY_OPTION = "KEY_DISPLAY_OPTION"
        private const val KEY_START_AT = "KEY_START_AT"

        private const val KEY_ALBUM = "ALBUM"

        @JvmStatic
        fun newInstance(album: Album) = StoryFragment().apply { arguments = Bundle().apply { putParcelable(KEY_ALBUM, album) }}
    }
}