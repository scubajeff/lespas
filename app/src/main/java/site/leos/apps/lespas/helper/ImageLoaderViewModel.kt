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
    private val rootPath = "${application.filesDir}${application.getString(R.string.lespas_base_folder_name)}"
    private val imageCache = ImageCache(((application.getSystemService(Context.ACTIVITY_SERVICE)) as ActivityManager).memoryClass / 8 * 1024 * 1024)
    private val errorBitmap = getBitmapFromVector(application, R.drawable.ic_baseline_broken_image_24)
    private val placeholderBitmap = getBitmapFromVector(application, R.drawable.ic_baseline_placeholder_24)

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

            if (type == TYPE_COVER) key = "$key-${photo.shareId}"   // suffix 'baseline' in case same photo chosen

            try {
                // Show something first
                //view.setImageBitmap(placeholderBitmap)
                //view.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                //if (type == TYPE_COVER) Log.e("----", "requesting $key")
                bitmap = imageCache.get(key)
                bitmap ?: when (type) {
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
                if (bitmap == null) bitmap = errorBitmap
                else imageCache.put(key, bitmap)

                withContext(Dispatchers.Main) {
                    view.setImageBitmap(bitmap)
                    //if (type == TYPE_COVER) view.postInvalidate()
                }
            } catch (e: Exception) {
                Log.e("ImageLoaderViewModel", e.message.toString())
            } finally {
                //Log.e("-------", "${imageCache.hitCount()} ${imageCache.missCount()} ${imageCache.evictionCount()}")
                //if (type == TYPE_COVER) Log.e("-----", "setting bitmap: $key")
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
    }
}