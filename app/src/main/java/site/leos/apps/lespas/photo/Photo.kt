package site.leos.apps.lespas.photo

import android.os.Parcelable
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.parcelize.Parcelize
import site.leos.apps.lespas.BaseDao
import java.time.LocalDateTime

@Entity(
    tableName = Photo.TABLE_NAME,
    indices = [Index(value = ["albumId"]), Index(value = ["dateTaken"])],
)
@Parcelize
data class Photo(
    @PrimaryKey var id: String,
    var albumId: String,
    var name: String,
    var eTag: String,
    var dateTaken: LocalDateTime,
    var lastModified: LocalDateTime,
    var width: Int,
    var height: Int,
    var mimeType: String,
    var shareId: Int,
): Parcelable {
    companion object {
        const val TABLE_NAME = "photos"
    }
}

data class PhotoETag(val id: String, val eTag: String)
data class PhotoName(val id: String, val name: String)
data class AlbumPhotoName(val albumId: String, val name: String)
data class PhotoMeta(val id: String, val name: String, val dateTaken: LocalDateTime, val mimeType: String, val width: Int, val height: Int)
data class MuzeiPhoto(val id: String, val albumId: String, val dateTaken: LocalDateTime, val width: Int, val height: Int)

@Dao
abstract class PhotoDao: BaseDao<Photo>() {
    @Query("DELETE FROM ${Photo.TABLE_NAME} WHERE id = :photoId")
    abstract fun deleteById(photoId: String): Int

    @Query("SELECT id, eTag FROM ${Photo.TABLE_NAME} WHERE albumId = :albumId ORDER BY dateTaken ASC")
    abstract fun getETagsMap(albumId: String): List<PhotoETag>

    @Query("SELECT id, name FROM ${Photo.TABLE_NAME} WHERE albumId = :albumId ORDER BY dateTaken ASC")
    abstract fun getNamesMap(albumId: String): List<PhotoName>

    @Query("UPDATE ${Photo.TABLE_NAME} SET name = :newName WHERE id = :id")
    abstract fun changeName(id: String, newName: String)

    //@Query("UPDATE ${Photo.TABLE_NAME} SET name = :newName WHERE albumId = :albumId AND name = :oldName")
    //abstract suspend fun changeName(albumId: String, oldName: String, newName: String)

    @Query("SELECT * FROM ${Photo.TABLE_NAME} WHERE albumId = :albumId ORDER BY dateTaken ASC")
    abstract fun getAlbumPhotos(albumId: String): List<Photo>

    @Query("SELECT * FROM ${Photo.TABLE_NAME} WHERE albumId = :albumId")
    abstract fun getAlbumPhotosFlowDistinct(albumId: String): Flow<List<Photo>>
    fun getAlbumPhotosFlow(albumId: String) = getAlbumPhotosFlowDistinct(albumId).distinctUntilChanged()

    @Query("DELETE FROM ${Photo.TABLE_NAME} WHERE albumId = :albumId")
    abstract fun deletePhotosByAlbum(albumId: String)

    @Query("UPDATE ${Photo.TABLE_NAME} SET albumId = :newId WHERE albumId = :oldId")
    abstract fun fixNewPhotosAlbumId(oldId: String, newId: String)

    @Query("UPDATE ${Photo.TABLE_NAME} SET id = :newId, name = :newName, eTag = :eTag, lastModified = :lastModified WHERE id = :oldId")
    abstract fun fixPhoto(oldId: String, newId: String, newName: String, eTag: String, lastModified: LocalDateTime)

    @Query("UPDATE ${Photo.TABLE_NAME} SET id = :newId, eTag = :eTag WHERE id = :oldId")
    abstract fun fixPhotoIdEtag(oldId: String, newId: String, eTag: String)

    @Query("SELECT albumId, name FROM ${Photo.TABLE_NAME}")
    abstract fun getAllPhotoNameMap(): List<AlbumPhotoName>

    //@Query("UPDATE ${Photo.TABLE_NAME} SET id = :newId, name = :newId, eTag = :eTag, lastModified = :lastModifiedDate, width = :width, height = :height, mimeType = :mimeType  WHERE id = :oldId")
    //abstract suspend fun updatePhoto(oldId: String, newId: String, eTag: String, lastModifiedDate: LocalDateTime, width: Int, height: Int, mimeType: String)

    //@Query("UPDATE ${Photo.TABLE_NAME} SET id = :newId, name = :newName, albumId = :newAlbumId, dateTaken = :newDateTaken, lastModified = :newLastModified, width = :newWidth, height = :newHeight, mimeType = :newMimeType, shareId = :newShareId WHERE id = :oldPhotoId")
    //abstract suspend fun replacePhoto(oldPhotoId: String, newId: String, newName: String, newAlbumId: String, newDateTaken: LocalDateTime, newLastModified: LocalDateTime, newWidth: Int, newHeight: Int, newMimeType: String, newShareId: Int)
/*
    @Transaction
    open suspend fun replacePhoto(oldPhoto: Photo, newPhoto: Photo) {
        delete(oldPhoto)
        insert(newPhoto)
    }
*/

    @Query("SELECT name FROM ${Photo.TABLE_NAME} WHERE id = :id")
    abstract fun getName(id: String): String

    @Query("SELECT * FROM ${Photo.TABLE_NAME} WHERE id = :photoId")
    abstract fun getPhotoById(photoId: String): Photo

    @Query("SELECT * FROM ${Photo.TABLE_NAME} WHERE (mimeType LIKE '%image/%')  ORDER BY dateTaken DESC")
    abstract fun getAllImage(): List<Photo>

    @Query("SELECT COUNT(*) FROM ${Photo.TABLE_NAME}")
    abstract fun getPhotoTotal(): Int

    @Query("SELECT eTag FROM ${Photo.TABLE_NAME} WHERE id = :photoId")
    abstract fun getETag(photoId: String): String

    @Query("SELECT id, name, dateTaken, mimeType, width, height FROM ${Photo.TABLE_NAME} WHERE albumId = :albumId AND eTag != ''")
    abstract fun getPhotoMetaInAlbum(albumId: String): List<PhotoMeta>

    //@Query("SELECT * FROM ${Photo.TABLE_NAME} WHERE width < height AND (mimeType LIKE '%jpeg%' OR mimeType LIKE '%png%')")
    @Query("SELECT id, albumId, dateTaken, width, height FROM ${Photo.TABLE_NAME} WHERE (CASE WHEN :portraitMode THEN width < height ELSE width > height END) AND mimeType IN ('image/jpeg', 'image/png', 'image/bmp', 'image/gif', 'image/webp') AND albumId NOT IN ( :exclusion )")
    abstract fun getMuzeiArtwork(exclusion: List<String>, portraitMode: Boolean): List<MuzeiPhoto>

    @Query("SELECT dateTaken FROM ${Photo.TABLE_NAME} WHERE albumId = :albumId ORDER BY dateTaken ASC")
    abstract fun getAlbumDuration(albumId: String): List<LocalDateTime>
}
