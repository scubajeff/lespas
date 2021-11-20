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
        // Don't update meta if cover does not have a a proper fileID, in that case, meta file will be maintained in SyncAdapter when the fileId is ready
        if (!cover.cover.contains('.')) actionRepository.updateAlbumMeta(albumId, cover.cover)
    }
    fun getAllPhotoInAlbum(albumId: String): LiveData<List<Photo>> = photoRepository.getAlbumPhotosFlow(albumId).asLiveData()
    fun setSortOrder(albumId: String, sortOrder: Int) = viewModelScope.launch(Dispatchers.IO) { albumRepository.setSortOrder(albumId, sortOrder) }
    fun getAllAlbumIdName(): List<IDandName> = albumRepository.getAllAlbumIdName()
    fun getThisAlbum(albumId: String): Album = albumRepository.getThisAlbum(albumId)
    fun getAllAlbumName(): List<String> = albumRepository.getAllAlbumName()
}