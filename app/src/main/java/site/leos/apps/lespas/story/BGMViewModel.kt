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
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class BGMViewModel(context: Context, bgmFile: String?): ViewModel() {
    private val bgmPlayer: ExoPlayer
    private var hasBGM = false
    private var fadingJob: Job? = null

    init {
        bgmPlayer = ExoPlayer.Builder(context).build()

        with(bgmPlayer) {
            repeatMode = ExoPlayer.REPEAT_MODE_ONE
            playWhenReady = false
            volume = 0f
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)

            bgmFile?.let {
                setMediaItem(MediaItem.fromUri("file://${bgmFile}"))
                hasBGM = true
                bgmPlayer.prepare()
            }
        }
    }

    fun fadeInBGM() {
        if (hasBGM) {
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
    }

    fun fadeOutBGM() {
        if (hasBGM) {
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
    }

    fun rewind() { bgmPlayer.seekTo(0L) }

    override fun onCleared() { bgmPlayer.release() }
}