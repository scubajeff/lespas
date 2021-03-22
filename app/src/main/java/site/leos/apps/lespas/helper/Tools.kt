package site.leos.apps.lespas.helper

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.drawable.AnimatedImageDrawable
import android.media.MediaMetadataRetriever
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import site.leos.apps.lespas.photo.Photo
import java.io.File
import java.text.CharacterIterator
import java.text.ParseException
import java.text.SimpleDateFormat
import java.text.StringCharacterIterator
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.*
import java.util.regex.Pattern

object Tools {
    fun getPhotoParams(pathName: String, mimeType: String, fileName: String): Photo {
        return getPhotoParams(pathName, mimeType, fileName, false)
    }

    @SuppressLint("SimpleDateFormat")
    fun getPhotoParams(pathName: String, mimeType: String, fileName: String, updateCreationDate: Boolean): Photo {
        val dateFormatter = SimpleDateFormat("yyyy:MM:dd HH:mm:ss").apply { timeZone = TimeZone.getDefault() }
        var timeString: String?
        var mMimeType = mimeType
        var width = 0
        var height = 0
        var tDate = LocalDateTime.MIN
        val wechatPattern = Pattern.compile("^mmexport[0-9]{10}.*")  // matching Wechat export file name, the 13 digits suffix is the export time in epoch long

        // Update dateTaken, width, height fields
        val lastModified = Date(File(pathName).lastModified())
        timeString = dateFormatter.format(lastModified)

        if (mimeType.startsWith("video/", true)) {
            with(MediaMetadataRetriever()) {
                setDataSource(pathName)

                // Try get creation date from metadata
                extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)?.let { cDate->
                    val f = SimpleDateFormat("yyyyMMdd'T'HHmmss.SSS'Z'").apply { timeZone = SimpleTimeZone(0, "UTC") }

                    try {
                        f.parse(cDate)?.let { tDate = dateToLocalDateTime(it) }
                    } catch (e: ParseException) { e.printStackTrace() }
                }

                // If metadata tells a funky date, reset it. Apple platform seems to set the date 1904/01/01 as default
                if (tDate.year == 1904) tDate = LocalDateTime.MIN

                // Could not get creation date from metadata, try guessing from file name
                if (tDate == LocalDateTime.MIN) {
                    if (wechatPattern.matcher(fileName).matches()) {
                        tDate = LocalDateTime.ofEpochSecond((fileName.substring(8, 18)).toLong(), 0, OffsetDateTime.now().offset)
                    }
                }

                // If the above fail, set creation date to the same as last modified date
                if (tDate == LocalDateTime.MIN) tDate = LocalDateTime.parse(timeString, DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss"))

                width = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 0
                height = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 0
                // Swap width and height if rotate 90 or 270 degree
                extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.let {
                    if (it == "90" || it == "270") {
                        height = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 0
                        width = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 0
                    }
                }
            }
        } else {
            when(mimeType) {
                "image/jpeg", "image/tiff"-> {
                    var saveExif = false
                    val exif = ExifInterface(pathName)
                    timeString = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    if (isUnknown(timeString)) timeString = exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED)
                    //if (isUnknown(timeString)) timeString = exif.getAttribute(ExifInterface.TAG_DATETIME)
                    if (isUnknown(timeString)) {
                        // Could not get creation date from exif, try guessing from file name
                        timeString = if (wechatPattern.matcher(fileName).matches()) {
                            (LocalDateTime.ofEpochSecond((fileName.substring(8, 18)).toLong(), 0, OffsetDateTime.now().offset))
                                .format(DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss"))
                        } else dateFormatter.format(lastModified)

                        if (updateCreationDate) {
                            exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, timeString)
                            exif.resetOrientation()
                            saveExif = true
                        }
                    }

                    val exifRotation = exif.rotationDegrees
                    if (exifRotation != 0) {
                        Bitmap.createBitmap(
                            BitmapFactory.decodeFile(pathName),
                            0, 0,
                            exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0),
                            exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0),
                            Matrix().apply { preRotate(exifRotation.toFloat()) },
                            true
                        ).apply {
                            compress(Bitmap.CompressFormat.JPEG, 100, File(pathName).outputStream())
                            recycle()
                        }

                        exif.resetOrientation()
                        val w = exif.getAttribute(ExifInterface.TAG_IMAGE_WIDTH)
                        exif.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, exif.getAttribute(ExifInterface.TAG_IMAGE_LENGTH))
                        exif.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, w)
                        saveExif = true
                    }

                    if (saveExif) {
                        try {
                            exif.saveAttributes()
                        } catch (e: Exception) {
                            // TODO: If EXIF.saveAttributes throw exception
                            Log.e("****Exception", e.stackTraceToString())
                        }
                    }
                }
                "image/gif"-> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        // Set my own image/agif mimetype for animated GIF
                        if (ImageDecoder.decodeDrawable(ImageDecoder.createSource(File(pathName))) is AnimatedImageDrawable) mMimeType = "image/agif"
                    }
                }
                "image/webp"-> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        // Set my own image/awebp mimetype for animated WebP
                        if (ImageDecoder.decodeDrawable(ImageDecoder.createSource(File(pathName))) is AnimatedImageDrawable) mMimeType = "image/awebp"
                    }
                }
                else-> { // TODO
                }
            }

            // Get image width and height
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
                BitmapFactory.decodeFile(pathName, this)
            }
            width = options.outWidth
            height = options.outHeight
            tDate = try {
                LocalDateTime.parse(timeString, DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss"))
            } catch (e: DateTimeParseException) {
                dateToLocalDateTime(lastModified)
            }
        }

        return Photo("", "", "", "", tDate, dateToLocalDateTime(lastModified), width, height, mMimeType, 0)
    }

    fun dateToLocalDateTime(date: Date): LocalDateTime = date.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime()

    fun isMediaPlayable(mimeType: String): Boolean = (mimeType == "image/agif") || (mimeType == "image/awebp") || (mimeType.startsWith("video/", true))

    @SuppressLint("DefaultLocale")
    fun humanReadableByteCountSI(size: Long): String {
        var bytes = size
        if (-1000 < bytes && bytes < 1000) return "$bytes B"
        val ci: CharacterIterator = StringCharacterIterator("kMGTPE")
        while (bytes <= -999950 || bytes >= 999950) {
            bytes /= 1000
            ci.next()
        }
        return java.lang.String.format("%d%cB", bytes/1000, ci.current())
    }

    fun getDeviceModel(): String {
        val manufacturer = Build.MANUFACTURER.toLowerCase(Locale.getDefault())
        var model = Build.MODEL.toLowerCase(Locale.getDefault())

        if (model.startsWith(manufacturer)) model = model.substring(manufacturer.length).trim()

        return "${manufacturer}_${model}"
    }

    fun getCameraRoll(cr: ContentResolver): MutableList<Photo> {
        val medias = mutableListOf<Photo>()
        val externalStorageUri = MediaStore.Files.getContentUri("external")

        @Suppress("DEPRECATION")
        val pathSelection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Files.FileColumns.RELATIVE_PATH else MediaStore.Files.FileColumns.DATA
        val dateSelection = "datetaken"     // MediaStore.MediaColumns.DATE_TAKEN
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            pathSelection,
            dateSelection,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.WIDTH,
            MediaStore.Files.FileColumns.HEIGHT,
            "orientation",  // MediaStore.Files.FileColumns.ORIENTATION,
        )
        val selection =
            "(${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}) AND ($pathSelection LIKE '%DCIM%')"

        cr.query(
            externalStorageUri, projection, selection, null, "$dateSelection DESC"
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndex(MediaStore.Files.FileColumns._ID)
            val nameColumn = cursor.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
            //val pathColumn = cursor.getColumnIndex(pathSelection)
            val dateColumn = cursor.getColumnIndex(dateSelection)
            val typeColumn = cursor.getColumnIndex(MediaStore.Files.FileColumns.MIME_TYPE)
            val sizeColumn = cursor.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
            val widthColumn = cursor.getColumnIndex(MediaStore.Files.FileColumns.WIDTH)
            val heightColumn = cursor.getColumnIndex(MediaStore.Files.FileColumns.HEIGHT)
            val orientationColumn = cursor.getColumnIndex("orientation")    // MediaStore.Files.FileColumns.ORIENTATION
            val defaultZone = ZoneId.systemDefault()

            while (cursor.moveToNext()) {
                // Insert media
                medias.add(
                    Photo(
                        ContentUris.withAppendedId(externalStorageUri, cursor.getString(idColumn).toLong()).toString(),
                        ImageLoaderViewModel.FROM_CAMERA_ROLL,
                        cursor.getString(nameColumn),
                        cursor.getString(sizeColumn),
                        LocalDateTime.ofInstant(Instant.ofEpochMilli(cursor.getLong(dateColumn)), defaultZone),     // DATE_TAKEN has nano adjustment
                        LocalDateTime.MIN,
                        cursor.getInt(widthColumn),
                        cursor.getInt(heightColumn),
                        cursor.getString(typeColumn),
                        cursor.getInt(orientationColumn)
                    )
                )
            }
        }

        return medias
    }

    private fun isUnknown(date: String?): Boolean {
        return (date == null || date.isEmpty() || date == "    :  :     :  :  ")
    }
}