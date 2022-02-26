package site.leos.apps.lespas.helper

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.graphics.*
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.view.View
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceManager
import kotlinx.coroutines.*
import site.leos.apps.lespas.R
import site.leos.apps.lespas.photo.Photo
import java.io.File
import java.io.IOException
import java.lang.Integer.max
import kotlin.math.min

class ImageLoaderViewModel(application: Application) : AndroidViewModel(application) {
    private val rootPath = Tools.getLocalRoot(application)
    private val contentResolver = application.contentResolver
    private val imageCache = ImageCache(((application.getSystemService(Context.ACTIVITY_SERVICE)) as ActivityManager).memoryClass / 8 * 1024 * 1024)
    private val errorBitmap = ContextCompat.getDrawable(application, R.drawable.ic_baseline_broken_image_24)!!.toBitmap()
    private val placeholderBitmap =  ContextCompat.getDrawable(application, R.drawable.ic_baseline_placeholder_24)!!.toBitmap()
    private val jobMap = HashMap<Int, Job>()
    private val sp = PreferenceManager.getDefaultSharedPreferences(application)
    private val autoReplayKey = application.getString(R.string.auto_replay_perf_key)

    fun interface LoadCompleteListener{
        fun onLoadComplete()
    }

    private fun decodeBitmap(photo: Photo, type: String): Pair<Bitmap?, Drawable?> {
        var bitmap: Bitmap? = null
        var animatedDrawable: Drawable? = null
        var fileName = ""
        var uri = Uri.EMPTY

        if (photo.albumId == FROM_CAMERA_ROLL) uri = Uri.parse(photo.id)
        else fileName = "${rootPath}/${photo.id}"

        try {
            bitmap = when (type) {
                TYPE_VIDEO -> getVideoThumbnail(photo, fileName)
                TYPE_GRID -> {
                    val option = BitmapFactory.Options().apply { inSampleSize = if ((photo.height < 1600) || (photo.width < 1600)) 2 else 8 }
                    with(photo.mimeType) {
                        when {
                            //this.startsWith("video") -> getVideoThumbnail(photo, fileName)
                            photo.albumId != FROM_CAMERA_ROLL -> BitmapFactory.decodeFile(fileName, option)
                            this == "image/jpeg" || this == "image/png" -> getImageThumbnail(photo)
                            this == "image/agif" || this == "image/gif" || this == "image/webp" || this == "image/awebp" -> BitmapFactory.decodeStream(contentResolver.openInputStream(uri), null, option)
                            else-> BitmapFactory.decodeFile(fileName, option)
                        }
                    }
                }
                TYPE_FULL, TYPE_QUATER -> {
                    //if (photo.mimeType.startsWith("video")) getVideoThumbnail(photo, fileName)
                    //else {
                        val option = BitmapFactory.Options().apply {
                            when {
                                type == TYPE_QUATER -> inSampleSize = 2
                                // Large photo, allocationByteCount could exceed 100,000,000 bytes if fully decoded
                                // TODO hard coded size limit
                                photo.width * photo.height > 33333334 -> inSampleSize = 2
                            }
                        }

                        if (photo.albumId == FROM_CAMERA_ROLL) {
                            // Photo from camera roll doesn't support image/awebp, image/agif
                            if (type == TYPE_FULL && (photo.mimeType == "image/webp" || photo.mimeType == "image/gif") && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                animatedDrawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(contentResolver, uri))
                                null
                            }
                            else {
                                var b = BitmapFactory.decodeStream(contentResolver.openInputStream(uri), null, option)
                                // Rotate according to EXIF when this photo comes from camera roll
                                if (photo.orientation != 0) b?.let { b = Bitmap.createBitmap(b!!, 0, 0, it.width, it.height, Matrix().apply { preRotate((photo.orientation).toFloat()) }, true) }
                                b
                            }
                        }
                        else {
                            if (type == TYPE_FULL && (photo.mimeType == "image/awebp" || photo.mimeType == "image/agif") && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                animatedDrawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(File(fileName)))
                                null
                            }
                            else BitmapFactory.decodeFile(fileName, option)
                        }
                    //}
                }
                TYPE_COVER, TYPE_SMALL_COVER -> {
/*
                    if (photo.mimeType.startsWith("video/")) { getVideoThumbnail(photo, fileName) }
                    else {
                        val size = if ((photo.height < 1600) || (photo.width < 1600)) 1 else if (type == TYPE_SMALL_COVER) 8 else 4
                        // cover baseline value passed in property shareId
                        val bottom = min(photo.shareId + (photo.width.toFloat() * 9 / 21).toInt(), photo.height)
                        val rect = Rect(0, photo.shareId, photo.width, bottom)
                        BitmapRegionDecoder.newInstance(fileName, false).decodeRegion(rect, BitmapFactory.Options().apply {
                            this.inSampleSize = size
                            this.inPreferredConfig = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) Bitmap.Config.RGBA_F16 else Bitmap.Config.ARGB_8888
                        }) ?: placeholderBitmap
                    }
*/
                    // cover baseline value passed in property shareId
                    val options = BitmapFactory.Options().apply {
                        this.inSampleSize = if ((photo.height < 1600) || (photo.width < 1600)) 1 else if (type == TYPE_SMALL_COVER) 8 else 4
                        this.inPreferredConfig = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Bitmap.Config.RGBA_F16 else Bitmap.Config.ARGB_8888
                    }
                    try {
                        var bmp: Bitmap? = null
                        if (photo.albumId == FROM_CAMERA_ROLL) {
                            // cover orientation passed in property eTag
                            (try {photo.eTag.toFloat()} catch (e: Exception) { 0.0F }).also { orientation->

                                val rect = when(orientation) {
                                    0.0F-> Rect(0, photo.shareId, photo.width - 1, min(photo.shareId + (photo.width.toFloat() * 9 / 21).toInt(), photo.height - 1))
                                    90.0F-> Rect(photo.shareId, 0, min(photo.shareId + (photo.height.toFloat() * 9 / 21).toInt(), photo.width - 1), photo.height - 1)
                                    180.0F-> (photo.height - photo.shareId).let { Rect(0, max(it - (photo.width.toFloat() * 9 / 21).toInt(), 0), photo.width - 1, it) }
                                    else-> (photo.width - photo.shareId).let { Rect(max(it - (photo.height.toFloat() * 9 / 21).toInt(), 0), 0, it, photo.height - 1) }
                                }

                                // Decode region
                                //bmp = BitmapRegionDecoder.newInstance(contentResolver.openInputStream(uri), false).decodeRegion(rect, options)
                                contentResolver.openInputStream(uri)?.let {
                                    @Suppress("DEPRECATION")
                                    bmp = (if (Build.VERSION.SDK_INT > Build.VERSION_CODES.R) BitmapRegionDecoder.newInstance(it) else BitmapRegionDecoder.newInstance(it, false))?.decodeRegion(rect, options)
                                }

                                // Rotate if needed
                                if (orientation != 0.0F) bmp?.let { bmp = Bitmap.createBitmap(bmp!!, 0, 0, bmp!!.width, bmp!!.height, Matrix().apply { preRotate(orientation) }, true) }
                            }
                        } else {
                            // If album's cover size changed from other ends, like picture cropped on server, SyncAdapter will not handle the changes, the baseline could be invalid
                            // TODO better way to handle this
                            val top = if (photo.shareId > photo.height - 1) 0 else photo.shareId

                            val bottom = min(top + (photo.width.toFloat() * 9 / 21).toInt(), photo.height - 1)
                            val rect = Rect(0, top, photo.width - 1, bottom)

                            @Suppress("DEPRECATION")
                            bmp = (if (Build.VERSION.SDK_INT > Build.VERSION_CODES.R) BitmapRegionDecoder.newInstance(fileName) else BitmapRegionDecoder.newInstance(fileName, false)).decodeRegion(rect, options) ?: placeholderBitmap
                        }

                        bmp ?: placeholderBitmap
                    } catch (e: IOException) {
                        // Video only album has video file as cover, BitmapRegionDecoder will throw IOException with "Image format not supported" stack trace message
                        //e.printStackTrace()
                        getVideoThumbnail(photo, fileName)
                    }
                }
                else -> errorBitmap
            }
        } catch (e: Exception) { e.printStackTrace() }

        return Pair(bitmap, animatedDrawable)
    }

    fun invalid(photoId: String) {
        imageCache.snapshot().keys.forEach { key-> if (key.startsWith(photoId)) imageCache.remove(key) }
    }

    /*
    fun reloadPhoto(photo: Photo) {
        viewModelScope.launch(Dispatchers.IO) {
            imageCache.snapshot().keys.forEach { key->
                if (key.startsWith(photo.id)) {
                    imageCache.put(key, decodeBitmap(photo, key.substringAfter(photo.id).substringBefore('-')))
                    // TODO recycle old bitmap?
                }
            }
        }
    }

     */

    @SuppressLint("NewApi")
    @JvmOverloads
    fun loadPhoto(photo: Photo, view: ImageView, photoType: String, callBack: LoadCompleteListener? = null) {
        val type = if (photo.mimeType.startsWith("video")) TYPE_VIDEO else photoType
        val jobKey = System.identityHashCode(view)

        val job = viewModelScope.launch(Dispatchers.IO) {
            var decodeResult: Pair<Bitmap?, Drawable?> = Pair(null, null)
            var bitmap: Bitmap?
            var key = "${photo.id}$type"

            // suffix 'baseline' in case same photo chosen
            if ((type == TYPE_COVER) || (type == TYPE_SMALL_COVER)) key = "$key-${photo.shareId}"

            try {
                // Show something first
                //view.setImageBitmap(placeholderBitmap)
                //view.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                bitmap = imageCache.get(key)
                // Give error another chance
                if (bitmap == null || bitmap == errorBitmap) {
                    when {
                        type != TYPE_FULL -> {}
                        photo.albumId != FROM_CAMERA_ROLL -> {
                            // Black placeholder for full image view so that the layout can be stable during transition to immersive mode
                            if (isActive) {
                                withContext(Dispatchers.Main) {
                                    imageCache.get("${photo.id}${TYPE_GRID}")?.let {
                                        view.setImageBitmap(it)
                                        callBack?.onLoadComplete()
                                    }
                                }
                            }
                        }
                        !Tools.isMediaPlayable(photo.mimeType) -> {
                            getImageThumbnail(photo)?.let {
                                if (isActive) {
                                    withContext(Dispatchers.Main) {
                                        view.setImageBitmap(it)
                                        callBack?.onLoadComplete()
                                    }
                                }
                            }
                        }
                    }
                    decodeResult = decodeBitmap(photo, type)
                    bitmap = decodeResult.first
                    if (bitmap == null) bitmap = errorBitmap
                    else if (type != TYPE_FULL && type != TYPE_QUATER) imageCache.put(key, bitmap)
                }

                //Log.e(">>>", "${bitmap.allocationByteCount} aa ${imageCache.putCount()} ${imageCache.snapshot().size}")

                // If we are still active at this moment, set the imageview
                if (isActive) {
                    withContext(Dispatchers.Main) {
                        decodeResult.second?.let {
                            view.setImageDrawable(it.apply {
                                (this as AnimatedImageDrawable).apply {
                                    if (sp.getBoolean(autoReplayKey, true)) this.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                                    start()
                                }
                            })
                        } ?: run { view.setImageBitmap(bitmap) }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                withContext(Dispatchers.Main) { callBack?.onLoadComplete() }
            }
        }

        // Replacing previous job
        replacePrevious(jobKey, job)
    }

    fun cancelLoading(view: View) { jobMap[System.identityHashCode(view)]?.cancel() }

    private fun replacePrevious(key: Int, newJob: Job) {
        jobMap[key]?.cancel()
        jobMap[key] = newJob
    }

    private fun getImageThumbnail(photo: Photo): Bitmap? =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, Uri.parse(photo.id))) { decoder, _, _ -> decoder.setTargetSampleSize(if ((photo.height < 1600) || (photo.width < 1600)) 2 else 8)}
                // TODO: For photo captured in Sony Xperia machine, loadThumbnail will load very small size bitmap
                //contentResolver.loadThumbnail(Uri.parse(photo.id), Size(photo.width/8, photo.height/8), null)
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Thumbnails.getThumbnail(contentResolver, photo.id.substringAfterLast('/').toLong(), MediaStore.Images.Thumbnails.MINI_KIND, null).run {
                    if (photo.shareId != 0) Bitmap.createBitmap(this, 0, 0, this.width, this.height, Matrix().also { it.preRotate(photo.shareId.toFloat()) }, true)
                    else this
                }
            }
        } catch(e: Exception) {
            e.printStackTrace()
            null
        }

    private fun getVideoThumbnail(photo: Photo, fileName: String): Bitmap? =
        try {
            if (photo.albumId == FROM_CAMERA_ROLL) {
                val photoId = photo.id.substringAfterLast('/').toLong()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        contentResolver.loadThumbnail(Uri.parse(photo.id), Size(photo.width, photo.height), null)
                    } catch (e: ArithmeticException) {
                        // Some Android Q Rom, like AEX for EMUI 9, throw this exception
                        e.printStackTrace()
                        @Suppress("DEPRECATION")
                        MediaStore.Video.Thumbnails.getThumbnail(contentResolver, photoId, MediaStore.Video.Thumbnails.MINI_KIND, null)
                    }
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Video.Thumbnails.getThumbnail(contentResolver, photoId, MediaStore.Video.Thumbnails.MINI_KIND, null)
                }
            }
            else {
                var bitmap: Bitmap?
                val thumbnailFile = "$fileName.thumbnail"
                bitmap = BitmapFactory.decodeFile(thumbnailFile)
                if (bitmap == null) {
                    MediaMetadataRetriever().apply {
                        setDataSource(fileName)
                        // Get first frame
                        bitmap = getFrameAtTime(0L)
                        release()
                    }
                    bitmap?.compress(Bitmap.CompressFormat.JPEG, 90, File(thumbnailFile).outputStream())
                }
                bitmap
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }

    override fun onCleared() {
        jobMap.forEach { if (it.value.isActive) it.value.cancel() }
        super.onCleared()
    }

    class ImageCache (maxSize: Int) : LruCache<String, Bitmap>(maxSize) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    companion object {
        const val TYPE_NULL = ""    // For startPostponedEnterTransition() immediately for video item
        const val TYPE_GRID = "_view"
        const val TYPE_FULL = "_full"
        const val TYPE_COVER = "_cover"
        const val TYPE_SMALL_COVER = "_smallcover"
        const val TYPE_QUATER = "_quater"
        const val TYPE_VIDEO = "_video"

        //const val FROM_CAMERA_ROLL = "!@#$%^&*()_+alkdfj4654"
        const val FROM_CAMERA_ROLL = "0"
    }
}