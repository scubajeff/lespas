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
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.*

class LesPasArtProvider: MuzeiArtProvider() {
    override fun onLoadRequested(initial: Boolean) {
        val lastAddedDate = lastAddedArtwork?.dateAdded ?: Date()

        if (initial || Date().time - lastAddedDate.time > 60000) {
            (context?.applicationContext as Application).let {
                val exclusionList = (PreferenceManager.getDefaultSharedPreferences(it).getStringSet(LesPasArtProviderSettingActivity.EXCLUSION_LIST, mutableSetOf<String>()) ?: mutableSetOf<String>()).toList()
                PhotoRepository(it).getMuzeiArtwork(exclusionList, it.resources.getBoolean(R.bool.portrait_artwork))?.also { photo ->
                    setArtwork(Artwork(title = AlbumRepository(it).getThisAlbum(photo.albumId)[0].name, token = photo.id, metadata = photo.albumId, byline = "${photo.dateTaken.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())}, ${photo.dateTaken.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT))}",))
                }
            }
        }
    }
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

    companion object {
        const val FROM_MUZEI_ALBUM = "FROM_MUZEI_ALBUM"
        const val FROM_MUZEI_PHOTO = "FROM_MUZEI_PHOTO"
    }
}