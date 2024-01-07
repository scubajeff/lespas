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

package site.leos.apps.lespas.muzei

import android.accounts.AccountManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.os.Build
import android.view.WindowManager
import androidx.preference.PreferenceManager
import com.google.android.apps.muzei.api.provider.Artwork
import com.google.android.apps.muzei.api.provider.MuzeiArtProvider
import site.leos.apps.lespas.MainActivity
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.AlbumRepository
import site.leos.apps.lespas.helper.OkHttpWebDav
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.photo.PhotoRepository
import site.leos.apps.lespas.tflite.ObjectDetectionModel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.lang.Integer.max
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale
import kotlin.random.Random

class LesPasArtProvider: MuzeiArtProvider() {
    override fun onLoadRequested(initial: Boolean) {
        val skipLateNightUpdate =
            if (PreferenceManager.getDefaultSharedPreferences(context).getBoolean(LesPasArtProviderSettingActivity.KEY_SKIP_LATE_NIGHT_UPDATE, false)) {
                LocalDateTime.now().hour in 0..5
            }
            else false
        if (initial || ((Date().time - (lastAddedArtwork?.dateAdded ?: Date()).time > 30000) && !skipLateNightUpdate)) updateArtwork()
    }

    override fun openFile(artwork: Artwork): InputStream {
        var sWidth: Float
        var sHeight: Float
        val pWidth: Float
        val pHeight: Float

        // Get screen real metrics
        Tools.getDisplayDimension(context!!.getSystemService(Context.WINDOW_SERVICE) as WindowManager).let {
            sWidth = it.first.toFloat()
            sHeight = it.second.toFloat()
        }

        // Adapt to original orientation
        val screenIsPortrait = context!!.resources.getBoolean(R.bool.portrait_artwork)
        if ((screenIsPortrait && sWidth > sHeight) || (!screenIsPortrait && sWidth < sHeight)) {
            sWidth -= sHeight
            sHeight += sWidth
            sWidth = sHeight - sWidth
        }

        with(artwork.metadata!!.split(',')) {
            pWidth = this[1].toFloat()
            pHeight = this[2].toFloat()
        }

        // Find out center of interested area
        var xCenter = 0.5f
        var yCenter = 0.5f
        val od = ObjectDetectionModel(context!!.applicationContext.assets)
        with(od.recognizeImage(BitmapFactory.decodeFile("${context!!.applicationContext.cacheDir}/${artwork.token!!}", BitmapFactory.Options().apply { inSampleSize = 8 }))) {
            if (this.isNotEmpty()) this[0].location.let { area ->
                xCenter = (area.left + (area.right - area.left) / 2) / ObjectDetectionModel.INPUT_SIZE
                yCenter = (area.top + (area.bottom - area.top) / 2) / ObjectDetectionModel.INPUT_SIZE

                if (xCenter == 0.0f) xCenter = 0.5f
                if (yCenter == 0.0f) yCenter = 0.5f
            }
        }
        od.close()

        // Get the crop rect
        val rect: Rect
        if (sWidth / sHeight <= pWidth / pHeight) {
            // Screen is narrower than artwork
            val aWidth = pHeight * sWidth / sHeight
            val center = pWidth * xCenter
            var left = max((center - aWidth / 2).toInt(), 0)
            var right = left + aWidth.toInt()
            if (right > pWidth) {
                right = pWidth.toInt()
                left = (pWidth - aWidth).toInt()
            }
            rect = Rect(left, 0, right, pHeight.toInt())
            //Log.e(">>>>>>>>", "openFile: screen is narrower, picture:${pWidth}x${pHeight} xCenter:$xCenter center:$center aW:${aWidth} $rect")
        } else {
            // Screen is wider than artwork
            val aHeight = pWidth * sHeight / sWidth
            val center = pHeight * yCenter
            var top = max((center - aHeight / 2).toInt(), 0)
            var bottom = top + aHeight.toInt()
            if (bottom > pHeight) {
                bottom = pHeight.toInt()
                top = (pHeight - aHeight).toInt()
            }
            rect = Rect(0, top, pWidth.toInt(), bottom)
            //Log.e(">>>>>>>>", "openFile: screen is wider picture:${pWidth}x${pHeight} yCenter:$yCenter center:$center aH:$aHeight $rect")
        }

        val out = ByteArrayOutputStream()
        with("${context!!.applicationContext.cacheDir}/${artwork.token!!}") {
            @Suppress("DEPRECATION")
            (if (Build.VERSION.SDK_INT > Build.VERSION_CODES.R) BitmapRegionDecoder.newInstance(this) else BitmapRegionDecoder.newInstance(this, false)).decodeRegion(rect, null).compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        return ByteArrayInputStream(out.toByteArray())
    }

    override fun getDescription(): String = lastAddedArtwork?.run { "$title" } ?: run { super.getDescription() }

    override fun getArtworkInfo(artwork: Artwork): PendingIntent? {
        val intent = Intent().apply {
            setClass(context!!, MainActivity::class.java)
            putExtra(FROM_MUZEI_PHOTO, artwork.token)
            putExtra(FROM_MUZEI_ALBUM, artwork.metadata!!.split(',')[0])
            //flags = Intent.FLAG_ACTIVITY_NO_HISTORY
        }

        return PendingIntent.getActivity(context!!, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun updateArtwork() {
        Thread {
            var additionalCaption = false

            (context?.applicationContext as Application).let { application ->
                val sp = PreferenceManager.getDefaultSharedPreferences(application)
                val exclusion = try { sp.getStringSet(LesPasArtProviderSettingActivity.KEY_EXCLUSION_LIST, setOf<String>()) } catch (e: ClassCastException) { setOf<String>() }
                val albumRepository = AlbumRepository(application)
                PhotoRepository(application).getMuzeiArtwork(exclusion!!.toMutableList().apply { addAll(albumRepository.getAllHiddenAlbumIds()) }).let { photoList ->
                    if (photoList.isEmpty()) null
                    else {
                        val today = LocalDate.now()
                        val random = Random(System.currentTimeMillis() * LocalDate.now().dayOfMonth / LocalDate.now().dayOfWeek.value)
                        when (sp.getInt(LesPasArtProviderSettingActivity.KEY_PREFER, LesPasArtProviderSettingActivity.PREFER_RANDOM)) {
                            LesPasArtProviderSettingActivity.PREFER_LATEST -> {
                                photoList.filter { p -> Period.between(p.dateTaken.toLocalDate(), today).toTotalMonths() < 1 }.let { recentList ->
                                    when {
                                        recentList.size == 1 -> recentList[0]
                                        recentList.isNotEmpty() -> recentList[random.nextInt(recentList.size)]
                                        else -> photoList[random.nextInt(photoList.size)]
                                    }
                                }
                            }
                            LesPasArtProviderSettingActivity.PREFER_TODAY_IN_HISTORY -> {
                                photoList.filter { p -> today.dayOfMonth == p.dateTaken.dayOfMonth && today.month == p.dateTaken.month }.let { sameDayHits ->
                                    additionalCaption = sameDayHits.isNotEmpty()

                                    when(sameDayHits.size) {
                                        0 -> photoList[random.nextInt(photoList.size)]
                                        1 -> sameDayHits[0]
                                        else -> {
/*
                                            var index = random.nextInt(sameDayHits.size)
                                            lastAddedArtwork?.let {
                                                // Prevent from choosing the last one again
                                                while(sameDayHits[index].id == it.token) index = random.nextInt(sameDayHits.size)
                                            }
                                            sameDayHits[index]
*/
                                            // Try to get today's photo list from Preference
                                            val photosOfDate = sp.getStringSet(PHOTOS_OF_TODAY, setOf())?.toMutableList() ?: mutableListOf()
                                            if (photosOfDate.contains(sameDayHits[0].id)) {
                                                // If list existed, loop thru. it
                                                photosOfDate[0] = (photosOfDate[0].toInt() + 1).let { i -> if (i == sameDayHits.size) 0 else i }.toString()
                                            } else {
                                                // Create today's photo shuffled list, add current index to the top of the list
                                                photosOfDate.clear()
                                                sameDayHits.shuffled().forEach { photosOfDate.add(it.id) }
                                                photosOfDate.add(0, "0")
                                            }

                                            // Save the list in Preference
                                            sp.edit().apply {
                                                putStringSet(PHOTOS_OF_TODAY, photosOfDate.toSet())
                                                apply()
                                            }

                                            // Supply the next in list to Muzei
                                            sameDayHits.find { it.id == photosOfDate[photosOfDate[0].toInt() + 1] }
                                        }
                                    }
                                }
                            }
                            else -> photoList[random.nextInt(photoList.size)]
                        }
                    }
                }?.let { photo ->
                    val album = albumRepository.getThisAlbum(photo.albumId)
                    val dest = File(application.cacheDir, photo.id)
                    try {
                        if (Tools.isRemoteAlbum(album) && photo.eTag != Photo.ETAG_NOT_YET_UPLOADED) {
                            // Download remote image
                            val accounts = AccountManager.get(application).getAccountsByType(application.getString(R.string.account_type_nc))
                            val webDav: OkHttpWebDav
                            val resourceRoot: String

                            if (accounts.isNotEmpty()) {
                                AccountManager.get(application).run {
                                    val userName = getUserData(accounts[0], application.getString(R.string.nc_userdata_username))
                                    val serverRoot = getUserData(accounts[0], application.getString(R.string.nc_userdata_server))

                                    resourceRoot = "$serverRoot${application.getString(R.string.dav_files_endpoint)}$userName${Tools.getRemoteHome(application)}"
                                    webDav = OkHttpWebDav(userName, getUserData(accounts[0], application.getString(R.string.nc_userdata_secret)), serverRoot, getUserData(accounts[0], application.getString(R.string.nc_userdata_selfsigned)).toBoolean(), getUserData(accounts[0], application.getString(R.string.nc_userdata_certificate)), null, "LesPas_${application.getString(R.string.lespas_version)}", 0,)
                                }

                                webDav.getStream("${resourceRoot}/${album.name}/${photo.name}", false, null).use {
                                    var bitmap: Bitmap? = BitmapFactory.decodeStream(it)
                                    if (bitmap != null && photo.orientation != 0) {
                                        bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { preRotate((photo.orientation).toFloat()) }, true)
                                    }
                                    bitmap?.compress(Bitmap.CompressFormat.JPEG, 90, dest.outputStream())
                                }
                            }
                        } else {
                            File("${Tools.getLocalRoot(application)}/${photo.id}").inputStream().use { source ->
                                dest.outputStream().use { target ->
                                    source.copyTo(target, 8192)
                                }
                            }
                        }
                    } catch (e: Exception) { e.printStackTrace() }

                    if (dest.exists()) setArtwork(Artwork(
                        title = album.name,
                        byline = "${photo.dateTaken.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())}, ${photo.dateTaken.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT))}" +
                                if (additionalCaption) with(LocalDate.now().year - photo.dateTaken.year) { if (this > 0) context!!.resources.getQuantityString(R.plurals.years_ago_today, this, this) else ""} else "" +
                                if (photo.locality != Photo.NO_ADDRESS) ", @${photo.locality}" else "",
                        token = photo.id,
                        metadata = "${photo.albumId},"+ if (photo.orientation == 90 || photo.orientation == 270) "${photo.height},${photo.width}" else "${photo.width},${photo.height}",
                    ))
                }
            }
        }.start()
    }

    companion object {
        const val FROM_MUZEI_ALBUM = "FROM_MUZEI_ALBUM"
        const val FROM_MUZEI_PHOTO = "FROM_MUZEI_PHOTO"

        private const val PHOTOS_OF_TODAY = "PHOTOS_OF_TODAY"
    }
}