package site.leos.apps.lespas.photo

import android.app.Application
import kotlinx.coroutines.flow.Flow
import site.leos.apps.lespas.LespasDatabase

class PhotoRepository(application: Application) {
    private val photoDao = LespasDatabase.getDatabase(application).photoDao()

    fun getAlbumPhotosByDateTakenASC(albumId: String): Flow<List<Photo>> = photoDao.getAlbumPhotosByDateTakenASC(albumId)
    fun getAlbumSize(albumId: String): Flow<Int> = photoDao.getAlbumSize(albumId)
    fun upsertSync(photo: Photo) { photoDao.upsertSync(photo) }
    fun deleteByIdSync(photoId: String) { photoDao.deleteByIdSync(photoId) }
    fun getSyncStatus(albumId: String): Map<String, String> = photoDao.getAlbumSyncStatus(albumId).map { it.id to it.eTag }.toMap()
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