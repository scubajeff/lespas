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

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.MenuProvider
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.preference.PreferenceManager
import com.github.chrisbanes.photoview.PhotoView
import com.google.android.material.transition.MaterialContainerTransform
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import site.leos.apps.lespas.BuildConfig
import site.leos.apps.lespas.R
import site.leos.apps.lespas.gallery.GalleryFragment
import site.leos.apps.lespas.helper.ShareOutDialogFragment
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.helper.Tools.parcelable
import site.leos.apps.lespas.publication.NCShareViewModel
import site.leos.apps.lespas.sync.ActionViewModel

class PhotoWithMapFragment: Fragment() {
    private lateinit var remotePhoto: NCShareViewModel.RemotePhoto
    private var searchScope = 0

    private lateinit var mapView: MapView
    private lateinit var photoView: PhotoView

    private val imageLoaderModel: NCShareViewModel by activityViewModels()
    private val actionModel: ActionViewModel by viewModels()
    private val searchModel: SearchFragment.SearchModel by viewModels(ownerProducer = { requireParentFragment() }) { SearchFragment.SearchModelFactory(requireActivity().application, imageLoaderModel, actionModel)}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        remotePhoto = requireArguments().parcelable(KEY_PHOTO)!!
        searchScope = requireArguments().getInt(KEY_SEARCH_SCOPE)

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
            if (bundle.getBoolean(ShareOutDialogFragment.SHARE_OUT_DIALOG_RESULT_KEY, true))
                searchModel.shareOut(
                    photos = listOf(remotePhoto),
                    strip = bundle.getBoolean(ShareOutDialogFragment.STRIP_RESULT_KEY, false),
                    lowResolution = bundle.getBoolean(ShareOutDialogFragment.LOW_RESOLUTION_RESULT_KEY, false),
                    removeAfterwards = bundle.getBoolean(ShareOutDialogFragment.REMOVE_AFTERWARDS_RESULT_KEY, false),
                )
        }

        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.photo_with_map_menu, menu)
                menu.findItem(R.id.option_menu_lespas).isVisible = searchScope != R.id.search_album

                // See if map app installed
                Intent(Intent.ACTION_VIEW).apply {
                    data = "geo:0.0,0.0?z=20".toUri()
                    resolveActivity(requireActivity().packageManager)?.let { menu.findItem(R.id.option_menu_open_in_map_app).isEnabled = true }
                }
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                return when(menuItem.itemId) {
                    R.id.option_menu_lespas -> {
                        // Add to LesPas menu enabled for photo from gallery only
                        searchModel.add(listOf(remotePhoto))
                        true
                    }
                    R.id.option_menu_share -> {
                        if (parentFragmentManager.findFragmentByTag(SHARE_OUT_DIALOG) == null) ShareOutDialogFragment.newInstance(mimeTypes = listOf(remotePhoto.photo.mimeType), showRemoveAfterwards = false)!!.show(parentFragmentManager, SHARE_OUT_DIALOG)    // ?: run { searchModel.shareOut(listOf(remotePhoto), strip = false, lowResolution = false, removeAfterwards = false) }
                        true
                    }
                    R.id.option_menu_open_in_map_app -> {
                        startActivity(Intent(Intent.ACTION_VIEW).apply {
                            data = (
                                if (PreferenceManager.getDefaultSharedPreferences(requireContext()).getBoolean(getString(R.string.chinese_map_pref_key), false))
                                    Tools.wGS84ToGCJ02(doubleArrayOf(remotePhoto.photo.latitude, remotePhoto.photo.longitude)).let { "geo:${it[0]},${it[1]}?z=20" }
                                else "geo:${remotePhoto.photo.latitude},${remotePhoto.photo.longitude}?z=20"
                            ).toUri()
                        })
                        true
                    }
                    else -> false
                }
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
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

    companion object {
        private const val KEY_PHOTO = "KEY_PHOTO"
        private const val KEY_SEARCH_SCOPE = "KEY_SEARCH_SCOPE"
        private const val SHARE_OUT_DIALOG = "SHARE_OUT_DIALOG"

        @JvmStatic
        fun newInstance(photo: NCShareViewModel.RemotePhoto, scope: Int) = PhotoWithMapFragment().apply {
            arguments = Bundle().apply {
                putParcelable(KEY_PHOTO, photo)
                putInt(KEY_SEARCH_SCOPE, scope)
            }
        }
    }
}