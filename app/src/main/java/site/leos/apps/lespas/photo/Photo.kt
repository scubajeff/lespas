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
    foreignKeys = [ForeignKey(entity = Album::class, parentColumns = arrayOf("id"), childColumns = arrayOf("albumId"), onDelete = CASCADE, onUpdate = CASCADE)]
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
abstract class PhotoDao: BaseDao<Photo> {
    @Query("DELETE FROM ${Photo.TABLE_NAME}")
    abstract suspend fun deleteAll()

    @Query("DELETE FROM ${Photo.TABLE_NAME} WHERE id = :fileId")
    abstract suspend fun deleteById(fileId: String): Int

    @Query("SELECT * FROM ${Photo.TABLE_NAME} ORDER BY dateTaken ASC")
    protected abstract fun getAllByDateTakenASC(): LiveData<List<Photo>>
    fun getAllByDateTakenASCDistinctLiveData(): LiveData<List<Photo>> = getAllByDateTakenASC().getDistinct()

    @Query("SELECT id, eTag FROM ${Photo.TABLE_NAME} WHERE albumId = :albumId ORDER BY id ASC")
    abstract fun getSyncStatus(albumId: String): List<PhotoSyncStatus>
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