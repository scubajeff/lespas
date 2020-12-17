package site.leos.apps.lespas.helper

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.drawable.AnimatedImageDrawable
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import site.leos.apps.lespas.photo.Photo
import java.io.File
import java.text.CharacterIterator
import java.text.ParseException
import java.text.SimpleDateFormat
import java.text.StringCharacterIterator
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.regex.Pattern

object Tools {
    @SuppressLint("SimpleDateFormat")
    fun getPhotoParams(pathName: String, mimeType: String, fileName: String): Photo {
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
                    val exif = ExifInterface(pathName)
                    timeString = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    if (timeString == null) timeString = exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED)
                    if (timeString == null) timeString = exif.getAttribute(ExifInterface.TAG_DATETIME)
                    if (timeString == null) {
                        // Could not get creation date from exif, try guessing from file name
                        timeString = if (wechatPattern.matcher(fileName).matches()) {
                            (LocalDateTime.ofEpochSecond((fileName.substring(8, 18)).toLong(), 0, OffsetDateTime.now().offset))
                                .format(DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss"))
                        } else dateFormatter.format(lastModified)
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
                        try {
                            exif.saveAttributes()
                        } catch (e: Exception) {
                            // TODO: If EXIF.saveAttributes throw exception, it's OK, we don't need these updated data which already saved in our table
                            Log.e("****Exception", e.stackTraceToString())
                        }
                    }
                }
                "image/gif"-> {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        // Set my own image/agif mimetype for animated GIF
                        if (ImageDecoder.decodeDrawable(ImageDecoder.createSource(File(pathName))) is AnimatedImageDrawable) mMimeType = "image/agif"
                    }
                }
                "image/webp"-> {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
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
            tDate = LocalDateTime.parse(timeString, DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss"))
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
}