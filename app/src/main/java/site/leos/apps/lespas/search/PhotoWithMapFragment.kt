package site.leos.apps.lespas.search

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.github.chrisbanes.photoview.PhotoView
import com.google.android.material.transition.MaterialContainerTransform
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.TilesOverlay
import site.leos.apps.lespas.BuildConfig
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.ImageLoaderViewModel

class PhotoWithMapFragment: Fragment() {
    private lateinit var photo: LocationSearchFragment.PhotoWithCoordinate
    private val imageLoaderViewModel by activityViewModels<ImageLoaderViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        photo = requireArguments().getParcelable(KEY_PHOTO)!!

        requireActivity().requestedOrientation = if (photo.photo.width < photo.photo.height) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? = inflater.inflate(R.layout.fragment_photo_with_map, container, false)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        postponeEnterTransition()
        sharedElementEnterTransition = MaterialContainerTransform().apply {
            duration = resources.getInteger(android.R.integer.config_mediumAnimTime).toLong()
            scrimColor = Color.TRANSPARENT
            //fadeMode = MaterialContainerTransform.FADE_MODE_CROSS
        }

        view.findViewById<PhotoView>(R.id.photo)?.apply {
            imageLoaderViewModel.loadPhoto(photo.photo, this, ImageLoaderViewModel.TYPE_FULL) { startPostponedEnterTransition() }
            ViewCompat.setTransitionName(this, photo.photo.id)
        }

        org.osmdroid.config.Configuration.getInstance().userAgentValue = BuildConfig.APPLICATION_ID
        view.findViewById<MapView>(R.id.map).apply {
            setMultiTouchControls(true)
            setUseDataConnection(true)
            setTileSource(TileSourceFactory.MAPNIK)
            overlays.add(CopyrightOverlay(requireContext()))
            val poi = GeoPoint(photo.lat, photo.long)
            controller.setZoom(17.5)
            controller.setCenter(poi)
            Marker(this).let {
                it.position = poi
                it.icon = ContextCompat.getDrawable(this.context, R.drawable.ic_baseline_location_marker_24)
                this.overlays.add(it)
            }
            if (this.context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) overlayManager.tilesOverlay.setColorFilter(TilesOverlay.INVERT_COLORS)
            invalidate()
        }
    }

    override fun onDestroy() {
        requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        super.onDestroy()
    }

    companion object {
        const val KEY_PHOTO = "KEY_PHOTO"

        @JvmStatic
        fun newInstance(photo: LocationSearchFragment.PhotoWithCoordinate) = PhotoWithMapFragment().apply { arguments = Bundle().apply { putParcelable(KEY_PHOTO, photo) }}
    }
}