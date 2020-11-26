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

class AlbumViewModel(application: Application) : AndroidViewModel(application){
    //private val repository: AlbumRepository = AlbumRepository.getRepository(application)
    private val albumRepository = AlbumRepository(application)
    private val photoRepository = PhotoRepository(application)

    val allAlbumsByEndDate: LiveData<List<Album>> = albumRepository.getAllAlbumsSortByEndDate().asLiveData()
    fun getAlbumDetail(albumId: String): LiveData<AlbumWithPhotos> = albumRepository.getAlbumDetail(albumId).asLiveData()
    fun insertAsync(album: Album) = viewModelScope.launch(Dispatchers.IO) { albumRepository.insert(album) }
    fun getAlbumByID(albumId: String): LiveData<Album> = albumRepository.getAlbumByID(albumId).asLiveData()
    fun setCover(album: Album, cover: Cover) = viewModelScope.launch(Dispatchers.IO) { albumRepository.setCover(album, cover) }
    val allAlbumNamesAndIds: LiveData<List<AlbumNameAndId>> = albumRepository.getAllAlbumNamesAndIds().asLiveData()
    suspend fun isAlbumNameExisted(name: String): Boolean = albumRepository.isAlbumExisted(name)
    suspend fun getAllCovers(ids: List<String>): List<Photo> = photoRepository.getThesePhotos(ids)
    suspend fun getCoverPhoto(coverId: String): Photo = photoRepository.getPhotoById(coverId)
    fun getAllPhotoInAlbum(albumId: String): LiveData<List<Photo>> = photoRepository.getAlbumPhotosByDateTakenASC(albumId).asLiveData()
}