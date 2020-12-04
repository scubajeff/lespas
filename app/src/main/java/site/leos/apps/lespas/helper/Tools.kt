package site.leos.apps.lespas.helper

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import site.leos.apps.lespas.photo.Photo
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*

object Tools {
    @SuppressLint("SimpleDateFormat")
    fun getPhotoParams(pathName: String): Photo {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        var timeString: String?
        val dateFormatter = SimpleDateFormat("yyyy:MM:dd HH:mm:ss").apply { timeZone = TimeZone.getDefault() }

        // Update dateTaken, width, height fields
        val lastModified = Date(File(pathName).lastModified())
        val exif = ExifInterface(pathName)
        timeString = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
        if (timeString == null) timeString = exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED)
        if (timeString == null) timeString = exif.getAttribute(ExifInterface.TAG_DATETIME)
        if (timeString == null) timeString = dateFormatter.format(lastModified)
        val tDate = LocalDateTime.parse(timeString, DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss"))

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
            exif.saveAttributes()
        }

        // Get width and height
        BitmapFactory.decodeFile(pathName, options)

        return Photo("", "", "", "", tDate, lastModified.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime(), options.outWidth, options.outHeight, 0)
    }
}