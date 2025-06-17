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

package site.leos.apps.lespas.helper

import android.app.Activity
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.SimpleCache
import okhttp3.OkHttpClient

@OptIn(UnstableApi::class)
class VideoPlayerViewModelFactory (
    private val activity: Activity, private val callFactory: OkHttpClient, private val cache: SimpleCache?,
    private val savedSystemVolume: Int, private val sessionVolumePercentage: Float,
    private val slideshowMode: Boolean = false): ViewModelProvider.NewInstanceFactory() {
    override fun <T : ViewModel> create(modelClass: Class<T>): T = modelClass.cast(VideoPlayerViewModel(activity, callFactory, cache, savedSystemVolume, sessionVolumePercentage, slideshowMode))!!
}