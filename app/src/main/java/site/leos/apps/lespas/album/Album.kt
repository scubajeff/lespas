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
    @PrimaryKey var id: String = "",
    var name: String = "",
    var startDate: LocalDateTime = LocalDateTime.MAX,   // Default to MAX for smooth start of comparing and sorting
    var endDate: LocalDateTime = LocalDateTime.MIN,     // Default to MIN for smooth start of comparing and sorting
    var cover: String = NO_COVER,
    var coverBaseline: Int = 0,
    var coverWidth: Int = 0,
    var coverHeight: Int = 0,
    var lastModified: LocalDateTime,
    var sortOrder: Int = BY_DATE_TAKEN_ASC,
    var eTag: String = ETAG_NOT_YET_UPLOADED,
    var shareId: Int = DEFAULT_FLAGS,
    var syncProgress: Float = SYNC_COMPLETED,
    var coverFileName: String = NO_COVER,
    var coverMimeType: String = Photo.DEFAULT_MIMETYPE,
    var bgmId: String = NO_BGM,
    var bgmETag: String = Photo.ETAG_NOT_YET_UPLOADED,
): Parcelable {
    companion object {
        const val TABLE_NAME = "albums"

        // Sort order
        const val BY_DATE_TAKEN_ASC = 0
        const val BY_DATE_TAKEN_DESC = 1
        const val BY_DATE_MODIFIED_ASC = 2
        const val BY_DATE_MODIFIED_DESC = 3
        const val BY_NAME_ASC = 4
        const val BY_NAME_DESC = 5

        const val NULL_ALBUM = 0                // Use by DestinationDialogFragment to add a fake album item used for "Add new album" function
        const val SHARED_ALBUM = 1 shl 0        // Album is shared on server
        const val REMOTE_ALBUM = 1 shl 1        // Remote album which media files are not saved locally
        const val EXCLUDED_ALBUM = 1 shl 2      // Exclude this album from any album list, because it's cover media file is not available yet, etc.
        const val DEFAULT_FLAGS = REMOTE_ALBUM  // Default as remote album

        const val NO_COVER = ""
        const val NO_BGM = ""

        const val ETAG_NOT_YET_UPLOADED = ""
        const val ETAG_CAMERA_ROLL_ALBUM = "CR"

        const val SYNC_COMPLETED = 1.0f
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

// TODO: upgrade DB
data class AlbumWithCover(
    @Embedded var album: Album,
    @Relation(
        parentColumn = "cover",
        entityColumn = "id"
    )
    var coverPhoto: Photo?
)

@Parcelize
data class Cover(val cover: String, val coverBaseline: Int, val coverWidth: Int, val coverHeight: Int, val coverFileName: String, val coverMimeType: String): Parcelable
data class IDandCover(val id: String, val cover: String)
data class IDandETag(val id: String, val eTag: String)
data class IDandName(val id: String, val name: String)
data class Meta(val sortOrder: Int, val cover: String, val coverBaseline: Int, val coverWidth: Int, val coverHeight: Int, val coverFileName: String, val coverMimeType: String)
//data class AlbumDestination(val id: String, val name: String, val cover: String)

@Dao
abstract class AlbumDao: BaseDao<Album>() {
    @Query("DELETE FROM ${Album.TABLE_NAME} WHERE id = :albumId")
    abstract fun deleteById(albumId: String): Int

    // Hidden albums not included
    @Query("SELECT * FROM ${Album.TABLE_NAME} WHERE (shareId & ${Album.EXCLUDED_ALBUM} != ${Album.EXCLUDED_ALBUM}) AND name NOT LIKE '.%' ORDER BY endDate DESC")
    abstract fun getAllSortByEndDateDistinct(): Flow<List<Album>>
    fun getAllSortByEndDate() = getAllSortByEndDateDistinct().distinctUntilChanged()

    // Hidden albums not included
    @Transaction
    @Query("SELECT * FROM ${Album.TABLE_NAME} WHERE (shareId & ${Album.EXCLUDED_ALBUM} != ${Album.EXCLUDED_ALBUM}) AND name NOT LIKE '.%' ORDER BY endDate DESC")
    abstract fun getAllWithCoverSortByEndDateDistinct(): Flow<List<AlbumWithCover>>
    fun getAllWithCoverSortByEndDate() = getAllWithCoverSortByEndDateDistinct().distinctUntilChanged()

    @Query("SELECT * FROM ${Album.TABLE_NAME} WHERE id = :albumId LIMIT 1")
    abstract fun getThisAlbum(albumId: String): Album

    @Query("SELECT * FROM ${Album.TABLE_NAME} WHERE id = :albumId")
    abstract fun getThisAlbumList(albumId: String): List<Album>

    @Query("SELECT * FROM ${Album.TABLE_NAME} WHERE name = :albumName")
    abstract fun getAlbumByName(albumName: String): Album?

    // Including hidden ones
    @Query("SELECT id, eTag FROM ${Album.TABLE_NAME} ORDER BY id ASC")
    abstract fun getAllIdAndETag(): List<IDandETag>

    @Query("UPDATE ${Album.TABLE_NAME} SET name = :newName WHERE id = :id")
    abstract fun changeName(id: String, newName: String)

    @Query("UPDATE ${Album.TABLE_NAME} SET cover = :coverId, coverBaseline = :coverBaseline, coverWidth = :width, coverHeight = :height, coverFileName = :filename, coverMimeType = :mimetype WHERE id = :albumId")
    abstract fun setCover(albumId: String, coverId: String, coverBaseline: Int, width: Int, height: Int, filename: String, mimetype: String)

    @Query("SELECT sortOrder, cover, coverBaseline, coverWidth, coverHeight, coverFileName, coverMimeType FROM ${Album.TABLE_NAME} WHERE id = :albumId")
    abstract fun getMeta(albumId: String): Meta

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
    abstract fun setSortOrder(albumId: String, sortOrder: Int)

    // Hidden albums not included
    @Query("SELECT id, name FROM ${Album.TABLE_NAME} WHERE name NOT LIKE '.%'")
    abstract fun getAllAlbumIdName(): List<IDandName>

    // Hidden albums not included
    @Query("SELECT COUNT(*) FROM ${Album.TABLE_NAME} WHERE name NOT LIKE '.%'")
    abstract fun getAlbumTotal(): Int

    // Hidden albums not included
    @Query("SELECT id, cover FROM ${Album.TABLE_NAME} WHERE eTag != '' AND name NOT LIKE '.%'")
    abstract fun getAllSyncedAlbum(): List<IDandCover>

    // Including hidden ones
    @Query("SELECT name FROM ${Album.TABLE_NAME}")
    abstract fun getAllAlbumName(): List<String>

    @Query("SELECT * FROM ${Album.TABLE_NAME} WHERE name LIKE '.%'")
    abstract fun getAllHiddenAlbumsDistinctFlow(): Flow<List<Album>>
    fun getAllHiddenAlbumsFlow() = getAllHiddenAlbumsDistinctFlow().distinctUntilChanged()

    @Query("SELECT id FROM ${Album.TABLE_NAME} WHERE name LIKE '.%'")
    abstract fun getAllHiddenAlbumIds(): List<String>

    @Query("UPDATE ${Album.TABLE_NAME} SET shareId = shareId | ${Album.REMOTE_ALBUM} WHERE id IN (:albumIds)")
    abstract fun setAsRemote(albumIds: List<String>)

    @Transaction
    @Query("UPDATE ${Album.TABLE_NAME} SET shareId = (shareId & ~${Album.REMOTE_ALBUM}) | ${Album.EXCLUDED_ALBUM}, eTag = '${Album.ETAG_NOT_YET_UPLOADED}' WHERE id IN (:albumIds)")
    abstract fun setAsLocal(albumIds: List<String>)

    @Query("UPDATE ${Album.TABLE_NAME} SET bgmId = :bgmId, bgmETag = :bgmETag WHERE id = :albumId")
    abstract fun fixBGM(albumId: String, bgmId: String, bgmETag: String)
}
