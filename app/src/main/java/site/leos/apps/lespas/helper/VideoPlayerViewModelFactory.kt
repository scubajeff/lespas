package site.leos.apps.lespas.helper

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.media3.datasource.cache.SimpleCache
import okhttp3.OkHttpClient

class VideoPlayerViewModelFactory(private val application: Application, private val callFactory: OkHttpClient, private val cache: SimpleCache): ViewModelProvider.NewInstanceFactory() {
    override fun <T : ViewModel> create(modelClass: Class<T>): T = modelClass.cast(VideoPlayerViewModel(application, callFactory, cache))!!
}