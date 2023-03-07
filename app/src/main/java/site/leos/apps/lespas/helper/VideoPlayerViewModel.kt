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
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
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
    private var brightness = Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255.0f
    private val audioManager = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    private val maxSystemVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    private var currentVolumePercentage = volume.toFloat() / maxSystemVolume

    init {
        //private var exoPlayer = SimpleExoPlayer.Builder(ctx, { _, _, _, _, _ -> arrayOf(MediaCodecVideoRenderer(ctx, MediaCodecSelector.DEFAULT)) }) { arrayOf(Mp4Extractor()) }.build()
        val okHttpDSFactory = DefaultDataSource.Factory(activity, OkHttpDataSource.Factory(callFactory))
        videoPlayer = ExoPlayer.Builder(activity)
            .setMediaSourceFactory(DefaultMediaSourceFactory(if (cache != null) CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(okHttpDSFactory) else okHttpDSFactory))
            .build()
        .apply {
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

            // Handle audio focus
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            // Initial volume as maximum of current system volume, effectively no change to current volume at the very beginning, see setVolume()
            volume = 1f
        }

        // Mute video sound during late night hours
        with(LocalDateTime.now().hour) { if (this >= 22 || this < 7) mute() }
    }

    fun addListener(listener: Player.Listener) {
        addedListener?.let { videoPlayer.removeListener(it) }
        addedListener = listener
        videoPlayer.addListener(listener)
    }

    fun resume(view: PlayerView?, uri: Uri?) {
        // Hide controller view by default
        view?.hideController()

        if (view != null && uri != null) {
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
            } else {
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
        } else {
            // OnWindowFocusChange called with hasFocus true
            videoPlayer.play()
        }
    }

    fun pause(uri: Uri?) {
        // Fragment onWindowFocusChanged will call this with Uri.EMPTY since fragment has no knowledge of video uri
        if (uri == currentVideo || uri == Uri.EMPTY && videoPlayer.playbackState != Player.STATE_IDLE) {
            // Only pause if current playing video is the same as the argument
            videoPlayer.pause()
            saveVideoPosition(currentVideo)
        }

        // Reset screen auto turn off
        Tools.keepScreenOn(window, false)
    }

    fun skip(seconds: Int) { videoPlayer.seekTo(videoPlayer.currentPosition + seconds * 1000) }
/*
    fun setVolume(increment: Float) {
        val currentSystemVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

        // Make sure maximum volume set if auto mute in midnight activated
        if (currentSystemVolume < maxSystemVolume && videoPlayer.volume == 0f) audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxSystemVolume,0)

        val volume = videoPlayer.volume + increment
        when {
            volume < 0f  -> videoPlayer.volume = 0f
            volume > 1f  -> {
                if (currentSystemVolume >= maxSystemVolume ) videoPlayer.volume = 1f
                else {
                    // Make sure maximum volume set when adjusting volume for the first time in this session
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxSystemVolume,0)
                    videoPlayer.volume = currentSystemVolume.toFloat() / maxSystemVolume + increment
                }
            }
            else -> videoPlayer.volume = volume
        }
    }
    fun getVolume(): Float = videoPlayer.volume
*/
    fun setVolume(increment: Float) {
        currentVolumePercentage += increment
        currentVolumePercentage = when {
            currentVolumePercentage < 0f -> 0f
            currentVolumePercentage > 1f -> 1f
            else -> currentVolumePercentage
        }
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (currentVolumePercentage * maxSystemVolume).toInt(), 0)
    }
    fun getVolume(): Float = currentVolumePercentage

    fun setBrightness(increment: Float) {
        brightness += increment
        if (brightness < 0f) brightness = 0f
        if (brightness > 1f) brightness = 1f

        window.attributes = window.attributes.apply { screenBrightness = brightness }
    }
    fun getBrightness(): Float = brightness
    fun resetBrightness() { window.attributes = window.attributes.apply { screenBrightness = -1f }}
    private fun mute() {
        //videoPlayer.volume = 0f
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        currentVolumePercentage = 0f
    }
/*
    fun unMute() { videoPlayer.volume = 1f }
    fun toggleMuteState() { if (videoPlayer.volume == 0f) unMute() else mute() }
    fun isMuted(): Boolean = videoPlayer.volume == 0f
*/

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

        // Reset screen auto turn off, brightness and volume setting
        Tools.keepScreenOn(window, false)
        resetBrightness()
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)

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