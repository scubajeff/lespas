/*
 *   Copyright 2019 Jeffrey Liu (scubajeffrey@criptext.com)
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

package site.leos.apps.lespas.helper

import android.app.Activity
import android.content.ContextWrapper
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.preference.PreferenceManager
import okhttp3.OkHttpClient
import site.leos.apps.lespas.R
import java.time.LocalDateTime

@androidx.annotation.OptIn(UnstableApi::class)
class VideoPlayerViewModel(activity: Activity, callFactory: OkHttpClient, cache: SimpleCache?): ViewModel() {
    private val videoPlayer: ExoPlayer
    private var currentVideo = Uri.EMPTY
    private var addedListener: Player.Listener? = null
    private var window = activity.window

    init {
        //private var exoPlayer = SimpleExoPlayer.Builder(ctx, { _, _, _, _, _ -> arrayOf(MediaCodecVideoRenderer(ctx, MediaCodecSelector.DEFAULT)) }) { arrayOf(Mp4Extractor()) }.build()
        val okHttpDSFactory = DefaultDataSource.Factory(activity, OkHttpDataSource.Factory(callFactory))
        videoPlayer = ExoPlayer.Builder(activity).setMediaSourceFactory(DefaultMediaSourceFactory(if (cache != null) CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(okHttpDSFactory) else okHttpDSFactory)).build().apply {
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

                    Tools.keepScreenOn(window, isPlaying)
                }
            })

            // Retrieve repeat mode setting
            repeatMode = if (PreferenceManager.getDefaultSharedPreferences(activity).getBoolean(activity.getString(R.string.auto_replay_perf_key), true)) ExoPlayer.REPEAT_MODE_ALL else ExoPlayer.REPEAT_MODE_OFF
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
        // Hide controller view by default
        view.hideController()
        if (view.context is Activity) window = (view.context as Activity).window
        if (view.context is ContextWrapper) window = ((view.context as ContextWrapper).baseContext as Activity).window

        // Keep screen on during playing
        Tools.keepScreenOn(window, true)

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
    }

    fun pause(uri: Uri?) {
        // Fragment onPause will call this with Uri.EMPTY since fragment has no knowledge of video uri
        if (uri == currentVideo || uri == Uri.EMPTY && videoPlayer.playbackState != Player.STATE_IDLE) {
            // Only pause if current playing video is the same as the argument
            videoPlayer.pause()
            saveVideoPosition(currentVideo)
        }

        // Reset screen auto turn off
        Tools.keepScreenOn(window, false)
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
        videoPlayer.release()

        // Reset screen auto turn off
        Tools.keepScreenOn(window, false)

        super.onCleared()
    }

    // Hashmap to store video uri and stop position
    private val videoMap = HashMap<Uri, Long>()
    private fun saveVideoPosition(uri: Uri) { videoMap[uri] = videoPlayer.currentPosition }
    private fun getVideoPosition(uri: Uri): Long {
        videoMap[uri] ?: run { videoMap[uri] = 0L }
        return videoMap[uri]!!
    }
}