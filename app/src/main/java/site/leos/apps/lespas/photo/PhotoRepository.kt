package site.leos.apps.lespas.photo

import android.app.Application
import kotlinx.coroutines.flow.Flow
import site.leos.apps.lespas.LespasDatabase
import java.time.LocalDateTime

class PhotoRepository(application: Application) {
    private val photoDao = LespasDatabase.getDatabase(application).photoDao()

    fun getAlbumPhotosFlow(albumId: String): Flow<List<Photo>> = photoDao.getAlbumPhotosFlow(albumId)
    fun upsertSync(photo: Photo) { photoDao.upsertSync(photo) }
    fun deleteByIdSync(photoId: String) { photoDao.deleteByIdSync(photoId) }
    fun getETagsMap(albumId: String): Map<String, String> = photoDao.getETagsMap(albumId).map { it.id to it.eTag }.toMap()
    fun getNamesMap(albumId: String): Map<String, String> = photoDao.getNamesMap(albumId).map { it.id to it.name }.toMap()
    fun changeName(photoId: String, newName: String) = photoDao.changeName(photoId, newName)
    suspend fun deletePhotos(photos: List<Photo>) { photoDao.delete(photos)}
    fun getAllPhotoIdsByAlbum(albumId: String): List<PhotoName> = photoDao.getNamesMap(albumId)
    fun deletePhotosByAlbum(albumId: String) = photoDao.deletePhotosByAlbum(albumId)
    fun getAlbumPhotos(albumId: String) = photoDao.getAlbumPhotos(albumId)
    suspend fun insert(photos: List<Photo>) { photoDao.insert(photos) }
    suspend fun insert(photo: Photo) { photoDao.insert(photo) }
    fun fixNewPhotosAlbumId(oldId: String, newId: String) { photoDao.fixNewPhotosAlbumId(oldId, newId) }
    fun fixPhotoId(oldId: String, newId: String, eTag: String, lastModified: LocalDateTime) { photoDao.fixPhotoId(oldId, newId, eTag, lastModified) }
    fun getAllPhotoNameMap(): List<AlbumPhotoName> = photoDao.getAllPhotoNameMap()
    suspend fun updatePhoto(oldId: String, newId: String, eTag: String, lastModifiedDate: LocalDateTime, width: Int, height: Int, mimeType: String) {
        photoDao.updatePhoto(oldId, newId, eTag, lastModifiedDate, width, height, mimeType)
    }
}