package site.leos.apps.lespas.helper

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import okhttp3.OkHttpClient

class VideoPlayerViewModelFactory(private val application: Application, private val callFactory: OkHttpClient?): ViewModelProvider.NewInstanceFactory() {
    override fun <T : ViewModel> create(modelClass: Class<T>): T = modelClass.cast(VideoPlayerViewModel(application, callFactory))!!
}