package site.leos.apps.lespas.helper

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Parcelable
import android.view.*
import android.widget.ImageButton
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.*
import androidx.recyclerview.widget.DiffUtil.ItemCallback
import com.github.chrisbanes.photoview.PhotoView
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.SimpleExoPlayer
import com.google.android.exoplayer2.ext.okhttp.OkHttpDataSource
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory
import com.google.android.exoplayer2.ui.PlayerView
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory
import com.google.android.exoplayer2.upstream.cache.CacheDataSource
import com.google.android.exoplayer2.upstream.cache.LeastRecentlyUsedCacheEvictor
import com.google.android.exoplayer2.upstream.cache.SimpleCache
import kotlinx.parcelize.Parcelize
import okhttp3.OkHttpClient
import site.leos.apps.lespas.R
import java.io.File
import java.time.LocalDateTime
import java.util.*

abstract class MediaSliderAdapter<T>(diffCallback: ItemCallback<T>, private val clickListener: (Boolean?) -> Unit, private val imageLoader: (T, ImageView, String) -> Unit, private val cancelLoader: (View) -> Unit
): ListAdapter<T, RecyclerView.ViewHolder>(diffCallback) {
    private lateinit var exoPlayer: SimpleExoPlayer
    private var currentVolume = 0f
    private var oldVideoViewHolder: VideoViewHolder? = null
    private var savedPlayerState = PlayerState()
    private var autoStart = false
    private var cache: SimpleCache? = null

    abstract fun getVideoItem(position: Int): VideoItem
    abstract fun getItemTransitionName(position: Int): String
    abstract fun getItemMimeType(position: Int): String

    override fun getItemViewType(position: Int): Int {
        with(getItemMimeType(position)) {
            return when {
                this == "image/agif" || this == "image/awebp" -> TYPE_ANIMATED
                this.startsWith("video/") -> TYPE_VIDEO
                else -> TYPE_PHOTO
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when(viewType) {
            TYPE_PHOTO -> PhotoViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.viewpager_item_photo, parent, false))
            TYPE_ANIMATED -> AnimatedViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.viewpager_item_gif, parent, false))
            else-> VideoViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.viewpager_item_exoplayer, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when(holder) {
            is MediaSliderAdapter<*>.VideoViewHolder -> holder.bind(getItem(position), getVideoItem(position), clickListener, imageLoader)
            is MediaSliderAdapter<*>.AnimatedViewHolder -> holder.bind(getItem(position), getItemTransitionName(position), clickListener, imageLoader)
            else-> (holder as MediaSliderAdapter<*>.PhotoViewHolder).bind(getItem(position), getItemTransitionName(position), clickListener, imageLoader)
        }
    }

    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        super.onViewAttachedToWindow(holder)
        if (holder is MediaSliderAdapter<*>.VideoViewHolder) holder.resume()
    }

    override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
        super.onViewDetachedFromWindow(holder)
        if (holder is MediaSliderAdapter<*>.VideoViewHolder) holder.pause()
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        cancelLoader(holder.itemView.findViewById(R.id.media) as View)
        super.onViewRecycled(holder)
    }

    inner class PhotoViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
        fun <T> bind(photo: T, transitionName: String, clickListener: (Boolean?) -> Unit, imageLoader: (T, ImageView, String) -> Unit) {
            itemView.findViewById<PhotoView>(R.id.media).apply {
                imageLoader(photo, this, ImageLoaderViewModel.TYPE_FULL)
                setOnPhotoTapListener { _, _, _ -> clickListener(null) }
                setOnOutsidePhotoTapListener { clickListener(null) }
                maximumScale = 5.0f
                mediumScale = 2.5f
                ViewCompat.setTransitionName(this, transitionName)
            }
        }
    }

    inner class AnimatedViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        fun <T> bind(photo: T, transitionName: String, clickListener: (Boolean?) -> Unit, imageLoader: (T, ImageView, String) -> Unit) {
            itemView.findViewById<ImageView>(R.id.media).apply {
                imageLoader(photo, this, ImageLoaderViewModel.TYPE_FULL)
                setOnClickListener { clickListener(null) }
                ViewCompat.setTransitionName(this, transitionName)
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

        fun <T> bind(item: T, video: VideoItem, clickListener: (Boolean?) -> Unit, imageLoader: (T, ImageView, String) -> Unit) {
            this.videoUri = video.uri

            if (savedPlayerState.stopPosition != FAKE_POSITION) {
                stopPosition = savedPlayerState.stopPosition
                savedPlayerState.stopPosition = FAKE_POSITION

                if (savedPlayerState.isMuted) exoPlayer.volume = 0f
            }
            muteButton = itemView.findViewById(R.id.exo_mute)
            videoView = itemView.findViewById(R.id.player_view)
            with(videoView) {
                hideController()
                controllerShowTimeoutMs = 3000
                //setShutterBackgroundColor(0)
                setOnClickListener { clickListener(!videoView.isControllerVisible) }
            }

            videoMimeType = video.mimeType

/*
            itemView.findViewById<ConstraintLayout>(R.id.videoview_container).let {
                // Fix view aspect ratio
                if (video.height != 0) with(ConstraintSet()) {
                    clone(it)
                    setDimensionRatio(R.id.media, "${video.width}:${video.height}")
                    applyTo(it)
                }

                it.setOnClickListener { clickListener(!videoView.isControllerVisible) }
            }
*/
            itemView.findViewById<ConstraintLayout>(R.id.videoview_container).setOnClickListener { clickListener(!videoView.isControllerVisible) }

            thumbnailView = itemView.findViewById<ImageView>(R.id.media).apply {
                // Even thought we don't load animated image with ImageLoader, we still need to call it here so that postponed enter transition can be started
                imageLoader(item, this, ImageLoaderViewModel.TYPE_FULL)
                ViewCompat.setTransitionName(this, video.transitionName)
            }

            muteButton.setOnClickListener { toggleMute() }
        }

        fun hideThumbnailView() { thumbnailView.visibility = View.INVISIBLE }
        fun hideControllers() { videoView.hideController() }
        fun setStopPosition(position: Long) {
            //Log.e(">>>","set stop position $position")
            stopPosition = position }

        // This step is important to reset the SurfaceView that ExoPlayer attached to, avoiding video playing with a black screen
        private fun resetVideoViewPlayer() { videoView.player = null }

        fun resume() {
            //Log.e(">>>>", "resume playback at $stopPosition")
            exoPlayer.apply {
                // Stop playing old video if swipe from it. The childDetachedFrom event of old VideoView always fired later than childAttachedTo event of new VideoView
                if (isPlaying) {
                    playWhenReady = false
                    stop()
                    oldVideoViewHolder?.apply {
                        if (this != this@VideoViewHolder) setStopPosition(currentPosition)
                    }
                }

                //playWhenReady = true
                playWhenReady = autoStart
                setMediaItem(MediaItem.Builder().setUri(videoUri).setMimeType(videoMimeType).build(), stopPosition)

                oldVideoViewHolder?.resetVideoViewPlayer()
                videoView.player = exoPlayer
                oldVideoViewHolder = this@VideoViewHolder

                prepare()

                // Maintain mute status indicator
                muteButton.setImageResource(if (exoPlayer.volume == 0f) R.drawable.ic_baseline_volume_off_24 else R.drawable.ic_baseline_volume_on_24)

                // Keep screen on
                (videoView.context as Activity).window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        fun pause() {
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

            savedPlayerState.setState(exoPlayer.volume == 0f, stopPosition)
        }

        fun startOver() {
            videoView.controllerAutoShow = false
            setAutoStart(true)
            videoView.visibility = View.VISIBLE
            exoPlayer.playWhenReady = true
            videoView.controllerAutoShow = true
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

    fun initializePlayer(ctx: Context, callFactory: OkHttpClient?) {
        //private var exoPlayer = SimpleExoPlayer.Builder(ctx, { _, _, _, _, _ -> arrayOf(MediaCodecVideoRenderer(ctx, MediaCodecSelector.DEFAULT)) }) { arrayOf(Mp4Extractor()) }.build()
        val builder = SimpleExoPlayer.Builder(ctx)
        //callFactory?.let { builder.setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSourceFactory(ctx, OkHttpDataSource.Factory(callFactory)))) }
        callFactory?.let {
            cache = SimpleCache(File(ctx.cacheDir, "media"), LeastRecentlyUsedCacheEvictor(100L * 1024L * 1024L))
            builder.setMediaSourceFactory(DefaultMediaSourceFactory(CacheDataSource.Factory().setCache(cache!!).setUpstreamDataSourceFactory(DefaultDataSourceFactory(ctx, OkHttpDataSource.Factory(callFactory)))))
        }
        exoPlayer = builder.build()

        currentVolume = exoPlayer.volume
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
                if (isPlaying) {
                    oldVideoViewHolder?.hideThumbnailView()
                    oldVideoViewHolder?.hideControllers()
                    clickListener(false)
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

    fun cleanUp() {
        cache?.release()
        exoPlayer.release()
    }
    fun setAutoStart(state: Boolean) { autoStart = state }

    fun setPlayerState(state: PlayerState) { savedPlayerState = state }
    fun getPlayerState(): PlayerState = savedPlayerState

    @Parcelize
    data class PlayerState(
        var isMuted: Boolean = false,
        var stopPosition: Long = FAKE_POSITION,
    ): Parcelable {
        fun setState(isMuted: Boolean, stopPosition: Long) {
            this.isMuted = isMuted
            this.stopPosition = stopPosition
        }
    }

    @Parcelize
    data class VideoItem(
        var uri: Uri,
        var mimeType: String,
        var width: Int,
        var height: Int,
        var transitionName: String,
    ): Parcelable

    companion object {
        private const val TYPE_PHOTO = 0
        private const val TYPE_ANIMATED = 1
        private const val TYPE_VIDEO = 2

        const val FAKE_POSITION = -1L
    }
}