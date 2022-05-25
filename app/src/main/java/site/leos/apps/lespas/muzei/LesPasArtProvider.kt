package site.leos.apps.lespas.muzei

import android.accounts.AccountManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.os.Build
import android.util.DisplayMetrics
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.*
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
        var sWidth: Int
        var sHeight: Int
        val pWidth: Int
        val pHeight: Int

        // Get screen real metrics
        val wm = context!!.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            with(DisplayMetrics()) {
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealMetrics(this)
                sWidth = widthPixels
                sHeight = heightPixels
            }
        } else {
            with(wm.maximumWindowMetrics.bounds) {
                sWidth = width()
                sHeight = height()
            }
        }

        // Adapt to original orientation
        val portrait = context!!.resources.getBoolean(R.bool.portrait_artwork)
        if ((portrait && sWidth > sHeight) || (!portrait && sWidth < sHeight)) {
            sWidth -= sHeight
            sHeight += sWidth
            sWidth = sHeight - sWidth
        }

        with(artwork.metadata!!.split(',')) {
            pWidth = this[1].toInt()
            pHeight = this[2].toInt()
        }

        // Center crop the picture
        val rect: Rect = if (sWidth.toFloat()/sHeight < pWidth.toFloat()/pHeight) {
            val left = ((pWidth - (pHeight.toFloat() * sWidth / sHeight)) / 2).toInt()
            Rect(left, 0, pWidth-left ,pHeight)
        } else {
            val top = ((pHeight - (pWidth.toFloat() * sHeight / sWidth)) / 2).toInt()
            Rect(0, top, pWidth, pHeight-top)
        }

        val out = ByteArrayOutputStream()
        with("${context!!.applicationContext.cacheDir}/${artwork.token!!}") {
            @Suppress("DEPRECATION")
            (if (Build.VERSION.SDK_INT > Build.VERSION_CODES.R) BitmapRegionDecoder.newInstance(this) else BitmapRegionDecoder.newInstance(this, false)).decodeRegion(rect, null).compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        return ByteArrayInputStream(out.toByteArray())

        //File(Tools.getLocalRoot(context!!), artwork.token!!).inputStream()
    }

    override fun getDescription(): String = lastAddedArtwork?.run { "$title" } ?: run { super.getDescription() }

    override fun getArtworkInfo(artwork: Artwork): PendingIntent? {
        val intent = Intent().apply {
            setClass(context!!, MainActivity::class.java)
            putExtra(FROM_MUZEI_PHOTO, artwork.token)
            putExtra(FROM_MUZEI_ALBUM, artwork.metadata!!.split(',')[0])
            flags = Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        return PendingIntent.getActivity(context!!, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun updateArtwork() {
        Thread {
            (context?.applicationContext as Application).let { application ->
                val sp = PreferenceManager.getDefaultSharedPreferences(application)
                val exclusion = try { sp.getStringSet(LesPasArtProviderSettingActivity.KEY_EXCLUSION_LIST, setOf<String>()) } catch (e: ClassCastException) { setOf<String>() }
                val albumRepository = AlbumRepository(application)
                PhotoRepository(application).getMuzeiArtwork(exclusion!!.toMutableList().apply { addAll(albumRepository.getAllHiddenAlbumIds()) }, application.resources.getBoolean(R.bool.portrait_artwork)).let { photoList ->
                    if (photoList.isEmpty()) null
                    else {
                        val today = LocalDate.now()
                        val random = Random(System.currentTimeMillis())
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
                                photoList.filter { p -> today.dayOfMonth == p.dateTaken.dayOfMonth && today.month == p.dateTaken.month }.let { hits ->
                                    when(hits.size) {
                                        0 -> photoList[random.nextInt(photoList.size)]
                                        1 -> hits[0]
                                        else -> {
                                            var index = random.nextInt(hits.size)
                                            lastAddedArtwork?.let {
                                                // Prevent from choosing the last one again
                                                while(hits[index].id == it.token) index = random.nextInt(hits.size)
                                            }
                                            hits[index]
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

                                    resourceRoot = "$serverRoot${application.getString(R.string.dav_files_endpoint)}$userName${application.getString(R.string.lespas_base_folder_name)}"
                                    webDav = OkHttpWebDav(
                                        userName,
                                        getUserData(accounts[0], application.getString(R.string.nc_userdata_secret)),
                                        serverRoot,
                                        getUserData(accounts[0], application.getString(R.string.nc_userdata_selfsigned)).toBoolean(),
                                        null,
                                        "LesPas_${application.getString(R.string.lespas_version)}",
                                        0,
                                    )
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
                        byline = "${photo.dateTaken.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())}, ${photo.dateTaken.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT))}",
                        token = photo.id,
                        metadata = "${photo.albumId},${photo.width},${photo.height}",
                    ))
                }
            }
        }.start()
    }

    companion object {
        const val FROM_MUZEI_ALBUM = "FROM_MUZEI_ALBUM"
        const val FROM_MUZEI_PHOTO = "FROM_MUZEI_PHOTO"
        const val UPDATE_CALL = "UPDATE_CALL"
    }
}