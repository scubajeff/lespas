/*
 *   Copyright 2019 Jeffrey Liu (scubajeffrey@protonmail.ch)
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

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
    @PrimaryKey var id: String = "",
    var albumId: String = "",
    var name: String = "",
    var eTag: String = ETAG_NOT_YET_UPLOADED,
    var dateTaken: LocalDateTime,
    var lastModified: LocalDateTime,
    var width: Int = 0,
    var height: Int = 0,
    var mimeType: String = DEFAULT_MIMETYPE,
    var shareId: Int = DEFAULT_PHOTO_FLAG,
    var orientation: Int = 0,
    var caption: String = "",
    var latitude: Double = NO_GPS_DATA,
    var longitude: Double = NO_GPS_DATA,
    var altitude: Double = NO_GPS_DATA,
    var bearing: Double = NO_GPS_DATA,
    var locality: String = NO_ADDRESS,
    var country: String = NO_ADDRESS,
    var countryCode: String = NO_ADDRESS,
    var classificationId: String = NO_CLASSIFICATION,
): Parcelable {
    companion object {
        const val TABLE_NAME = "photos"

        const val ETAG_NOT_YET_UPLOADED = ""
        const val ETAG_FAKE = "1"

        // shareId property bits
        const val DEFAULT_PHOTO_FLAG = 0
        const val NOT_YET_UPLOADED = 1 shl 0    // New photo created at local device, not yet sync, means there is no copy or wrong version on server and other devices
        const val NEED_REFRESH = 1 shl 1        // Need to refresh photo's preview from server
        const val EXCLUDE_FROM_BLOG = 1 shl 2   // Exclude from blog post

        const val NO_GPS_DATA = -1000.0         // Photo does not contain GPS data
        const val GPS_DATA_UNKNOWN = -10000.0   // Use in processing camera roll server archive, GPS data not yet available, need extracting from EXIF
        const val NO_ADDRESS = ""
        const val NO_CLASSIFICATION = ""
        const val DEFAULT_MIMETYPE = "image/jpeg"
    }
}

data class PhotoETag(val id: String, val eTag: String)
data class PhotoName(val id: String, val name: String)
data class AlbumPhotoName(val albumId: String, val name: String)
data class PhotoMeta(val id: String, val name: String, val dateTaken: LocalDateTime, val mimeType: String, val width: Int, val height: Int, val orientation: Int, val caption: String, val latitude: Double, val longitude: Double, val altitude: Double, val bearing: Double)
data class MuzeiPhoto(val id: String, val name: String, val albumId: String, val dateTaken: LocalDateTime, val width: Int, val height: Int, val orientation: Int, val eTag: String, val locality: String)
// Photo extras which don't go with the physical image file like EXIF
data class PhotoExtras(val id: String, val caption: String, val locality: String, val country: String, val countryCode: String, val classificationId: String)
@Parcelize
data class PhotoWithCoordinate(
    val photo: Photo,
    val lat: Double,
    val long: Double,
): Parcelable

data class PhotoCaption(val id: String, val caption: String, val shareId: Int): java.io.Serializable

@Dao
abstract class PhotoDao: BaseDao<Photo>() {
    @Query("DELETE FROM ${Photo.TABLE_NAME} WHERE id = :photoId")
    abstract fun deleteById(photoId: String): Int

    // Including photos from hidden albums
    @Query("SELECT id, eTag FROM ${Photo.TABLE_NAME} WHERE albumId = :albumId ORDER BY dateTaken ASC")
    abstract fun getETagsMap(albumId: String): List<PhotoETag>

    // Including photos from hidden albums
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

    @Query("UPDATE ${Photo.TABLE_NAME} SET id = :newId, eTag = :eTag, shareId = shareId & ~${Photo.NOT_YET_UPLOADED} WHERE id = :oldId")
    abstract fun fixPhotoIdEtag(oldId: String, newId: String, eTag: String)

    @Query("UPDATE ${Photo.TABLE_NAME} SET id = :newId, eTag = :eTag, shareId = ((shareId & ~${Photo.NOT_YET_UPLOADED}) | ${Photo.NEED_REFRESH}) WHERE id = :oldId")
    abstract fun fixPhotoIdEtagRefresh(oldId: String, newId: String, eTag: String)

    @Query("UPDATE ${Photo.TABLE_NAME} SET shareId = shareId & ~${Photo.NEED_REFRESH} WHERE id = :photoId")
    abstract fun resetNetworkRefresh(photoId: String)

    // Including photos from hidden albums
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

    // Including photos from hidden albums
    @Query("SELECT * FROM ${Photo.TABLE_NAME} WHERE (mimeType LIKE '%image/%')  ORDER BY dateTaken DESC")
    abstract fun getAllImage(): List<Photo>

    // Including photos from hidden albums
    @Query("SELECT COUNT(*) FROM ${Photo.TABLE_NAME}")
    abstract fun getPhotoTotal(): Int

    @Query("SELECT eTag FROM ${Photo.TABLE_NAME} WHERE id = :photoId")
    abstract fun getETag(photoId: String): String

    @Query("SELECT id, name, dateTaken, mimeType, width, height, orientation, caption, latitude, longitude, altitude, bearing FROM ${Photo.TABLE_NAME} WHERE albumId = :albumId AND eTag != '${Photo.ETAG_NOT_YET_UPLOADED}'")
    abstract fun getPhotoMetaInAlbum(albumId: String): List<PhotoMeta>

/*
    @Query("SELECT id, name, albumId, dateTaken, width, height, orientation, eTag FROM ${Photo.TABLE_NAME} WHERE (CASE WHEN :portraitMode THEN width < height ELSE width > height END) AND mimeType IN ('image/jpeg', 'image/png', 'image/bmp', 'image/gif', 'image/webp', 'image/heic', 'image/heif') AND albumId NOT IN ( :exclusion ) AND eTag != '${Photo.ETAG_NOT_YET_UPLOADED}'")
    abstract fun getMuzeiArtwork(exclusion: List<String>, portraitMode: Boolean): List<MuzeiPhoto>
*/
    @Query("SELECT id, name, albumId, dateTaken, width, height, orientation, eTag, locality FROM ${Photo.TABLE_NAME} WHERE mimeType IN ('image/jpeg', 'image/png', 'image/bmp', 'image/gif', 'image/webp', 'image/heic', 'image/heif') AND albumId NOT IN ( :exclusion ) AND eTag != '${Photo.ETAG_NOT_YET_UPLOADED}'")
    abstract fun getMuzeiArtwork(exclusion: List<String>): List<MuzeiPhoto>

    @Query("SELECT dateTaken FROM ${Photo.TABLE_NAME} WHERE albumId = :albumId ORDER BY dateTaken ASC")
    abstract fun getAlbumDuration(albumId: String): List<LocalDateTime>

    // Clear photo's eTag (e.g. eTag miss-match with server) will trigger file download for photo in local album
    @Query("UPDATE ${Photo.TABLE_NAME} SET eTag = '${Photo.ETAG_NOT_YET_UPLOADED}' WHERE albumId IN (:albumIds)")
    abstract fun setAsLocal(albumIds: List<String>)

    @Query("UPDATE ${Photo.TABLE_NAME} SET locality = :locality, country = :countryName, countryCode = :countryCode WHERE id = :photoId")
    abstract fun updateAddress(photoId: String, locality: String, countryName: String, countryCode: String)

    @Query("UPDATE ${Photo.TABLE_NAME} SET locality = '${Photo.NO_ADDRESS}', country = '${Photo.NO_ADDRESS}', countryCode = '${Photo.NO_ADDRESS}'")
    abstract fun clearLocality()

    @Query("UPDATE ${Photo.TABLE_NAME} SET dateTaken = :dateTaken WHERE id = :photoId")
    abstract fun updateDateTaken(photoId: String, dateTaken: LocalDateTime)

    @Query("UPDATE ${Photo.TABLE_NAME} SET caption = :newCaption WHERE id = :photoId")
    abstract fun updateCaption(photoId: String, newCaption: String)

    @Query("SELECT id, caption, locality, country, countryCode, classificationId FROM ${Photo.TABLE_NAME} WHERE albumId = :albumId")
    abstract fun getPhotoExtras(albumId: String): List<PhotoExtras>

    @Query("UPDATE ${Photo.TABLE_NAME} SET shareId = shareId | ${Photo.EXCLUDE_FROM_BLOG} WHERE id IN (:photoIds)")
    abstract fun setExcludeFromBlog(photoIds: List<String>)

    @Query("UPDATE ${Photo.TABLE_NAME} SET shareId = shareId & ~${Photo.EXCLUDE_FROM_BLOG} WHERE id IN (:photoIds)")
    abstract fun setIncludeInBlog(photoIds: List<String>)

    @Query("SELECT * FROM ${Photo.TABLE_NAME} WHERE id = :photoId")
    abstract fun getThisPhoto(photoId: String): Photo

    @Query("SELECT * FROM ${Photo.TABLE_NAME} WHERE albumId = :albumId AND (shareId & ${Photo.EXCLUDE_FROM_BLOG} = 0)")
    abstract fun getPhotosForBlog(albumId: String): List<Photo>

    @Query("SELECT id, caption, shareId FROM ${Photo.TABLE_NAME} WHERE albumId = :albumId")
    abstract fun getAllCaptionsInAlbum(albumId: String): List<PhotoCaption>

    @Query("UPDATE ${Photo.TABLE_NAME} SET caption = :newCaption, shareId = :exclusionSetting WHERE id = :photoId")
    abstract fun updateCaptionAndBlogSetting(photoId: String, newCaption: String, exclusionSetting: Int)
    @Transaction
    open fun restoreCaptionsInAlbum(captionList: List<PhotoCaption>) { captionList.forEach { updateCaptionAndBlogSetting(it.id, it.caption, it.shareId) }}
}