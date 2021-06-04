package site.leos.apps.lespas.publication

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.ImageButton
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.app.SharedElementCallback
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.github.chrisbanes.photoview.PhotoView
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.SimpleExoPlayer
import com.google.android.exoplayer2.ui.PlayerView
import com.google.android.material.transition.MaterialContainerTransform
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.ImageLoaderViewModel
import java.io.File
import java.time.LocalDateTime

class RemoteMediaFragment: Fragment() {
    private lateinit var window: Window
    private lateinit var slider: ViewPager2
    private lateinit var pAdapter: RemoteMediaAdapter

    private val shareModel: NCShareViewModel by activityViewModels()

    private var previousNavBarColor = 0
    private var videoStopPosition = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        this.window = requireActivity().window

        pAdapter = RemoteMediaAdapter(
            { toggleSystemUI() },
            { media, view, type->
                if (media.mimeType.startsWith("video")) startPostponedEnterTransition()
                else shareModel.getPhoto(media, view, type) { startPostponedEnterTransition() }}
        ).apply {
            stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            @Suppress("UNCHECKED_CAST")
            submitList((arguments?.getParcelableArray(REMOTE_MEDIA)!! as Array<NCShareViewModel.RemotePhoto>).toMutableList())
        }

        sharedElementEnterTransition = MaterialContainerTransform().apply {
            duration = resources.getInteger(android.R.integer.config_mediumAnimTime).toLong()
            scrimColor = Color.TRANSPARENT
        }

        // Adjusting the shared element mapping
        setEnterSharedElementCallback(object : SharedElementCallback() {
            override fun onMapSharedElements(names: MutableList<String>?, sharedElements: MutableMap<String, View>?) {
                sharedElements?.put(names?.get(0)!!, slider.getChildAt(0).findViewById(R.id.media))
            }
        })

        savedInstanceState?.getLong(STOP_POSITION)?.apply {
            pAdapter.setSavedStopPosition(this)
            videoStopPosition = this
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

    @Suppress("DEPRECATION")
    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        val systemBarBackground = ContextCompat.getColor(requireContext(), R.color.dark_grey_overlay_background)
        (requireActivity() as AppCompatActivity).supportActionBar!!.hide()
        window.run {
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
        }
        toggleSystemUI()
    }

    private var visible: Boolean = true
    private val hideHandler = Handler(Looper.getMainLooper())
    private fun toggleSystemUI() {
        hideHandler.removeCallbacksAndMessages(null)
        hideHandler.post(if (visible) hideSystemUI else showSystemUI)
    }

    override fun onStart() {
        super.onStart()
        pAdapter.initializePlayer(requireContext())
    }

    override fun onResume() {
        super.onResume()
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)

        (slider.getChildAt(0) as RecyclerView).findViewHolderForAdapterPosition(slider.currentItem).apply {
            if (this is RemoteMediaAdapter.VideoViewHolder) this.resume()
        }
    }

    override fun onPause() {
        super.onPause()
        window.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)

        (slider.getChildAt(0) as RecyclerView).findViewHolderForAdapterPosition(slider.currentItem).apply {
            if (this is RemoteMediaAdapter.VideoViewHolder) videoStopPosition = this.pause()
        }

    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(STOP_POSITION, videoStopPosition)
    }

    override fun onStop() {
        super.onStop()
        pAdapter.cleanUp()
    }

    @Suppress("DEPRECATION")
    override fun onDestroy() {
        // BACK TO NORMAL UI
        hideHandler.removeCallbacksAndMessages(null)

        requireActivity().window.run {
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

        }

        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        (requireActivity() as AppCompatActivity).supportActionBar!!.show()

        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private val hideSystemUI = Runnable {
        window.run {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE
                    // Set the content to appear under the system bars so that the
                    // content doesn't resize when the system bars hide and show.
                    //or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
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
        }

        visible = false
    }

    @Suppress("DEPRECATION")
    private val showSystemUI = Runnable {
        window.run {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
            else insetsController?.show(WindowInsets.Type.systemBars())
        }

        visible = true

        // auto hide
        hideHandler.postDelayed(hideSystemUI, AUTO_HIDE_DELAY_MILLIS)
    }

    class RemoteMediaAdapter(private val clickListener: () -> Unit, private val imageLoader: (NCShareViewModel.RemotePhoto, ImageView, type: String) -> Unit
    ): ListAdapter<NCShareViewModel.RemotePhoto, RecyclerView.ViewHolder>(PhotoDiffCallback()) {
        private lateinit var exoPlayer: SimpleExoPlayer
        private var currentVolume = 0f
        private var oldVideoViewHolder: VideoViewHolder? = null
        private var savedStopPosition = FAKE_POSITION

        inner class PhotoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            fun bindView(photo: NCShareViewModel.RemotePhoto) {
                itemView.findViewById<PhotoView>(R.id.media).apply {
                    imageLoader(photo, this as ImageView, ImageLoaderViewModel.TYPE_FULL)
                    setOnPhotoTapListener { _, _, _ -> clickListener() }
                    setOnOutsidePhotoTapListener { clickListener() }
                    maximumScale = 5.0f
                    mediumScale = 2.5f
                    ViewCompat.setTransitionName(this, photo.fileId)
                }
            }
        }

        inner class AnimatedViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            @SuppressLint("ClickableViewAccessibility")
            fun bindView(photo: NCShareViewModel.RemotePhoto) {
                itemView.findViewById<ImageView>(R.id.media).apply {
                    // Even thought we don't load animated image with ImageLoader, we still need to call it here so that postponed enter transition can be started
                    imageLoader(photo, this, ImageLoaderViewModel.TYPE_FULL)

                    setOnTouchListener { _, event ->
                        if (event.action == MotionEvent.ACTION_DOWN) {
                            clickListener()
                            true
                        } else false
                    }

                    ViewCompat.setTransitionName(this, photo.fileId)
                }
            }
        }

        inner class VideoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private lateinit var videoView: PlayerView
            private lateinit var thumbnailView: ImageView
            private lateinit var muteButton: ImageButton
            private lateinit var videoUri: Uri
            private var videoMimeType = ""
            private var stopPosition = 0L

            @SuppressLint("ClickableViewAccessibility")
            fun bindView(video: NCShareViewModel.RemotePhoto) {
                if (savedStopPosition != FAKE_POSITION) {
                    stopPosition = savedStopPosition
                    savedStopPosition = FAKE_POSITION
                }
                muteButton = itemView.findViewById(R.id.exo_mute)
                videoView = itemView.findViewById<PlayerView>(R.id.player_view).apply {
                    controllerShowTimeoutMs = 3000
                    setOnClickListener { clickListener() }
                }

                with(itemView.context) { videoUri = Uri.fromFile(File("${cacheDir}/${getString(R.string.lespas_base_folder_name)}/videos/${video.path.substringAfterLast('/')}")) }
                videoMimeType = video.mimeType

                itemView.findViewById<ConstraintLayout>(R.id.videoview_container).let {
                    // Fix view aspect ratio
                    if (video.height != 0) with(ConstraintSet()) {
                        clone(it)
                        setDimensionRatio(R.id.media, "${video.width}:${video.height}")
                        applyTo(it)
                    }

                    it.setOnClickListener { clickListener() }
                }

                thumbnailView = itemView.findViewById<ImageView>(R.id.media).apply {
                    // Even thought we don't load animated image with ImageLoader, we still need to call it here so that postponed enter transition can be started
                    imageLoader(video, this, ImageLoaderViewModel.TYPE_FULL)
                    ViewCompat.setTransitionName(this, video.fileId)
                }

                muteButton.setOnClickListener { toggleMute() }
            }

            // Need to call this so that exit transition can happen
            fun showThumbnail() { thumbnailView.visibility = View.INVISIBLE }

            fun hideControllers() { videoView.hideController() }
            fun setStopPosition(position: Long) {
                //Log.e(">>>","set stop position $position")
                stopPosition = position }

            // This step is important to reset the SurfaceView that ExoPlayer attached to, avoiding video playing with a black screen
            fun resetVideoViewPlayer() { videoView.player = null }

            fun resume() {
                //Log.e(">>>>", "resume playback at $stopPosition")
                exoPlayer.apply {
                    // Stop playing old video if swipe from it. The childDetachedFrom event of old VideoView always fired later than childAttachedTo event of new VideoView
                    if (isPlaying) {
                        playWhenReady = false
                        stop()
                        oldVideoViewHolder?.apply {
                            if (this != this@VideoViewHolder) {
                                setStopPosition(currentPosition)
                                showThumbnail()
                            }
                        }
                    }
                    playWhenReady = true
                    setMediaItem(MediaItem.Builder().setUri(videoUri).setMimeType(videoMimeType).build(), stopPosition)
                    //setMediaItem(MediaItem.fromUri(videoUri), stopPosition)
                    prepare()
                    oldVideoViewHolder?.resetVideoViewPlayer()
                    videoView.player = exoPlayer
                    oldVideoViewHolder = this@VideoViewHolder

                    // Maintain mute status indicator
                    muteButton.setImageResource(if (exoPlayer.volume == 0f) R.drawable.ic_baseline_volume_off_24 else R.drawable.ic_baseline_volume_on_24)

                    // Keep screen on
                    (videoView.context as Activity).window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            fun pause(): Long {
                //Log.e(">>>>", "pause playback")
                // If swipe out to a new VideoView, then no need to perform stop procedure. The childDetachedFrom event of old VideoView always fired later than childAttachedTo event of new VideoView
                if (oldVideoViewHolder == this) {
                    exoPlayer.apply {
                        playWhenReady = false
                        stop()
                        setStopPosition(currentPosition)
                    }
                    hideControllers()
                }

                // Resume auto screen off
                (videoView.context as Activity).window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

                return stopPosition
            }

            private fun mute() {
                currentVolume = exoPlayer.volume
                exoPlayer.volume = 0f
                muteButton.setImageResource(R.drawable.ic_baseline_volume_off_24)
            }

            private fun toggleMute() {
                exoPlayer.apply {
                    if (volume == 0f) {
                        volume = currentVolume
                        muteButton.setImageResource(R.drawable.ic_baseline_volume_on_24)
                    }
                    else mute()
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return when(viewType) {
                TYPE_VIDEO -> VideoViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.viewpager_item_exoplayer, parent, false))
                TYPE_ANIMATED -> AnimatedViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.viewpager_item_gif, parent, false))
                else-> PhotoViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.viewpager_item_photo, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when(holder) {
                is VideoViewHolder -> holder.bindView(getItem(position))
                is AnimatedViewHolder -> holder.bindView(getItem(position))
                else-> (holder as PhotoViewHolder).bindView(getItem(position))
            }
        }

        override fun getItemViewType(position: Int): Int {
            with(getItem(position).mimeType) {
                return when {
                    this == "image/agif" || this == "image/awebp" -> TYPE_ANIMATED
                    this.startsWith("video/") -> TYPE_VIDEO
                    else -> TYPE_PHOTO
                }
            }
        }

        override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
            super.onViewAttachedToWindow(holder)
            if (holder is VideoViewHolder) holder.resume()
        }

        override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
            super.onViewDetachedFromWindow(holder)
            if (holder is VideoViewHolder) holder.pause()
        }

        fun setSavedStopPosition(position: Long) { savedStopPosition = position }

        fun initializePlayer(ctx: Context) {
            //private var exoPlayer = SimpleExoPlayer.Builder(ctx, { _, _, _, _, _ -> arrayOf(MediaCodecVideoRenderer(ctx, MediaCodecSelector.DEFAULT)) }) { arrayOf(Mp4Extractor()) }.build()
            exoPlayer = SimpleExoPlayer.Builder(ctx).build()
            exoPlayer.addListener(object: Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    super.onPlaybackStateChanged(state)

                    if (state == Player.STATE_ENDED) {
                        exoPlayer.playWhenReady = false
                        exoPlayer.seekTo(0L)
                        oldVideoViewHolder?.setStopPosition(0L)

                        // Resume auto screen off
                        (oldVideoViewHolder?.itemView?.context as Activity).window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    super.onIsPlayingChanged(isPlaying)
                    if (isPlaying) oldVideoViewHolder?.run {
                        showThumbnail()
                        hideControllers()
                    }
                }
            })

            // Default mute the video playback during late night
            with(LocalDateTime.now().hour) {
                if (this >= 22 || this < 7) {
                    currentVolume = exoPlayer.volume
                    exoPlayer.volume = 0f
                }
            }
        }

        fun cleanUp() { exoPlayer.release() }

        companion object {
            private const val TYPE_PHOTO = 0
            private const val TYPE_ANIMATED = 1
            private const val TYPE_VIDEO = 2

            private const val FAKE_POSITION = -1L
        }
    }

    class PhotoDiffCallback: DiffUtil.ItemCallback<NCShareViewModel.RemotePhoto>() {
        override fun areItemsTheSame(oldItem: NCShareViewModel.RemotePhoto, newItem: NCShareViewModel.RemotePhoto): Boolean = oldItem.fileId == newItem.fileId
        override fun areContentsTheSame(oldItem: NCShareViewModel.RemotePhoto, newItem: NCShareViewModel.RemotePhoto): Boolean = oldItem.fileId == newItem.fileId
    }

    companion object {
        private const val REMOTE_MEDIA = "REMOTE_MEDIA"
        private const val STOP_POSITION = "STOP_POSITION"
        private const val AUTO_HIDE_DELAY_MILLIS = 3000L // The number of milliseconds to wait after user interaction before hiding the system UI.

        @JvmStatic
        fun newInstance(media: List<NCShareViewModel.RemotePhoto>) = RemoteMediaFragment().apply { arguments = Bundle().apply { putParcelableArray(REMOTE_MEDIA, media.toTypedArray()) } }
    }
}