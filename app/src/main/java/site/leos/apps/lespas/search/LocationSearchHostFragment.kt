package site.leos.apps.lespas.search

import android.app.Application
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.*
import androidx.activity.OnBackPressedCallback
import androidx.exifinterface.media.ExifInterface
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.*
import androidx.lifecycle.Observer
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.bonuspack.location.GeocoderNominatim
import site.leos.apps.lespas.BuildConfig
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.Album
import site.leos.apps.lespas.album.AlbumRepository
import site.leos.apps.lespas.helper.SingleLiveEvent
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.photo.PhotoRepository
import site.leos.apps.lespas.publication.NCShareViewModel
import java.io.IOException
import java.time.LocalDateTime
import java.util.*

class LocationSearchHostFragment: Fragment() {
    private var loadingProgressBar: CircularProgressIndicator? = null
    private var menu: Menu? = null
    private val searchViewModel: LocationSearchViewModel by viewModels { LocationSearchViewModelFactory(requireActivity().application, requireArguments().getBoolean(SEARCH_COLLECTION)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)

        requireActivity().onBackPressedDispatcher.addCallback(this, object: OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (childFragmentManager.backStackEntryCount > 1) childFragmentManager.popBackStack() else parentFragmentManager.popBackStack()
            }
        })
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? = inflater.inflate(R.layout.fragment_location_search_host, container, false)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        searchViewModel.getProgress().observe(viewLifecycleOwner, Observer { progress ->
            loadingProgressBar?.setProgressCompat(progress, true)
            if (progress == 100) disableMenuItem(R.id.option_menu_search_progress)
        })

        if (childFragmentManager.backStackEntryCount == 0) childFragmentManager.beginTransaction().replace(R.id.container_child_fragment, LocationResultByLocalitiesFragment.newInstance(requireArguments().getBoolean(SEARCH_COLLECTION)), LocationResultByLocalitiesFragment::class.java.canonicalName).addToBackStack(null).commit()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.location_search_menu, menu)
        this.menu = menu
        menu.findItem(R.id.option_menu_search_progress)?.apply {
            loadingProgressBar = actionView.findViewById(R.id.search_progress)
            searchViewModel.getProgress().value?.also { progress-> if (progress == 100) this.disable() }
        }
        when(childFragmentManager.backStackEntryCount) {
            2-> menu.findItem(R.id.option_menu_in_map).enable()
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

    @Suppress("UNCHECKED_CAST")
    class LocationSearchViewModelFactory(private val application: Application, private val searchCollection: Boolean): ViewModelProvider.NewInstanceFactory() {
        override fun <T : ViewModel> create(modelClass: Class<T>): T = LocationSearchViewModel(application, searchCollection) as T
    }

    class LocationSearchViewModel(application: Application, searchCollection: Boolean): AndroidViewModel(application) {
        private var job = viewModelScope.launch(Dispatchers.IO) {
            (if (searchCollection) PhotoRepository(application).getAllImageNotHidden() else Tools.getCameraRoll(application.contentResolver, true).toList()).run {
                val lespasBaseFolder = application.getString(R.string.lespas_base_folder_name)
                val cr = application.contentResolver
                val albums = AlbumRepository(application).getAllAlbumAttribute()
                val total = this.size
                var p = NCShareViewModel.RemotePhoto(Photo(dateTaken = LocalDateTime.MIN, lastModified = LocalDateTime.MIN), "")

                this.asReversed().forEachIndexed { i, photo ->
                    progress.postValue((i * 100.0 / total).toInt())
                    if (Tools.hasExif(photo.mimeType)) {
                        (if (searchCollection) {
                            //ExifInterface("$baseFolder/${if (File(baseFolder, photo.id).exists()) photo.id else photo.name}")
                            if (photo.latitude != Photo.NO_GPS_DATA) doubleArrayOf(photo.latitude, photo.longitude)
                            else return@forEachIndexed
                        } else {
                            try {
                                cr.openInputStream(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.setRequireOriginal(Uri.parse(photo.id)) else Uri.parse(photo.id))
                            } catch (e: SecurityException) {
                                cr.openInputStream(Uri.parse(photo.id))
                            } catch (e: UnsupportedOperationException) {
                                cr.openInputStream(Uri.parse(photo.id))
                            }?.let { ExifInterface(it).latLong } ?: run { null }
                        })?.also { latLong ->
                            try {
                                GeocoderNominatim(Locale.getDefault(), BuildConfig.APPLICATION_ID).getFromLocation(latLong[0], latLong[1], 1)
                            } catch (e: IOException) {
                                null
                            }?.get(0)?.apply {
                                if (searchCollection) {
                                    val album = albums.find { it.id == photo.albumId }
                                    album?.let {
                                        p = NCShareViewModel.RemotePhoto(photo, if (album.shareId and Album.REMOTE_ALBUM == Album.REMOTE_ALBUM && photo.eTag != Photo.ETAG_NOT_YET_UPLOADED) "${lespasBaseFolder}/${album.name}" else "")
                                    } ?: run {
                                        return@forEachIndexed
                                    }
                                } else p = NCShareViewModel.RemotePhoto(photo.copy(latitude = latLong[0], longitude = latLong[1]), "")

                                val city = this.locality ?: this.adminArea ?: ""
                                resultList.find { result -> result.country == this.countryName && result.locality == city }
                                    ?.let { existed ->
                                        existed.photos.add(p)
                                        existed.total++
                                    }
                                    ?: run { resultList.add(LocationSearchResult(arrayListOf(p), 1, this.countryName, city)) }

                                // Update UI
                                result.postValue(resultList)
                            }
                        }
                    }
                }
            }

            // Show progress to the end
            delay(500)
            progress.postValue(100)
        }

        override fun onCleared() {
            job.cancel()
            super.onCleared()
        }

        private val resultList = mutableListOf<LocationSearchResult>()
        private val result = MutableLiveData<List<LocationSearchResult>>()
        fun getResult(): LiveData<List<LocationSearchResult>> = result

        private val progress = SingleLiveEvent<Int>()
        fun getProgress(): SingleLiveEvent<Int> = progress

/*
        private var currentLocality = ""
        fun getCurrentLocality(): String = currentLocality
        fun putCurrentLocality(locality: String) { currentLocality = locality }
*/
    }

    data class LocationSearchResult (
        var photos: MutableList<NCShareViewModel.RemotePhoto>,
        var total: Int,
        val country: String,
        val locality: String,
    )

    companion object {
        private const val SEARCH_COLLECTION = "SEARCH_COLLECTION"

        @JvmStatic
        fun newInstance(searchCollection: Boolean) = LocationSearchHostFragment().apply { arguments = Bundle().apply { putBoolean(SEARCH_COLLECTION, searchCollection) } }
    }
}