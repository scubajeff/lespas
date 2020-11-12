package site.leos.apps.lespas.album

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import site.leos.apps.lespas.sync.Action
import site.leos.apps.lespas.sync.ActionRepository

class AlbumViewModel(application: Application) : AndroidViewModel(application){
    //private val repository: AlbumRepository = AlbumRepository.getRepository(application)
    private val albumRepository = AlbumRepository(application)
    private val actionRepository = ActionRepository(application)
    val allAlbumsByEndDate: LiveData<List<Album>>

    init {
        allAlbumsByEndDate = albumRepository.allAlbumsSortByEndDate
    }

    fun insertAsync(album: Album) = viewModelScope.launch(Dispatchers.IO) { albumRepository.insert(album) }
    fun getAlbumByID(albumId: String): LiveData<Album> = albumRepository.getAlbumByID(albumId)
    fun setCover(album: Album, cover: Cover) = viewModelScope.launch(Dispatchers.IO) { albumRepository.setCover(album, cover) }
    fun deleteAlbums(albums: List<Album>) {
        viewModelScope.launch(Dispatchers.IO) {
            albumRepository.deleteAlbums(albums)

            val actions = mutableListOf<Action>()
            val timestamp = System.currentTimeMillis()
            albums.forEach {album -> actions.add(Action(null, Action.ACTION_DELETE_DIRECTORY_ON_SERVER, album.id, album.name, timestamp, 0)) }
            actionRepository.addActions(actions)
        }
    }
}