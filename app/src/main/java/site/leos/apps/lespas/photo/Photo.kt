package site.leos.apps.lespas.photo

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import site.leos.apps.lespas.BaseDao
import java.time.LocalDateTime

@Entity(
    tableName = Photo.TABLE_NAME,
    indices = [Index(value = ["albumId"]), Index(value = ["dateTaken"])],
)
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
    var shareId: Int) {
    companion object {
        const val TABLE_NAME = "photos"
    }
}

data class PhotoETag(val id: String, val eTag: String)
data class PhotoName(val id: String, val name: String)
data class AlbumPhotoName(val albumId: String, val name: String)

@Dao
abstract class PhotoDao: BaseDao<Photo>() {
    @Query("DELETE FROM ${Photo.TABLE_NAME}")
    abstract suspend fun deleteAll()

    @Query("DELETE FROM ${Photo.TABLE_NAME} WHERE id = :photoId")
    abstract suspend fun deleteById(photoId: String): Int

    @Query("DELETE FROM ${Photo.TABLE_NAME} WHERE id = :photoId")
    abstract fun deleteByIdSync(photoId: String): Int

    @Query("SELECT id, eTag FROM ${Photo.TABLE_NAME} WHERE albumId = :albumId ORDER BY dateTaken ASC")
    abstract fun getETagsMap(albumId: String): List<PhotoETag>

    @Query("SELECT id, name FROM ${Photo.TABLE_NAME} WHERE albumId = :albumId ORDER BY dateTaken ASC")
    abstract fun getNamesMap(albumId: String): List<PhotoName>

    @Query("UPDATE ${Photo.TABLE_NAME} SET name = :newName WHERE id = :id")
    abstract fun changeName(id: String, newName: String)

    @Query("SELECT * FROM ${Photo.TABLE_NAME} WHERE albumId = :albumId ORDER BY dateTaken ASC")
    abstract fun getAlbumPhotos(albumId: String): List<Photo>

    @Query("SELECT * FROM ${Photo.TABLE_NAME} WHERE albumId = :albumId ORDER BY dateTaken ASC")
    abstract fun _getAlbumPhotosByDateTakenASC(albumId: String): Flow<List<Photo>>
    fun getAlbumPhotosByDateTakenASC(albumId: String) = _getAlbumPhotosByDateTakenASC(albumId).distinctUntilChanged()

    @Query("SELECT COUNT(*) FROM ${Photo.TABLE_NAME} WHERE albumId = :albumId")
    abstract fun getAlbumSize(albumId: String): Flow<Int>

    @Query("DELETE FROM ${Photo.TABLE_NAME} WHERE albumId = :albumId")
    abstract fun deletePhotosByAlbum(albumId: String)

    @Query("SELECT * FROM ${Photo.TABLE_NAME} WHERE id = :photoId")
    abstract suspend fun getPhotoById(photoId: String): Photo

    @Query("SELECT * FROM ${Photo.TABLE_NAME} WHERE id IN (:ids)")
    abstract suspend fun getThesePhotos(ids: List<String>): List<Photo>

    @Query("UPDATE ${Photo.TABLE_NAME} SET albumId = :newId WHERE albumId = :oldId")
    abstract fun fixNewPhotosAlbumId(oldId: String, newId: String)

    @Query("UPDATE ${Photo.TABLE_NAME} SET id = :newId, eTag = :eTag, lastModified = :lastModified WHERE id = :oldId")
    abstract fun fixPhotoId(oldId: String, newId: String, eTag: String, lastModified: LocalDateTime)

    @Query("SELECT albumId, name FROM ${Photo.TABLE_NAME}")
    abstract fun getAllPhotoNameMap(): List<AlbumPhotoName>
}

/**
 * LiveData that propagates only distinct emissions.
fun <T> LiveData<T>.getDistinct(): LiveData<T> {
val distinctLiveData = MediatorLiveData<T>()
distinctLiveData.addSource(this, object : Observer<T> {
private var initialized = false
private var lastObj: T? = null

override fun onChanged(obj: T?) {
if (!initialized) {
initialized = true
lastObj = obj
distinctLiveData.postValue(lastObj)
} else if ((obj == null && lastObj != null) || obj != lastObj) {
lastObj = obj
distinctLiveData.postValue(lastObj)
}
}
})

return distinctLiveData
 */
