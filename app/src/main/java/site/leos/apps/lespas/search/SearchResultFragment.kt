package site.leos.apps.lespas.search

import android.annotation.SuppressLint
import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.*
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.*
import site.leos.apps.lespas.cameraroll.CameraRollFragment
import site.leos.apps.lespas.helper.ImageLoaderViewModel
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.photo.PhotoRepository
import site.leos.apps.lespas.search.SearchFragment.Companion.SEARCH_COLLECTION
import site.leos.apps.lespas.tflite.ObjectDetectionModel
import java.io.BufferedReader
import java.io.InputStreamReader
import java.time.*
import java.time.format.TextStyle
import java.util.*
import kotlin.collections.HashMap

class SearchResultFragment : Fragment() {
    private lateinit var searchResultAdapter: SearchResultAdapter
    private val imageLoaderModel: ImageLoaderViewModel by activityViewModels()
    private val albumModel: AlbumViewModel by activityViewModels()
    private val adhocSearchViewModel: AdhocSearchViewModel by viewModels {
        AdhocAdhocSearchViewModelFactory(requireActivity().application, arguments?.getString(CATEGORY_ID)!!, arguments?.getBoolean(SEARCH_COLLECTION)!!)
    }

    private var loadingIndicator: MenuItem? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)

        searchResultAdapter = SearchResultAdapter(
            { result ->
                if (arguments?.getBoolean(SEARCH_COLLECTION)!!) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val album = albumModel.getThisAlbum(result.photo.albumId)[0]
                        withContext(Dispatchers.Main) {
                            parentFragmentManager.beginTransaction().replace(R.id.container_root, AlbumDetailFragment.newInstance(album, result.photo.id), AlbumDetailFragment::class.java.canonicalName).addToBackStack(null).commit()
                        }
                    }
                }
                else parentFragmentManager.beginTransaction().replace(R.id.container_root, CameraRollFragment.newInstance(result.photo.id), CameraRollFragment::class.java.canonicalName).addToBackStack(null).commit()
            },
            { photo: Photo, view: ImageView, type: String -> imageLoaderModel.loadPhoto(photo, view, type) }
        ).apply {
            // Get album's name for display
            Thread { setAlbumNameList(albumModel.getAllAlbumName()) }.start()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.fragment_albumdetail, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<RecyclerView>(R.id.photogrid).apply {
            adapter = searchResultAdapter
        }

        adhocSearchViewModel.getResultList().observe(viewLifecycleOwner, Observer { searchResult -> searchResultAdapter.submitList(searchResult) })
        adhocSearchViewModel.isFinished().observe(viewLifecycleOwner, Observer { finished ->
            if (finished) loadingIndicator?.apply {
                isVisible = false
                isEnabled = false
            }
        })
    }

    override fun onResume() {
        super.onResume()

        (activity as? AppCompatActivity)?.supportActionBar?.run {
            arguments?.let { title = getString(if (it.getBoolean(SEARCH_COLLECTION)) R.string.title_in_album else R.string.title_in_cameraroll, it.getString(CATEGORY_LABEL)) }
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowTitleEnabled(true)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.search_result_menu, menu)
        loadingIndicator = menu.findItem(R.id.option_menu_search_progress)

        if (adhocSearchViewModel.isFinished().value!!) loadingIndicator?.apply {
            isVisible = false
            isEnabled = false
        }
    }

    @Suppress("UNCHECKED_CAST")
    class AdhocAdhocSearchViewModelFactory(private val application: Application, private val categoryId: String, private val searchCollection: Boolean): ViewModelProvider.NewInstanceFactory() {
        override fun <T : ViewModel?> create(modelClass: Class<T>): T = AdhocSearchViewModel(application, categoryId, searchCollection) as T
    }

    class AdhocSearchViewModel(private val app: Application, private val categoryId: String, private val searchInAlbums: Boolean): AndroidViewModel(app) {
        private val finished = MutableLiveData(false)
        private val resultList = mutableListOf<Result>()
        private val result = MutableLiveData<List<Result>>()
        private var job: Job? = null

        init {
            // Run job in init(), since it's singleton
            job = viewModelScope.launch(Dispatchers.IO) {
                //val photos = if (searchInAlbums) PhotoRepository(app).getAllImage() else getCameraCollection(app.contentResolver)
                val photos = if (searchInAlbums) PhotoRepository(app).getAllImage() else Tools.getCameraRoll(app.contentResolver)
                val od = ObjectDetectionModel(app.assets)
                val rootPath = "${app.filesDir}${app.getString(R.string.lespas_base_folder_name)}"
                var length: Int
                var size: Int
                val option = BitmapFactory.Options()

                // Load object index and positive threshold
                val labelIndex = arrayListOf<Pair<String, Float>>()
                BufferedReader(InputStreamReader(app.assets.open("label_mobile_ssd_coco_90.txt"))).use {
                    var line = it.readLine()
                    while(line != null) {
                        line.split(',').apply { labelIndex.add(Pair(this[0], this[1].toFloat())) }
                        line = it.readLine()
                    }
                }

                for(photo in photos) {
                    if (!isActive) return@launch

                    // Decode file with dimension just above 300
                    size = 1
                    length = Integer.min(photo.width, photo.height)
                    while(length / size > 600) { size *= 2 }
                    option.inSampleSize = size
                    val bitmap =
                        if (searchInAlbums) BitmapFactory.decodeFile("$rootPath/${photo.id}", option)
                        else BitmapFactory.decodeStream(app.contentResolver.openInputStream(Uri.parse(photo.id)),null, option)

                    // Inference
                    bitmap?.let {
                        with(od.recognizeImage(Bitmap.createScaledBitmap(bitmap, 300, 300, true))) {
                            if (this.isNotEmpty()) with(this[0]) {
                                val found = labelIndex[this.title.toInt()]
                                if (found.first == categoryId && this.confidence > found.second) {
                                    resultList.add(Result(photo, this.title, this.confidence))
                                    result.postValue(resultList)
                                } else {
                                    // Special inference
                                    if (categoryId == CATEGORY_PLANT) {
                                        if ((this.title == "51" || this.title == "52" || this.title == "54") && this.confidence < found.second && this.confidence > 0.3) {
                                            // "banana 51", "apple 52", "orange 54", could well be plant
                                            resultList.add(Result(photo, this.title, this.confidence))
                                            result.postValue(resultList)
                                        }
                                        if ((this.title == "56" || this.title == "22" || this.title == "55") && this.confidence < 0.45 && this.confidence > 0.24) {
                                            // Low confidence "carrot 56", "bear 22", "broccoli 55" could be plant
                                            resultList.add(Result(photo, this.title, this.confidence))
                                            result.postValue(resultList)
                                        }
                                        /* too much false positive
                                        if ((this.title == "15") && this.confidence < 0.5 && this.confidence > 0.36) {
                                            // "bird 15" with confidence range of 0.35~0.5 could be plant
                                            resultList.add(Result(photo, this.title, this.confidence))
                                            result.postValue(resultList)
                                        }
                                         */
                                    }
                                }
                            }
                        }
                    }
                }
                // Inform caller that search is finished
                finished.postValue(true)
            }
        }

        override fun onCleared() {
            // Stop search coroutine
            job?.cancel()

            super.onCleared()
        }

        fun isFinished(): LiveData<Boolean> = finished
        fun getResultList(): LiveData<List<Result>> = result
    }

    class SearchResultAdapter(private val clickListener: (Result) -> Unit, private val imageLoader: (Photo, ImageView, String) -> Unit
    ): ListAdapter<Result, SearchResultAdapter.ViewHolder>(SearchResultDiffCallback()) {
        private val albumNames = HashMap<String, String>()

        inner class ViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
            @SuppressLint("SetTextI18n")
            fun bind(item: Result) {
                with(itemView.findViewById<ImageView>(R.id.photo)) {
                    imageLoader(item.photo, this, ImageLoaderViewModel.TYPE_GRID)
                    setOnClickListener { clickListener(item) }
                }
                //itemView.findViewById<TextView>(R.id.label).text = "${item.subLabel}${String.format("  %.4f", item.similarity)}"
                itemView.findViewById<TextView>(R.id.label).text =
                    if (item.photo.albumId != ImageLoaderViewModel.FROM_CAMERA_ROLL) albumNames[item.photo.albumId]
                    else item.photo.dateTaken.run { "${this.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${this.year}/${this.monthValue}/${this.dayOfMonth}" }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
            ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_search_result, parent, false))

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        override fun submitList(list: List<Result>?) {
            super.submitList(list?.toMutableList())
        }

        fun setAlbumNameList(list: List<IDandName>) {
            for (album in list) { albumNames[album.id] = album.name }
        }
    }

    class SearchResultDiffCallback : DiffUtil.ItemCallback<Result>() {
        override fun areItemsTheSame(oldItem: Result, newItem: Result): Boolean {
            return oldItem.photo.id == newItem.photo.id
        }

        override fun areContentsTheSame(oldItem: Result, newItem: Result): Boolean {
            return oldItem == newItem
        }
    }

    data class Result(
        val photo: Photo,
        val subLabel: String,
        val similarity: Float,
    )

    companion object {
        private const val CATEGORY_TYPE = "CATEGORY_TYPE"
        private const val CATEGORY_ID = "CATEGORY_ID"
        private const val CATEGORY_LABEL = "CATEGORY_LABEL"

        private const val CATEGORY_ANIMAL = "1"
        private const val CATEGORY_PLANT = "2"
        private const val CATEGORY_FOOD = "3"
        private const val CATEGORY_VEHICLE = "4"

        @JvmStatic
        fun newInstance(categoryType: Int, categoryId: String, categoryLabel: String, searchCollection: Boolean) = SearchResultFragment().apply {
            arguments = Bundle().apply {
                putInt(CATEGORY_TYPE, categoryType)
                putString(CATEGORY_ID, categoryId)
                putString(CATEGORY_LABEL, categoryLabel)
                putBoolean(SEARCH_COLLECTION, searchCollection)
            }
        }
    }
}