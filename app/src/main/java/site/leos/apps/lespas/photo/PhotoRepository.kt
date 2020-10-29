package site.leos.apps.lespas.photo

import android.app.Application
import androidx.lifecycle.LiveData
import site.leos.apps.lespas.LespasDatabase

class PhotoRepository(application: Application) {
    private val photoDao = LespasDatabase.getDatabase(application).photoDao()

    val allPhotoByDateTaken: LiveData<List<Photo>> = photoDao.getAllByDateTakenASCDistinctLiveData()
    suspend fun insert(photo: Photo) { photoDao.insert(photo) }
    fun insertSync(photo: Photo) { photoDao.insertSync(photo) }
    suspend fun update(photo: Photo) { photoDao.update(photo) }
    fun getSyncStatus(albumId: String): Map<String, String> {
        return photoDao.getSyncStatus(albumId).map { it.id to it.eTag }.toMap()
    }

    companion object {
        private var repo: PhotoRepository? = null
        fun getRepository(application: Application): PhotoRepository {
            synchronized(Any()) {
                repo = repo ?: PhotoRepository(application)
            }
            return repo!!
        }
    }
}