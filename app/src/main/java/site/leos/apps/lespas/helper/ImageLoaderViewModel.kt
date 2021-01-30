package site.leos.apps.lespas.helper

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.graphics.*
import android.media.ThumbnailUtils
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import site.leos.apps.lespas.R
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.photo.PhotoRepository
import java.io.File
import kotlin.math.min

class ImageLoaderViewModel(application: Application) : AndroidViewModel(application) {
    private val rootPath = "${application.filesDir}${application.getString(R.string.lespas_base_folder_name)}"
    private val imageCache = ImageCache(((application.getSystemService(Context.ACTIVITY_SERVICE)) as ActivityManager).memoryClass / 6 * 1024 * 1024)
    private val errorBitmap = getBitmapFromVector(application, R.drawable.ic_baseline_broken_image_24)
    private val placeholderBitmap = getBitmapFromVector(application, R.drawable.ic_baseline_placeholder_24)
    private val jobMap = HashMap<Int, Job>()

    private val photoRepository = PhotoRepository(application)

    fun interface LoadCompleteListener{
        fun onLoadComplete()
    }

    private fun getBitmapFromVector(application: Application, vectorResource: Int): Bitmap {
        val vectorDrawable = ContextCompat.getDrawable(application, vectorResource)!!
        val bitmap = Bitmap.createBitmap(vectorDrawable.intrinsicWidth, vectorDrawable.intrinsicHeight, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).run {
            vectorDrawable.setBounds(0, 0, width, height)
            vectorDrawable.draw(this)
        }
        return bitmap
    }

    private fun decodeBitmap(photo: Photo, type: String): Bitmap? {
        var bitmap: Bitmap? = null
        var fileName = "${rootPath}/${photo.id}"

        if (!(File(fileName).exists())) {
            fileName = "${rootPath}/${photo.name}"
            if (!(File(fileName).exists())) {
                // Under some situation, cover photo is created from Album record in runtime, therefore does not contain name property value
                if (type == TYPE_SMALL_COVER || type == TYPE_COVER) {
                    fileName = "${rootPath}/${photoRepository.getPhotoName(photo.id)}"
                    if (!File(fileName).exists()) return errorBitmap
                }
                else return errorBitmap
            }
        }

        try {
            bitmap = when (type) {
                TYPE_GRID -> {
                    val size = if ((photo.height < 1600) || (photo.width < 1600)) 2 else 8
                    val rect: Rect
                    rect = if (photo.height > photo.width) {
                        val top = (photo.height - photo.width) / 2
                        val bottom = top + photo.width
                        Rect(0, top, photo.width, bottom)
                    } else {
                        val left = (photo.width - photo.height) / 2
                        val right = left + photo.height
                        Rect(left, 0, right, photo.height)
                    }
                    with(photo.mimeType) {
                        when {
                            this.startsWith("video")-> {
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                                    ThumbnailUtils.createVideoThumbnail(File(fileName), Size(384, 384), null)
                                } else {
                                    ThumbnailUtils.createVideoThumbnail(fileName, MediaStore.Images.Thumbnails.MINI_KIND)
                                }
                            }
                            this == "image/agif" || this == "image/gif" || this == "image/webp" || this == "image/awebp" -> {
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q)
                                    ThumbnailUtils.createImageThumbnail(File(fileName), Size(300, 300), null)
                                else BitmapFactory.decodeFile(fileName, BitmapFactory.Options().apply { this.inSampleSize = size })
                            }
                            this == "image/jpeg" || this == "image/png" -> {
                                BitmapRegionDecoder.newInstance(fileName, false).decodeRegion(rect, BitmapFactory.Options().apply {
                                    this.inSampleSize = size
                                    this.inPreferredConfig = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) Bitmap.Config.RGBA_F16 else Bitmap.Config.ARGB_8888
                                })
                            }
                            else-> BitmapFactory.decodeFile(fileName, BitmapFactory.Options().apply { this.inSampleSize = size })
                        }
                    }
                }
                TYPE_FULL -> {
                    BitmapFactory.decodeFile(fileName)
                }
                TYPE_COVER, TYPE_SMALL_COVER -> {
                    val size = if ((photo.height < 1600) || (photo.width < 1600)) 1 else if (type == TYPE_SMALL_COVER) 8 else 4
                    // cover baseline value passed in property shareId
                    val bottom = min(photo.shareId + (photo.width.toFloat() * 9 / 21).toInt(), photo.height)
                    val rect = Rect(0, photo.shareId, photo.width, bottom)
                    BitmapRegionDecoder.newInstance(fileName, false).decodeRegion(rect, BitmapFactory.Options().apply {
                        this.inSampleSize = size
                        this.inPreferredConfig = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) Bitmap.Config.RGBA_F16 else Bitmap.Config.ARGB_8888
                    }) ?: placeholderBitmap
                }
                else -> errorBitmap
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            return bitmap
        }
    }

    fun invalid(photo: Photo) {
        imageCache.snapshot().keys.forEach { key-> if (key.startsWith(photo.id)) imageCache.remove(key) }
    }

    fun loadPhoto(photo: Photo, view: ImageView, type: String) {
        loadPhoto(photo, view, type, null)
    }

    fun loadPhoto(photo: Photo, view: ImageView, type: String, callBack: LoadCompleteListener?) {
        val job = viewModelScope.launch(Dispatchers.IO) {
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
                if (bitmap == null || bitmap == errorBitmap) bitmap = decodeBitmap(photo, type)
                if (bitmap == null) bitmap = errorBitmap
                else imageCache.put(key, bitmap)

                // If we are still active at this moment, set the imageview
                if (isActive) {
                    withContext(Dispatchers.Main) { view.setImageBitmap(bitmap) }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                callBack?.onLoadComplete()
            }
        }

        // Replacing previous job
        replacePrevious(System.identityHashCode(view), job)
    }

    private fun replacePrevious(key: Int, newJob: Job) {
        jobMap[key]?.cancel()
        jobMap[key] = newJob
    }

    override fun onCleared() {
        super.onCleared()
        jobMap.forEach { if (it.value.isActive) it.value.cancel() }
    }

    class ImageCache (maxSize: Int) : LruCache<String, Bitmap>(maxSize) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    companion object {
        const val TYPE_GRID = "_view"
        const val TYPE_FULL = "_full"
        const val TYPE_COVER = "_cover"
        const val TYPE_SMALL_COVER = "_smallcover"
    }
}