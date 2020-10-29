package site.leos.apps.lespas.photo

import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.Observer
import androidx.room.*
import androidx.room.ForeignKey.CASCADE
import site.leos.apps.lespas.BaseDao
import site.leos.apps.lespas.album.Album
import java.util.*

@Entity(
    tableName = Photo.TABLE_NAME,
    indices = [Index(value = ["albumId"]), Index(value = ["dateTaken"])],
    foreignKeys = [ForeignKey(entity = Album::class, parentColumns = arrayOf("id"), childColumns = arrayOf("albumId"), onDelete = CASCADE)]
)
data class Photo(
    @PrimaryKey var id: String,
    var albumId: String,
    var name: String,
    var eTag: String,
    var dateTaken: Date?,
    var lastModified: Date?,
    var shareId: Int)
{
    companion object {
        const val TABLE_NAME = "photos"
    }
}

data class PhotoSyncStatus(val id: String, val eTag: String)

@Dao
abstract class PhotoDao: BaseDao<Photo>() {
    @Query("DELETE FROM ${Photo.TABLE_NAME}")
    abstract suspend fun deleteAll()

    @Query("DELETE FROM ${Photo.TABLE_NAME} WHERE id = :photoId")
    abstract suspend fun deleteById(photoId: String): Int

    @Query("DELETE FROM ${Photo.TABLE_NAME} WHERE id = :photoId")
    abstract fun deleteByIdSync(photoId: String): Int

    @Query("SELECT id, eTag FROM ${Photo.TABLE_NAME} WHERE albumId = :albumId")
    abstract fun getAlbumSyncStatus(albumId: String): List<PhotoSyncStatus>

    @Query("SELECT * FROM ${Photo.TABLE_NAME} WHERE albumId = :albumId ORDER BY dateTaken ASC")
    protected abstract fun getAlbumPhotosByDateTakenASC(albumId: String): LiveData<List<Photo>>
    fun getAlbumPhotosByDateTakenASCDistinctLiveData(albumId: String): LiveData<List<Photo>> = getAlbumPhotosByDateTakenASC(albumId).getDistinct()

    @Query("SELECT COUNT(*) FROM ${Photo.TABLE_NAME} WHERE albumId = :albumId")
    protected abstract fun getAlbumSize(albumId: String): LiveData<Int>
    fun getAlbumSizeDistinctLiveData(albumId: String): LiveData<Int> = getAlbumSize(albumId).getDistinct()
}

/**
 * LiveData that propagates only distinct emissions.
 */
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
}