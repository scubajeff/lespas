package site.leos.apps.lespas.search

import android.app.Application
import android.os.Bundle
import android.view.*
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.exifinterface.media.ExifInterface
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.*
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.bonuspack.location.GeocoderNominatim
import site.leos.apps.lespas.BuildConfig
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.ImageLoaderViewModel
import site.leos.apps.lespas.helper.SingleLiveEvent
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.photo.PhotoRepository
import java.io.File
import java.io.IOException
import java.util.*

class LocationSearchFragment: Fragment() {
    private var loadingIndicator: MenuItem? = null
    private var loadingProgressBar: CircularProgressIndicator? = null
    private val imageLoaderModel: ImageLoaderViewModel by activityViewModels()
    private val searchViewModel: LocationSearchViewModel by activityViewModels { LocationSearchViewModelFactory(requireActivity().application) }

    private lateinit var resultAdapter: LocationSearchResultAdapter
    private lateinit var resultView: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)

        resultAdapter = LocationSearchResultAdapter(
            { result -> },
            { photo, imageView -> imageLoaderModel.loadPhoto(photo, imageView, ImageLoaderViewModel.TYPE_GRID) },
        ).apply { stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_search, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        resultView = view.findViewById<RecyclerView>(R.id.category_list).apply {
            adapter = resultAdapter
        }

        searchViewModel.getResult().observe(viewLifecycleOwner, Observer { result ->
            val items = mutableListOf<LocationSearchResult>()
            var photoList: List<Photo>

            // General a new result list, this is crucial for DiffUtil to detect changes
            result.forEach {
                // Take the last 4 since we only show 4, this also create a new list which is crucial for DiffUtil to detect changes in nested list
                photoList = it.photos.takeLast(4)
                items.add(LocationSearchResult(photoList.toMutableList(), it.total, it.country, it.locality))
            }

            resultAdapter.submitList(items.sortedWith(compareBy({it.country}, {it.locality})))
            //resultAdapter.submitList(result.map { it.copy() }.sortedWith(compareBy({it.country}, {it.locality})))
        })
        searchViewModel.getProgress().observe(viewLifecycleOwner, Observer { progress ->
            loadingProgressBar?.setProgressCompat(progress, true)
            if (progress == 100) loadingIndicator?.disable()
        })

        savedInstanceState ?: run {
            searchViewModel.performSearch()
        }
    }

    override fun onResume() {
        super.onResume()
        (activity as? AppCompatActivity)?.supportActionBar?.run {
            title = getString(R.string.item_location_search)
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowTitleEnabled(true)
        }
        if (searchViewModel.getProgress().value == 100) loadingIndicator?.disable()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.search_result_menu, menu)
        loadingIndicator = menu.findItem(R.id.option_menu_search_progress)?.apply {
            loadingProgressBar = actionView.findViewById<CircularProgressIndicator>(R.id.search_progress).apply {
                isIndeterminate = false
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    class LocationSearchViewModelFactory(private val application: Application): ViewModelProvider.NewInstanceFactory() {
        override fun <T : ViewModel> create(modelClass: Class<T>): T = LocationSearchViewModel(application) as T
    }

    class LocationSearchViewModel(application: Application): AndroidViewModel(application) {
        private val baseFolder = Tools.getLocalRoot(application)
        private var total = 0
        private val photoRepo = PhotoRepository(application)
        private var job: Job? = null

        override fun onCleared() {
            job?.cancel()
            super.onCleared()
        }

        fun performSearch() {
            job = viewModelScope.launch(Dispatchers.IO) {
                // Clear result for re-searching
                resultList.clear()
                result.postValue(resultList)
                progress.postValue(0)

                photoRepo.getAllImage().run {
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
                                                existed.photos.add(photo)
                                                existed.total++
                                            }
                                            ?: run { resultList.add(LocationSearchResult(arrayListOf(photo), 1, this.countryName, city)) }

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
        }

        private val resultList = mutableListOf<LocationSearchResult>()
        private val result = MutableLiveData<List<LocationSearchResult>>()
        fun getResult(): LiveData<List<LocationSearchResult>> = result

        private val progress = SingleLiveEvent<Int>()
        fun getProgress(): SingleLiveEvent<Int> = progress
    }

    class LocationSearchResultAdapter(private val clickListener: (LocationSearchResult) -> Unit, private val imageLoader: (Photo, ImageView) -> Unit
    ): ListAdapter<LocationSearchResult, LocationSearchResultAdapter.ViewHolder>(LocationSearchResultDiffCallback()) {
        inner class ViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
            private var photoAdapter = PhotoAdapter { photo, imageView ->  imageLoader(photo, imageView) }
            private val tvCountry = itemView.findViewById<TextView>(R.id.country)
            private val tvLocality = itemView.findViewById<TextView>(R.id.locality)
            private val tvCount = itemView.findViewById<TextView>(R.id.count)
            private val photoRVViewPool = RecyclerView.RecycledViewPool()

            init {
                itemView.findViewById<RecyclerView>(R.id.items)?.apply {
                    adapter = photoAdapter
                    setRecycledViewPool(photoRVViewPool)
                    (layoutManager as RecyclerView.LayoutManager).isItemPrefetchEnabled = false
                }
            }

            fun bind(item: LocationSearchResult) {
                tvLocality.text = item.locality
                tvCountry.text = item.country
                tvCount.text = if (item.total > 4) item.total.toString() else ""

                photoAdapter.submitList(item.photos)

                itemView.setOnClickListener { clickListener(item) }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_location_search, parent, false))
        override fun onBindViewHolder(holder: ViewHolder, position: Int) { holder.bind(getItem(position)) }
    }

    class LocationSearchResultDiffCallback: DiffUtil.ItemCallback<LocationSearchResult>() {
        override fun areItemsTheSame(oldItem: LocationSearchResult, newItem: LocationSearchResult): Boolean = oldItem.country == newItem.country && oldItem.locality == newItem.locality
        override fun areContentsTheSame(oldItem: LocationSearchResult, newItem: LocationSearchResult): Boolean = oldItem.photos.last().id == newItem.photos.last().id
    }

    class PhotoAdapter(private val imageLoader: (Photo, ImageView) -> Unit): ListAdapter<Photo, PhotoAdapter.ViewHolder>(PhotoDiffCallback()) {
        inner class ViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
            private val ivPhoto = itemView.findViewById<ImageView>(R.id.photo)

            fun bind(item: Photo) {
                imageLoader(item, ivPhoto)
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_photo, parent, false))
        override fun onBindViewHolder(holder: ViewHolder, position: Int) { holder.bind(getItem(position)) }
    }

    class PhotoDiffCallback: DiffUtil.ItemCallback<Photo>() {
        override fun areItemsTheSame(oldItem: Photo, newItem: Photo): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Photo, newItem: Photo): Boolean = oldItem.eTag == newItem.eTag
    }

    data class LocationSearchResult (
        var photos: MutableList<Photo>,
        var total: Int,
        val country: String,
        val locality: String,
    )

    private fun MenuItem.disable() {
        this.isEnabled = false
        this.isVisible = false
    }
}