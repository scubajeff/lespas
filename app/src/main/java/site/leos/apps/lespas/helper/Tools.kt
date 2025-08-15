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

package site.leos.apps.lespas.helper

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.exifinterface.media.ExifInterface
import androidx.preference.PreferenceManager
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textview.MaterialTextView
import org.json.JSONException
import org.json.JSONObject
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.Album
import site.leos.apps.lespas.gallery.GalleryFragment
import site.leos.apps.lespas.gpx.TimezoneMapper
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.photo.PhotoMeta
import site.leos.apps.lespas.photo.SnapshotFile
import site.leos.apps.lespas.publication.NCShareViewModel
import site.leos.apps.lespas.settings.SettingsFragment
import site.leos.apps.lespas.sync.SyncAdapter
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.text.CharacterIterator
import java.text.Collator
import java.text.DecimalFormat
import java.text.StringCharacterIterator
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

object Tools {
    private val COMMON_FORMAT = arrayOf("jpeg", "png", "webp", "heif", "heic", "panorama")
    val RAW_FORMAT = arrayOf("x-dcraw", "x-sony-arw", "x-sony-sr2", "x-sony-srf", "x-adobe-dng", "x-fuji-raf", "x-canon-cr2", "x-canon-crw", "x-nikon-nef", "x-olympus-orf", "x-panasonic-raw", "x-pentax-pef", "x-sigma-x3f", "x-kodak-dcr", "x-kodak-k25", "x-kodak-kdc", "x-minolta-mrw")
    private val FORMATS_WITH_EXIF = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) COMMON_FORMAT + arrayOf("avif") + RAW_FORMAT else COMMON_FORMAT + RAW_FORMAT
    val SUPPORTED_PICTURE_FORMATS = arrayOf("gif", "bmp") + FORMATS_WITH_EXIF
    const val PANORAMA_SIGNATURE = "GPano:UsePanoramaViewer=\"True\""
    const val MOTION_PHOTO_SIGNATURE = "GCamera:MotionPhoto=\"1\""
    const val PANORAMA_MIMETYPE = "image/panorama"
    const val PHOTO_SPHERE_MIMETYPE = "application/vnd.google.panorama360+jpg"

    @SuppressLint("RestrictedApi")
    fun getPhotoParams(
        metadataRetriever: MediaMetadataRetriever?, exifInterface: ExifInterface?,
        localPath: String, mimeType: String, fileName: String,
        keepOriginalOrientation: Boolean = false,
        uri: Uri? = null, cr: ContentResolver? = null,
    ): Photo {
        var mMimeType = mimeType
        var width = 0
        var height = 0
        var latlong: DoubleArray = doubleArrayOf(Photo.NO_GPS_DATA, Photo.NO_GPS_DATA)
        var altitude = Photo.NO_GPS_DATA
        var bearing = Photo.NO_GPS_DATA
        var caption = ""
        var orientation = 0
        val isLocalFileExist = localPath.isNotEmpty()
        var dateTaken: LocalDateTime = LocalDateTime.now()
        val lastModified = LocalDateTime.ofInstant(Instant.ofEpochMilli(if (isLocalFileExist) File(localPath).lastModified() else System.currentTimeMillis()), ZoneId.systemDefault())
        var isMotionPhoto = false
        //val lastModified = LocalDateTime.ofInstant(Instant.ofEpochMilli(if (isLocalFileExist) File(localPath).lastModified() else System.currentTimeMillis()), ZoneId.of("Z"))

        if (mimeType.startsWith("video/", true)) {
            metadataRetriever?.run {
                getVideoDateAndLocation(this, fileName).let {
                    dateTaken = it.first ?: lastModified
                    latlong = it.second
                }

                extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.let { rotate ->
                    orientation = rotate.toInt()

                    if (rotate == "90" || rotate == "270") {
                        extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.let { height = it.toInt() }
                        extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.let { width = it.toInt() }
                    } else {
                        extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.let { width = it.toInt() }
                        extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.let { height = it.toInt() }
                    }
                }
            }
        } else {
            // Get default date taken value
            dateTaken = parseDateFromFileName(fileName) ?: lastModified

            when(val imageFormat = mimeType.substringAfter("image/", "")) {
                in FORMATS_WITH_EXIF-> {
                    // Try extracting photo's capture date from EXIF, try rotating the photo if EXIF tell us to, save EXIF if we rotated the photo
                    var saveExif = false

                    exifInterface?.let { exif->
                        exif.getAttribute(ExifInterface.TAG_USER_COMMENT)?.let { caption = it }
                        // TODO OMD put an advertising text in ExifInterface.TAG_IMAGE_DESCRIPTION
                        //if (caption.isBlank()) exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION)?.let { caption = it }

                        // GPS data
                        exif.latLong?.let { latlong = it }
                        altitude = exif.getAltitude(Photo.NO_GPS_DATA)
                        bearing = getBearing(exif)

                        // Taken date
                        getImageTakenDate(exif)?.let { dateTaken = it }
/*
                        if (updateCreationDate) {
                            exif.setDateTime(dateTaken.toInstant(OffsetTime.now().offset).toEpochMilli())
                            saveExif = true
                        }
*/

                        width = exifInterface.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
                        height = exifInterface.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)

                        orientation = exif.rotationDegrees
                        if (orientation != 0 && !keepOriginalOrientation) {
                            if (isLocalFileExist) {
                                // Either by acquiring file from local or downloading media file from server for Local album, must rotate file
                                try {
                                    // TODO what if rotation fails?
                                    BitmapFactory.decodeFile(localPath)?.let {
                                        Bitmap.createBitmap(it, 0, 0, it.width, it.height, Matrix().apply { preRotate(orientation.toFloat()) }, true).apply {
                                            if (compress(Bitmap.CompressFormat.JPEG, 95, File(localPath).outputStream())) {
                                                mMimeType = Photo.DEFAULT_MIMETYPE

                                                // Swap width and height value, write back to exif and save in Room (see return value at the bottom)
                                                if (orientation == 90 || orientation == 270) {
                                                    val t = width
                                                    width = height
                                                    height = t
                                                }
                                                exif.resetOrientation()
                                                exif.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, "$width")
                                                exif.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, "$height")
                                                saveExif = true
                                            }
                                            recycle()
                                        }
                                    }
                                } catch (_: Exception) {}
                            } else {
                                // Swap width and height value if needed and save it to Room
                                if (orientation == 90 || orientation == 270) {
                                    val t = width
                                    width = height
                                    height = t
                                }
                            }
                        }

                        exif.getAttribute(ExifInterface.TAG_XMP)?.let {
                            if (it.contains(PANORAMA_SIGNATURE)) mMimeType = PANORAMA_MIMETYPE
                            if (it.contains(MOTION_PHOTO_SIGNATURE)) isMotionPhoto = true
                        }

                        if (saveExif) {
                            try { exif.saveAttributes() }
                            catch (e: Exception) {
                                // TODO: better way to handle this
                                Log.e("****Exception", e.stackTraceToString())
                            }
                        }
                    }

                    if (imageFormat == "webp") {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            try {
                                // Set my own image/awebp mimetype for animated WebP
                                if (isLocalFileExist) if (ImageDecoder.decodeDrawable(ImageDecoder.createSource(File(localPath))) is AnimatedImageDrawable) mMimeType = "image/awebp"
                                else uri?.let { if (ImageDecoder.decodeDrawable(ImageDecoder.createSource(cr!!, it)) is AnimatedImageDrawable) mMimeType = "image/awebp" }
                            } catch (_: Exception) {}
                        }
                    }
                }
                "gif"-> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        // Set my own image/agif mimetype for animated GIF
                        try {
                            if (isLocalFileExist) if (ImageDecoder.decodeDrawable(ImageDecoder.createSource(File(localPath))) is AnimatedImageDrawable) mMimeType = "image/agif"
                            else uri?.let { if (ImageDecoder.decodeDrawable(ImageDecoder.createSource(cr!!, it)) is AnimatedImageDrawable) mMimeType = "image/agif" }
                        } catch (_: Exception) {}
                    }
                }
                else-> {}
            }

            // Get image width and height for local album if they can't fetched from EXIF
            if (width == 0) try {
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                    if (isLocalFileExist) BitmapFactory.decodeFile(localPath, this)
                    else uri?.let { BitmapFactory.decodeStream(cr!!.openInputStream(it), null, this) }
                }
                width = options.outWidth
                height = options.outHeight
            } catch (_: Exception) {}
        }

        return Photo(
            mimeType = mMimeType,
            dateTaken = dateTaken, lastModified = lastModified,
            width = width, height = height,
            caption = caption,
            latitude = latlong[0], longitude = latlong[1], altitude = altitude, bearing = bearing,
            orientation = if (keepOriginalOrientation) orientation else 0,
            shareId = if (isMotionPhoto) Photo.MOTION_PHOTO else Photo.DEFAULT_PHOTO_FLAG
        )
    }

    private const val ISO_6709_PATTERN = "([+-][0-9]{2}.[0-9]{4})([+-][0-9]{3}.[0-9]{4})/"
    fun getVideoLocation(extractor: MediaMetadataRetriever): DoubleArray {
        val latLong = doubleArrayOf(Photo.NO_GPS_DATA, Photo.NO_GPS_DATA)
        extractor.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)?.let {
            val matcher = Pattern.compile(ISO_6709_PATTERN).matcher(it)
            if (matcher.matches()) {
                try {
                    latLong[0] = matcher.group(1)?.toDouble() ?: Photo.NO_GPS_DATA
                    latLong[1] = matcher.group(2)?.toDouble() ?: Photo.NO_GPS_DATA
                } catch (_: Exception) {}
            }
        }
        return latLong
    }

    fun getVideoDateAndLocation(extractor: MediaMetadataRetriever, fileName: String): Pair<LocalDateTime?, DoubleArray> {
        val latLong = getVideoLocation(extractor)

        // For video file produced by phone camera, MediaMetadataRetriever.METADATA_KEY_DATE always return date value in UTC since there is no safe way to determine the timezone
        // However file name usually has a pattern of yyyyMMdd_HHmmss which can be parsed to a date adjusted by correct timezone
        var videoDate = parseDateFromFileName(fileName)

        // If date can't be parsed from file name, try get creation date from metadata
        if (videoDate == null) extractor.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)?.let { cDate->
            try {
                videoDate = LocalDateTime.parse(cDate, DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss.SSS'Z'"))
                // If metadata tells a funky date, reset it. extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE) return 1904/01/01 as default if it can't find "creation_time" tag in the video file
                if (videoDate?.year == 1904) videoDate = null
                // Try adjust date according to timezone derived from longitude, although it's not always correct, especially for countries observe only one timezone adjustment, like China
                if (videoDate != null && latLong[1] != Photo.NO_GPS_DATA) videoDate = videoDate.plusHours((latLong[1]/15).toLong())
            } catch (e: Exception) { e.printStackTrace() }
        }
        // Eventually user can always use changing name function to manually adjust media's creation date information
/*
        // If metadata tells a funky date, reset it. extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE) return 1904/01/01 as default if it can't find "creation_time" tag in the video file
        // Could not get creation date from metadata, try guessing from file name
        if (videoDate?.year == 1904 || videoDate == LocalDateTime.MIN) videoDate = parseDateFromFileName(fileName)
*/

        return Pair(videoDate, latLong)
    }

    @SuppressLint("RestrictedApi")
    fun getImageTakenDate(exif: ExifInterface): LocalDateTime? =
        try {
/*
            exif.dateTimeOriginal?.let {
                LocalDateTime.ofInstant(Instant.ofEpochMilli(it), if (applyTZOffset) exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)?.let { offsetZone -> ZoneId.of(offsetZone) } ?: ZoneId.of("UTC") else ZoneId.systemDefault())
            } ?: run {
                exif.dateTimeDigitized?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), if (applyTZOffset) exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED)?.let { offsetZone -> ZoneId.of(offsetZone) } ?: ZoneId.of("UTC") else ZoneId.systemDefault()) }
            }
*/
            // Get offset time, which is in human perspective
            (exif.dateTimeOriginal?.let {
                LocalDateTime.ofInstant(Instant.ofEpochMilli(it), exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)?.let { offsetZone -> ZoneId.of(offsetZone) } ?: ZoneId.of("UTC"))
            } ?: run {
                exif.dateTimeDigitized?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED)?.let { offsetZone -> ZoneId.of(offsetZone) } ?: ZoneId.of("UTC")) }
            } ?: run {
                exif.dateTime?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it), exif.getAttribute(ExifInterface.TAG_OFFSET_TIME)?.let { offsetZone -> ZoneId.of(offsetZone) } ?: ZoneId.of("UTC")) }
            })?.run {
                // Save it as it's, e.g., in UTC timezone
                LocalDateTime.ofInstant(toInstant(ZoneOffset.UTC), ZoneId.of("Z"))
            }
        } catch (_: Exception) { null }

    // Match Wechat export file name, the 13 digits suffix is the export time in epoch millisecond
    private const val PATTERN_WECHAT = "^mmexport([0-9]{13}).*"
    // Match file name of yyyyMMddHHmmss or yyyyMMdd_HHmmss or yyyyMMdd-HHmmss, with optional millisecond
    private const val PATTERN_TIMESTAMP = ".?([12][0-9]{3})[-_]?(0[1-9]|1[0-2])[-_]?(0[1-9]|[12][0-9]|3[01])[-_]?([01][0-9]|2[0-3])[-_]?([0-5][0-9])[-_]?([0-5][0-9])[-_]?([0-9]{3})?.*"
    private const val PATTERN_WHATSAPP = ".*-([12][0-9]{3})(0[1-9]|1[0-2])(0[1-9]|[12][0-9]|3[01])-.*"
    fun parseDateFromFileName(fileName: String): LocalDateTime? {
        return try {
            var matcher = Pattern.compile(PATTERN_WECHAT).matcher(fileName)
            //if (matcher.matches()) matcher.group(1)?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it.toLong()), ZoneId.systemDefault()) }
            if (matcher.matches()) matcher.group(1)?.let { LocalDateTime.ofInstant(Instant.ofEpochMilli(it.toLong()), ZoneId.of("Z")) }
            else {
                matcher = Pattern.compile(PATTERN_TIMESTAMP).matcher(fileName)
                if (matcher.find()) {
                    LocalDateTime.parse(
                        matcher.run {
                            var millisecond = try { group(7) } catch (_: Exception) { "000" }
                            if (millisecond.isNullOrEmpty()) millisecond = "000"
                            "${group(1)}:${group(2)}:${group(3)} ${group(4)}:${group(5)}:${group(6)} $millisecond"
                        },
                        DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss SSS")
                    )
                }
                else {
                    matcher = Pattern.compile(PATTERN_WHATSAPP).matcher(fileName)
                    if (matcher.matches()) LocalDateTime.parse(matcher.run { "${group(1)}:${group(2)}:${group(3)} 00:00:00" }, DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss"))
                    else null
                }
            }
        } catch (_: Exception) { null }
    }

    fun epochToLocalDateTime(epoch: Long, useUTC: Boolean = false): LocalDateTime =
        try {
            // Always display time in current timezone
            LocalDateTime.ofInstant(if (epoch > 9999999999) Instant.ofEpochMilli(epoch) else Instant.ofEpochSecond(epoch), if (useUTC) ZoneId.of("Z") else ZoneId.systemDefault())
        } catch (_: DateTimeException) { LocalDateTime.now() }

    fun isMediaPlayable(mimeType: String): Boolean = (mimeType == "image/agif") || (mimeType == "image/awebp") || (mimeType.startsWith("video/", true))
    fun isMediaAnimated(mimeType: String): Boolean = (mimeType == "image/agif") || (mimeType == "image/awebp")

    fun hasExif(mimeType: String): Boolean = mimeType.substringAfter("image/", "") in FORMATS_WITH_EXIF

    @SuppressLint("DefaultLocale")
    fun humanReadableByteCountSI(size: Long): String {
        var bytes = size
        if (-1000 < bytes && bytes < 1000) return "$bytes B"
        val ci: CharacterIterator = StringCharacterIterator("kMGTPE")
        while (bytes <= -999950 || bytes >= 999950) {
            bytes /= 1000
            ci.next()
        }
        return java.lang.String.format("%s%cB", DecimalFormat("###.#").format(bytes/1000.0), ci.current())
    }

    fun getDeviceModel(): String {
        val manufacturer = Build.MANUFACTURER.lowercase()
        var model = Build.MODEL.lowercase()

        if (model.startsWith(manufacturer)) model = model.substring(manufacturer.length).trim()

        return "${manufacturer}_${model}"
    }

/*
    fun listGalleryImages(cr: ContentResolver): MutableList<Photo> {
        val medias = mutableListOf<Photo>()
        val pathSelection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Files.FileColumns.RELATIVE_PATH else MediaStore.Files.FileColumns.DATA
        val dateSelection = "datetaken"     // MediaStore.MediaColumns.DATE_TAKEN, hardcoded here since it's only available in Android Q or above
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            pathSelection,
            dateSelection,
            MediaStore.Files.FileColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.WIDTH,
            MediaStore.Files.FileColumns.HEIGHT,
            "orientation",                  // MediaStore.Files.FileColumns.ORIENTATION, hardcoded here since it's only available in Android Q or above
        )
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}"

        try {
            cr.query(MediaStore.Files.getContentUri("external"), projection, selection, null, "$dateSelection DESC")?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                //val pathColumn = cursor.getColumnIndexOrThrow(pathSelection)
                val dateColumn = cursor.getColumnIndexOrThrow(dateSelection)
                val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
                val typeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.WIDTH)
                val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.HEIGHT)
                val orientationColumn = cursor.getColumnIndexOrThrow("orientation")    // MediaStore.Files.FileColumns.ORIENTATION, hardcoded here since it's only available in Android Q or above
                val defaultZone = ZoneId.systemDefault()
                var mimeType: String
                var date: Long
                var reSort = false

                cursorLoop@ while (cursor.moveToNext()) {
                    // Insert media
                    mimeType = cursor.getString(typeColumn)
                    // Make sure image type is supported
                    if (mimeType.substringAfter("image/", "") !in SUPPORTED_PICTURE_FORMATS) continue@cursorLoop

                    date = cursor.getLong(dateColumn)
                    if (date == 0L) {
                        // Sometimes dateTaken is not available from system, use dateAdded instead
                        date = cursor.getLong(dateAddedColumn) * 1000
                        reSort = true
                    }
                    medias.add(
                        Photo(
                            id = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getString(idColumn).toLong()).toString(),
                            albumId = GalleryFragment.FROM_DEVICE_GALLERY,
                            name = cursor.getString(nameColumn) ?: "",
                            dateTaken = LocalDateTime.ofInstant(Instant.ofEpochMilli(date), defaultZone),     // DATE_TAKEN has nano adjustment
                            lastModified = LocalDateTime.MIN,
                            width = cursor.getInt(widthColumn),
                            height = cursor.getInt(heightColumn),
                            mimeType = mimeType,
                            shareId = cursor.getInt(sizeColumn),                  // Saving photo size value in shareId property
                            orientation = cursor.getInt(orientationColumn)        // Saving photo orientation value in shareId property, keep original orientation, CameraRollFragment will handle the rotation
                        )
                    )
                }

                // Resort the list if dateAdded used
                if (reSort) medias.sortWith(compareByDescending { it.dateTaken })
            }
        } catch (_: Exception) {}

        return medias
    }
*/

    fun getGalleryAlbum(cr: ContentResolver, albumName: String): Album {
        val externalStorageUri = MediaStore.Files.getContentUri("external")
        var startDate = LocalDateTime.MIN
        var endDate: LocalDateTime
        var coverId: String
        val coverBaseline = 0   // TODO better default baseline
        var coverWidth: Int
        var coverHeight: Int
        var coverFileName: String
        var coverMimeType: String
        var orientation: Int

        //val dateSelection = "datetaken"     // MediaStore.MediaColumns.DATE_TAKEN, hardcoded here since it's only available in Android Q or above
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            //dateSelection,
            MediaStore.Files.FileColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.WIDTH,
            MediaStore.Files.FileColumns.HEIGHT,
            "orientation",                  // MediaStore.Files.FileColumns.ORIENTATION, hardcoded here since it's only available in Android Q or above
        )
        val selection ="(${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}) AND (${MediaStore.Files.FileColumns.WIDTH}!=0)"

        try {
            cr.query(externalStorageUri, projection, selection, null, "${MediaStore.Files.FileColumns.DATE_ADDED} DESC")?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val dateColumn = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_ADDED)
                    val defaultZone = ZoneId.systemDefault()
                    coverMimeType = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE))
                    val externalUri = if (coverMimeType.startsWith("video")) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI

                    // Get album's end date, cover
                    endDate = LocalDateTime.ofInstant(Instant.ofEpochSecond(cursor.getLong(dateColumn)), defaultZone)
                    coverId = ContentUris.withAppendedId(externalUri, cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)).toLong()).toString()
                    coverFileName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME))
                    coverWidth = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.WIDTH))
                    coverHeight = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.HEIGHT))
                    orientation = cursor.getInt(cursor.getColumnIndexOrThrow("orientation"))

                    // Get album's start date
                    if (cursor.moveToLast()) startDate = LocalDateTime.ofInstant(Instant.ofEpochSecond(cursor.getLong(dateColumn)), defaultZone)

                    // Cover's mimetype passed in property eTag, cover's orientation passed in property shareId
                    //return Album(GalleryFragment.FROM_CAMERA_ROLL, albumName, startDate, endDate, coverId, coverBaseline, coverWidth, coverHeight, endDate, Album.BY_DATE_TAKEN_DESC, mimeType, orientation, 1.0F)
                    return Album(
                        id = GalleryFragment.FROM_DEVICE_GALLERY, name = albumName,
                        startDate = startDate, endDate = endDate, lastModified = endDate,
                        cover = coverId, coverFileName = coverFileName, coverBaseline = coverBaseline, coverWidth = coverWidth, coverHeight = coverHeight, coverMimeType = coverMimeType,
                        sortOrder = Album.BY_DATE_TAKEN_DESC,
                        eTag = Album.ETAG_GALLERY_ALBUM,
                        shareId = Album.NULL_ALBUM,
                        coverOrientation = orientation,
                    )
                }
            }
        } catch (_: Exception) {}

        return Album(
            id = GalleryFragment.FROM_DEVICE_GALLERY, name = albumName,
            lastModified = LocalDateTime.now(), startDate = LocalDateTime.now(), endDate = LocalDateTime.now(),
            sortOrder = Album.BY_DATE_TAKEN_DESC, eTag = Album.ETAG_GALLERY_ALBUM, shareId = Album.NULL_ALBUM,
            cover = GalleryFragment.EMPTY_GALLERY_COVER_ID, coverWidth = 192, coverHeight = 108,
        )
    }

    fun getFolderStatistic(cr: ContentResolver, folderName: String): Pair<Int, Long> {
        var itemCount = 0
        var totalSize = 0L

        val externalStorageUri = MediaStore.Files.getContentUri("external")
        val pathSelection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Files.FileColumns.RELATIVE_PATH else MediaStore.Files.FileColumns.DATA
        val projection = arrayOf(
            pathSelection,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.SIZE,
        )
        val path = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) folderName else "${GalleryFragment.STORAGE_EMULATED}_/${folderName}"
        val selection = "(${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}) AND ($pathSelection LIKE '${path}%')"

        try {
            cr.query(externalStorageUri, projection, selection, null, null)?.use { cursor ->
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)

                while(cursor.moveToNext()) totalSize += cursor.getLong(sizeColumn)

                itemCount = cursor.count
            }
        } catch (_: Exception) {}

        return Pair(itemCount, totalSize)
    }

    fun getLocalRoot(context: Context): String {
        return "${if (PreferenceManager.getDefaultSharedPreferences(context).getBoolean(SettingsFragment.KEY_STORAGE_LOCATION, true)) "${context.filesDir}" else "${context.getExternalFilesDirs(null)[1]}"}${context.getString(R.string.local_base)}"
    }

    fun getRemoteHome(context: Context): String = getPathOnServer(context, 1)
    fun getArchiveBase(context: Context): String = getPathOnServer(context, 3)
    fun getDeviceArchiveBase(context: Context): String = "${getArchiveBase(context)}/${getDeviceModel()}"
    private fun getPathOnServer(context: Context, id: Int): String {
        val sp = PreferenceManager.getDefaultSharedPreferences(context)
        return (sp.getString(SettingsFragment.SERVER_HOME_FOLDER, "") ?: "") + when(id) {
            1 -> if (sp.getBoolean(SettingsFragment.NEW_HOME_SETTING, false)) "" else context.getString(R.string.local_base)
            3 -> SyncAdapter.ARCHIVE_BASE
            else -> ""
        }
    }

    fun getStorageSize(context: Context): Long {
        var totalBytes = 0L
        val path = getLocalRoot(context)

        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) { File(path).listFiles()?.forEach { file -> totalBytes += file.length() }}
            else { totalBytes = Files.walk(Paths.get(path)).mapToLong { p -> p.toFile().length() }.sum() }
        } catch (e: Exception) { e.printStackTrace() }

        return totalBytes
    }

    fun getRoundBitmap(context: Context, bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        return createBitmap(width, height).apply {
            Canvas(this).apply {
                clipPath(Path().apply { addCircle((width.toFloat() / 2), (height.toFloat() / 2), min(width.toFloat(), (height.toFloat() / 2)), Path.Direction.CCW) })
                drawPaint(Paint().apply {
                    color = ContextCompat.getColor(context, R.color.color_avatar_default_background)
                    style = Paint.Style.FILL
                })
                drawBitmap(bitmap, 0f, 0f, null)
            }
        }
    }

    fun getSelectedMarkDrawable(context: Activity, scale: Float): Drawable = getScaledDrawable(context, scale, R.drawable.ic_baseline_selected_24)
    fun getPlayMarkDrawable(context: Activity, scale: Float): Drawable = getScaledDrawable(context, scale, R.drawable.ic_baseline_play_mark_24)
    fun getPanoramaMarkDrawable(context: Activity, scale: Float): Drawable = getScaledDrawable(context, scale, R.drawable.ic_baseline_vrpano_24)
    private fun getScaledDrawable(context: Activity, scale: Float, resId: Int): Drawable {
        val size: Int = (scale * getDisplayDimension(context).first).toInt()

        val bmp = createBitmap(size, size)
        ContextCompat.getDrawable(context, resId)?.apply {
            setBounds(0, 0, size, size)
            draw(Canvas(bmp))
        }

        return bmp.toDrawable(context.resources)
    }

    fun setImmersive(window: Window, on: Boolean) {
        if (on) {
            WindowCompat.getInsetsController(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        else {
            WindowCompat.getInsetsController(window, window.decorView).apply {
                show(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            }
        }
    }

    fun getPreparingSharesSnackBar(anchorView: View, cancelAction: View.OnClickListener?): Snackbar {
        val ctx = anchorView.context
        return Snackbar.make(anchorView, R.string.preparing_shares, Snackbar.LENGTH_INDEFINITE).apply {
            try {
                (view.findViewById<MaterialTextView>(com.google.android.material.R.id.snackbar_text).parent as ViewGroup).addView(ProgressBar(ctx).apply {
                    // Android Snackbar text size is 14sp
                    val pbHeight = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics).roundToInt()
                    layoutParams = (LinearLayout.LayoutParams(pbHeight, pbHeight)).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END }
                    indeterminateTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.color_text_light))
                }, 0)
            } catch (_: Exception) {}
            cancelAction?.let { setAction(android.R.string.cancel, it) }
        }
    }

    fun getDisplayDimension(context: Activity): Pair<Int, Int> = getDisplayDimension(context.windowManager)
    fun getDisplayDimension(wm: WindowManager): Pair<Int, Int> {
        return DisplayMetrics().run {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(this)
                Pair(widthPixels, heightPixels)
            } else {
                wm.currentWindowMetrics.bounds.let { return Pair(it.width(), it.height()) }
            }
        }
    }

    fun isRemoteAlbum(album: Album): Boolean = (album.shareId and Album.REMOTE_ALBUM) == Album.REMOTE_ALBUM || album.id == Album.JOINT_ALBUM_ID
    fun isExcludedAlbum(album: Album): Boolean = (album.shareId and Album.EXCLUDED_ALBUM) == Album.EXCLUDED_ALBUM
    fun isWideListAlbum(sortOrder: Int): Boolean = sortOrder in Album.BY_DATE_TAKEN_ASC_WIDE..200

    private const val PI = 3.141592653589793
    private const val EE = 0.006693421622965943
    private const val A = 6378245.0
    fun wGS84ToGCJ02(latLong: DoubleArray): DoubleArray {
        // Out of China
        if (TimezoneMapper.latLngToTimezoneString(latLong[0], latLong[1]) != "Asia/Shanghai") return latLong
        //if (latLong[0] < 0.8293 || latLong[0] > 55.8271) return latLong
        //if (latLong[1] < 72.004 || latLong[1] > 137.8347) return latLong

        var dLat = translateLat(latLong[1] - 105.0, latLong[0] - 35.0)
        var dLong = translateLong(latLong[1] - 105.0, latLong[0] - 35.0)
        val radLat = latLong[0] / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI)
        dLong = (dLong * 180.0) / (A / sqrtMagic * cos(radLat) * PI)

        return doubleArrayOf(latLong[0] + dLat, latLong[1] + dLong)
    }

    private fun translateLat(x: Double, y: Double): Double {
        var lat = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        lat += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        lat += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        lat += (160.0 * sin(y / 12.0 * PI) + 320.0 * sin(y * PI / 30.0)) * 2.0 / 3.0

        return lat
    }

    private fun translateLong(x: Double, y: Double): Double {
        var long = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        long += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        long += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        long += (150.0 * sin( x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0

        return long
    }

    fun readContentMeta(inputStream: InputStream, sharePath: String, sortOrder: Int = Album.BY_DATE_TAKEN_DESC, useUTC: Boolean = false): List<NCShareViewModel.RemotePhoto> {
        val result = mutableListOf<NCShareViewModel.RemotePhoto>()
        var caption: String

        val lespasJson = try {
            JSONObject(inputStream.reader().readText()).getJSONObject("lespas")
        } catch (_: JSONException) { return result }

        val version = try {
            lespasJson.getInt("version")
        } catch (_: JSONException) {
            1
        }

        var mDate: LocalDateTime
        val photos = lespasJson.getJSONArray("photos")
        for (i in 0 until photos.length()) {
            photos.getJSONObject(i).apply {
                mDate = try { epochToLocalDateTime(getLong("stime"), useUTC) } catch (_: DateTimeException) { LocalDateTime.now() }
                when {
                    // TODO make sure later version json file downward compatible
                    version >= 2 -> {
                        try {
                            // Version checking, trigger exception
                            getInt("orientation")

                            // Caption text might not be escaped in previous release, like '\n', catch it here so that the whole process can finish
                            caption = try { getString("caption") } catch (_: JSONException) { "" }

                            getString("mime").let { mimeType ->
                                // TODO Safety check on media mimetype is here to avoid adding wrong file into album due to a bug introduced when adding AVIF format support in version 2.9.16
                                if (mimeType.startsWith("image/", true) || mimeType.startsWith("video/", true)) {
                                    result.add(
                                        NCShareViewModel.RemotePhoto(
                                            Photo(
                                                id = getString("id"), name = getString("name"), mimeType = mimeType, width = getInt("width"), height = getInt("height"), lastModified = LocalDateTime.MIN, dateTaken = mDate,
                                                // Version 2 additions
                                                orientation = getInt("orientation"), caption = jsonStringToStringEscape(caption), latitude = getDouble("latitude"), longitude = getDouble("longitude"), altitude = getDouble("altitude"), bearing = getDouble("bearing"),
                                                // Should set eTag to value not as Photo.ETAG_NOT_YET_UPLOADED
                                                eTag = Photo.ETAG_FAKE
                                            ), sharePath
                                        )
                                    )
                                }
                            }
                        } catch (_: JSONException) {
                            try {
                                result.add(
                                    NCShareViewModel.RemotePhoto(
                                        Photo(
                                            id = getString("id"), name = getString("name"), mimeType = getString("mime"), width = getInt("width"), height = getInt("height"), lastModified = LocalDateTime.MIN, dateTaken = mDate,
                                            // Should set eTag to value not as Photo.ETAG_NOT_YET_UPLOADED
                                            eTag = Photo.ETAG_FAKE
                                        ), sharePath
                                    )
                                )
                            } catch (_: JSONException) {}
                        }
                    }
                    // Version 1 of content meta json
                    else -> {
                        try {
                            result.add(
                                NCShareViewModel.RemotePhoto(
                                    Photo(
                                        id = getString("id"), name = getString("name"), mimeType = getString("mime"), width = getInt("width"), height = getInt("height"), lastModified = LocalDateTime.MIN, dateTaken = mDate,
                                        // Should set eTag to value not as Photo.ETAG_NOT_YET_UPLOADED
                                        eTag = Photo.ETAG_FAKE
                                    ), sharePath
                                )
                            )
                        } catch (_: JSONException) {}
                    }
                }
            }
        }
        when (sortOrder % 100) {
            Album.BY_DATE_TAKEN_ASC -> result.sortWith(compareBy { it.photo.dateTaken })
            Album.BY_DATE_TAKEN_DESC -> result.sortWith(compareByDescending { it.photo.dateTaken })
            Album.BY_NAME_ASC -> result.sortWith(compareBy(Collator.getInstance().apply { strength = Collator.PRIMARY }) { it.photo.name })
            Album.BY_NAME_DESC -> result.sortWith(compareByDescending(Collator.getInstance().apply { strength = Collator.PRIMARY }) { it.photo.name })
        }

        return result
    }

/*
    fun photosToMetaJSONString(photos: List<Photo>): String {
        var content = SyncAdapter.PHOTO_META_HEADER

        photos.forEach { photo ->
            with(photo) {
                //content += String.format(Locale.ROOT, SyncAdapter.PHOTO_META_JSON_V2, id, name, dateTaken.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), mimeType, width, height, orientation, caption.replace("\"", "\\\""), latitude, longitude, altitude, bearing)
                content += String.format(Locale.ROOT, SyncAdapter.PHOTO_META_JSON_V2, id, name, dateTaken.atZone(ZoneId.of("Z")).toInstant().toEpochMilli(), mimeType, width, height, orientation, caption.replace("\"", "\\\""), latitude, longitude, altitude, bearing)
            }
        }

        return content.dropLast(1) + "]}}"
    }
*/

    fun remotePhotosToMetaJSONString(remotePhotos: List<NCShareViewModel.RemotePhoto>): String {
        var content = SyncAdapter.PHOTO_META_HEADER

        remotePhotos.forEach {
            //content += String.format(PHOTO_META_JSON, it.fileId, it.path.substringAfterLast('/'), it.timestamp, it.mimeType, it.width, it.height)
            with(it.photo) {
                //content += String.format(Locale.ROOT, SyncAdapter.PHOTO_META_JSON_V2, id, name, dateTaken.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), mimeType, width, height, orientation, caption.replace("\"", "\\\""), latitude, longitude, altitude, bearing)
                content += String.format(Locale.ROOT, SyncAdapter.PHOTO_META_JSON_V2, id, name, dateTaken.toInstant(ZoneOffset.UTC).toEpochMilli(), mimeType, width, height, orientation, stringTOJSONStringEscape(caption), latitude, longitude, altitude, bearing)
            }
        }

        return content.dropLast(1) + "]}}"
    }

    fun stringTOJSONStringEscape(source: String): String = source.replace("\"", "\\\"").replace("\n", "\\n")
    fun jsonStringToStringEscape(source: String): String = source.replace("\\\"", "\"").replace("\\n", "\n")
    fun metasToJSONString(photoMeta: List<PhotoMeta>): String {
        var content = SyncAdapter.PHOTO_META_HEADER

        photoMeta.forEach {
            //content += String.format(PHOTO_META_JSON, it.id, it.name, it.dateTaken.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), it.mimeType, it.width, it.height)
            //content += String.format(Locale.ROOT, SyncAdapter.PHOTO_META_JSON_V2, it.id, it.name, it.dateTaken.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), it.mimeType, it.width, it.height, it.orientation, it.caption.replace("\"", "\\\""), it.latitude, it.longitude, it.altitude, it.bearing)
            content += String.format(Locale.ROOT, SyncAdapter.PHOTO_META_JSON_V2, it.id, it.name, it.dateTaken.toInstant(ZoneOffset.UTC).toEpochMilli(), it.mimeType, it.width, it.height, it.orientation, stringTOJSONStringEscape(it.caption), it.latitude, it.longitude, it.altitude, it.bearing)
        }

        return content.dropLast(1) + "]}}"
    }

    fun archiveToJSONString(archiveList: List<GalleryFragment.GalleryMedia>): String {
        val defaultOffset = OffsetDateTime.now().offset
        var content = "{\"archive\":{\"version\":1,\"photos\":["
        archiveList.forEach { localMedia ->
            with(localMedia.media.photo) {
                content += String.format(
                    Locale.ROOT,
                    "{\"id\":\"%s\",\"name\":\"%s\",\"dateTaken\":%d,\"lastModified\":%d,\"mime\":\"%s\",\"width\":%d,\"height\":%d,\"orientation\":%d,\"size\":%d,\"latitude\":%.5f,\"longitude\":%.5f,\"altitude\":%.5f,\"bearing\":%.5f,\"volume\":\"%s\",\"fullPath\":\"%s\"},",
                    id, name,
                    // When retrieving date from device Gallery in GalleryFragment's asGallery(), local default timezone was used, have to use it here too so that the result timestamp is the same as retrieved from photo's EXIF
                    dateTaken.toInstant(defaultOffset).toEpochMilli(), lastModified.toInstant(defaultOffset).toEpochMilli(),
                    mimeType, width, height, orientation, caption.toLong(), latitude, longitude, altitude, bearing,
                    localMedia.volume, localMedia.fullPath,
                )
            }
        }

        return (if (archiveList.isEmpty()) content else content.dropLast(1)) + "]}}"
    }

    fun jsonToArchiveList(snapshotFile: File, archiveBase: String): List<GalleryFragment.GalleryMedia> {
        val result = mutableListOf<GalleryFragment.GalleryMedia>()
        val defaultZone = ZoneId.systemDefault()
        var volume: String
        var fullPath: String

        try {
            jacksonObjectMapper().readValue<SnapshotFile>(snapshotFile).archive.photos.forEach { photo ->
                volume = photo.volume
                fullPath = photo.fullPath.dropLast(1)

                result.add(
                    GalleryFragment.GalleryMedia(
                        GalleryFragment.GalleryMedia.IS_REMOTE,
                        if (fullPath.isEmpty()) volume else fullPath.substringBefore('/'),
                        NCShareViewModel.RemotePhoto(
                            photo = Photo(
                                id = photo.id,
                                albumId = "",
                                name = photo.name,
                                eTag = Photo.ETAG_ARCHIVE,
                                mimeType = photo.mime,
                                dateTaken = LocalDateTime.ofInstant(Instant.ofEpochMilli(photo.dateTaken), defaultZone),
                                lastModified = LocalDateTime.ofInstant(Instant.ofEpochMilli(photo.lastModified), defaultZone),
                                width = photo.width,
                                height = photo.height,
                                orientation = photo.orientation,
                                caption = photo.size.toString(),
                                latitude = photo.latitude,
                                longitude = photo.longitude,
                                altitude = photo.altitude,
                                bearing = photo.bearing,
                            ),
                            remotePath = "${archiveBase}/${volume}/${fullPath}"
                        ),
                        volume = volume,
                        fullPath = "${fullPath}/",
                        appName = if (fullPath.isEmpty()) volume else fullPath.substringAfterLast('/'),
                        remoteFileId = photo.id,
                    )
                )
            }
        } catch (e: Exception) { e.printStackTrace() }

        return result
    }

    fun getPhotosWithCoordinate(list: List<Photo>, autoConvergent: Boolean, albumSortOrder: Int): List<Photo> {
        val result = mutableListOf<Photo>()

        mutableListOf<Photo>().run {
            val photos = (if (albumSortOrder % 100 == Album.BY_DATE_TAKEN_ASC) list else list.sortedWith(compareBy { it.dateTaken })).filter { !isMediaPlayable(it.mimeType) }

            photos.forEach { if (it.mimeType.startsWith("image/") && it.latitude != Photo.NO_GPS_DATA) add(it) }

            if (autoConvergent) {
                add(0, Photo(dateTaken = LocalDateTime.MIN, lastModified = LocalDateTime.MIN))
                add(Photo(dateTaken = LocalDateTime.MAX, lastModified = LocalDateTime.MAX))

                var start: Photo = get(0)
                var end: Photo = get(1)
                var index = 1

                val offset = OffsetDateTime.now().offset
                val maximum = 20 * 60   // 20 minutes maximum
                var secondsToStart: Long
                var secondsToEnd: Long
                var startEpochSecond = start.dateTaken.toEpochSecond(offset)
                var endEpochSecond = end.dateTaken.toEpochSecond(offset)

                photos.forEach { photo ->
                    when(photo.id) {
                        start.id -> {}
                        end.id -> {
                            result.add(end)
                            start = end
                            end = get(++index)
                            startEpochSecond = endEpochSecond
                            endEpochSecond = end.dateTaken.toEpochSecond(offset)
                        }
                        else -> {
                            photo.dateTaken.toEpochSecond(offset).run {
                                secondsToStart = abs(this - startEpochSecond)
                                secondsToEnd = abs(endEpochSecond - this)
                            }
                            when {
                                (secondsToStart < maximum) && (secondsToEnd > maximum) ->
                                    result.add(photo.copy(latitude = start.latitude, longitude = start.longitude, altitude = start.altitude, bearing = start.bearing).apply {
                                        start = this
                                        startEpochSecond = start.dateTaken.toEpochSecond(offset)
                                    })
                                (secondsToStart > maximum) && (secondsToEnd < maximum) -> result.add(photo.copy(latitude = end.latitude, longitude = end.longitude, altitude = end.altitude, bearing = end.bearing))
                                (secondsToStart < maximum) && (secondsToEnd < maximum) -> result.add(
                                    if (secondsToStart < secondsToEnd) photo.copy(latitude = start.latitude, longitude = start.longitude, altitude = start.altitude, bearing = start.bearing).apply {
                                        start = this
                                        startEpochSecond = start.dateTaken.toEpochSecond(offset)
                                    }
                                    else photo.copy(latitude = end.latitude, longitude = end.longitude, altitude = end.altitude, bearing = end.bearing)
                                )
                                else -> {}
                            }
                        }
                    }
                }
            } else result.addAll(this)
        }

        return sortPhotos(result, albumSortOrder)
    }

/*
    fun getAttributeResourceId(context: Context, attrId: Int): Int {
        TypedValue().let {
            context.theme.resolveAttribute(attrId, it, true)
            return it.resourceId
        }
    }
*/

    fun getAttributeColor(context: Context, attrId: Int): Int {
        TypedValue().let {
            context.theme.resolveAttribute(attrId, it, true)
            return ContextCompat.getColor(context, it.resourceId)
        }
    }

    fun applyTheme(context: AppCompatActivity, normalThemeId: Int, trueBlackThemeId: Int) {
        val sp = PreferenceManager.getDefaultSharedPreferences(context)
        sp.getString(context.getString(R.string.auto_theme_perf_key), context.getString(R.string.theme_auto_values))?.let { AppCompatDelegate.setDefaultNightMode(it.toInt()) }
        if (sp.getBoolean(context.getString(R.string.true_black_pref_key), false) && (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            context.setTheme(trueBlackThemeId)
        } else context.setTheme(normalThemeId)
    }

    fun getBearing(exif: ExifInterface): Double {
        var bearing = Photo.NO_GPS_DATA
        exif.getAttribute(ExifInterface.TAG_GPS_DEST_BEARING)?.let { try { bearing = it.toDouble() } catch (_: NumberFormatException) {} }
        if (bearing == Photo.NO_GPS_DATA) exif.getAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION)?.let { try { bearing = it.toDouble() } catch (_: NumberFormatException) {} }
        return bearing
    }

    fun getStoragePermissionsArray(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        else -> arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    fun shouldRequestStoragePermission(context: Context): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        else -> ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
    }

    fun collectBlogResult(body: String?): List<NCShareViewModel.Blog>? {
        val blogs = mutableListOf<NCShareViewModel.Blog>()

        try {
            body?.let {
                val sites = JSONObject(it).getJSONArray("websites")
                for (i in 0 until sites.length()) {
                    sites.getJSONObject(i).run {
                        getString("path").let { pathName ->
                            if (pathName.contains(SyncAdapter.BLOG_FOLDER)) blogs.add(NCShareViewModel.Blog(getString("id"), getString("name"), getString("site"), getString("theme"), getInt("type"), pathName, getLong("creation")))
                        }
                    }
                }
            }
        } catch (_: Exception) { return null }

        return blogs
    }

    // Construct blog site name by using user's login name, site name only allow these characters: 'a-z', '0-9', '-', and '_', NC's login name allow '*', '.', uppercase letters and space too
    fun getBlogSiteName(loginName: String): String = loginName.substringBefore('@').lowercase().replace('.', '_').replace(' ', '_')

    fun clearBit(number: Int, bit: Int): Int = number and bit.inv()
    fun setBit(number: Int, bit: Int): Int = number or bit
    fun isBitSet(number: Int, bit: Int): Boolean = number and bit != 0

    fun sortPhotos(photos: List<Photo>, order: Int): List<Photo> =
        when (order % 100) {
            Album.BY_DATE_TAKEN_ASC -> photos.sortedWith(compareBy { it.dateTaken })
            Album.BY_DATE_TAKEN_DESC -> photos.sortedWith(compareByDescending { it.dateTaken })
            Album.BY_NAME_ASC -> photos.sortedWith(compareBy(Collator.getInstance().apply { strength = Collator.PRIMARY }) { it.name })
            Album.BY_NAME_DESC -> photos.sortedWith(compareByDescending(Collator.getInstance().apply { strength = Collator.PRIMARY }) { it.name })
            else -> photos
        }

    fun isPhotoFromGallery(rPhoto: NCShareViewModel.RemotePhoto) = rPhoto.photo.albumId == GalleryFragment.FROM_DEVICE_GALLERY
    fun isPhotoFromGallery(photo: Photo) = photo.albumId == GalleryFragment.FROM_DEVICE_GALLERY
    fun isPhotoFromArchive(rPhoto: NCShareViewModel.RemotePhoto) = rPhoto.photo.albumId == GalleryFragment.FROM_ARCHIVE
    fun isMotionPhoto(flag: Int) = isBitSet(flag, Photo.MOTION_PHOTO)

    inline fun <reified T : Parcelable> Bundle.parcelable(key: String): T? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> getParcelable(key, T::class.java)
        else -> @Suppress("DEPRECATION") getParcelable(key) as? T
    }
    inline fun <reified T : Parcelable> Bundle.parcelableArrayList(key: String): ArrayList<T>? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> getParcelableArrayList(key, T::class.java)
        else -> @Suppress("DEPRECATION") getParcelableArrayList(key)
    }
    @Suppress("UNCHECKED_CAST")
    inline fun <reified T : Parcelable> Bundle.parcelableArray(key: String): Array<T>? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> getParcelableArray(key, T::class.java)
        else -> @Suppress("DEPRECATION") (getParcelableArray(key) as Array<T>?)
    }
    inline fun <reified T : Parcelable> Intent.parcelable(key: String): T? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> getParcelableExtra(key, T::class.java)
        else -> @Suppress("DEPRECATION") getParcelableExtra(key) as? T
    }
    inline fun <reified T : Parcelable> Intent.parcelableArrayList(key: String): ArrayList<T>? = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> getParcelableArrayListExtra(key, T::class.java)
        else -> @Suppress("DEPRECATION") getParcelableArrayListExtra(key)
    }
}