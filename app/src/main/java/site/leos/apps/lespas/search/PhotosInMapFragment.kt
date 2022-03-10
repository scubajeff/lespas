package site.leos.apps.lespas.search

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.*
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.ActionBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.*
import org.osmdroid.api.IGeoPoint
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.TilesOverlay
import org.osmdroid.views.overlay.infowindow.InfoWindow
import org.osmdroid.views.overlay.simplefastpoint.SimpleFastPointOverlay
import org.osmdroid.views.overlay.simplefastpoint.SimpleFastPointOverlayOptions
import org.osmdroid.views.overlay.simplefastpoint.SimplePointTheme
import site.leos.apps.lespas.BuildConfig
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.Album
import site.leos.apps.lespas.album.BGMDialogFragment
import site.leos.apps.lespas.cameraroll.CameraRollFragment
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.publication.NCShareViewModel
import java.io.File
import java.lang.Double.max
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.*
import kotlin.math.roundToInt

@androidx.annotation.OptIn(UnstableApi::class)
class PhotosInMapFragment: Fragment() {
    private var locality: String? = null
    private var country: String? = null
    private var albumNames: HashMap<String, String>? = null
    private var album: Album? = null
    private var remotePhoto: MutableList<NCShareViewModel.RemotePhoto>? = null
    private var poiBoundingBox: BoundingBox? = null

    private lateinit var rootPath: String

    private lateinit var mapView: MapView
    private val markerClickListener = MarkerClickListener()

    private lateinit var window: Window
    private lateinit var playMenuItem: MenuItem
    private var slideshowJob: Job? = null
    private var isSlideshowPlaying = false
    private var spaceHeight = 0

    private lateinit var muteMenuItem: MenuItem
    private var hasBGM = false
    private var isMuted = false
    private lateinit var bgmPlayer: ExoPlayer

    private val imageLoaderModel: NCShareViewModel by activityViewModels()
    private lateinit var lespasPath: String
    private var isLocalAlbum = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lespasPath = getString(R.string.lespas_base_folder_name)

        requireArguments().apply {
            locality = getString(KEY_LOCALITY)
            country = getString(KEY_COUNTRY)
            albumNames = getSerializable(KEY_ALBUM_NAMES) as HashMap<String, String>?
            album = getParcelable(KEY_ALBUM)
        }

        rootPath = Tools.getLocalRoot(requireContext())

        requireActivity().onBackPressedDispatcher.addCallback(this, object: OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                album?.let { if (hasBGM) fadeOutBGM(true) }
                closeAllInfoWindow()
                mapView.overlayManager.clear()
                mapView.controller.zoomTo(max(mapView.zoomLevelDouble - 5, 0.0), 400)
                Handler(Looper.getMainLooper()).postDelayed({
                    parentFragmentManager.popBackStack()
                }, 300)
            }
        })

        album?.run {
            setHasOptionsMenu(true)
            window = requireActivity().window
            bgmPlayer = ExoPlayer.Builder(requireContext()).build()
            bgmPlayer.run {
                repeatMode = ExoPlayer.REPEAT_MODE_ONE
                playWhenReady = true

                var bgmFile = "$rootPath/${album?.id}${BGMDialogFragment.BGM_FILE_SUFFIX}"
                if (File(bgmFile).exists()) setBGM(bgmFile)
                else {
                    // BGM for publication downloaded in cache folder
                    bgmFile = "${requireContext().cacheDir}/${album?.id}${BGMDialogFragment.BGM_FILE_SUFFIX}"
                    if (File(bgmFile).exists()) setBGM(bgmFile)
                }

                // Mute the video sound during late night hours
                with(LocalDateTime.now().hour) { if (this >= 22 || this < 7) isMuted = true }

                playerHandler = Handler(bgmPlayer.applicationLooper)
            }
            isLocalAlbum = !Tools.isRemoteAlbum(this)
            remotePhoto = mutableListOf()
            requireArguments().getParcelableArrayList<Photo>(KEY_PHOTOS)?.forEach { remotePhoto?.add(NCShareViewModel.RemotePhoto(it, if(isLocalAlbum) "" else "$lespasPath/${album!!.name}")) }
        }
    }

    private fun setBGM(bgmFile: String) {
        bgmPlayer.setMediaItem(MediaItem.fromUri("file://${bgmFile}"))
        hasBGM = true
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? = inflater.inflate(R.layout.fragment_photos_in_map, container, false)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        mapView = view.findViewById(R.id.map)
        org.osmdroid.config.Configuration.getInstance().userAgentValue = BuildConfig.APPLICATION_ID

        mapView.apply {
            if (this.context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) overlayManager.tilesOverlay.setColorFilter(TilesOverlay.INVERT_COLORS)
            setMultiTouchControls(true)
            setUseDataConnection(true)
            overlays.add(CopyrightOverlay(requireContext()))
            setTileSource(TileSourceFactory.MAPNIK)
        }

        mapView.doOnLayout {
            // Create POI markers
            lifecycleScope.launch(Dispatchers.IO) {
                var poi: GeoPoint
                val points = arrayListOf<IGeoPoint>()
                val pin = ContextCompat.getDrawable(mapView.context, R.drawable.ic_baseline_location_marker_24)
                spaceHeight = mapView.height / 2 - pin!!.intrinsicHeight - TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, mapView.context.resources.displayMetrics).roundToInt()

                if (locality != null) remotePhoto = ViewModelProvider(
                    requireParentFragment(),
                    LocationSearchHostFragment.LocationSearchViewModelFactory(requireActivity().application, true)
                )[LocationSearchHostFragment.LocationSearchViewModel::class.java].getResult().value?.find { it.locality == locality && it.country == country }?.photos

                remotePhoto?.forEach { remotePhoto ->
                    poi = GeoPoint(remotePhoto.photo.latitude, remotePhoto.photo.longitude)
                    val marker = Marker(mapView).apply {
                        position = poi
                        icon = pin
                        if (remotePhoto.remotePath.isEmpty()) loadImage(this, remotePhoto.photo)
                        relatedObject = spaceHeight
                    }
                    marker.infoWindow = object : InfoWindow(R.layout.map_info_window, mapView) {
                        override fun onOpen(item: Any?) {
                            mView.apply {
                                findViewById<ImageView>(R.id.photo)?.let { v ->
                                    if (remotePhoto.remotePath.isEmpty()) {
                                        v.setImageDrawable(marker.image)
                                        (marker.image.intrinsicHeight - marker.relatedObject as Int).apply { mapView.setMapCenterOffset(0, if (this > 0) this else 0) }
                                    }
                                    else {
                                        imageLoaderModel.setImagePhoto(remotePhoto, v, NCShareViewModel.TYPE_IN_MAP) {
                                            (v.drawable.intrinsicHeight - marker.relatedObject as Int).apply { mapView.setMapCenterOffset(0, if (this > 0) this else 0) }
                                        }
                                    }
                                }
                                findViewById<TextView>(R.id.label).text =
                                    if (remotePhoto.photo.albumId == CameraRollFragment.FROM_CAMERA_ROLL) remotePhoto.photo.dateTaken.run { this.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)) }
                                    else albumNames?.get(remotePhoto.photo.albumId)
                                setOnClickListener(InfoWindowClickListener(mapView))
                            }
                        }

                        override fun onClose() {
                            mView.setOnClickListener(null)
                        }
                    }
                    marker.setOnMarkerClickListener(markerClickListener)
                    mapView.overlays.add(marker)

                    points.add(poi)
                }

                mapView.invalidate()

                // Start zooming to the bounding box
                if (points.isNotEmpty()) withContext(Dispatchers.Main) {
                    //while(!mapView.isLayoutOccurred) { delay(50) }
                    poiBoundingBox = SimpleFastPointOverlay(SimplePointTheme(points, false), SimpleFastPointOverlayOptions.getDefaultStyle()).boundingBox
                    mapView.zoomToBoundingBox(poiBoundingBox,savedInstanceState == null, 100, MAXIMUM_ZOOM, 800)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()

        (requireActivity() as AppCompatActivity).supportActionBar?.run {
            title = locality ?: album?.name
            displayOptions = ActionBar.DISPLAY_HOME_AS_UP or ActionBar.DISPLAY_SHOW_TITLE
        }

        mapView.onResume()
    }

    override fun onPause() {
        mapView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        slideshowJob?.cancel()
        requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        album?.run {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            bgmPlayer.release()
        }

        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.photo_in_map_menu, menu)
        playMenuItem = menu.findItem(R.id.option_menu_map_slideshow)
        muteMenuItem = menu.findItem(R.id.option_menu_mute)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)

        with(muteMenuItem) {
            if (hasBGM) setIcon(if (isMuted) R.drawable.ic_baseline_volume_off_24 else R.drawable.ic_baseline_volume_on_24)
            else {
                isVisible = false
                isEnabled = false
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when(item.itemId) {
            R.id.option_menu_map_slideshow -> {
                if (isSlideshowPlaying) {
                    slideshowJob?.cancel()
                    stopSlideshow()
                } else {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
                    isSlideshowPlaying = true
                    playMenuItem.setIcon(R.drawable.ic_baseline_stop_24)

                    slideshowJob?.cancel()
                    slideshowJob = lifecycleScope.launch {
                        closeAllInfoWindow()
                        mapView.zoomToBoundingBox(poiBoundingBox, true, 100, MAXIMUM_ZOOM, 400)

                        val allZoomLevel = (mapView.zoomLevelDouble + MAXIMUM_ZOOM) / 2
                        val animationController = AnimationMapController(mapView)
                        var lastPos = (mapView.overlays[1] as Marker).position
                        //var poiCenter: Int

                        if (hasBGM) {
                            bgmPlayer.volume = if (isMuted) 0f else 1f
                            bgmPlayer.prepare()
                        }

                        try {
                            for (i in 1 until mapView.overlays.size) {
                                if (!isActive) break
                                    (mapView.overlays[i] as Marker).let { stop ->
                                    // Pan map to reveal full image
                                    //poiCenter = kotlin.math.max((stop.image.intrinsicHeight - spaceHeight), 0)

                                    if (stop.position.distanceToAsDouble(lastPos) > 3000.0) {
                                        // If next POI is 3km away, use jump animation
                                        animationController.setOnAnimationEndListener {
                                            animationController.setOnAnimationEndListener {
                                                animationController.setOnAnimationEndListener {
                                                    stop.showInfoWindow()
                                                }
                                                animationController.animateTo(stop.position, MAXIMUM_ZOOM, ANIMATION_TIME)
                                            }
                                            //mapView.setMapCenterOffset(0, poiCenter)
                                            animationController.animateTo(stop.position, allZoomLevel, ANIMATION_TIME)
                                        }
                                        animationController.animateTo(lastPos, allZoomLevel, ANIMATION_TIME)
                                        delay(6400)     // 4000 + 3 * 800
                                    } else {
                                        //mapView.setMapCenterOffset(0, poiCenter)
                                        animationController.setOnAnimationEndListener {
                                            stop.showInfoWindow()
                                        }
                                        animationController.animateTo(stop.position, MAXIMUM_ZOOM, ANIMATION_TIME)
                                        delay(4000)
                                    }
                                    stop.closeInfoWindow()
                                    lastPos = stop.position
                                }
                            }
                        } catch (e: Exception) {
                            // Race condition might happen when user press back key while slideshow is playing
                        }

                        if (isActive) stopSlideshow()
                    }
                }
                true
            }
            R.id.option_menu_mute -> {
                isMuted = !isMuted
                muteMenuItem.setIcon(
                    if (isMuted) {
                        fadeOutBGM()
                        R.drawable.ic_baseline_volume_off_24
                    } else {
                        fadeInBGM()
                        R.drawable.ic_baseline_volume_on_24
                    }
                )
                true
            }
            else -> false
        }
    }

    private lateinit var playerHandler: Handler
    private fun fadeInBGM() {
        Timer().also { timer ->
            timer.schedule(object : TimerTask() {
                override fun run() {
                    if (bgmPlayer.volume < 1f) {
                        playerHandler.post { bgmPlayer.volume += 0.05f }
                    } else {
                        playerHandler.post { bgmPlayer.volume = 1f }
                        timer.cancel()
                    }
                }
            }, 0, 75)
        }
    }
    private fun fadeOutBGM(stopPlaying: Boolean = false) {
        Timer().also { timer ->
            timer.schedule(object : TimerTask() {
                override fun run() {
                    if (bgmPlayer.volume > 0f) {
                        playerHandler.post { bgmPlayer.volume -= 0.05f }
                    } else {
                        playerHandler.post {
                            bgmPlayer.volume = 0f
                            if (stopPlaying) {
                                bgmPlayer.stop()
                                bgmPlayer.seekTo(0)
                            }
                        }
                        timer.cancel()
                    }
                }
            }, 0, 75)
        }
    }

    private fun stopSlideshow() {
        closeAllInfoWindow()
        mapView.setMapCenterOffset(0, 0)
        mapView.zoomToBoundingBox(poiBoundingBox, true, 100, MAXIMUM_ZOOM, 400)
        requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        isSlideshowPlaying = false
        playMenuItem.setIcon(R.drawable.ic_baseline_play_arrow_24)
        if (hasBGM) fadeOutBGM(true)
    }

    private fun closeAllInfoWindow() {
        mapView.overlays.forEach { if (it is Marker) it.closeInfoWindow() }
    }

    private fun loadImage(marker: Marker, photo: Photo) {
        lifecycleScope.launch(Dispatchers.IO) {
            val option = BitmapFactory.Options().apply {
                val vW = mapView.width - TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24f, mapView.context.resources.displayMetrics).roundToInt()
                val vH = mapView.height * 3 / 5
                inSampleSize = 1
                while(photo.width > vW * inSampleSize || photo.height > vH * inSampleSize) { inSampleSize += 2 }
            }
            marker.image = BitmapDrawable(resources,
                if (photo.albumId == CameraRollFragment.FROM_CAMERA_ROLL) {
                    var bmp = BitmapFactory.decodeStream(requireContext().contentResolver.openInputStream(Uri.parse(photo.id)), null, option)
                    if (photo.orientation != 0) bmp?.let { bmp = Bitmap.createBitmap(bmp!!, 0, 0, it.width, it.height, Matrix().apply { preRotate((photo.orientation).toFloat()) }, true) }
                    bmp
                }
                else BitmapFactory.decodeFile("$rootPath/${photo.id}", option)
            )
        }
    }

    class MarkerClickListener: Marker.OnMarkerClickListener {
        override fun onMarkerClick(marker: Marker, mapView: MapView): Boolean {
            if (marker.isInfoWindowShown) marker.closeInfoWindow()
            else {
                mapView.overlays.forEach { if (it is Marker) it.closeInfoWindow() }
                marker.showInfoWindow()
                //marker.image?.let { (marker.image.intrinsicHeight - marker.relatedObject as Int).apply { mapView.setMapCenterOffset(0, if (this > 0) this else 0) }}
                mapView.controller.animateTo(marker.position)
            }

            return true
        }
    }

    class InfoWindowClickListener(private val mapView: MapView): View.OnClickListener {
        override fun onClick(v: View) {
            val marker = (v.tag as InfoWindow).relatedObject as Marker
            for (i in mapView.overlays.indexOf(marker) - 1 downTo 0) {
                mapView.overlays[i].apply {
                    if (this is Marker) {
                        if (this.position == marker.position) {
                            marker.closeInfoWindow()
                            this.showInfoWindow()
                            return
                        }
                    }
                }
            }
            marker.closeInfoWindow()
        }
    }

    class AnimationMapController(mapView: MapView): MapController(mapView) {
        private lateinit var animationListener: ()-> Unit?

        override fun onAnimationEnd() {
            super.onAnimationEnd()
            animationListener()
        }

        fun setOnAnimationEndListener(animationListener: ()-> Unit) {
            this.animationListener = animationListener
        }
    }

    companion object {
        private const val KEY_LOCALITY = "KEY_LOCALITY"
        private const val KEY_COUNTRY = "KEY_COUNTRY"
        private const val KEY_ALBUM_NAMES = "KEY_ALBUM_NAMES"
        private const val KEY_ALBUM = "KEY_ALBUM"
        private const val KEY_PHOTOS = "KEY_PHOTOS"

        private const val MAXIMUM_ZOOM = 19.5
        private const val ANIMATION_TIME = 800L

        @JvmStatic
        fun newInstance(locality: String, country: String, albumNames: HashMap<String, String>) = PhotosInMapFragment().apply {
            arguments = Bundle().apply {
                putString(KEY_LOCALITY, locality)
                putString(KEY_COUNTRY, country)
                putSerializable(KEY_ALBUM_NAMES, albumNames)
            }
        }

        @JvmStatic
        fun newInstance(album: Album, photos: List<Photo>) = PhotosInMapFragment().apply {
            arguments = Bundle().apply {
                putParcelable(KEY_ALBUM, album)
                putParcelableArrayList(KEY_PHOTOS, ArrayList(photos))
            }
        }
    }
}