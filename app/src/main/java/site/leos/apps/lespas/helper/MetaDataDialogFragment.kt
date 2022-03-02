package site.leos.apps.lespas.helper

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration.UI_MODE_NIGHT_MASK
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
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
import org.osmdroid.views.overlay.TilesOverlay
import site.leos.apps.lespas.BuildConfig
import site.leos.apps.lespas.R
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.publication.NCShareViewModel
import java.io.File
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

class MetaDataDialogFragment : LesPasDialogFragment(R.layout.fragment_info_dialog) {
    private var mapIntent = Intent(Intent.ACTION_VIEW)
    private lateinit var mapView: MapView
    private lateinit var mapButton: MaterialButton
    private var photo: Photo? = null
    private var remotePhotoSharePath = ""
    private var size = 0L

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<MaterialButton>(R.id.ok_button).setOnClickListener { dismiss() }
        mapButton = view.findViewById(R.id.map_button)
        mapView = view.findViewById(R.id.map)
        // Don't abuse map tile source
        Configuration.getInstance().userAgentValue = BuildConfig.APPLICATION_ID

        try {
            requireArguments().getParcelable<Photo>(KEY_MEDIA)?.let {
                remotePhotoSharePath = ""
                photo = it
            }
            requireArguments().getParcelable<NCShareViewModel.RemotePhoto>(KEY_REMOTE_MEDIA)?.let {
                remotePhotoSharePath = it.path
                photo = it.photo
            }

            photo?.apply {
                view.findViewById<TextView>(R.id.info_filename).text = name
                view.findViewById<TextView>(R.id.info_shotat).text = dateTaken.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT, FormatStyle.SHORT))

                lifecycleScope.launch(Dispatchers.IO) {
                    var exif: ExifInterface? = null
                    if (remotePhotoSharePath.isEmpty()) {
                        if (albumId != ImageLoaderViewModel.FROM_CAMERA_ROLL) {
                            with(if (File("${Tools.getLocalRoot(requireContext())}/${id}").exists()) "${Tools.getLocalRoot(requireContext())}/${id}" else "${Tools.getLocalRoot(requireContext())}/${name}") {
                                //with("${Tools.getLocalRoot(requireContext())}/${if (eTag.isNotEmpty()) id else name}") {
                                size = File(this).length()
                                exif = try { ExifInterface(this) } catch (e: Exception) { null }
                            }
                        } else {
                            size = eTag.toLong()
                            exif = try {
                                (requireContext().contentResolver.openInputStream(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.setRequireOriginal(Uri.parse(id)) else Uri.parse(id)))
                            } catch (e: SecurityException) {
                                requireContext().contentResolver.openInputStream(Uri.parse(id))
                            } catch (e: UnsupportedOperationException) {
                                requireContext().contentResolver.openInputStream(Uri.parse(id))
                            }?.use { ExifInterface(it) }
                        }
                    } else {
                        (ViewModelProvider(requireActivity())[NCShareViewModel::class.java].getMediaExif(requireArguments().getParcelable(KEY_REMOTE_MEDIA)!!))?.also {
                            exif = it.first
                            size = it.second
                        }
                    }

                    withContext(Dispatchers.Main) {
                        view.findViewById<TextView>(R.id.info_size).text = if (size == 0L) String.format("%sw × %sh", "$width", "$height") else String.format("%s, %s", Tools.humanReadableByteCountSI(size), String.format("%sw × %sh", "$width", "$height"))
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

                            t = ((getAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM) ?: getAttribute(ExifInterface.TAG_FOCAL_LENGTH))?.let { "${it.substringBefore("/").toInt() / it.substringAfter("/", "1").toInt()}mm  " } ?: "") +
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

                            if (latitude != Photo.NO_GPS_DATA) {
                                with(mapView) {
                                    // Initialization
                                    // TODO user setting?
                                    setMultiTouchControls(true)
                                    setUseDataConnection(true)
                                    setTileSource(TileSourceFactory.MAPNIK)
                                    isFlingEnabled = false
                                    overlays.add(CopyrightOverlay(requireContext()))

                                    // Enable map panning inside Scrollview
                                    setOnTouchListener { v, event ->
                                        when (event.action) {
                                            MotionEvent.ACTION_DOWN -> v.parent.parent.requestDisallowInterceptTouchEvent(true)  // TODO if layout xml changed, do make sure we get hold of the scrollview here
                                            MotionEvent.ACTION_UP -> v.parent.parent.requestDisallowInterceptTouchEvent(false)
                                        }

                                        false
                                    }

                                    val poi = GeoPoint(latitude, longitude)
                                    controller.setZoom(18.5)
                                    controller.setCenter(poi)
                                    Marker(this).let {
                                        it.position = poi
                                        it.icon = ContextCompat.getDrawable(this.context, R.drawable.ic_baseline_location_marker_24)
                                        this.overlays.add(it)
                                    }
                                    if (this.context.resources.configuration.uiMode and UI_MODE_NIGHT_MASK == UI_MODE_NIGHT_YES) overlayManager.tilesOverlay.setColorFilter(TilesOverlay.INVERT_COLORS)
                                    invalidate()

                                    isVisible = true
                                }

                                mapIntent.data = Uri.parse("geo:${latitude},${longitude}?z=20")
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
            } ?: run {
                parentFragmentManager.popBackStack()
            }
        } catch (e:Exception) {
            e.printStackTrace()
            parentFragmentManager.popBackStack()
        }
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        mapView.onPause()
        super.onPause()
    }

    companion object {
        const val KEY_MEDIA = "KEY_MEDIA"
        const val KEY_REMOTE_MEDIA = "KEY_REMOTE_MEDIA"

        @JvmStatic
        fun newInstance(media: Photo) = MetaDataDialogFragment().apply { arguments = Bundle().apply { putParcelable(KEY_MEDIA, media) }}

        @JvmStatic
        fun newInstance(media: NCShareViewModel.RemotePhoto) = MetaDataDialogFragment().apply { arguments = Bundle().apply { putParcelable(KEY_REMOTE_MEDIA, media) }}
    }
}