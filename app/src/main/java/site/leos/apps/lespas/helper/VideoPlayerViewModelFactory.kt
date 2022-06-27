package site.leos.apps.lespas.helper

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.media3.datasource.cache.SimpleCache
import okhttp3.OkHttpClient

class VideoPlayerViewModelFactory(private val activity: Activity, private val callFactory: OkHttpClient, private val cache: SimpleCache?): ViewModelProvider.NewInstanceFactory() {
    override fun <T : ViewModel> create(modelClass: Class<T>): T = modelClass.cast(VideoPlayerViewModel(activity, callFactory, cache))!!
}