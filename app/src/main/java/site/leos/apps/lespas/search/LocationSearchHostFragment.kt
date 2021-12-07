package site.leos.apps.lespas.search

import android.app.Application
import android.os.Bundle
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
import site.leos.apps.lespas.helper.SingleLiveEvent
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.photo.PhotoRepository
import java.io.File
import java.io.IOException
import java.util.*

class LocationSearchHostFragment: Fragment() {
    private var loadingProgressBar: CircularProgressIndicator? = null
    private lateinit var menu: Menu
    private val searchViewModel: LocationSearchViewModel by viewModels { LocationSearchViewModelFactory(requireActivity().application) }

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

        if (childFragmentManager.backStackEntryCount == 0) childFragmentManager.beginTransaction().replace(R.id.container_child_fragment, LocationSearchFragment(), LocationSearchFragment::class.java.canonicalName).addToBackStack(null).commit()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.location_search_menu, menu)
        this.menu = menu
        menu.findItem(R.id.option_menu_search_progress)?.apply {
            loadingProgressBar = actionView.findViewById(R.id.search_progress)
            searchViewModel.getProgress().value?.also { progress-> if (progress == 100) this.disable() }
        }
    }

    fun enableMenuItem(itemId: Int): MenuItem? = menu.findItem(itemId).apply { this?.enable() }
    fun disableMenuItem(itemId: Int) { menu.findItem(itemId).disable() }

    private fun MenuItem.disable() {
        this.isEnabled = false
        this.isVisible = false
    }
    private fun MenuItem.enable() {
        this.isEnabled = true
        this.isVisible = true
    }

    @Suppress("UNCHECKED_CAST")
    class LocationSearchViewModelFactory(private val application: Application): ViewModelProvider.NewInstanceFactory() {
        override fun <T : ViewModel> create(modelClass: Class<T>): T = LocationSearchViewModel(application) as T
    }

    class LocationSearchViewModel(application: Application): AndroidViewModel(application) {
        private val baseFolder = Tools.getLocalRoot(application)
        private var total = 0
        private var job = viewModelScope.launch(Dispatchers.IO) {
            PhotoRepository(application).getAllImage().run {
                total = this.size
                this.forEachIndexed { i, photo ->
                    progress.postValue((i * 100.0 / total).toInt())
                    if (Tools.hasExif(photo.mimeType)) try { ExifInterface("$baseFolder/${if (File(baseFolder, photo.id).exists()) photo.id else photo.name}") } catch (e: Exception) { null }?.latLong?.also {
                        when {
                            it[0] == 0.0 -> {}
                            it[0] >= 90.0 -> {}
                            it[0] <= -90.0 -> {}
                            it[1] == 0.0 -> {}
                            it[1] >= 180.0 -> {}
                            it[1] <= -180.0 -> {}
                            else -> {
                                try { GeocoderNominatim(Locale.getDefault(), BuildConfig.APPLICATION_ID).getFromLocation(it[0], it[1], 1) } catch (e: IOException) { null }?.get(0)?.apply {
                                    val city = this.locality ?: this.adminArea ?: ""
                                    resultList.find { result-> result.country == this.countryName && result.locality == city }
                                        ?.let { existed ->
                                            existed.photos.add(LocationSearchFragment.PhotoWithCoordinate(photo, it[0], it[1]))
                                            existed.total++
                                        }
                                        ?: run { resultList.add(LocationSearchFragment.LocationSearchResult(arrayListOf(LocationSearchFragment.PhotoWithCoordinate(photo, it[0], it[1])), 1, this.countryName, city)) }

                                    // Update UI
                                    result.postValue(resultList)
                                }
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

        private val resultList = mutableListOf<LocationSearchFragment.LocationSearchResult>()
        private val result = MutableLiveData<List<LocationSearchFragment.LocationSearchResult>>()
        fun getResult(): LiveData<List<LocationSearchFragment.LocationSearchResult>> = result

        private val progress = SingleLiveEvent<Int>()
        fun getProgress(): SingleLiveEvent<Int> = progress

/*
        private var currentLocality = ""
        fun getCurrentLocality(): String = currentLocality
        fun putCurrentLocality(locality: String) { currentLocality = locality }
*/
    }
}