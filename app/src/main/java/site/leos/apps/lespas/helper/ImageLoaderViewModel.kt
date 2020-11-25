package site.leos.apps.lespas.helper

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.graphics.*
import android.util.Log
import android.util.LruCache
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import site.leos.apps.lespas.R
import site.leos.apps.lespas.photo.Photo
import kotlin.math.min

class ImageLoaderViewModel(application: Application) : AndroidViewModel(application) {
    private val rootPath: String
    private val imageCache: ImageCache

    init {
        val heapSize = ((application.getSystemService(Context.ACTIVITY_SERVICE)) as ActivityManager).memoryClass
        imageCache = ImageCache(1024 * 1024 * heapSize / 8).apply {
            put(ERROR_BITMAP, getBitmapFromVector(application, R.drawable.ic_baseline_broken_image_24))
            put(PLACEHOLDER_BITMAP, getBitmapFromVector(application, R.drawable.ic_baseline_placeholder_24))
        }
        rootPath = "${application.filesDir}${application.getString(R.string.lespas_base_folder_name)}"
    }

    private fun getBitmapFromVector(application: Application, vectorResource: Int): Bitmap {
        val vectorDrawable = ContextCompat.getDrawable(application, vectorResource)!!
        val bitmap = Bitmap.createBitmap(vectorDrawable.intrinsicWidth, vectorDrawable.intrinsicHeight, Bitmap.Config.RGB_565)
        Canvas(bitmap).run {
            vectorDrawable.setBounds(0, 0, width, height)
            vectorDrawable.draw(this)
        }
        return bitmap
    }

    fun loadPhoto(photo: Photo, view: ImageView, type: String) {
        viewModelScope.launch(Dispatchers.IO) {
            var bitmap: Bitmap?
            var key = "${photo.id}$type"

            if (type == TYPE_COVER) key = "$key-${photo.shareId}"   // suffix baseline

            try {
                // Show something first
                //view.setImageBitmap(imageCache.get(PLACEHOLDER_BITMAP))
                //view.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                bitmap = imageCache.get(key)
                if (bitmap == null) when (type) {
                    TYPE_VIEW -> {
                        /*
                        var inSampleSize = 1
                        if ((photo.height > view.measuredHeight) || (photo.width > view.measuredWidth)) {
                            val halfHeight = photo.height / 2
                            val halfWidth = photo.width / 2
                            while ((halfHeight / inSampleSize >= view.measuredHeight) && (halfWidth / inSampleSize >= view.measuredWidth)) {
                                inSampleSize *= 2
                                Log.e("+++++", "$inSampleSize")
                            }
                        }

                        */
                        val size = if ((photo.height < 1000) || (photo.width < 1000)) 2 else 4
                        bitmap = BitmapFactory.decodeFile("$rootPath/${photo.id}", BitmapFactory.Options().apply { this.inSampleSize = size })
                    }
                    TYPE_FULL -> {
                        bitmap = BitmapFactory.decodeFile("$rootPath/${photo.id}")
                    }
                    TYPE_COVER -> {
                        val size = if ((photo.height < 1000) || (photo.width < 1000)) 1 else 2
                        // cover baseline passed in field shareId
                        val bottom = min(photo.shareId + (photo.width * 9 / 21).toInt(), photo.height)
                        val rect = Rect(0, photo.shareId, photo.width, bottom)
                        bitmap = BitmapRegionDecoder.newInstance("$rootPath/${photo.id}", false).decodeRegion(rect, BitmapFactory.Options().apply { this.inSampleSize = size })
                    }
                }
                if (bitmap != null) {
                    withContext(Dispatchers.Main) { view.setImageBitmap(bitmap) }
                    imageCache.put(key, bitmap)
                } else view.setImageBitmap(imageCache.get(ERROR_BITMAP))
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                Log.e("-------", "${imageCache.hitCount()} ${imageCache.missCount()} ${imageCache.evictionCount()}")
            }
        }
    }

    open class ImageCache constructor(maxSize: Int) : LruCache<String, Bitmap>(maxSize) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount
        }
    }

    companion object {
        const val TYPE_VIEW = "_view"
        const val TYPE_FULL = "_full"
        const val TYPE_COVER = "_cover"

        const val ERROR_BITMAP = "ERROR_BITMAP"
        const val PLACEHOLDER_BITMAP = "PLACEHOLDER_BITMAP"
    }
}