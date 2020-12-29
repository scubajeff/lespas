package site.leos.apps.lespas.album

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.photo.PhotoRepository
import java.time.LocalDateTime

class AlbumViewModel(application: Application) : AndroidViewModel(application){
    private val albumRepository = AlbumRepository(application)
    private val photoRepository = PhotoRepository(application)

    val allAlbumsByEndDate: LiveData<List<Album>> = albumRepository.getAllAlbumsSortByEndDate().asLiveData()
    fun getAlbumDetail(albumId: String): LiveData<AlbumWithPhotos> = albumRepository.getAlbumDetail(albumId).asLiveData()
    fun setCover(albumId: String, cover: Cover) = viewModelScope.launch(Dispatchers.IO) { albumRepository.setCover(albumId, cover) }
    fun getAllPhotoInAlbum(albumId: String): LiveData<List<Photo>> = photoRepository.getAlbumPhotosByDateTakenASC(albumId).asLiveData()
    fun setSortOrder(albumId: String, sortOrder: Int) = viewModelScope.launch(Dispatchers.IO) { albumRepository.setSortOrder(albumId, sortOrder)}
    fun fixCoverId(albumId: String, newCoverId: String) = viewModelScope.launch(Dispatchers.IO) { albumRepository.fixCoverId(albumId, newCoverId) }
    suspend fun addPhoto(photo: Photo) = photoRepository.insert(photo)
    suspend fun updatePhoto(oldId: String, newId: String, lastModifiedDate: LocalDateTime, width: Int, height: Int, mimeType: String) =
        photoRepository.updatePhoto(oldId, newId, "", lastModifiedDate, width, height, mimeType)
}