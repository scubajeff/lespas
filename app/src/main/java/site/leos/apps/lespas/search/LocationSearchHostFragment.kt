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

import android.app.Application
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.osmdroid.bonuspack.location.GeocoderNominatim
import site.leos.apps.lespas.BuildConfig
import site.leos.apps.lespas.R
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.photo.PhotoRepository
import site.leos.apps.lespas.publication.NCShareViewModel
import java.util.Locale

class LocationSearchHostFragment: Fragment() {
    private var menu: Menu? = null
    private var loadingProgressBar: CircularProgressIndicator? = null

    private val imageLoaderModel: NCShareViewModel by activityViewModels()
    private val searchModel: SearchFragment.SearchModel by viewModels(ownerProducer = { requireParentFragment() }) { SearchFragment.SearchModelFactory(requireActivity().application, imageLoaderModel)}
    //private val locationSearchModel: LocationSearchViewModel by viewModels { LocationSearchViewModelFactory(requireActivity().application, requireArguments().getInt(KEY_SEARCH_SCOPE), imageLoaderModel, searchPayloadModel) }
    private val locationSearchModel: LocationSearchViewModel by viewModels { LocationSearchViewModelFactory(requireActivity().application, requireArguments().getInt(KEY_SEARCH_SCOPE), searchModel) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requireActivity().onBackPressedDispatcher.addCallback(this, object: OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (childFragmentManager.backStackEntryCount > 1) childFragmentManager.popBackStack() else parentFragmentManager.popBackStack()
            }
        })
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? = inflater.inflate(R.layout.fragment_container, container, false)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (childFragmentManager.backStackEntryCount == 0) childFragmentManager.beginTransaction().replace(R.id.container_child_fragment, LocationResultByLocalitiesFragment.newInstance(requireArguments().getInt(KEY_SEARCH_SCOPE)), LocationResultByLocalitiesFragment::class.java.canonicalName).addToBackStack(null).commit()

        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
                inflater.inflate(R.menu.search_location_on_map_menu, menu)
                this@LocationSearchHostFragment.menu = menu
                menu.findItem(R.id.option_menu_search_progress)?.apply {
                    loadingProgressBar = actionView?.findViewById(R.id.search_progress)
                    if (locationSearchModel.getProgress() == Int.MAX_VALUE) this.disable()
                }
                when(childFragmentManager.backStackEntryCount) {
                    2-> menu.findItem(R.id.option_menu_in_map).enable()
                }
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean = true
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    launch { locationSearchModel.maxProgress.collect { loadingProgressBar?.max = it }}
                    launch {
                        locationSearchModel.progress.collect { progress ->
                            when (progress) {
                                0 -> loadingProgressBar?.isIndeterminate = true
                                Int.MAX_VALUE -> disableMenuItem(R.id.option_menu_search_progress)
                                else -> loadingProgressBar?.run {
                                    isIndeterminate = false
                                    setProgressCompat(progress, true)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    //private fun enableMenuItem(itemId: Int): MenuItem? = menu?.findItem(itemId)?.apply { this.enable() }
    private fun disableMenuItem(itemId: Int) { menu?.findItem(itemId)?.disable() }

    private fun MenuItem.disable() {
        this.isEnabled = false
        this.isVisible = false
    }
    private fun MenuItem.enable() {
        this.isEnabled = true
        this.isVisible = true
    }

/*
    class LocationSearchViewModelFactory(private val application: Application, private val searchScope: Int, private val remoteImageModel: NCShareViewModel, private val payloadModel: SearchFragment.SearchPayloadModel): ViewModelProvider.NewInstanceFactory() {
        override fun <T : ViewModel> create(modelClass: Class<T>): T = modelClass.cast(LocationSearchViewModel(application, searchScope, remoteImageModel, payloadModel))!!
    }
*/
    class LocationSearchViewModelFactory(private val application: Application, private val searchScope: Int, private val payloadModel: SearchFragment.SearchModel): ViewModelProvider.NewInstanceFactory() {
        override fun <T : ViewModel> create(modelClass: Class<T>): T = modelClass.cast(LocationSearchViewModel(application, searchScope, payloadModel))!!
    }

    //class LocationSearchViewModel(application: Application, searchScope: Int, remoteImageModel: NCShareViewModel, payloadModel: SearchFragment.SearchPayloadModel): AndroidViewModel(application) {
    class LocationSearchViewModel(application: Application, searchScope: Int, payloadModel: SearchFragment.SearchModel): AndroidViewModel(application) {
        private val photoRepository = PhotoRepository(application)
        private lateinit var resultList: MutableList<LocationSearchResult>
        private val _result = MutableStateFlow<List<LocationSearchResult>>(mutableListOf())
        val result: StateFlow<List<LocationSearchResult>> = _result
        private val _progress = MutableStateFlow(0)
        val progress: StateFlow<Int> = _progress
        private val _maxProgress = MutableStateFlow(0)
        val maxProgress: StateFlow<Int> = _maxProgress

        //private val patchActions = mutableListOf<Action>()

        init {
            viewModelScope.launch(Dispatchers.IO) {
                (if (searchScope == R.id.search_album) payloadModel.albumPhotos else payloadModel.galleryPhotos).collect { remotePhotos ->
                    if (remotePhotos.isNotEmpty()) {
                        val nominatim = GeocoderNominatim(Locale.getDefault(), BuildConfig.APPLICATION_ID)
/*
                        val remoteBaseFolder = Tools.getRemoteHome(application)
                        val remoteCameraArchiveFolder = Tools.getArchiveBase(application)
                        val cr = application.contentResolver
                        val albums = AlbumRepository(application).getAllAlbumAttribute()

                        var latitude = Photo.GPS_DATA_UNKNOWN
                        var longitude = Photo.GPS_DATA_UNKNOWN
                        var altitude = Photo.GPS_DATA_UNKNOWN
                        var bearing = Photo.GPS_DATA_UNKNOWN
*/

                        resultList = mutableListOf()
                        _result.emit(resultList)
                        _maxProgress.emit(remotePhotos.size)

                        remotePhotos.asReversed().forEachIndexed { i, remotePhoto ->
                            remotePhoto.photo.let { photo ->
                                ensureActive()
                                _progress.emit(i)

                                if (photo.latitude == Photo.NO_GPS_DATA) return@forEachIndexed
                                else doubleArrayOf(photo.latitude, photo.longitude).let { latLong ->
                                    if (photo.country.isEmpty()) {
                                        try {
                                            nominatim.getFromLocation(latLong[0], latLong[1], 1)
                                        } catch (e: Exception) {
                                            null
                                        }?.let { result ->
                                            if (result.isEmpty()) null
                                            else {
                                                result[0]?.let { address ->
                                                    if (address.countryName != null) {
                                                        val locality = address.locality ?: address.adminArea ?: Photo.NO_ADDRESS
                                                        if (searchScope == R.id.search_album) photoRepository.updateAddress(photo.id, locality, address.countryName, address.countryCode ?: Photo.NO_ADDRESS)
                                                        //Pair(it.countryName, locality)
                                                        LocationAddress(address.countryName, locality, address.countryCode)
                                                    } else null
                                                }
                                            }
                                        }
                                    } else {
                                        LocationAddress(photo.country, photo.locality, photo.countryCode)
                                    }?.apply {
                                        resultList.find { result -> result.country == this.country && result.locality == this.locality }
                                            ?.let { existed ->
                                                existed.photos.add(remotePhoto)
                                                existed.total++
                                            }
                                            ?: run { resultList.add(LocationSearchResult(arrayListOf(remotePhoto), 1, this.country, this.locality, getFlagEmoji(this.countryCode.uppercase()))) }

                                        // Update UI
                                        _result.emit(resultList.toList())
                                    }
                                }

/*
                                if (Tools.hasExif(photo.mimeType)) {
                                    when (searchScope) {
                                        R.id.search_album -> {
                                            if (photo.latitude != Photo.NO_GPS_DATA)
                                            else return@forEachIndexed
                                        }
                                        R.id.search_gallery -> {
                                            try {
                                                cr.openInputStream(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.setRequireOriginal(Uri.parse(photo.id)) else Uri.parse(photo.id))
                                            } catch (e: SecurityException) {
                                                cr.openInputStream(Uri.parse(photo.id))
                                            } catch (e: UnsupportedOperationException) {
                                                cr.openInputStream(Uri.parse(photo.id))
                                            }?.let {
                                                try {
                                                    ExifInterface(it).latLong
                                                } catch (_: NullPointerException) {
                                                    return@forEachIndexed
                                                } catch (_: OutOfMemoryError) {
                                                    null
                                                }
                                            } ?: run { return@forEachIndexed }
                                        }
*/
                                /*
                            R.id.search_archive -> {
                                when(photo.latitude) {
                                    Photo.NO_GPS_DATA -> return@forEachIndexed
                                    Photo.GPS_DATA_UNKNOWN -> remoteImageModel.getMediaExif(NCShareViewModel.RemotePhoto(photo, remoteCameraArchiveFolder))?.let { exif ->
                                        exif.latLong?.run {
                                            latitude = this[0]
                                            longitude = this[1]
                                            altitude = exif.getAltitude(Photo.NO_GPS_DATA)
                                            bearing = Tools.getBearing(exif)
                                        } ?: run {
                                            latitude = Photo.NO_GPS_DATA
                                            longitude = Photo.NO_GPS_DATA
                                            altitude = Photo.NO_GPS_DATA
                                            bearing = Photo.NO_GPS_DATA
                                        }

                                        // Patch WebDAV properties in archive
                                        // exif.dateTimeOriginal, exif.dateTimeDigitized both return timestamp in UTC time zone
                                        // Property folder name should "/DCIM" here, SyncAdapter will handle user base correctly
                                        // FIXME foldername under new backup folder structure
                                        patchActions.add(Action(null, Action.ACTION_PATCH_PROPERTIES, "","/DCIM",
                                            "<oc:${OkHttpWebDav.LESPAS_LATITUDE}>" + latitude + "</oc:${OkHttpWebDav.LESPAS_LATITUDE}>" +
                                                    "<oc:${OkHttpWebDav.LESPAS_LONGITUDE}>" + longitude + "</oc:${OkHttpWebDav.LESPAS_LONGITUDE}>" +
                                                    "<oc:${OkHttpWebDav.LESPAS_ALTITUDE}>" + altitude + "</oc:${OkHttpWebDav.LESPAS_ALTITUDE}>" +
                                                    "<oc:${OkHttpWebDav.LESPAS_BEARING}>" + bearing + "</oc:${OkHttpWebDav.LESPAS_BEARING}>" +
                                                    "<oc:${OkHttpWebDav.LESPAS_ORIENTATION}>" + exif.rotationDegrees + "</oc:${OkHttpWebDav.LESPAS_ORIENTATION}>" +
                                                    "<oc:${OkHttpWebDav.LESPAS_WIDTH}>" + exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0) + "</oc:${OkHttpWebDav.LESPAS_WIDTH}>" +
                                                    "<oc:${OkHttpWebDav.LESPAS_HEIGHT}>" + exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0) + "</oc:${OkHttpWebDav.LESPAS_HEIGHT}>" +
                                                    //"<oc:${OkHttpWebDav.LESPAS_DATE_TAKEN}>" + (exif.dateTimeOriginal ?: exif.dateTimeDigitized ?: try { (photo.dateTaken.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()) } catch (e: Exception) { System.currentTimeMillis() }) + "</oc:${OkHttpWebDav.LESPAS_DATE_TAKEN}>",
                                                    "<oc:${OkHttpWebDav.LESPAS_DATE_TAKEN}>" + (exif.dateTimeOriginal ?: exif.dateTimeDigitized ?: try { (photo.dateTaken.atZone(ZoneId.of("Z")).toInstant().toEpochMilli()) } catch (e: Exception) { System.currentTimeMillis() }) + "</oc:${OkHttpWebDav.LESPAS_DATE_TAKEN}>",
                                            photo.name, System.currentTimeMillis(), 1)
                                        )

                                        exif.latLong
                                    }
                                    else -> doubleArrayOf(photo.latitude, photo.longitude)
                                }
                            }
*/
                                //else -> null
                            //}?.also { latLong ->
                            }
                        }

                        _progress.emit(Int.MAX_VALUE)
                    }
                }
            }
        }

/*
        override fun onCleared() {
            if (patchActions.isNotEmpty()) ActionRepository(getApplication()).addActions(patchActions)
            super.onCleared()
        }
*/

/*
        private var currentLocality = ""
        fun getCurrentLocality(): String = currentLocality
        fun putCurrentLocality(locality: String) { currentLocality = locality }
*/
        fun getProgress() = _progress.value

        private fun getFlagEmoji(countryCode: String): String =
            String(Character.toChars(Character.codePointAt(countryCode, 0) - 0x41 + 0x1F1E6)) + String(Character.toChars(Character.codePointAt(countryCode, 1) - 0x41 + 0x1F1E6))
    }

    data class LocationSearchResult (
        var photos: MutableList<NCShareViewModel.RemotePhoto>,
        var total: Int,
        val country: String,
        val locality: String,
        val flag: String,
    )

    data class LocationAddress (
        val country: String,
        val locality: String,
        val countryCode: String,
    )

    companion object {
        private const val KEY_SEARCH_SCOPE = "KEY_SEARCH_SCOPE"

        @JvmStatic
        fun newInstance(scope: Int) = LocationSearchHostFragment().apply { arguments = Bundle().apply { putInt(KEY_SEARCH_SCOPE, scope) } }
    }
}