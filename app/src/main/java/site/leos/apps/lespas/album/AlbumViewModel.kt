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
import site.leos.apps.lespas.sync.ActionRepository

class AlbumViewModel(application: Application) : AndroidViewModel(application){
    private val albumRepository = AlbumRepository(application)
    private val photoRepository = PhotoRepository(application)
    private val actionRepository = ActionRepository(application)

    val allAlbumsByEndDate: LiveData<List<Album>> = albumRepository.getAllAlbumsSortByEndDate().asLiveData()
    fun getAlbumDetail(albumId: String): LiveData<AlbumWithPhotos> = albumRepository.getAlbumDetail(albumId).asLiveData()
    fun setCover(albumId: String, cover: Cover) = viewModelScope.launch(Dispatchers.IO) {
        albumRepository.setCover(albumId, cover)
        // TODO don't update meta if cover does not have a a proper fileID, in that case, meta file will be maintained in SyncAdapter when the fileId is ready
        if (!cover.cover.contains('.')) actionRepository.updateMeta(albumId)
    }
    fun getAllPhotoInAlbum(albumId: String): LiveData<List<Photo>> = photoRepository.getAlbumPhotosFlow(albumId).asLiveData()
    fun setSortOrder(albumId: String, sortOrder: Int) = viewModelScope.launch(Dispatchers.IO) { albumRepository.setSortOrder(albumId, sortOrder) }
    fun isAlbumExisted(name: String) = albumRepository.isAlbumExisted(name)
    //fun fixCoverId(albumId: String, newCoverId: String) = viewModelScope.launch(Dispatchers.IO) { albumRepository.fixCoverId(albumId, newCoverId) }
    //suspend fun addPhoto(photo: Photo) = photoRepository.insert(photo)
    //suspend fun updatePhoto(oldId: String, newId: String, lastModifiedDate: LocalDateTime, width: Int, height: Int, mimeType: String) = photoRepository.updatePhoto(oldId, newId, "", lastModifiedDate, width, height, mimeType)
    //suspend fun replacePhoto(oldPhoto: Photo, newPhoto: Photo) { photoRepository.replacePhoto(oldPhoto, newPhoto) }
    //suspend fun replaceCover(albumId: String, newCoverId: String, newWidth: Int, newHeight: Int, newBaseline: Int) { albumRepository.replaceCover(albumId, newCoverId, newWidth, newHeight, newBaseline) }
    //fun removePhoto(photo: Photo) { photoRepository.removePhoto(photo) }
    //suspend fun getPhotoById(photoId: String): Photo = photoRepository.getPhotoById(photoId)
    fun getAllAlbumName(): List<IDandName> = albumRepository.getAllAlbumName()
    fun getThisAlbum(albumId: String) = albumRepository.getThisAlbum(albumId)
}