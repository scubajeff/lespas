package site.leos.apps.lespas.photo

import android.app.Application
import androidx.lifecycle.*

class PhotoViewModel(application: Application, private val albumId: String): AndroidViewModel(application) {
    private val photoRepository = PhotoRepository(application)

    val allPhotoInAlbum: LiveData<List<Photo>> = photoRepository.getAlbumPhotosByDateTakenASC(albumId).asLiveData()
    val albumSize: LiveData<Int> = photoRepository.getAlbumSize(albumId).asLiveData()
}

class PhotoViewModelFactory(private val application: Application, private val albumId: String) : ViewModelProvider.NewInstanceFactory() {
    override fun <T : ViewModel?> create(modelClass: Class<T>): T = PhotoViewModel(application, albumId) as T
}