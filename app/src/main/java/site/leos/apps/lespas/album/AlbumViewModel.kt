package site.leos.apps.lespas.album

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import site.leos.apps.lespas.LespasDatabase

class AlbumViewModel(application: Application) : AndroidViewModel(application){
    private val repository: AlbumRepository
    val allAlbumsByEndDate: LiveData<List<Album>>

    init {
        val albumDao = LespasDatabase.getDatabase(application).albumDao()
        repository = AlbumRepository(albumDao)
        allAlbumsByEndDate = repository.allAlbumsByEndDate
    }

    fun insert(album: Album) = viewModelScope.launch(Dispatchers.IO){
        repository.insert(album)
    }
}