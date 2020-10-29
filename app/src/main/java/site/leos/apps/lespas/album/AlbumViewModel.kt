package site.leos.apps.lespas.album

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AlbumViewModel(application: Application) : AndroidViewModel(application){
    private val repository: AlbumRepository = AlbumRepository.getRepository(application)
    val allAlbumsByEndDate: LiveData<List<Album>>

    init {
        allAlbumsByEndDate = repository.allAlbumsByEndDate
    }

    fun insertAsync(album: Album) = viewModelScope.launch(Dispatchers.IO){
        repository.insert(album)
    }
}