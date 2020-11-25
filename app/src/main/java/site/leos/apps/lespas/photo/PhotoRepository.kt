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
    fun getETagsMap(albumId: String): Map<String, String> = photoDao.getETagsMap(albumId).map { it.id to it.eTag }.toMap()
    fun getNamesMap(albumId: String): Map<String, String> = photoDao.getNamesMap(albumId).map { it.id to it.name }.toMap()
    fun changeName(photoId: String, newName: String) = photoDao.changeName(photoId, newName)
    suspend fun deletePhotos(photos: List<Photo>) { photoDao.delete(photos)}
    fun getAllPhotoIdsByAlbum(albumId: String): List<PhotoName> = photoDao.getNamesMap(albumId)
    fun deletePhotosByAlbum(albumId: String) = photoDao.deletePhotosByAlbum(albumId)
    fun getAlbumPhotos(albumId: String) = photoDao.getAlbumPhotos(albumId)
    suspend fun getPhotoById(photoId: String): Photo = photoDao.getPhotoById(photoId)
    suspend fun getThesePhotos(ids: List<String>): List<Photo> = photoDao.getThesePhotos(ids)

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