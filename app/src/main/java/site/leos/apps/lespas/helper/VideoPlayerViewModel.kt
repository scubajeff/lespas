package site.leos.apps.lespas.helper

import android.app.Application
import android.net.Uri
import android.view.Window
import android.view.WindowManager
import androidx.lifecycle.AndroidViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.preference.PreferenceManager
import okhttp3.OkHttpClient
import site.leos.apps.lespas.R
import java.io.File
import java.time.LocalDateTime

@androidx.annotation.OptIn(UnstableApi::class)
class VideoPlayerViewModel(application: Application, callFactory: OkHttpClient?): AndroidViewModel(application) {
    private val videoPlayer: ExoPlayer
    private var cache: SimpleCache? = null
    private var currentVideo = Uri.EMPTY
    private var addedListener: Player.Listener? = null
    private lateinit var window: Window

    init {
        //private var exoPlayer = SimpleExoPlayer.Builder(ctx, { _, _, _, _, _ -> arrayOf(MediaCodecVideoRenderer(ctx, MediaCodecSelector.DEFAULT)) }) { arrayOf(Mp4Extractor()) }.build()
        val builder = ExoPlayer.Builder(application)
        callFactory?.let {
            cache = SimpleCache(File(application.cacheDir, "media"), LeastRecentlyUsedCacheEvictor(100L * 1024L * 1024L), StandaloneDatabaseProvider(application))
            builder.setMediaSourceFactory(DefaultMediaSourceFactory(CacheDataSource.Factory().setCache(cache!!).setUpstreamDataSourceFactory(DefaultDataSource.Factory(application, OkHttpDataSource.Factory(callFactory)))))
        }
        videoPlayer = builder.build().apply {
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    super.onPlaybackStateChanged(playbackState)

                    if (playbackState == Player.STATE_ENDED) {
                        playWhenReady = false
                        seekTo(0L)
                        saveVideoPosition(currentVideo)
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    super.onIsPlayingChanged(isPlaying)

                    if (isPlaying) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            })

            // Retrieve repeat mode setting
            repeatMode = if (PreferenceManager.getDefaultSharedPreferences(application).getBoolean(application.getString(R.string.auto_replay_perf_key), true)) ExoPlayer.REPEAT_MODE_ALL else ExoPlayer.REPEAT_MODE_OFF
        }

        // Mute the video sound during late night hours
        with(LocalDateTime.now().hour) { if (this >= 22 || this < 7) mute() }
    }

    fun addListener(listener: Player.Listener) {
        addedListener?.let { videoPlayer.removeListener(it) }
        addedListener = listener
        videoPlayer.addListener(listener)
    }

    fun resume(view: PlayerView, uri: Uri) {
        if (uri == currentVideo) {
            // Resuming the same video
            if (videoPlayer.isPlaying) {
                // Reattach player to playerView after screen rotate
                view.player = videoPlayer
                return
            }
        }
        else {
            // Pause the current one
            if (videoPlayer.isPlaying) pause(currentVideo)

            // Switch to new video
            currentVideo = uri
        }

        // Swap to the new playerView
        view.player = videoPlayer

        // Play it
        with(videoPlayer) {
            setMediaItem(MediaItem.fromUri(currentVideo), getVideoPosition(currentVideo))
            prepare()
            play()
        }

        // Hide controller view by default
        view.hideController()

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    fun pause(uri: Uri?) {
        // Fragment onPause will call this with Uri.EMPTY since fragment has no knowledge of video uri
        if (uri == currentVideo || uri == Uri.EMPTY && videoPlayer.playbackState != Player.STATE_IDLE) {
            // Only pause if current playing video is the same as the argument
            videoPlayer.pause()
            saveVideoPosition(currentVideo)
        }

        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    fun mute() { videoPlayer.volume = 0f }
    fun unMute() { videoPlayer.volume = 1f }
    fun toggleMuteState() { if (videoPlayer.volume == 0f) unMute() else mute() }
    fun isMuted(): Boolean = videoPlayer.volume == 0f

    fun resetPlayer() {
        videoMap.clear()
        videoPlayer.clearMediaItems()
        videoPlayer.clearVideoSurface()
        addedListener?.let {
            videoPlayer.removeListener(it)
            addedListener = null
        }
    }

    override fun onCleared() {
        cache?.release()
        videoPlayer.release()

        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        super.onCleared()
    }

    // Hashmap to store video uri and stop position
    private val videoMap = HashMap<Uri, Long>()
    private fun saveVideoPosition(uri: Uri) { videoMap[uri] = videoPlayer.currentPosition }
    private fun getVideoPosition(uri: Uri): Long {
        videoMap[uri] ?: run { videoMap[uri] = 0L }
        return videoMap[uri]!!
    }

    fun setWindow(window: Window) { this.window = window }
}