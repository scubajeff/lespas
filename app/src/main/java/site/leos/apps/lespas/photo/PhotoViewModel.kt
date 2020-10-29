package site.leos.apps.lespas.photo

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData

class PhotoViewModel(application: Application, private val albumId: String): AndroidViewModel(application) {
    private val repository = PhotoRepository.getRepository(application)
    val allPhotoInAlbum: LiveData<List<Photo>>
    val albumSize: LiveData<Int>

    init {
        allPhotoInAlbum = repository.getAllByDateTakenASCDistinctLiveData(albumId)
        albumSize = repository.getAlbumSizeDistinctLiveData(albumId)
    }
}