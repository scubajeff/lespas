package site.leos.apps.lespas.search

import android.content.ClipData
import android.content.ContentResolver
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.*
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.github.chrisbanes.photoview.PhotoView
import com.google.android.material.transition.MaterialContainerTransform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.TilesOverlay
import site.leos.apps.lespas.BuildConfig
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.AlbumRepository
import site.leos.apps.lespas.cameraroll.CameraRollFragment
import site.leos.apps.lespas.helper.ConfirmDialogFragment
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.helper.YesNoDialogFragment
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.publication.NCShareViewModel
import site.leos.apps.lespas.sync.AcquiringDialogFragment
import site.leos.apps.lespas.sync.DestinationDialogFragment
import site.leos.apps.lespas.sync.ShareReceiverActivity
import java.io.File
import java.util.*

class PhotoWithMapFragment: Fragment() {
    private lateinit var remotePhoto: NCShareViewModel.RemotePhoto
    private val imageLoaderModel: NCShareViewModel by activityViewModels()
    private lateinit var mapView: MapView
    private lateinit var photoView: PhotoView

    private var stripExif = "2"
    private var shareOutJob: Job? = null
    private var shareOutUri = arrayListOf<Uri>()
    private val destinationModel: DestinationDialogFragment.DestinationViewModel by activityViewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        remotePhoto = requireArguments().getParcelable(KEY_PHOTO)!!

        sharedElementEnterTransition = MaterialContainerTransform().apply {
            duration = resources.getInteger(android.R.integer.config_mediumAnimTime).toLong()
            scrimColor = Color.TRANSPARENT
            //fadeMode = MaterialContainerTransform.FADE_MODE_CROSS
        }
        with(remotePhoto.photo) {
            requireActivity().requestedOrientation = if ((width < height) || (albumId == CameraRollFragment.FROM_CAMERA_ROLL && (shareId == 90 || shareId == 270))) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

        setHasOptionsMenu(true)

        stripExif = PreferenceManager.getDefaultSharedPreferences(requireContext()).getString(getString(R.string.strip_exif_pref_key), getString(R.string.strip_ask_value))!!
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? = inflater.inflate(R.layout.fragment_photo_with_map, container, false)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        postponeEnterTransition()

        photoView = view.findViewById<PhotoView>(R.id.photo).apply {
            imageLoaderModel.setImagePhoto(remotePhoto, this, NCShareViewModel.TYPE_IN_MAP) { startPostponedEnterTransition() }

            ViewCompat.setTransitionName(this, remotePhoto.photo.id)
        }

        org.osmdroid.config.Configuration.getInstance().userAgentValue = BuildConfig.APPLICATION_ID
        mapView = view.findViewById<MapView>(R.id.map).apply {
            if (this.context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) overlayManager.tilesOverlay.setColorFilter(TilesOverlay.INVERT_COLORS)
            setMultiTouchControls(true)
            setUseDataConnection(true)
            setTileSource(TileSourceFactory.MAPNIK)
            overlays.add(CopyrightOverlay(requireContext()))
            controller.setZoom(17.5)

            val poi = GeoPoint(remotePhoto.photo.latitude, remotePhoto.photo.longitude)
            controller.setCenter(poi)
            overlays.add(Marker(this).let {
                it.position = poi
                it.icon = ContextCompat.getDrawable(this.context, R.drawable.ic_baseline_location_marker_24)
                it
            })
            invalidate()
        }

        parentFragmentManager.setFragmentResultListener(ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY, viewLifecycleOwner) { key, bundle ->
            if (key == ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY) {
                when(bundle.getString(ConfirmDialogFragment.INDIVIDUAL_REQUEST_KEY)) {
                    STRIP_REQUEST_KEY -> shareOut(bundle.getBoolean(ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY, false))
                }
            }
        }
        destinationModel.getDestination().observe(viewLifecycleOwner) { album ->
            album?.apply {
                // Acquire files
                if (parentFragmentManager.findFragmentByTag(TAG_ACQUIRING_DIALOG) == null)
                    AcquiringDialogFragment.newInstance(shareOutUri, this, destinationModel.shouldRemoveOriginal()).show(parentFragmentManager, TAG_ACQUIRING_DIALOG)
            }
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

    override fun onDestroyView() {
        imageLoaderModel.cancelSetImagePhoto(photoView)

        super.onDestroyView()
    }

    override fun onDestroy() {
        requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.photo_with_map_menu, menu)
        menu.findItem(R.id.option_menu_lespas).isVisible = remotePhoto.photo.albumId == CameraRollFragment.FROM_CAMERA_ROLL
        Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("geo:0.0,0.0?z=20")
            resolveActivity(requireActivity().packageManager)?.let { menu.findItem(R.id.option_menu_open_in_map_app).isEnabled = true }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when(item.itemId) {
            R.id.option_menu_lespas -> {
                // Allow removing original (e.g. move) is too much. TODO or is it?
                prepareShares(remotePhoto.photo, false, requireContext().contentResolver)?.let {
                    shareOutUri = arrayListOf(it)
                    if (parentFragmentManager.findFragmentByTag(TAG_DESTINATION_DIALOG) == null) DestinationDialogFragment.newInstance(shareOutUri, false).show(parentFragmentManager, TAG_DESTINATION_DIALOG)
                }
                true
            }
            R.id.option_menu_share -> {
                if (stripExif == getString(R.string.strip_ask_value)) {
                    if (Tools.hasExif(remotePhoto.photo.mimeType)) {
                        if (parentFragmentManager.findFragmentByTag(CONFIRM_DIALOG) == null) YesNoDialogFragment.newInstance(getString(R.string.strip_exif_msg, getString(R.string.strip_exif_title)), STRIP_REQUEST_KEY).show(parentFragmentManager, CONFIRM_DIALOG)
                    } else shareOut(false)
                } else shareOut(stripExif == getString(R.string.strip_on_value))
                true
            }
            R.id.option_menu_open_in_map_app -> {
                startActivity(Intent(Intent.ACTION_VIEW).apply {
                    data = Uri.parse(
                        if (PreferenceManager.getDefaultSharedPreferences(requireContext()).getBoolean(getString(R.string.chinese_map_pref_key), false))
                            Tools.wGS84ToGCJ02(doubleArrayOf(remotePhoto.photo.latitude, remotePhoto.photo.longitude)).let { "geo:${it[0]},${it[1]}?z=20" }
                        else "geo:${remotePhoto.photo.latitude},${remotePhoto.photo.longitude}?z=20"
                    )
                })
                true
            }
            else -> false
        }
    }

    private fun prepareShares(photo: Photo, strip: Boolean, cr: ContentResolver): Uri? {
        return try {
            // Synced file is named after id, not yet synced file is named after file's name
            //val sourceFile = File(Tools.getLocalRoot(requireContext()), if (photo.eTag != Photo.ETAG_NOT_YET_UPLOADED) photo.id else photo.name)
            val sourceFile = File(Tools.getLocalRoot(requireContext()), photo.id)
            val destFile = File(requireContext().cacheDir, if (strip) "${UUID.randomUUID()}.${photo.name.substringAfterLast('.')}" else photo.name)

            // Copy the file from fileDir/id to cacheDir/name, strip EXIF base on setting
            if (strip && Tools.hasExif(photo.mimeType)) {
                if (photo.albumId == CameraRollFragment.FROM_CAMERA_ROLL) {
                    // Strip EXIF, rotate picture if needed
                    BitmapFactory.decodeStream(cr.openInputStream(Uri.parse(photo.id)))?.let { bmp->
                        (if (photo.orientation != 0) Bitmap.createBitmap(bmp, 0, 0, photo.width, photo.height, Matrix().apply { preRotate(photo.orientation.toFloat()) }, true) else bmp)
                            .compress(Bitmap.CompressFormat.JPEG, 95, destFile.outputStream())
                    }
                }
                else {
                    if (sourceFile.exists()) BitmapFactory.decodeFile(sourceFile.canonicalPath)?.compress(Bitmap.CompressFormat.JPEG, 95, destFile.outputStream())
                    else {
                        val albumName = AlbumRepository(requireActivity().application).getThisAlbum(photo.albumId).name
                        if (!imageLoaderModel.downloadFile("${getString(R.string.lespas_base_folder_name)}/${albumName}/${photo.name}", destFile, true)) return null
                    }
                }

                FileProvider.getUriForFile(requireContext(), getString(R.string.file_authority), destFile)
            } else {
                if (photo.albumId == CameraRollFragment.FROM_CAMERA_ROLL) Uri.parse(photo.id)
                else {
                    if (sourceFile.exists()) sourceFile.copyTo(destFile, true, 4096)
                    else {
                        val albumName = AlbumRepository(requireActivity().application).getThisAlbum(photo.albumId).name
                        if (!imageLoaderModel.downloadFile("${getString(R.string.lespas_base_folder_name)}/${albumName}/${photo.name}", destFile, false)) return null
                    }

                    FileProvider.getUriForFile(requireContext(), getString(R.string.file_authority), destFile)
                }
            }
        } catch (e: Exception) { null }
    }

    private fun shareOut(strip: Boolean) {
        val cr = requireContext().contentResolver
        val handler = Handler(Looper.getMainLooper())
        val waitingMsg = Tools.getPreparingSharesSnackBar(mapView, strip) { shareOutJob?.cancel(cause = null) }

        shareOutJob = lifecycleScope.launch(Dispatchers.IO) {
            // Show a SnackBar if it takes too long (more than 500ms) preparing shares
            withContext(Dispatchers.Main) {
                handler.removeCallbacksAndMessages(null)
                handler.postDelayed({ waitingMsg.show() }, 500)
            }

            prepareShares(remotePhoto.photo, strip, cr)?.let {
                withContext(Dispatchers.Main) {
                    // Dismiss snackbar before showing system share chooser, avoid unpleasant screen flicker
                    if (waitingMsg.isShownOrQueued) waitingMsg.dismiss()

                    // Call system share chooser
                    startActivity(Intent.createChooser(Intent().apply {
                        action = Intent.ACTION_SEND
                        type = cr.getType(it) ?: "image/*"
                        putExtra(Intent.EXTRA_STREAM, it)
                        clipData = ClipData.newUri(cr, "", it)
                        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                        // Allow removing original (e.g. move) is too much. TODO or is it?
                        putExtra(ShareReceiverActivity.KEY_SHOW_REMOVE_OPTION, false)
                    }, null))
                }
            }
        }

        shareOutJob?.invokeOnCompletion {
            // Dismiss waiting SnackBar
            handler.removeCallbacksAndMessages(null)
            if (waitingMsg.isShownOrQueued) waitingMsg.dismiss()
        }
    }

    companion object {
        private const val KEY_PHOTO = "KEY_PHOTO"

        const val TAG_DESTINATION_DIALOG = "PHOTO_WITH_MAP_DESTINATION_DIALOG"
        const val TAG_ACQUIRING_DIALOG = "PHOTO_WITH_MAP_ACQUIRING_DIALOG"
        private const val CONFIRM_DIALOG = "CONFIRM_DIALOG"
        private const val STRIP_REQUEST_KEY = "PHOTO_WITH_MAP_STRIP_REQUEST_KEY"

        @JvmStatic
        fun newInstance(photo: NCShareViewModel.RemotePhoto) = PhotoWithMapFragment().apply { arguments = Bundle().apply { putParcelable(KEY_PHOTO, photo) }}
    }
}