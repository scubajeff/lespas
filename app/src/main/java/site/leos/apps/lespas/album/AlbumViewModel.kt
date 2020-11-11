package site.leos.apps.lespas.album

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AlbumViewModel(application: Application) : AndroidViewModel(application){
    //private val repository: AlbumRepository = AlbumRepository.getRepository(application)
    private val repository: AlbumRepository = AlbumRepository(application)
    val allAlbumsByEndDate: LiveData<List<Album>>

    init {
        allAlbumsByEndDate = repository.allAlbumsSortByEndDate
    }

    fun insertAsync(album: Album) = viewModelScope.launch(Dispatchers.IO) { repository.insert(album) }

    fun getAlbumByID(albumId: String): LiveData<Album> = repository.getAlbumByID(albumId)

    fun setCover(album: Album, cover: Cover) = viewModelScope.launch(Dispatchers.IO) { repository.setCover(album, cover) }
}