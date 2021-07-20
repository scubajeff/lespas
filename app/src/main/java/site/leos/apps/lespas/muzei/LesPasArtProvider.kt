package site.leos.apps.lespas.muzei

import android.app.Application
import androidx.preference.PreferenceManager
import com.google.android.apps.muzei.api.provider.Artwork
import com.google.android.apps.muzei.api.provider.MuzeiArtProvider
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
                PhotoRepository(it).getMuzeiArtwork(PreferenceManager.getDefaultSharedPreferences(it).getString(LesPasArtProviderSettingActivity.EXCLUSION_LIST, "") ?: "")?.also { photo ->
                    setArtwork(
                        Artwork(
                            title = AlbumRepository(it).getThisAlbum(photo.albumId)[0].name,
                            byline = "${photo.dateTaken.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())}, ${photo.dateTaken.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT))}",
                            token = photo.id,
                        )
                    )
                }
            }
        }
    }

    override fun openFile(artwork: Artwork): InputStream = File(Tools.getLocalRoot(context!!), artwork.token!!).inputStream()
}