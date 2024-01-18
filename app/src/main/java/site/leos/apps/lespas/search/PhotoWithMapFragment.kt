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

package site.leos.apps.lespas.search

import android.content.ClipData
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.github.chrisbanes.photoview.PhotoView
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.transition.MaterialContainerTransform
import kotlinx.coroutines.launch
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import site.leos.apps.lespas.BuildConfig
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.Album
import site.leos.apps.lespas.gallery.GalleryFragment
import site.leos.apps.lespas.helper.ShareOutDialogFragment
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.helper.Tools.parcelable
import site.leos.apps.lespas.publication.NCShareViewModel
import site.leos.apps.lespas.sync.AcquiringDialogFragment
import site.leos.apps.lespas.sync.Action
import site.leos.apps.lespas.sync.ActionViewModel
import site.leos.apps.lespas.sync.DestinationDialogFragment
import site.leos.apps.lespas.sync.ShareReceiverActivity

class PhotoWithMapFragment: Fragment() {
    private lateinit var remotePhoto: NCShareViewModel.RemotePhoto
    private var target = 0

    private lateinit var mapView: MapView
    private lateinit var photoView: PhotoView

    private var shareOutUri = arrayListOf<Uri>()
    private var shareOutType = GENERAL_SHARE
    private var shareOutMimeType = ""
    private var waitingMsg: Snackbar? = null
    private val handler = Handler(Looper.getMainLooper())

    private val imageLoaderModel: NCShareViewModel by activityViewModels()
    private val destinationModel: DestinationDialogFragment.DestinationViewModel by activityViewModels()

    private lateinit var remoteBase: String

    private lateinit var shareOutBackPressedCallback: OnBackPressedCallback

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        remoteBase = Tools.getRemoteHome(requireContext())

        //remotePhoto = requireArguments().getParcelable(KEY_PHOTO)!!
        remotePhoto = requireArguments().parcelable(KEY_PHOTO)!!
        target = requireArguments().getInt(KEY_TARGET)

        sharedElementEnterTransition = MaterialContainerTransform().apply {
            duration = resources.getInteger(android.R.integer.config_mediumAnimTime).toLong()
            scrimColor = Color.TRANSPARENT
            //fadeMode = MaterialContainerTransform.FADE_MODE_CROSS
        }
        with(remotePhoto.photo) {
            requireActivity().requestedOrientation =
                if ((width < height) || (albumId == GalleryFragment.FROM_DEVICE_GALLERY && (orientation == 90 || orientation == 270)) || (remotePhoto.remotePath.isNotEmpty() && (orientation == 90 || orientation == 270))) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

        @Suppress("DEPRECATION")
        setHasOptionsMenu(true)

        shareOutBackPressedCallback = object: OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                // Cancel share out job if it's running
                waitingMsg?.let {
                    if (it.isShownOrQueued) {
                        imageLoaderModel.cancelShareOut()
                        it.dismiss()
                    }
                }
                isEnabled = false
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(this, shareOutBackPressedCallback)
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
            if (this.context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) overlayManager.tilesOverlay.setColorFilter(ColorMatrixColorFilter(
                floatArrayOf(
                    1.05f, 0f, 0f, 0f, -72f,  // red, reduce brightness about 1/4, increase contrast by 5%
                    0f, 1.05f, 0f, 0f, -72f,  // green, reduce brightness about 1/4, reduced contrast by 5%
                    0f, 0f, 1.05f, 0f, -72f,  // blue, reduce brightness about 1/4, reduced contrast by 5%
                    0f, 0f, 0f, 1f, 0f,
                )
            ))
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

        // Share out dialog result handler
        parentFragmentManager.setFragmentResultListener(ShareOutDialogFragment.SHARE_OUT_DIALOG_RESULT_KEY, viewLifecycleOwner) { _, bundle ->
            if (bundle.getBoolean(ShareOutDialogFragment.SHARE_OUT_DIALOG_RESULT_KEY, true)) shareOut(bundle.getBoolean(ShareOutDialogFragment.STRIP_RESULT_KEY, false), bundle.getBoolean(ShareOutDialogFragment.LOW_RESOLUTION_RESULT_KEY, false), GENERAL_SHARE)
        }

        destinationModel.getDestination().observe(viewLifecycleOwner) {
            it?.let { targetAlbum ->
                if (destinationModel.doOnServer()) {
                    val actions = mutableListOf<Action>()

                    when (targetAlbum.id) {
                        "" -> {
                            // Create new album first, since this whole operations will be carried out on server, we don't have to worry about cover here, SyncAdapter will handle all the rest during next sync
                            actions.add(0, Action(null, Action.ACTION_ADD_DIRECTORY_ON_SERVER, "", targetAlbum.name, "", "", System.currentTimeMillis(), 1))
                        }
                        Album.JOINT_ALBUM_ID -> Snackbar.make(mapView, getString(R.string.msg_joint_album_not_updated_locally), Snackbar.LENGTH_LONG).show()
                    }

                    actions.add(Action(
                        null,
                        if (destinationModel.shouldRemoveOriginal()) Action.ACTION_MOVE_ON_SERVER else Action.ACTION_COPY_ON_SERVER,
                        remotePhoto.remotePath,
                        if (targetAlbum.id != Album.JOINT_ALBUM_ID) "${remoteBase}/${targetAlbum.name}" else targetAlbum.coverFileName.substringBeforeLast('/'),
                        "",
                        "${remotePhoto.photo.name}|${targetAlbum.id == Album.JOINT_ALBUM_ID}|${Tools.isRemoteAlbum(targetAlbum)}",
                        System.currentTimeMillis(), 1
                    ))

                    ViewModelProvider(requireActivity())[ActionViewModel::class.java].addActions(actions)
                } else {
                    // Acquire files
                    if (parentFragmentManager.findFragmentByTag(TAG_ACQUIRING_DIALOG) == null) AcquiringDialogFragment.newInstance(shareOutUri, targetAlbum, destinationModel.shouldRemoveOriginal()).show(parentFragmentManager, TAG_ACQUIRING_DIALOG)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            imageLoaderModel.shareOutUris.collect { uris ->
                // Dismiss snackbar before showing system share chooser, avoid unpleasant screen flicker
                handler.removeCallbacksAndMessages(null)
                if (waitingMsg?.isShownOrQueued == true) {
                    waitingMsg?.dismiss()
                    shareOutBackPressedCallback.isEnabled = false
                }

                // Call system share chooser
                if (uris.isNotEmpty()) when (shareOutType) {
                    GENERAL_SHARE -> {
                        startActivity(Intent.createChooser(Intent().apply {
                            action = Intent.ACTION_SEND
                            type = shareOutMimeType
                            putExtra(Intent.EXTRA_STREAM, uris[0])
                            clipData = ClipData.newUri(requireContext().contentResolver, "", uris[0])
                            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                            // Allow removing original (e.g. move) is too much. TODO or is it?
                            putExtra(ShareReceiverActivity.KEY_SHOW_REMOVE_OPTION, false)
                        }, null))
                    }
                    SHARE_TO_LESPAS -> {
                        // Allow removing original (e.g. move) is too much. TODO or is it?
                        if (parentFragmentManager.findFragmentByTag(TAG_DESTINATION_DIALOG) == null) DestinationDialogFragment.newInstance(uris, false).show(parentFragmentManager, TAG_DESTINATION_DIALOG)
                    }
                }
            }
        }.invokeOnCompletion {
            handler.removeCallbacksAndMessages(null)
            if (waitingMsg?.isShownOrQueued == true) {
                waitingMsg?.dismiss()
                shareOutBackPressedCallback.isEnabled = false
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

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.photo_with_map_menu, menu)
        menu.findItem(R.id.option_menu_lespas).isVisible = target != R.id.search_album
        menu.findItem(R.id.option_menu_share).icon = ContextCompat.getDrawable(requireContext(), if (target == R.id.search_archive) R.drawable.ic_baseline_archivev_download_24 else R.drawable.ic_baseline_share_24)

        // See if map app installed
        Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("geo:0.0,0.0?z=20")
            resolveActivity(requireActivity().packageManager)?.let { menu.findItem(R.id.option_menu_open_in_map_app).isEnabled = true }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when(item.itemId) {
            R.id.option_menu_lespas -> {
                if (target == R.id.search_cameraroll) shareOut(strip = false, lowResolution = false, shareType = SHARE_TO_LESPAS)
                else if (parentFragmentManager.findFragmentByTag(TAG_DESTINATION_DIALOG) == null) DestinationDialogFragment.newInstance(arrayListOf(remotePhoto), "", true).show(parentFragmentManager, TAG_DESTINATION_DIALOG)
                true
            }
            R.id.option_menu_share -> {
/*
                if (target == R.id.search_archive) imageLoaderModel.batchDownload(requireContext(), listOf(remotePhoto))
                else if (parentFragmentManager.findFragmentByTag(SHARE_OUT_DIALOG) == null) ShareOutDialogFragment.newInstance(mimeTypes = listOf(remotePhoto.photo.mimeType))?.show(parentFragmentManager, SHARE_OUT_DIALOG) ?: run { shareOut(strip = false, lowResolution = false, shareType = GENERAL_SHARE) }
*/
                if (parentFragmentManager.findFragmentByTag(SHARE_OUT_DIALOG) == null)
                    ShareOutDialogFragment.newInstance(mimeTypes = listOf(remotePhoto.photo.mimeType))?.show(parentFragmentManager, SHARE_OUT_DIALOG) ?: run { shareOut(strip = false, lowResolution = false, shareType = GENERAL_SHARE) }
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

    private fun shareOut(strip: Boolean, lowResolution: Boolean, shareType: Int) {
        shareOutType = shareType
        waitingMsg = Tools.getPreparingSharesSnackBar(mapView) {
            imageLoaderModel.cancelShareOut()
            shareOutBackPressedCallback.isEnabled = false
        }

        // Show a SnackBar if it takes too long (more than 500ms) preparing shares
        handler.postDelayed({
            waitingMsg?.show()
            shareOutBackPressedCallback.isEnabled = true
        }, 500)

        // Prepare media files for sharing
        imageLoaderModel.prepareFileForShareOut(listOf(remotePhoto.photo.apply { shareOutMimeType = mimeType }), strip, lowResolution, remotePhoto.remotePath.isNotEmpty(), remotePhoto.remotePath)
    }

    companion object {
        private const val KEY_PHOTO = "KEY_PHOTO"
        private const val KEY_TARGET = "KEY_TARGET"

        private const val GENERAL_SHARE = 0
        private const val SHARE_TO_LESPAS = 2

        const val TAG_DESTINATION_DIALOG = "PHOTO_WITH_MAP_DESTINATION_DIALOG"
        const val TAG_ACQUIRING_DIALOG = "PHOTO_WITH_MAP_ACQUIRING_DIALOG"
        private const val SHARE_OUT_DIALOG = "SHARE_OUT_DIALOG"

        @JvmStatic
        fun newInstance(photo: NCShareViewModel.RemotePhoto, target: Int) = PhotoWithMapFragment().apply {
            arguments = Bundle().apply {
                putParcelable(KEY_PHOTO, photo)
                putInt(KEY_TARGET, target)
            }
        }
    }
}