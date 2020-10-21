package site.leos.apps.lespas.album

import androidx.lifecycle.LiveData
import androidx.room.*

@Entity(tableName = "album_table", indices = [Index(value = ["endDate"])])
data class Album(
    @PrimaryKey(autoGenerate = true) val id: Long,
    var name: String,
    var startDate: Long,
    var endDate: Long,
    var cover: String,
    var coverBaseline: Int,
    var total: Int
)

@Dao
interface AlbumDao{
    @Query("DELETE FROM album_table")
    suspend fun deleteAll()

    @Delete
    suspend fun delete(vararg album: Album): Int

    @Query("DELETE FROM album_table WHERE id=:rowId")
    suspend fun deleteById(rowId: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(album: Album): Long

    @Update(onConflict = OnConflictStrategy.IGNORE)
    suspend fun update(vararg album: Album): Int

    @Query("SELECT * from album_table ORDER BY endDate ASC")
    fun getAllByEndDate(): LiveData<List<Album>>
}