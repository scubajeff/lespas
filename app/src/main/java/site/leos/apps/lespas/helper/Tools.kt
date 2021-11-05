package site.leos.apps.lespas.helper

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.AnimatedImageDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.preference.PreferenceManager
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textview.MaterialTextView
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.Album
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.settings.SettingsFragment
import java.io.File
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Paths
import java.text.*
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.*
import java.util.regex.Pattern
import kotlin.math.min
import kotlin.math.roundToInt

object Tools {
    fun getPhotoParams(pathName: String, mimeType: String, fileName: String): Photo {
        return getPhotoParams(pathName, mimeType, fileName, false)
    }

    @SuppressLint("SimpleDateFormat")
    fun getPhotoParams(pathName: String, mimeType: String, fileName: String, updateCreationDate: Boolean): Photo {
        val dateFormatter = SimpleDateFormat("yyyy:MM:dd HH:mm:ss").apply { timeZone = TimeZone.getDefault() }
        var timeString: String?
        var mMimeType = mimeType
        var width: Int
        var height: Int
        var tDate: LocalDateTime

        // Update dateTaken, width, height fields
        val lastModified = Date(File(pathName).lastModified())
        timeString = dateFormatter.format(lastModified)

        if (mimeType.startsWith("video/", true)) {
            with(MediaMetadataRetriever()) {
                setDataSource(pathName)

                tDate = getVideoFileDate(this, fileName)

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
                "image/jpeg", "image/png"-> {
                    // Try extracting photo's capture date from EXIF, try rotating the photo if EXIF tell us to, save EXIF if we rotated the photo
                    var saveExif = false

                    try { ExifInterface(pathName) }
                    catch (e: Exception) {
                        Log.e("****Exception", e.stackTraceToString())
                        null
                    }?.let { exif->
                        timeString = getImageFileDate(exif, fileName)
                        if (isUnknown(timeString)) {
                            timeString = dateFormatter.format(lastModified)

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
                                compress(Bitmap.CompressFormat.JPEG, 95, File(pathName).outputStream())
                                recycle()
                            }

                            exif.resetOrientation()
                            val w = exif.getAttribute(ExifInterface.TAG_IMAGE_WIDTH)
                            exif.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, exif.getAttribute(ExifInterface.TAG_IMAGE_LENGTH))
                            exif.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, w)
                            saveExif = true
                        }

                        if (saveExif) {
                            try { exif.saveAttributes() }
                            catch (e: Exception) {
                                // TODO: better way to handle this
                                Log.e("****Exception", e.stackTraceToString())
                            }
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
                else-> {}
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

    @SuppressLint("SimpleDateFormat")
    fun getVideoFileDate(extractor: MediaMetadataRetriever, fileName: String): LocalDateTime {
        var videoDate: LocalDateTime = LocalDateTime.MIN

        // Try get creation date from metadata
        extractor.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)?.let { cDate->
            val f = SimpleDateFormat("yyyyMMdd'T'HHmmss.SSS'Z'").apply { timeZone = SimpleTimeZone(0, "UTC") }

            try {
                f.parse(cDate)?.let { videoDate = dateToLocalDateTime(it) }
            } catch (e: ParseException) { e.printStackTrace() }
        }

        // If metadata tells a funky date, reset it. Apple platform seems to set the date 1904/01/01 as default
        if (videoDate.year == 1904) videoDate = LocalDateTime.MIN

        // Could not get creation date from metadata, try guessing from file name
        if (videoDate == LocalDateTime.MIN) {
            if (Pattern.compile(wechatPattern).matcher(fileName).matches()) {
                videoDate = LocalDateTime.ofEpochSecond((fileName.substring(8, 18)).toLong(), 0, OffsetDateTime.now().offset)
            }
        }

        if (videoDate == LocalDateTime.MIN) videoDate = LocalDateTime.now()

        return videoDate
    }

    fun getImageFileDate(exifInterface: ExifInterface, fileName: String): String? {
        var timeString: String?

        timeString = exifInterface.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
        if (isUnknown(timeString)) timeString = exifInterface.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED)
        //if (isUnknown(timeString)) timeString = exif.getAttribute(ExifInterface.TAG_DATETIME)
        if (isUnknown(timeString)) {
            // Could not get creation date from exif, try guessing from file name
            timeString = if (Pattern.compile(wechatPattern).matcher(fileName).matches()) {
                (LocalDateTime.ofEpochSecond((fileName.substring(8, 18)).toLong(), 0, OffsetDateTime.now().offset))
                    .format(DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss"))
            } else null
        }

        return timeString
    }

    private fun isUnknown(date: String?): Boolean {
        return (date == null || date.isEmpty() || date == "    :  :     :  :  ")
    }

    // matching Wechat export file name, the 13 digits suffix is the export time in epoch long
    private const val wechatPattern = "^mmexport[0-9]{10}.*"

    fun dateToLocalDateTime(date: Date): LocalDateTime = date.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime()

    fun isMediaPlayable(mimeType: String): Boolean = (mimeType == "image/agif") || (mimeType == "image/awebp") || (mimeType.startsWith("video/", true))

    fun hasExif(mimeType: String): Boolean = mimeType.substringAfter('/') in setOf("jpeg", "png", "webp")

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

    fun getCameraRoll(cr: ContentResolver, imageOnly: Boolean): MutableList<Photo> = listMediaContent("DCIM", cr, imageOnly, false)
    fun listMediaContent(folder: String, cr: ContentResolver, imageOnly: Boolean, strict: Boolean): MutableList<Photo> {
        val medias = mutableListOf<Photo>()
        val externalStorageUri = MediaStore.Files.getContentUri("external")

        @Suppress("DEPRECATION")
        val pathSelection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Files.FileColumns.RELATIVE_PATH else MediaStore.Files.FileColumns.DATA
        val dateSelection = MediaStore.Files.FileColumns.DATE_ADDED
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
            "orientation",                  // MediaStore.Files.FileColumns.ORIENTATION, hardcoded here since it's only available in Android Q or above
        )
        val selection = if (imageOnly) "(${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}) AND ($pathSelection LIKE '%${folder}%')"
            else "(${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}) AND ($pathSelection LIKE '%${folder}%')"

        cr.query(externalStorageUri, projection, selection, null, "$dateSelection DESC")?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            //val pathColumn = cursor.getColumnIndexOrThrow(pathSelection)
            val dateAddedColumn = cursor.getColumnIndexOrThrow(dateSelection)
            val typeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.WIDTH)
            val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.HEIGHT)
            val orientationColumn = cursor.getColumnIndexOrThrow("orientation")    // MediaStore.Files.FileColumns.ORIENTATION, hardcoded here since it's only available in Android Q or above
            val defaultZone = ZoneId.systemDefault()
            var externalUri: Uri
            var mimeType: String
            var date: Long

            while (cursor.moveToNext()) {
                if ((strict) && (cursor.getString(cursor.getColumnIndexOrThrow(pathSelection)) ?: folder).substringAfter(folder).contains('/')) continue
                // Insert media
                mimeType = cursor.getString(typeColumn)
                date = cursor.getLong(dateAddedColumn)
                externalUri = if (mimeType.startsWith("video")) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                if (mimeType.startsWith("image") && mimeType.substringAfter('/') !in setOf("jpeg", "png", "gif", "webp", "bmp", "heif")) continue
                medias.add(
                    Photo(
                        ContentUris.withAppendedId(externalUri, cursor.getString(idColumn).toLong()).toString(),
                        ImageLoaderViewModel.FROM_CAMERA_ROLL,
                        cursor.getString(nameColumn) ?: "",
                        cursor.getString(sizeColumn),
                        LocalDateTime.ofInstant(Instant.ofEpochSecond(date), defaultZone),
                        LocalDateTime.MIN,
                        cursor.getInt(widthColumn),
                        cursor.getInt(heightColumn),
                        mimeType,
                        cursor.getInt(orientationColumn)
                    )
                )
            }
        }

        return medias
    }

    fun getCameraRollAlbum(cr: ContentResolver, albumName: String): Album? {
        val externalStorageUri = MediaStore.Files.getContentUri("external")
        var startDate = LocalDateTime.MIN
        var endDate: LocalDateTime
        var coverId: String
        val coverBaseline = 0   // TODO better default baseline
        var coverWidth: Int
        var coverHeight: Int
        var mimeType: String
        var orientation: Int

        @Suppress("DEPRECATION")
        val pathSelection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Files.FileColumns.RELATIVE_PATH else MediaStore.Files.FileColumns.DATA
        val dateSelection = MediaStore.Files.FileColumns.DATE_ADDED
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            dateSelection,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.WIDTH,
            MediaStore.Files.FileColumns.HEIGHT,
            "orientation",                  // MediaStore.Files.FileColumns.ORIENTATION, hardcoded here since it's only available in Android Q or above
        )
        val selection ="(${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}) AND ($pathSelection LIKE '%DCIM%') AND (${MediaStore.Files.FileColumns.WIDTH}!=0)"

        cr.query(externalStorageUri, projection, selection, null, "$dateSelection DESC")?.use { cursor ->
            if (cursor.moveToFirst()) {
                val dateColumn = cursor.getColumnIndex(dateSelection)
                val defaultZone = ZoneId.systemDefault()
                mimeType = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE))
                val externalUri = if (mimeType.startsWith("video")) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI

                // Get album's end date, cover
                endDate = LocalDateTime.ofInstant(Instant.ofEpochSecond(cursor.getLong(dateColumn)), defaultZone)
                coverId = ContentUris.withAppendedId(externalUri, cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)).toLong()).toString()
                coverWidth = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.WIDTH))
                coverHeight = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.HEIGHT))
                orientation = cursor.getInt(cursor.getColumnIndexOrThrow("orientation"))

                // Get album's start date
                if (cursor.moveToLast()) startDate = LocalDateTime.ofInstant(Instant.ofEpochSecond(cursor.getLong(dateColumn)), defaultZone)

                // Cover's mimetype passed in property eTag, cover's orientation passed in property shareId
                return Album(ImageLoaderViewModel.FROM_CAMERA_ROLL, albumName, startDate, endDate, coverId, coverBaseline, coverWidth, coverHeight, endDate, Album.BY_DATE_TAKEN_DESC, mimeType, orientation, 1.0F)
            } else return null
        } ?: return null
    }

    fun getFolderFromUri(uriString: String, contentResolver: ContentResolver): Pair<String, String>? {
        val colon = "%3A"
        val storageUriSignature = "com.android.externalstorage.documents"
        val mediaProviderUriSignature = "com.android.providers.media.documents"
        val downloadProviderUriSignature = "com.android.providers.downloads.documents"

        //Log.e(">>>>>", "input: $uriString")
        return try {
            when {
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && uriString.contains(downloadProviderUriSignature)) || uriString.contains(storageUriSignature) -> {
                    var id: String? = null
                    val folder = URLDecoder.decode(uriString.substringAfter(colon), "UTF-8").substringBeforeLast("/")
                    val externalStorageUri = MediaStore.Files.getContentUri("external")
                    @Suppress("DEPRECATION")
                    val pathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Files.FileColumns.RELATIVE_PATH else MediaStore.Files.FileColumns.DATA
                    val projection = arrayOf(
                        MediaStore.Files.FileColumns._ID,
                        MediaStore.Files.FileColumns.DISPLAY_NAME,
                        pathColumn,
                    )
                    val selection = "($pathColumn LIKE '%${folder}%') AND (${MediaStore.Files.FileColumns.DISPLAY_NAME}='${URLDecoder.decode(uriString, "UTF-8").substringAfterLast('/')}')"

                    contentResolver.query(externalStorageUri, projection, selection, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) id = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID))
                    }

                    id?.let { Pair("$folder/", id!!) }
                }
                uriString.contains(mediaProviderUriSignature) || uriString.contains(downloadProviderUriSignature) -> {
                    var folderName: String? = null
                    val id = uriString.substringAfter(colon)
                    val externalStorageUri = MediaStore.Files.getContentUri("external")
                    @Suppress("DEPRECATION")
                    val pathSelection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Files.FileColumns.RELATIVE_PATH else MediaStore.Files.FileColumns.DATA
                    val projection = arrayOf(
                        MediaStore.Files.FileColumns._ID,
                        pathSelection,
                    )
                    val selection = "${MediaStore.Files.FileColumns._ID} = $id"

                    contentResolver.query(externalStorageUri, projection, selection, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) folderName = cursor.getString(cursor.getColumnIndexOrThrow(pathSelection))
                    }

                    folderName?.let { Pair(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) folderName!! else "${folderName!!.substringAfter("/storage/emulated/0/").substringBeforeLast('/')}/", id) }
                }
                else -> null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun getBitmapFromVector(context: Context, vectorResource: Int): Bitmap {
        val vectorDrawable = ContextCompat.getDrawable(context, vectorResource)!!
        val bitmap = Bitmap.createBitmap(vectorDrawable.intrinsicWidth, vectorDrawable.intrinsicHeight, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).run {
            vectorDrawable.setBounds(0, 0, width, height)
            vectorDrawable.draw(this)
        }
        return bitmap
    }

    fun getLocalRoot(context: Context): String {
        return "${if (PreferenceManager.getDefaultSharedPreferences(context).getBoolean(SettingsFragment.KEY_STORAGE_LOCATION, true)) "${context.filesDir}" else "${context.getExternalFilesDirs(null)[1]}"}/${context.getString(R.string.lespas_base_folder_name)}"
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
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
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


    fun goImmersive(window: Window) {
        @Suppress("DEPRECATION")
        if (window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN != View.SYSTEM_UI_FLAG_FULLSCREEN) window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE or
            // Set the content to appear under the system bars so that the
            // content doesn't resize when the system bars hide and show.
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            // Hide the nav bar and status bar
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN
        )
/*
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE or
                // Set the content to appear under the system bars so that the
                // content doesn't resize when the system bars hide and show.
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                // Hide the nav bar and status bar
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN
            )
        } else {
            window.insetsController?.apply {
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsets.Type.systemBars())
            }
        }
*/
    }

    fun getPreparingSharesSnackBar(anchorView: View, strip: Boolean): Snackbar {
        val ctx = anchorView.context
        return Snackbar.make(anchorView, if (strip) R.string.striping_exif else R.string.preparing_shares, Snackbar.LENGTH_INDEFINITE).apply {
            try {
                (view.findViewById<MaterialTextView>(com.google.android.material.R.id.snackbar_text).parent as ViewGroup).addView(ProgressBar(ctx).apply {
                    // Android Snackbar text size is 14sp
                    val pbHeight = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics).roundToInt()
                    layoutParams = (LinearLayout.LayoutParams(pbHeight, pbHeight)).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END }
                    indeterminateTintList = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.color_text_light))
                })
            } catch (e: Exception) {}
            animationMode = Snackbar.ANIMATION_MODE_FADE
            setBackgroundTint(ContextCompat.getColor(ctx, R.color.color_primary))
            setTextColor(ContextCompat.getColor(ctx, R.color.color_text_light))
        }
    }
}