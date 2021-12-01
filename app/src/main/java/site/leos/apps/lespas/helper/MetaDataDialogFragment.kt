package site.leos.apps.lespas.helper

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.TableRow
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import site.leos.apps.lespas.R
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.publication.NCShareViewModel
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

class MetaDataDialogFragment : LesPasDialogFragment(R.layout.fragment_info_dialog) {
    private var mapIntent = Intent(Intent.ACTION_VIEW)
    private lateinit var map: MapView
    private lateinit var mapButton: MaterialButton

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<MaterialButton>(R.id.ok_button).setOnClickListener { dismiss() }
        mapButton = view.findViewById(R.id.map_button)

        map = view.findViewById<MapView>(R.id.map).apply {
            // TODO user setting?
            setUseDataConnection(true)
            setTileSource(TileSourceFactory.MAPNIK)
            isFlingEnabled = false
            overlays.add(CopyrightOverlay(requireContext()))

            // Enable map panning inside Scrollview
            setOnTouchListener { v, event ->
                when(event.action) {
                    MotionEvent.ACTION_DOWN-> v.parent.parent.requestDisallowInterceptTouchEvent(true)  // TODO if layout xml changed, do make sure we get hold of the scrollview here
                    MotionEvent.ACTION_UP-> v.parent.parent.requestDisallowInterceptTouchEvent(false)
                }

                false
            }
        }
        // Don't abuse map tile source
        Configuration.getInstance().userAgentValue = BuildConfig.APPLICATION_ID

        try {
            var id = ""
            var name = ""
            var dateString = ""
            var widthString = ""
            var heightString = ""
            //var eTag = ""
            var size = 0L
            var local = true

            requireArguments().getParcelable<Photo>(KEY_MEDIA)?.apply {
                id = this.id
                name = this.name
                dateString = this.dateTaken.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT, FormatStyle.SHORT))
                widthString = this.width.toString()
                heightString = this.height.toString()
                //eTag = this.eTag

                local = true
            } ?: run {
                requireArguments().getParcelable<NCShareViewModel.RemotePhoto>(KEY_REMOTE_MEDIA)?.also { media->
                    id = media.fileId
                    name = media.path.substringAfterLast('/')
                    dateString = LocalDateTime.ofInstant(Instant.ofEpochSecond(media.timestamp), ZoneOffset.systemDefault()).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT, FormatStyle.SHORT))
                    widthString = media.width.toString()
                    heightString = media.height.toString()

                    local = false
                }
            }

            view.findViewById<TextView>(R.id.info_filename).text = name
            view.findViewById<TextView>(R.id.info_shotat).text = dateString

            lifecycleScope.launch(Dispatchers.IO) {
                var exif: ExifInterface? = null
                if (local) {
                    with(if (File("${Tools.getLocalRoot(requireContext())}/${id}").exists()) "${Tools.getLocalRoot(requireContext())}/${id}" else "${Tools.getLocalRoot(requireContext())}/${name}") {
                    //with("${Tools.getLocalRoot(requireContext())}/${if (eTag.isNotEmpty()) id else name}") {
                        size = File(this).length()
                        exif = try { ExifInterface(this) } catch (e: Exception) { null }
                    }
                }
                else {
                    (ViewModelProvider(requireActivity()).get(NCShareViewModel::class.java).getMediaExif(requireArguments().getParcelable(KEY_REMOTE_MEDIA)!!))?.also {
                        exif = it.first
                        size = it.second
                    }
                }

                withContext(Dispatchers.Main) {
                    view.findViewById<TextView>(R.id.info_size).text = if (size == 0L) String.format("%sw × %sh", widthString, heightString) else String.format("%s, %s", Tools.humanReadableByteCountSI(size), String.format("%sw × %sh", widthString, heightString))
                    view.findViewById<TableRow>(R.id.size_row).visibility = View.VISIBLE

                    exif?.apply {
                        var t = getAttribute(ExifInterface.TAG_MAKE)?.substringBefore(" ") ?: ""
                        if (t.isNotEmpty()) {
                            view.findViewById<TableRow>(R.id.mfg_row).visibility = View.VISIBLE
                            view.findViewById<TextView>(R.id.info_camera_mfg).text = t
                        }

                        t = (getAttribute(ExifInterface.TAG_MODEL)?.trim() ?: "") + (getAttribute(ExifInterface.TAG_LENS_MODEL)?.let { "\n${it.trim()}" } ?: "")
                        if (t.isNotEmpty()) {
                            view.findViewById<TableRow>(R.id.model_row).visibility = View.VISIBLE
                            view.findViewById<TextView>(R.id.info_camera_model).text = t
                        }

                        t = (getAttribute(ExifInterface.TAG_FOCAL_LENGTH)?.let { "${it.substringBefore("/").toInt() / it.substringAfter("/").toInt()}mm  " } ?: "") +
                                (getAttribute(ExifInterface.TAG_F_NUMBER)?.let { "f$it  " } ?: "") +
                                (getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.let {
                                    val exp = it.toFloat()
                                    if (exp < 1) "1/${(1 / it.toFloat()).roundToInt()}s  " else "${exp.roundToInt()}s  "
                                } ?: "") +
                                (getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.let { "ISO$it" } ?: "")
                        if (t.trim().isNotEmpty()) {
                            view.findViewById<TableRow>(R.id.param_row).visibility = View.VISIBLE
                            view.findViewById<TextView>(R.id.info_parameter).text = t
                        }

                        t = getAttribute((ExifInterface.TAG_ARTIST)) ?: ""
                        if (t.isNotEmpty()) {
                            view.findViewById<TableRow>(R.id.artist_row).visibility = View.VISIBLE
                            view.findViewById<TextView>(R.id.info_artist).text = t
                        }

                        // View in map button
                        val longitudeRef: String?
                        val latitude: String?
                        val latitudeRef: String?
                        val longitude = getAttribute(ExifInterface.TAG_GPS_LONGITUDE) ?: getAttribute(ExifInterface.TAG_GPS_DEST_LONGITUDE)
                        longitude?.run {
                            val poi = GeoPoint(FAKE_COORDINATE, FAKE_COORDINATE)
                            longitudeRef = getAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF) ?: getAttribute(ExifInterface.TAG_GPS_DEST_LONGITUDE_REF)
                            latitude = getAttribute(ExifInterface.TAG_GPS_LATITUDE) ?: getAttribute(ExifInterface.TAG_GPS_DEST_LATITUDE)
                            latitudeRef = getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF) ?: getAttribute(ExifInterface.TAG_GPS_DEST_LATITUDE_REF)

                            val coordinatePattern = "(.*)/(.*),(.*)/(.*),(.*)/(.*)".toRegex()
                            coordinatePattern.matchEntire(longitude)?.destructured?.let { (d0, d1, m0, m1, s0, s1) ->
                                try { poi.longitude = d0.toDouble()/d1.toInt() + (m0.toDouble() / m1.toInt()) / 60 + (s0.toDouble() / s1.toInt()) / 3600 } catch (e: NumberFormatException) {}
                            }

                            if (poi.longitude != FAKE_COORDINATE) {
                                coordinatePattern.matchEntire(latitude ?: "1000/1,0/1,0/1")?.destructured?.let { (d0, d1, m0, m1, s0, s1) ->
                                    try { poi.latitude = d0.toDouble()/d1.toInt() + (m0.toDouble() / m1.toInt()) / 60 + (s0.toDouble() / s1.toInt()) / 3600 } catch (e: NumberFormatException) {}
                                }

                                if (poi.latitude != FAKE_COORDINATE) {
                                    if (longitudeRef == "W") poi.longitude = -poi.longitude
                                    if (latitudeRef == "S") poi.latitude = -poi.latitude

                                    with(map) {
                                        controller.setZoom(18.5)
                                        controller.setCenter(poi)
                                        Marker(this).let {
                                            it.position = poi
                                            it.icon = ContextCompat.getDrawable(this.context, R.drawable.ic_baseline_location_marker_24)
                                            this.overlays.add(it)
                                        }
                                        invalidate()

                                        isVisible = true
                                    }

                                    mapIntent.data = Uri.parse("geo:${poi.latitude},${poi.longitude}?z=22")
                                    mapIntent.resolveActivity(requireActivity().packageManager)?.let {
                                        mapButton.apply {
                                            setOnClickListener {
                                                startActivity(mapIntent)
                                                dismiss()
                                            }

                                            isVisible = true
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (e:Exception) { e.printStackTrace() }
    }

    companion object {
        const val KEY_MEDIA = "KEY_MEDIA"
        const val KEY_REMOTE_MEDIA = "KEY_REMOTE_MEDIA"

        private const val FAKE_COORDINATE = 1000.0

        @JvmStatic
        fun newInstance(media: Photo) = MetaDataDialogFragment().apply { arguments = Bundle().apply { putParcelable(KEY_MEDIA, media) }}

        @JvmStatic
        fun newInstance(media: NCShareViewModel.RemotePhoto) = MetaDataDialogFragment().apply { arguments = Bundle().apply { putParcelable(KEY_REMOTE_MEDIA, media) }}
    }
}