package site.leos.apps.lespas.album

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.photo.PhotoRepository
import java.io.File

class AlbumViewModel(application: Application) : AndroidViewModel(application){
    private val albumRepository = AlbumRepository(application)
    private val photoRepository = PhotoRepository(application)
    private val localRootFolder = Tools.getLocalRoot(application)

    val allAlbumsByEndDate: LiveData<List<Album>> = albumRepository.getAllAlbumsSortByEndDate().asLiveData()
    fun getAlbumDetail(albumId: String): LiveData<AlbumWithPhotos> = albumRepository.getAlbumDetail(albumId).asLiveData()
    fun getAllPhotoInAlbum(albumId: String): LiveData<List<Photo>> = photoRepository.getAlbumPhotosFlow(albumId).asLiveData()
    fun setSortOrder(albumId: String, sortOrder: Int) = viewModelScope.launch(Dispatchers.IO) { albumRepository.setSortOrder(albumId, sortOrder) }
    fun getAllAlbumIdName(): List<IDandName> = albumRepository.getAllAlbumIdName()
    fun getThisAlbum(albumId: String): Album = albumRepository.getThisAlbum(albumId)
    fun getAllAlbumName(): List<String> = albumRepository.getAllAlbumName()
    val allHiddenAlbums: LiveData<List<Album>> = albumRepository.getAllHiddenAlbumsFlow().asLiveData()
    fun getCoverFileName(album: Album): String = photoRepository.getPhotoName(album.cover)

    fun setAsRemote(albumIds: List<String>, asRemote: Boolean) {
        // Update local db
        albumRepository.setAsRemote(albumIds, asRemote)

        if (asRemote) {
            // If changing from local to remote
            albumIds.forEach {
                photoRepository.getAlbumPhotos(it).forEach { photo ->
                    // Remove all local media files (named after file id), for those media files pending upload, they will be remove after upload complete since album attribute has been changed to remote
                    if (photo.eTag != Album.ETAG_NOT_YET_UPLOADED) {
                        try { File(localRootFolder, photo.id).delete() } catch (e: Exception) { e.printStackTrace() }
                        if (photo.mimeType.startsWith("video")) try { File(localRootFolder, "${photo.id}.thumbnail").delete() } catch (e: Exception) { e.printStackTrace() }
                    }
                }
            }
        } else {
            photoRepository.setAsLocal(albumIds)
        }
    }
}