package site.leos.apps.lespas.muzei

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import androidx.preference.PreferenceManager
import com.google.android.apps.muzei.api.provider.Artwork
import com.google.android.apps.muzei.api.provider.MuzeiArtProvider
import site.leos.apps.lespas.MainActivity
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.AlbumRepository
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.photo.PhotoRepository
import java.io.File
import java.io.InputStream
import java.time.LocalDate
import java.time.Period
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.*
import kotlin.random.Random

class LesPasArtProvider: MuzeiArtProvider() {
    override fun onLoadRequested(initial: Boolean) { if (initial || Date().time - (lastAddedArtwork?.dateAdded ?: Date()).time > 30000) updateArtwork() }
    override fun openFile(artwork: Artwork): InputStream = File(Tools.getLocalRoot(context!!), artwork.token!!).inputStream()
    override fun getDescription(): String = lastAddedArtwork?.run { "$title" } ?: run { super.getDescription() }
    override fun getArtworkInfo(artwork: Artwork): PendingIntent? {
        val intent = Intent().apply {
            setClass(context!!, MainActivity::class.java)
            putExtra(FROM_MUZEI_PHOTO, artwork.token)
            putExtra(FROM_MUZEI_ALBUM, artwork.metadata)
        }

        return PendingIntent.getActivity(context!!, 0, intent, PendingIntent.FLAG_CANCEL_CURRENT)
    }

    private fun updateArtwork() {
        Thread {
            (context?.applicationContext as Application).let {
                val sp = PreferenceManager.getDefaultSharedPreferences(it)
                PhotoRepository(it).getMuzeiArtwork((sp.getStringSet(LesPasArtProviderSettingActivity.EXCLUSION_LIST_KEY, mutableSetOf<String>()) ?: mutableSetOf<String>()).toList(), it.resources.getBoolean(R.bool.portrait_artwork)).let { photoList ->
                    if (photoList.isEmpty()) null
                    else {
                        val today = LocalDate.now()
                        when (sp.getInt(LesPasArtProviderSettingActivity.PREFER_KEY, LesPasArtProviderSettingActivity.PREFER_RANDOM)) {
                            LesPasArtProviderSettingActivity.PREFER_LATEST -> {
                                photoList.filter { p -> Period.between(p.dateTaken.toLocalDate(), today).toTotalMonths() < 1 }.let { recentList ->
                                    when {
                                        recentList.size == 1 -> recentList[0]
                                        recentList.isNotEmpty() -> recentList[Random.nextInt(recentList.size - 1)]
                                        else -> photoList[Random.nextInt(photoList.size - 1)]
                                    }
                                }
                            }
                            LesPasArtProviderSettingActivity.PREFER_TODAY_IN_HISTORY -> {
                                photoList.filter { p -> today.dayOfMonth == p.dateTaken.dayOfMonth && today.month == p.dateTaken.month }.let { tih ->
                                    when {
                                        tih.size == 1 -> tih[0]
                                        tih.isNotEmpty() -> tih[Random.nextInt(tih.size - 1)]
                                        else -> photoList[Random.nextInt(photoList.size - 1)]
                                    }
                                }
                            }
                            else -> photoList[Random.nextInt(photoList.size - 1)]
                        }
                    }
                }?.let { photo -> setArtwork(Artwork(title = AlbumRepository(it).getThisAlbum(photo.albumId)[0].name, token = photo.id, metadata = photo.albumId, byline = "${photo.dateTaken.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())}, ${photo.dateTaken.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT))}",)) }
            }
        }.start()
    }

    companion object {
        const val FROM_MUZEI_ALBUM = "FROM_MUZEI_ALBUM"
        const val FROM_MUZEI_PHOTO = "FROM_MUZEI_PHOTO"
        const val UPDATE_CALL = "UPDATE_CALL"
    }
}