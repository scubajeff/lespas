package site.leos.apps.lespas.album

import android.os.Parcelable
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.parcelize.Parcelize
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
    var coverWidth: Int,
    var coverHeight: Int,
    var lastModified: LocalDateTime,
    var sortOrder: Int,
    var eTag: String,
    var shareId: Int,
    var syncProgress: Float,
): Parcelable {
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

data class Cover(val cover: String, val coverBaseline: Int, val coverWidth: Int, val coverHeight: Int)
data class IDandCover(val id: String, val cover: String)
data class IDandName(val id: String, val name: String)
data class Meta(val sortOrder: Int, val cover: String, val coverBaseline: Int, val coverWidth: Int, val coverHeight: Int)
//data class AlbumDestination(val id: String, val name: String, val cover: String)

@Dao
abstract class AlbumDao: BaseDao<Album>() {
    @Query("DELETE FROM ${Album.TABLE_NAME} WHERE id = :albumId")
    abstract fun deleteByIdSync(albumId: String): Int

    @Query("SELECT * FROM ${Album.TABLE_NAME} WHERE cover != '' ORDER BY endDate DESC")
    abstract fun getAllSortByEndDateDistinct(): Flow<List<Album>>
    fun getAllSortByEndDate() = getAllSortByEndDateDistinct().distinctUntilChanged()

    @Query("SELECT * FROM ${Album.TABLE_NAME} WHERE id = :albumId")
    abstract fun getThisAlbum(albumId: String): List<Album>

    @Query("SELECT * FROM ${Album.TABLE_NAME} WHERE id = :albumId")
    abstract suspend fun getAlbumById(albumId: String): Album

    @Query("SELECT id, cover FROM ${Album.TABLE_NAME} ORDER BY id ASC")
    abstract fun getAllIdAndCover(): List<IDandCover>

    @Query("UPDATE ${Album.TABLE_NAME} SET name = :newName WHERE id = :id")
    abstract fun changeName(id: String, newName: String)

    @Query("UPDATE ${Album.TABLE_NAME} SET cover = :cover, coverBaseline = :coverBaseline, coverWidth = :width, coverHeight = :height WHERE id = :albumId")
    abstract suspend fun setCover(albumId: String, cover: String, coverBaseline: Int, width: Int, height: Int)

    @Query("SELECT sortOrder, cover, coverBaseline, coverWidth, coverHeight FROM ${Album.TABLE_NAME} WHERE id = :albumId")
    abstract fun getMeta(albumId: String): Meta

    @Query("SELECT EXISTS (SELECT name FROM ${Album.TABLE_NAME} WHERE name = :name)")
    abstract fun isAlbumExisted(name: String): Boolean

    @Transaction
    @Query("SELECT * FROM ${Album.TABLE_NAME} WHERE id = :albumId")
    abstract fun getAlbumDetail(albumId: String): Flow<AlbumWithPhotos>

    @Query( "UPDATE ${Album.TABLE_NAME} SET syncProgress = :progress, startDate = :startDate, endDate = :endDate WHERE id = :albumId")
    abstract fun updateAlbumSyncStatus(albumId: String, progress: Float, startDate: LocalDateTime, endDate: LocalDateTime)

    @Query("UPDATE ${Album.TABLE_NAME} SET id = :newId, cover = :coverId, syncProgress = 1 WHERE id = :oldId")
    abstract fun fixNewLocalAlbumId(oldId: String, newId: String, coverId: String)

    @Query("UPDATE ${Album.TABLE_NAME} SET cover = :newCoverId WHERE id = :albumId")
    abstract fun fixCoverId(albumId: String, newCoverId: String)

    @Query("UPDATE ${Album.TABLE_NAME} SET sortOrder = :sortOrder WHERE id = :albumId")
    abstract suspend fun setSortOrder(albumId: String, sortOrder: Int)

    @Query("UPDATE ${Album.TABLE_NAME} SET cover = :newCoverId, coverBaseline = :newBaseline, coverWidth = :newWidth, coverHeight = :newHeight WHERE id = :albumId")
    abstract fun replaceCover(albumId: String, newCoverId: String, newWidth: Int, newHeight: Int, newBaseline: Int)

    @Query("SELECT id, name FROM ${Album.TABLE_NAME}")
    abstract fun getAllAlbumName(): List<IDandName>

    @Query("SELECT COUNT(*) FROM ${Album.TABLE_NAME}")
    abstract suspend fun getAlbumTotal(): Int

    @Query("SELECT id, cover FROM ${Album.TABLE_NAME} WHERE eTag != ''")
    abstract fun getAllSyncedAlbum(): List<IDandCover>
}
