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

package site.leos.apps.lespas.gpx

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.exifinterface.media.ExifInterface
import androidx.fragment.app.viewModels
import androidx.lifecycle.*
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.osmdroid.bonuspack.location.GeocoderNominatim
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.advancedpolyline.ColorMapping
import org.osmdroid.views.overlay.advancedpolyline.MonochromaticPaintList
import org.osmdroid.views.overlay.advancedpolyline.PolychromaticPaintList
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import site.leos.apps.lespas.BuildConfig
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.Album
import site.leos.apps.lespas.helper.LesPasDialogFragment
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.helper.Tools.parcelable
import site.leos.apps.lespas.helper.Tools.parcelableArray
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.photo.PhotoRepository
import site.leos.apps.lespas.publication.NCShareViewModel
import site.leos.apps.lespas.sync.Action
import site.leos.apps.lespas.sync.ActionRepository
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.util.*
import kotlin.math.abs

class GPXImportDialogFragment: LesPasDialogFragment(R.layout.fragment_gpx_import_dialog) {
    private lateinit var overwriteCheckBox: CheckBox
    private lateinit var dstCheckBox: CheckBox
    private lateinit var offsetEditText: TextInputEditText
    private lateinit var okButton: MaterialButton
    private lateinit var mapView: MapView
    private lateinit var invalidTextView: TextView

    private val track = Polyline()
    private lateinit var trackColor: TrackPaintList
    private val trackPoints = mutableListOf<GPXTrackPoint>()

    private val taggingViewModel: GeoTaggingViewModel by viewModels()

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        invalidTextView = view.findViewById(R.id.invalid_gpx)
        mapView = view.findViewById<MapView>(R.id.map).apply {
            if (this.context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) overlayManager.tilesOverlay.setColorFilter(
                ColorMatrixColorFilter(floatArrayOf(
                    1.1f, 0f, 0f, 0f, -72f,  // red, reduce brightness about 1/4, increase contrast by 5%
                    0f, 1.1f, 0f, 0f, -72f,  // green, reduce brightness about 1/4, increase contrast by 5%
                    0f, 0f, 1.1f, 0f, -72f,  // blue, reduce brightness about 1/4, increase contrast by 5%
                    0f, 0f, 0f, 1f, 0f,
                )
            ))
            setMultiTouchControls(true)
            setUseDataConnection(true)
            overlays.add(CopyrightOverlay(requireContext()))
            setTileSource(TileSourceFactory.MAPNIK)
            setOnTouchListener { v, _ ->
                hideSoftKeyboard(v)
                false
            }

            // Don't abuse map tile source
            org.osmdroid.config.Configuration.getInstance().userAgentValue = BuildConfig.APPLICATION_ID
        }

        overwriteCheckBox = view.findViewById<CheckBox?>(R.id.overwrite).apply { setOnClickListener { hideSoftKeyboard(it) }}
        dstCheckBox = view.findViewById<CheckBox?>(R.id.dst).apply { setOnClickListener { hideSoftKeyboard(it) }}
        offsetEditText = view.findViewById<TextInputEditText>(R.id.offset_textinputedittext).apply { setOnFocusChangeListener { v, hasFocus -> if (!hasFocus) hideSoftKeyboard(v) }}
        view.findViewById<MaterialButton>(R.id.cancel_button).setOnClickListener {
            hideSoftKeyboard(it)
            taggingViewModel.stop()
            dismiss()
        }
        okButton = view.findViewById<MaterialButton>(R.id.ok_button).apply {
            setOnClickListener {
                if (text == getString(R.string.button_text_tag)) {
                    hideSoftKeyboard(it)
                    isEnabled = false
                    overwriteCheckBox.isEnabled = false
                    dstCheckBox.isEnabled = false
                    offsetEditText.isEnabled = false

                    taggingViewModel.run(requireActivity().application, requireArguments().parcelable(ALBUM)!!, requireArguments().parcelableArray<Photo>(PHOTOS)!!.toList(), trackPoints, overwriteCheckBox.isChecked, if (dstCheckBox.isChecked) 3600000L else 0L, offsetEditText.text.toString().toInt() * 60000L, ViewModelProvider(requireActivity())[NCShareViewModel::class.java])
                } else dismiss()
            }
        }

        taggingViewModel.isRunning()?.let { isRunning ->
            if (isRunning) okButton.isEnabled = false
            else setStatusDone()
        }

        trackColor = TrackPaintList(Tools.getAttributeColor(requireContext(), androidx.appcompat.R.attr.colorPrimary))
        showTrack(savedInstanceState == null)

        viewLifecycleOwner.lifecycleScope.launch {
            taggingViewModel.progress.collect {
                if (it == trackPoints.size) setStatusDone()
                else {
                    // Show tagging progress on track
                    trackColor.setCurrentIndex(it)
                    mapView.invalidate()
                }
            }
        }
    }

    private fun showTrack(animateZoom: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            // Gather track points from GPX file
            try {
                requireContext().contentResolver.openInputStream(requireArguments().parcelable(GPX_FILE)!!)?.use {
                    var trackPoint = GPXTrackPoint()
                    var text = ""

                    val parser = XmlPullParserFactory.newInstance().newPullParser()
                    //parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                    parser.setInput(it.bufferedReader())
                    while (parser.next() != XmlPullParser.END_DOCUMENT) {
                        when (parser.eventType) {
                            XmlPullParser.START_TAG -> {
                                when (parser.name) {
                                    // New way point
                                    "trkpt", "wpt" -> {
                                        trackPoint = GPXTrackPoint(
                                            longitude = try {
                                                parser.getAttributeValue(null, "lon")?.toDouble()?.let { value ->
                                                    if (value in -180.0..180.0) value else Photo.NO_GPS_DATA
                                                } ?: run { Photo.NO_GPS_DATA }
                                            } catch (e: java.lang.NumberFormatException) {
                                                Photo.NO_GPS_DATA
                                            },
                                            latitude = try {
                                                parser.getAttributeValue(null, "lat")?.toDouble()?.let { value ->
                                                    if (value in -90.0..90.0) value else Photo.NO_GPS_DATA
                                                } ?: run { Photo.NO_GPS_DATA }
                                            } catch (e: java.lang.NumberFormatException) { Photo.NO_GPS_DATA },
                                        )
                                    }
                                }
                            }
                            XmlPullParser.TEXT -> text = parser.text
                            XmlPullParser.END_TAG -> {
                                when (parser.name) {
                                    "ele" -> trackPoint.altitude = try { text.toDouble() } catch (e: NumberFormatException) { Photo.NO_GPS_DATA }
                                    "course" -> trackPoint.bearing = try { text.toDouble() } catch (e: NumberFormatException) { Photo.NO_GPS_DATA }
                                    "time" -> trackPoint.timeStamp = try {
                                        // Automatically adjust track point's timestamp base on track point's timezone offset. Java Timezone library DST data is not reliable, for example, Egypt observers DST during summer time but is not correctly reported
                                        Instant.parse(text).toEpochMilli() +
                                            if (trackPoint.latitude != Photo.NO_GPS_DATA && trackPoint.longitude != Photo.NO_GPS_DATA) TimeZone.getTimeZone(TimezoneMapper.latLngToTimezoneString(trackPoint.latitude, trackPoint.longitude)).rawOffset
                                            else 0
                                    } catch (_: Exception) { 0L }
                                    "cmt" -> trackPoint.caption = text
                                    "trkpt", "wpt" -> {
                                        // Only way point with timestamp is needed
                                        if (trackPoint.timeStamp != 0L && trackPoint.latitude != Photo.NO_GPS_DATA && trackPoint.longitude != Photo.NO_GPS_DATA) trackPoints.add(trackPoint)
                                        text = ""
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }

            if (trackPoints.isEmpty()) {
                // If GPX file is not valid or does not contain any way point with timestamp
                withContext(Dispatchers.Main) {
                    invalidTextView.run {
                        var fileName = ""
                        try {
                            requireContext().contentResolver.query(requireArguments().parcelable(GPX_FILE)!!, null, null, null, null)?.use {
                                val columnIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                it.moveToFirst()
                                if (columnIndex != -1) try { fileName = it.getString(columnIndex) } catch (_: Exception) {}
                            }
                        } catch (_: Exception) {}
                        text = getString(R.string.msg_invalid_gpx_file, fileName)
                        isVisible = true
                    }
                    mapView.controller.run {
                        setCenter(GeoPoint(0.0, 0.0))
                        setZoom(19.0)
                    }
                    mapView.invalidate()
                    overwriteCheckBox.isEnabled = false
                    dstCheckBox.isEnabled = false
                    offsetEditText.isEnabled = false
                    okButton.isEnabled = false
                }
            } else {
                trackPoints.sortBy { it.timeStamp }

                // Populate map view
                val points = mutableListOf<GeoPoint>()
                trackPoints.forEach { trackPoint -> points.add(GeoPoint(trackPoint.latitude, trackPoint.longitude, trackPoint.altitude)) }
                track.apply {
                    setPoints(points)

                    outlinePaintLists.add(MonochromaticPaintList(
                        Paint().apply {
                            color = ContextCompat.getColor(requireContext(), R.color.lespas_white)
                            strokeWidth = 20f
                            strokeCap = Paint.Cap.ROUND
                            isAntiAlias = true
                            style = Paint.Style.FILL_AND_STROKE
                        }
                    ))
                    outlinePaintLists.add(
                        PolychromaticPaintList(
                            Paint().apply {
                                strokeWidth = 14f
                                strokeCap = Paint.Cap.ROUND
                                isAntiAlias = true
                                style = Paint.Style.FILL
                            },
                            trackColor.apply { setCurrentIndex(if (taggingViewModel.isRunning() == true) 0 else points.size) },
                            false
                        )
                    )
                }
                mapView.overlays.add(track)
                mapView.invalidate()

                // Zoom to bounding box, animate if it's first run
                if (points.isNotEmpty()) withContext(Dispatchers.Main) { mapView.zoomToBoundingBox(track.bounds, animateZoom, 100, MAXIMUM_ZOOM, 800) }
            }
        }
    }

    private fun hideSoftKeyboard(view: View) { (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).run { hideSoftInputFromWindow(view.windowToken, 0) }}
    private fun setStatusDone() {
        okButton.text = getString(R.string.button_text_done)
        okButton.isEnabled = true
        overwriteCheckBox.isEnabled = true
        dstCheckBox.isEnabled = true
        offsetEditText.isEnabled = true
    }

    class GeoTaggingViewModel: ViewModel() {
        private val _progress = MutableSharedFlow<Int>(0)
        val progress: SharedFlow<Int> = _progress

        private var job: Job? = null

        fun run(context: Application, album: Album, photos: List<Photo>, trackPoints: List<GPXTrackPoint>, overwrite: Boolean, dstOffset: Long, diffAllowed: Long, ncModel: NCShareViewModel) {
            job = viewModelScope.launch(Dispatchers.IO) {
                val localLesPasFolder = Tools.getLocalRoot(context)
                val remoteLesPasFolder = "${Tools.getRemoteHome(context)}/${album.name}"
                val actions = mutableListOf<Action>()
                val updatedPhotos = mutableListOf<Photo>()
                val photoRepository = PhotoRepository(context)
                val actionRepository = ActionRepository(context)
                val nominatim = GeocoderNominatim(Locale.getDefault(), BuildConfig.APPLICATION_ID)

                var startWith = 0
                var match: Int
                var diff: Long
                var minDiff: Long
                var takenTime: Long
                val minDate = trackPoints.first().timeStamp + dstOffset
                val maxDate = trackPoints.last().timeStamp + dstOffset

                run outer@{
                    photos.sortedBy { it.dateTaken }.forEach { photo ->
                        // GPS location data won't work on playable media
                        if (Tools.isMediaPlayable(photo.mimeType)) return@forEach

                        // If picture's taken date doesn't fall into track points period, continue to next picture or quit
                        takenTime = photo.dateTaken.toInstant(ZoneOffset.UTC).toEpochMilli()
                        if (takenTime < minDate) return@forEach
                        if (takenTime > maxDate) return@outer

                        // If photo does not have location data yet or user choose to overwrite existing ones
                        if (overwrite || photo.latitude == Photo.NO_GPS_DATA) {

                            // Try finding the nearest match in GPX
                            match = NO_MATCH
                            minDiff = Long.MAX_VALUE

                            for (i in startWith until trackPoints.size) {
                                diff = abs(takenTime - trackPoints[i].timeStamp - dstOffset)
                                if (diff < diffAllowed && diff < minDiff) {
                                    minDiff = diff
                                    match = i
                                }
                            }

                            if (match != NO_MATCH) {
                                _progress.emit(match)

                                val targetFile = File(localLesPasFolder, photo.name)
                                ensureActive()
                                try {
                                    // Update EXIF
                                    if (Tools.isRemoteAlbum(album)) {
                                        // For remote album's uploaded photo, download original
                                        if (photo.eTag != Photo.ETAG_NOT_YET_UPLOADED) ncModel.downloadFile("${remoteLesPasFolder}/${photo.name}", targetFile)

                                        // TODO race condition if file is being uploaded to server
                                        try { ExifInterface(targetFile) } catch (_: OutOfMemoryError) { null }?.run {
                                            setLatLong(trackPoints[match].latitude, trackPoints[match].longitude)
                                            if (trackPoints[match].altitude != Photo.NO_GPS_DATA) setAltitude(trackPoints[match].altitude)
                                            saveAttributes()
                                        }
                                    } else {
                                        // For local album, update local photo directly so that MetaDataDialogFragment will show updated information immediately
                                        val sourceFile = File(localLesPasFolder, if (photo.eTag == Photo.ETAG_NOT_YET_UPLOADED) photo.name else photo.id)

                                        // TODO race condition if file is being uploaded to server
                                        try { ExifInterface(sourceFile) } catch (_: OutOfMemoryError) { null }?.run {
                                            setLatLong(trackPoints[match].latitude, trackPoints[match].longitude)
                                            if (trackPoints[match].altitude != Photo.NO_GPS_DATA) setAltitude(trackPoints[match].altitude)
                                            saveAttributes()
                                        }

                                        if (photo.eTag != Photo.ETAG_NOT_YET_UPLOADED) {
                                            // File need to be located in lespas private storage for Action.ACTION_ADD_FILES_ON_SERVER to work
                                            sourceFile.inputStream().use { source ->
                                                targetFile.outputStream().use { target ->
                                                    source.copyTo(target, 8192)
                                                }
                                            }
                                        }
                                    }

                                    // Update local database
                                    // Geocoding
                                    ensureActive()
                                    try { nominatim.getFromLocation(trackPoints[match].latitude, trackPoints[match].longitude, 1) } catch (_: Exception) { null }?.get(0)?.let {
                                        if (it.countryName != null) {
                                            photo.locality = it.locality ?: it.adminArea ?: Photo.NO_ADDRESS
                                            photo.country = it.countryName
                                            photo.countryCode = it.countryCode ?: Photo.NO_ADDRESS
                                        } else {
                                            photo.locality = Photo.NO_ADDRESS
                                            photo.country = Photo.NO_ADDRESS
                                            photo.countryCode = Photo.NO_ADDRESS
                                        }
                                    }
                                    photo.latitude = trackPoints[match].latitude
                                    photo.longitude = trackPoints[match].longitude
                                    photo.altitude = trackPoints[match].altitude
                                    //photo.bearing = trackPoints[match].bearing

                                    // Prepare batch update list
                                    updatedPhotos.add(photo)
                                    actions.add(Action(null, Action.ACTION_ADD_FILES_ON_SERVER, photo.mimeType, album.name, photo.id, photo.name, System.currentTimeMillis(), album.shareId))
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }

                                startWith = match
                            }
                        }
                    }
                }

                // Local DB batch update and upload modified image file to server
                if (updatedPhotos.isNotEmpty()) photoRepository.upsert(updatedPhotos)
                if (actions.isNotEmpty()) {
                    actionRepository.addActions(actions)
                    // Remove remote album cache, so that the updated image file could be retrieve in MetaDataDialogFragment
                    if (Tools.isRemoteAlbum(album)) File("${Tools.getLocalRoot(context)}/cache").deleteRecursively()
                }

                // Signaling end of progress
                _progress.emit(trackPoints.size)
            }
        }

        fun isRunning(): Boolean? = job?.isActive
        fun stop() { job?.cancel() }

        override fun onCleared() {
            job?.cancel()
            super.onCleared()
        }
    }

    class TrackPaintList(private val fillColor: Int): ColorMapping {
        private var currentIndex = 0
        fun setCurrentIndex(index: Int) { currentIndex = index }

        override fun getColorForIndex(pSegmentIndex: Int): Int = if (pSegmentIndex < currentIndex) fillColor else 0
    }

    data class GPXTrackPoint (
        var latitude: Double = Photo.NO_GPS_DATA,
        var longitude: Double = Photo.NO_GPS_DATA,
        var altitude: Double = Photo.NO_GPS_DATA,
        var bearing: Double = Photo.NO_GPS_DATA,
        var timeStamp: Long = 0L,
        var caption: String = "",
    )

    companion object {
        private const val NO_MATCH = -1
        private const val MAXIMUM_ZOOM = 19.5

        private const val GPX_FILE = "GPX_FILE"
        private const val ALBUM = "ALBUM"
        private const val PHOTOS = "PHOTOS"

        @JvmStatic
        fun newInstance(gpxFile: Uri, album: Album, photos: List<Photo>) = GPXImportDialogFragment().apply {
            arguments = Bundle().apply {
                putParcelable(GPX_FILE, gpxFile)
                putParcelable(ALBUM, album)
                putParcelableArray(PHOTOS, photos.toTypedArray())
            }
        }
    }
}