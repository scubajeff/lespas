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

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

@androidx.annotation.OptIn(UnstableApi::class)
class BGMViewModel(context: Context, callFactory: OkHttpClient, bgmFile: String): ViewModel() {
    private val bgmPlayer: ExoPlayer
    private var fadingJob: Job? = null

    init {
        bgmPlayer = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, OkHttpDataSource.Factory(callFactory))))
            .build()

        with(bgmPlayer) {
            repeatMode = ExoPlayer.REPEAT_MODE_ONE
            playWhenReady = false
            volume = 0f
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)

            setMediaItem(MediaItem.fromUri(bgmFile))
            bgmPlayer.prepare()
        }
    }

    fun fadeInBGM() {
        fadingJob?.cancel()

        if (bgmPlayer.volume < 1f) fadingJob = viewModelScope.launch {
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

    fun fadeOutBGM() {
        fadingJob?.cancel()

        if (bgmPlayer.volume > 0f) fadingJob = viewModelScope.launch {
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

    fun rewind() { bgmPlayer.seekTo(0L) }

    override fun onCleared() { bgmPlayer.release() }
}