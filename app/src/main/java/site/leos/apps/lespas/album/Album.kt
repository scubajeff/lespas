package site.leos.apps.lespas.album

import android.os.Parcelable
import androidx.room.*
import kotlinx.android.parcel.Parcelize
import kotlinx.coroutines.flow.Flow
import site.leos.apps.lespas.BaseDao
import site.leos.apps.lespas.photo.Photo
import java.time.LocalDateTime

@Entity(tableName = Album.TABLE_NAME)
@Parcelize
data class Album(
    @PrimaryKey var id: String,
    var name: String,
    var startDate: LocalDateTime,
    var endDate: LocalDateTime,
    var cover: String,
    var coverBaseline: Int,
    var lastModified: LocalDateTime,
    var sortOrder: Int,
    var eTag: String,
    var shareId: Int): Parcelable {
    companion object {
        const val TABLE_NAME = "albums"

        const val BY_DATE_TAKEN_ASC = 0
        const val BY_DATE_TAKEN_DESC = 1
        const val BY_DATE_MODIFIED_ASC = 2
        const val BY_DATE_MODIFIED_DESC = 3
        const val BY_NAME_ASC = 4
        const val BY_NAME_DESC = 5
    }
}

data class AlbumWithPhotos(
    @Embedded var album: Album,
    @Relation(
        parentColumn = "id",
        entityColumn = "albumId"
    )
    var photos: List<Photo>
)

data class Cover(val id: String, val baseLine: Int)
data class AlbumNameAndId(val id: String, val name: String)

@Dao
abstract class AlbumDao: BaseDao<Album>() {
    @Query("DELETE FROM ${Album.TABLE_NAME}")
    abstract suspend fun deleteAll()

    @Query("DELETE FROM ${Album.TABLE_NAME} WHERE id = :albumId")
    abstract suspend fun deleteById(albumId: String): Int

    @Query("DELETE FROM ${Album.TABLE_NAME} WHERE id = :albumId")
    abstract fun deleteByIdSync(albumId: String): Int

    @Query("SELECT * FROM ${Album.TABLE_NAME} ORDER BY endDate DESC")
    abstract fun getAllSortByEndDate(): Flow<List<Album>>

    @Query("SELECT * FROM ${Album.TABLE_NAME} WHERE id = :albumId")
    abstract fun getAlbumByID(albumId: String): Flow<Album>

    @Query("SELECT * FROM ${Album.TABLE_NAME} WHERE id = :albumId")
    abstract fun getThisAlbum(albumId: String): List<Album>

    @Query("SELECT id FROM ${Album.TABLE_NAME} ORDER BY id ASC")
    abstract fun getAllIds(): List<String>

    @Query("UPDATE ${Album.TABLE_NAME} SET name = :newName WHERE id = :id")
    abstract fun changeName(id: String, newName: String)

    @Query("UPDATE ${Album.TABLE_NAME} SET cover = :cover, coverBaseline = :coverBaseline WHERE id = :albumId")
    abstract suspend fun setCover(albumId: String, cover: String, coverBaseline: Int)

    @Query("SELECT name FROM ${Album.TABLE_NAME} WHERE id = :albumId")
    abstract fun getAlbumName(albumId: String): String

    @Query("SELECT id, name FROM ${Album.TABLE_NAME} ORDER BY id ASC")
    abstract fun getAllAlbumNamesAndId(): Flow<List<AlbumNameAndId>>

    @Query("SELECT EXISTS (SELECT name FROM ${Album.TABLE_NAME} WHERE name = :name)")
    abstract fun isAlbumExisted(name: String): Boolean

    @Query("SELECT * FROM ${Album.TABLE_NAME} WHERE id IN (:albums) ORDER BY id ASC")
    abstract fun getTheseAlbums(albums: ArrayList<String>): List<Album>

    @Query("SELECT * FROM ${Album.TABLE_NAME} WHERE id = :albumId")
    abstract fun getAlbumWithPhotos(albumId: String): List<AlbumWithPhotos>
}
