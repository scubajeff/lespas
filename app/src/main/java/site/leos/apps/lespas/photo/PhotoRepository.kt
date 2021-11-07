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
    fun changeName(photoId: String, newName: String) { photoDao.changeName(photoId, newName) }
    //suspend fun changeName(albumId: String, oldName: String, newName: String) = photoDao.changeName(albumId, oldName, newName)
    suspend fun deletePhotos(photos: List<Photo>) { photoDao.delete(photos)}
    fun getAllPhotoIdsByAlbum(albumId: String): List<PhotoName> = photoDao.getNamesMap(albumId)
    fun deletePhotosByAlbum(albumId: String) = photoDao.deletePhotosByAlbum(albumId)
    fun getAlbumPhotos(albumId: String): List<Photo> = photoDao.getAlbumPhotos(albumId)
    suspend fun insert(photos: List<Photo>) { photoDao.insert(photos) }
    suspend fun insert(photo: Photo) { photoDao.insert(photo) }
    fun fixNewPhotosAlbumId(oldId: String, newId: String) { photoDao.fixNewPhotosAlbumId(oldId, newId) }
    fun fixPhoto(oldId: String, newId: String, newName: String, eTag: String, lastModified: LocalDateTime) { photoDao.fixPhoto(oldId, newId, newName, eTag, lastModified) }
    fun fixPhotoIdEtag(oldId: String, newId: String, eTag: String) { photoDao.fixPhotoIdEtag(oldId, newId, eTag) }
    fun getAllPhotoNameMap(): List<AlbumPhotoName> = photoDao.getAllPhotoNameMap()
    //suspend fun updatePhoto(oldId: String, newId: String, eTag: String, lastModifiedDate: LocalDateTime, width: Int, height: Int, mimeType: String) { photoDao.updatePhoto(oldId, newId, eTag, lastModifiedDate, width, height, mimeType) }
    //suspend fun replacePhoto(oldPhoto: Photo, newPhoto: Photo) { photoDao.replacePhoto(oldPhoto, newPhoto) }
    //fun removePhoto(photo: Photo) { photoDao.deleteSync(photo) }
    fun getPhotoName(id: String): String = photoDao.getName(id)
    fun getAllImage(): List<Photo> = photoDao.getAllImage()
    suspend fun getPhotoTotal(): Int = photoDao.getPhotoTotal()
    //suspend fun getPhotoById(photoId: String): Photo = photoDao.getPhotoById(photoId)
    fun getPhotoMetaInAlbum(albumId: String): List<PhotoMeta> = photoDao.getPhotoMetaInAlbum(albumId)
    fun getMuzeiArtwork(exclusion: List<String>, portraitMode: Boolean): List<MuzeiPhoto> = photoDao.getMuzeiArtwork(exclusion, portraitMode)
    fun getAlbumDuration(albumId: String): Pair<LocalDateTime, LocalDateTime> = with(photoDao.getAlbumDuration(albumId)) { Pair(this.first(), this.last()) }
}