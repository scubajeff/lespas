package site.leos.apps.lespas.album

import androidx.lifecycle.LiveData
import androidx.room.*

@Entity(tableName = Album.TABLE_NAME, indices = [Index(value = ["endDate"])])
data class Album(
    @PrimaryKey(autoGenerate = true) val _id: Long,
    var name: String,
    var startDate: Long,
    var endDate: Long,
    var cover: String,
    var coverBaseline: Int,
    var total: Int,
    var eTag: String,
    var fileId: String,
    var shareId: Int)
{
    companion object {
        const val TABLE_NAME = "album_table"
    }
}

@Dao
interface AlbumDao{
    @Transaction
    @Query("DELETE FROM ${Album.TABLE_NAME}")
    suspend fun deleteAll()

    @Delete
    suspend fun delete(vararg album: Album): Int

    @Query("DELETE FROM ${Album.TABLE_NAME} WHERE _id=:rowId")
    suspend fun deleteById(rowId: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(album: Album): Long

    @Update(onConflict = OnConflictStrategy.IGNORE)
    suspend fun update(vararg album: Album): Int

    @Query("SELECT * FROM ${Album.TABLE_NAME} ORDER BY endDate ASC")
    fun getAllByEndDate(): LiveData<List<Album>>
}