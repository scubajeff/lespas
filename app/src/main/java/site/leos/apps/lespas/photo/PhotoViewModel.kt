package site.leos.apps.lespas.photo

import android.app.Application
import androidx.lifecycle.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import site.leos.apps.lespas.sync.Action
import site.leos.apps.lespas.sync.ActionRepository

class PhotoViewModel(application: Application, private val albumId: String): AndroidViewModel(application) {
    //private val repository = PhotoRepository.getRepository(application)
    private val photoRepository = PhotoRepository(application)
    private val actionRepository = ActionRepository(application)
    val allPhotoInAlbum: LiveData<List<Photo>>
    val albumSize: LiveData<Int>

    init {
        allPhotoInAlbum = photoRepository.getAllByDateTakenASCDistinctLiveData(albumId)
        albumSize = photoRepository.getAlbumSizeDistinctLiveData(albumId)
    }

    fun deletePhotos(photos: List<Photo>)  {
        viewModelScope.launch(Dispatchers.IO) {
            // Delete from local database
            photoRepository.deletePhotos(photos)

            // Create new actions on server side
            val actions = mutableListOf<Action>()
            val timestamp = System.currentTimeMillis()
            photos.forEach {photo ->  actions.add(Action(null, Action.ACTION_DELETE_FILES_ON_SERVER, photo.albumId, photo.name, timestamp)) }
            actionRepository.addActions(actions)
        }
    }
}

class PhotoViewModelFactory(private val application: Application, private val albumId: String) : ViewModelProvider.NewInstanceFactory() {
    override fun <T : ViewModel?> create(modelClass: Class<T>): T = PhotoViewModel(application, albumId) as T
}