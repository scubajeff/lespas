package site.leos.apps.lespas.photo

import android.app.Application
import androidx.lifecycle.LiveData
import site.leos.apps.lespas.LespasDatabase

class PhotoRepository(application: Application) {
    private val photoDao = LespasDatabase.getDatabase(application).photoDao()

    fun upsertSync(photo: Photo) { photoDao.upsertSync(photo) }
    fun deleteByIdSync(photoId: String) { photoDao.deleteByIdSync(photoId) }
    fun getAllByDateTakenASCDistinctLiveData(albumId: String): LiveData<List<Photo>> { return photoDao.getAlbumPhotosByDateTakenASCDistinctLiveData(albumId) }
    fun getAlbumSizeDistinctLiveData(albumId: String): LiveData<Int> { return photoDao.getAlbumSizeDistinctLiveData(albumId) }
    fun getSyncStatus(albumId: String): Map<String, String> {
        return photoDao.getAlbumSyncStatus(albumId).map { it.id to it.eTag }.toMap()
    }
    suspend fun deletePhotos(photos: List<Photo>) { photoDao.delete(photos)}

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