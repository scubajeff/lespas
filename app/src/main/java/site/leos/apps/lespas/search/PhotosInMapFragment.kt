package site.leos.apps.lespas.search

import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.osmdroid.api.IGeoPoint
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.TilesOverlay
import org.osmdroid.views.overlay.simplefastpoint.SimpleFastPointOverlay
import org.osmdroid.views.overlay.simplefastpoint.SimpleFastPointOverlayOptions
import org.osmdroid.views.overlay.simplefastpoint.SimplePointTheme
import site.leos.apps.lespas.BuildConfig
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.Tools

class PhotosInMapFragment: Fragment() {
    private lateinit var rootPath: String
    private lateinit var locality: String
    private lateinit var country: String
    private lateinit var poisBoundingBox: BoundingBox

    private val searchViewModel: LocationSearchHostFragment.LocationSearchViewModel by viewModels({requireParentFragment()}) { LocationSearchHostFragment.LocationSearchViewModelFactory(requireActivity().application) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requireArguments().apply {
            locality = getString(KEY_LOCALITY)!!
            country = getString(KEY_COUNTRY)!!
        }

        rootPath = Tools.getLocalRoot(requireContext())
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? = inflater.inflate(R.layout.fragment_photos_in_map, container, false)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        org.osmdroid.config.Configuration.getInstance().userAgentValue = BuildConfig.APPLICATION_ID
        view.findViewById<MapView>(R.id.map).apply {
            if (this.context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) overlayManager.tilesOverlay.setColorFilter(TilesOverlay.INVERT_COLORS)
            setMultiTouchControls(true)
            setUseDataConnection(true)
            setTileSource(TileSourceFactory.MAPNIK)
            overlays.add(CopyrightOverlay(requireContext()))

            val pin = ContextCompat.getDrawable(this.context, R.drawable.ic_baseline_location_marker_24)
            var poi: GeoPoint
            val points = arrayListOf<IGeoPoint>()
            searchViewModel.getResult().value?.find { it.locality == locality && it.country == country }?.photos?.forEach { photo->
                poi = GeoPoint(photo.lat, photo.long)
                overlays.add(Marker(this).apply {
                    position = poi
                    icon = pin
                    loadImage(this, photo.photo.id)
                })

                points.add(poi)
            }
            poisBoundingBox = SimpleFastPointOverlay(SimplePointTheme(points, false), SimpleFastPointOverlayOptions.getDefaultStyle()).boundingBox
            invalidate()
        }.doOnLayout {
            (it as MapView).apply {
                controller.setCenter(boundingBox.centerWithDateLine)
                zoomToBoundingBox(poisBoundingBox, savedInstanceState == null, 100, 19.5, 800)
            }
        }
    }

    private fun loadImage(marker: Marker, photoId: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            marker.image = BitmapDrawable(resources, BitmapFactory.decodeFile("$rootPath/$photoId", BitmapFactory.Options().apply { inSampleSize = 8 }))
        }
    }

    companion object {
        const val KEY_LOCALITY = "KEY_LOCALITY"
        const val KEY_COUNTRY = "KEY_COUNTRY"

        @JvmStatic
        fun newInstance(locality: String, country: String) = PhotosInMapFragment().apply {
            arguments = Bundle().apply {
                putString(KEY_LOCALITY, locality)
                putString(KEY_COUNTRY, country)
            }
        }
    }
}