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

package site.leos.apps.lespas.helper

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration.UI_MODE_NIGHT_MASK
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import android.graphics.ColorMatrixColorFilter
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TableRow
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.exifinterface.media.ExifInterface
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.*
import androidx.lifecycle.Observer
import androidx.preference.PreferenceManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import site.leos.apps.lespas.BuildConfig
import site.leos.apps.lespas.R
import site.leos.apps.lespas.cameraroll.CameraRollFragment
import site.leos.apps.lespas.helper.Tools.parcelable
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.publication.NCShareViewModel
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.*
import kotlin.math.roundToInt

class MetaDataDialogFragment : LesPasDialogFragment(R.layout.fragment_info_dialog, 0.8f) {
    private var mapIntent = Intent(Intent.ACTION_VIEW)
    private lateinit var mapView: MapView
    private lateinit var mapButton: MaterialButton
    private lateinit var photo: NCShareViewModel.RemotePhoto
    private lateinit var exifModel: ExifModel
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var waitingMsg: Snackbar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        //requireArguments().getParcelable<Photo>(KEY_MEDIA)?.let { photo = NCShareViewModel.RemotePhoto(it, "") }
        //requireArguments().getParcelable<NCShareViewModel.RemotePhoto>(KEY_REMOTE_MEDIA)?.let { photo = it }
        requireArguments().parcelable<Photo>(KEY_MEDIA)?.let { photo = NCShareViewModel.RemotePhoto(it, "") }
        requireArguments().parcelable<NCShareViewModel.RemotePhoto>(KEY_REMOTE_MEDIA)?.let { photo = it }
        exifModel = ViewModelProvider(this, ExifModelFactory(requireActivity(), photo))[ExifModel::class.java]
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<MaterialButton>(R.id.ok_button).setOnClickListener { dismiss() }
        mapButton = view.findViewById(R.id.map_button)
        mapView = view.findViewById(R.id.map)
        // Don't abuse map tile source
        Configuration.getInstance().userAgentValue = BuildConfig.APPLICATION_ID

        // Show basic information
        view.findViewById<TextView>(R.id.info_filename).text = photo.photo.name.substringAfterLast("/")
        view.findViewById<TextView>(R.id.info_shotat).text = String.format("%s %s", photo.photo.dateTaken.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()), photo.photo.dateTaken.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT, FormatStyle.MEDIUM)))

        exifModel.getPhotoMeta().observe(viewLifecycleOwner, Observer { photoMeta ->
            handler.removeCallbacksAndMessages(null)
            if (waitingMsg.isShownOrQueued) waitingMsg.dismiss()

            with(photoMeta.photo) {
                // Size row
                val pWidth: Int
                val pHeight: Int
                if (orientation == 90 || orientation == 270) {
                    pWidth = height
                    pHeight = width
                } else {
                    pWidth = width
                    pHeight = height
                }
                view.findViewById<TextView>(R.id.info_size).text = if (photoMeta.size == 0L) String.format("%sw × %sh", "$pWidth", "$pHeight") else String.format("%s, %s", Tools.humanReadableByteCountSI(photoMeta.size), String.format("%sw × %sh", "$pWidth", "$pHeight"))
                view.findViewById<TableRow>(R.id.size_row).visibility = View.VISIBLE

                if (photoMeta.mfg.isNotEmpty()) {
                    view.findViewById<TableRow>(R.id.mfg_row).visibility = View.VISIBLE
                    view.findViewById<TextView>(R.id.info_camera_mfg).text = photoMeta.mfg
                }
                if (photoMeta.model.isNotEmpty()) {
                    view.findViewById<TableRow>(R.id.model_row).visibility = View.VISIBLE
                    view.findViewById<TextView>(R.id.info_camera_model).text = photoMeta.model
                }
                if (photoMeta.params.trim().isNotEmpty()) {
                    view.findViewById<TableRow>(R.id.param_row).visibility = View.VISIBLE
                    view.findViewById<TextView>(R.id.info_parameter).text = photoMeta.params
                }
                if (photoMeta.artist.isNotEmpty()) {
                    view.findViewById<TableRow>(R.id.artist_row).visibility = View.VISIBLE
                    view.findViewById<TextView>(R.id.info_artist).text = photoMeta.artist
                }
                photoMeta.date?.let { view.findViewById<TextView>(R.id.info_shotat).text = String.format("%s %s", it.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()), it.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT, FormatStyle.MEDIUM))) }

                try {
                    if (latitude != Photo.NO_GPS_DATA) {
                        with(mapView) {
                            // Initialization
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
                            if (this.context.resources.configuration.uiMode and UI_MODE_NIGHT_MASK == UI_MODE_NIGHT_YES) {
                                overlayManager.tilesOverlay.setColorFilter(ColorMatrixColorFilter(
                                    floatArrayOf(
                                        1.05f, 0f, 0f, 0f, -72f,  // red, reduce brightness about 1/4, increase contrast by 5%
                                        0f, 1.05f, 0f, 0f, -72f,  // green, reduce brightness about 1/4, reduced contrast by 5%
                                        0f, 0f, 1.05f, 0f, -72f,  // blue, reduce brightness about 1/4, reduced contrast by 5%
                                        0f, 0f, 0f, 1f, 0f,
                                    )
                                ))
                            }
                            invalidate()

                            isVisible = true
                        }

                        mapIntent.data = Uri.parse(
                            if (PreferenceManager.getDefaultSharedPreferences(requireContext()).getBoolean(getString(R.string.chinese_map_pref_key), false))
                                Tools.wGS84ToGCJ02(doubleArrayOf(latitude, longitude)).let { "geo:${it[0]},${it[1]}?z=20" }
                            else "geo:${latitude},${longitude}?z=20"
                        )
                        mapIntent.resolveActivity(requireActivity().packageManager)?.let {
                            mapButton.apply {
                                setOnClickListener {
                                    startActivity(mapIntent)
                                    dismiss()
                                }

                                isVisible = true
                            }
                        }
                        // TODO use map text overlay instead
                        if (locality.isNotEmpty() && country.isNotEmpty()) {
                            view.findViewById<TextView>(R.id.locality).run {
                                visibility = View.VISIBLE
                                text = String.format("%s, %s", locality, country)
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        })
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        // Show a SnackBar if it takes too long (more than 500ms) preparing shares
        waitingMsg = Snackbar.make(requireActivity().window.decorView, getString(R.string.msg_downloading_exif), Snackbar.LENGTH_INDEFINITE)
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ waitingMsg.show() }, 500)

        return super.onCreateView(inflater, container, savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacksAndMessages(null)
        if (waitingMsg.isShownOrQueued) waitingMsg.dismiss()
    }

    @Suppress("UNCHECKED_CAST")
    private class ExifModelFactory(private val context: FragmentActivity, private val photo: NCShareViewModel.RemotePhoto): ViewModelProvider.NewInstanceFactory() {
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ExifModel(context, photo) as T
    }

    private class ExifModel(context: FragmentActivity, rPhoto: NCShareViewModel.RemotePhoto): ViewModel() {
        val photoMeta = MutableLiveData<PhotoMeta>()

        init {
            viewModelScope.launch(Dispatchers.IO) {
                val pm = PhotoMeta(rPhoto.photo)
                var exif: ExifInterface? = null

                try {
                    if (rPhoto.remotePath.isEmpty()) {
                        if (rPhoto.photo.albumId != CameraRollFragment.FROM_CAMERA_ROLL) {
                            // Media in album
                            val fPath = Tools.getLocalRoot(context)
                            with(if (File("${fPath}/${rPhoto.photo.id}").exists()) "${fPath}/${rPhoto.photo.id}" else "${fPath}/${rPhoto.photo.name}") {
                                pm.size = File(this).length()
                                if (Tools.hasExif(rPhoto.photo.mimeType)) exif = try { ExifInterface(this) } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }
                            }
                        } else {
                            // Media from camera roll
                            pm.size = rPhoto.photo.shareId.toLong()
                            val pUri = Uri.parse(rPhoto.photo.id)
                            if (Tools.hasExif(rPhoto.photo.mimeType)) {
                                exif = try {
                                    (context.contentResolver.openInputStream(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.setRequireOriginal(pUri) else pUri))
                                } catch (e: SecurityException) {
                                    context.contentResolver.openInputStream(pUri)
                                } catch (e: UnsupportedOperationException) {
                                    context.contentResolver.openInputStream(pUri)
                                }?.use { try { ExifInterface(it) } catch (_: OutOfMemoryError) { null }}
                            } else {
                                if (rPhoto.photo.mimeType.startsWith("video/")) {
                                    MediaMetadataRetriever().run {
                                        try {
                                            setDataSource(context, pUri)
                                            Tools.getVideoDateAndLocation(this, rPhoto.photo.name).let {
                                                pm.date = it.first
                                                pm.photo.latitude = it.second[0]
                                                pm.photo.longitude = it.second[1]
                                            }
                                        } catch (_: SecurityException) {}
                                        release()
                                    }
                                }
                            }
                        }
                    } else {
                        (ViewModelProvider(context))[NCShareViewModel::class.java].getMediaExif(rPhoto)?.let {
                            exif = it.first
                            pm.size = it.second
                        }
                    }

                    exif?.run {
                        pm.mfg = getAttribute(ExifInterface.TAG_MAKE)?.substringBefore(" ") ?: ""
                        pm.model = (getAttribute(ExifInterface.TAG_MODEL)?.trim() ?: "") + (getAttribute(ExifInterface.TAG_LENS_MODEL)?.let { "\n${it.trim()}" } ?: "")
                        pm.params = ((getAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM) ?: getAttribute(ExifInterface.TAG_FOCAL_LENGTH))?.let { "${it.substringBefore("/").toInt() / it.substringAfter("/", "1").toInt()}mm  " } ?: "") +
                                (getAttribute(ExifInterface.TAG_F_NUMBER)?.let { "f$it  " } ?: "") +
                                (getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.let {
                                    val exp = it.toFloat()
                                    if (exp < 1) "1/${(1 / it.toFloat()).roundToInt()}s  " else "${exp.roundToInt()}s  "
                                } ?: "") +
                                (getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.let { "ISO$it" } ?: "")
                        pm.artist = getAttribute((ExifInterface.TAG_ARTIST)) ?: ""

                        latLong?.let {
                            pm.photo.latitude = it[0]
                            pm.photo.longitude = it[1]
                        }
                        pm.date = Tools.getImageTakenDate(this, applyTZOffset = true)
                    }

                    photoMeta.postValue(pm)

                } catch (_: Exception) {}
            }
        }

        fun getPhotoMeta(): LiveData<PhotoMeta> = photoMeta
    }

    private data class PhotoMeta(
        val photo: Photo,
        var size: Long = 0L,
        var mfg: String = "",
        var model: String = "",
        var params: String = "",
        var artist: String = "",
        var date: LocalDateTime? = null,
    )

    companion object {
        const val KEY_MEDIA = "KEY_MEDIA"
        const val KEY_REMOTE_MEDIA = "KEY_REMOTE_MEDIA"

        @JvmStatic
        fun newInstance(media: Photo) = MetaDataDialogFragment().apply { arguments = Bundle().apply { putParcelable(KEY_MEDIA, media) }}

        @JvmStatic
        fun newInstance(media: NCShareViewModel.RemotePhoto) = MetaDataDialogFragment().apply { arguments = Bundle().apply { putParcelable(KEY_REMOTE_MEDIA, media) }}
    }
}