package site.leos.apps.lespas.search

import android.annotation.SuppressLint
import android.app.Application
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.SharedElementCallback
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.*
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.transition.MaterialElevationScale
import kotlinx.android.synthetic.main.recyclerview_item_photo.view.*
import kotlinx.coroutines.*
import site.leos.apps.lespas.MainActivity
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.*
import site.leos.apps.lespas.cameraroll.CameraRollFragment
import site.leos.apps.lespas.helper.ImageLoaderViewModel
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.photo.PhotoRepository
import site.leos.apps.lespas.tflite.ObjectDetectionModel
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.*
import kotlin.collections.HashMap

class SearchResultFragment : Fragment() {
    private lateinit var searchResultAdapter: SearchResultAdapter
    private lateinit var searchResultRecyclerView: RecyclerView
    private lateinit var stub: View
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
            { result, imageView ->
                if (arguments?.getBoolean(SEARCH_COLLECTION)!!) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val album = albumModel.getThisAlbum(result.photo.albumId)[0]
                        withContext(Dispatchers.Main) {
                            exitTransition = MaterialElevationScale(false).apply { duration = resources.getInteger(android.R.integer.config_shortAnimTime).toLong() }
                            //reenterTransition = MaterialElevationScale(true).apply { duration = resources.getInteger(android.R.integer.config_shortAnimTime).toLong() }
                            parentFragmentManager.beginTransaction().setReorderingAllowed(true).addSharedElement(imageView, ViewCompat.getTransitionName(imageView)!!)
                                .replace(R.id.container_root, AlbumDetailFragment.newInstance(album, result.photo.id), AlbumDetailFragment::class.java.canonicalName).addToBackStack(null).commit()
                        }
                    }
                }
                else {
                    // Adjusting the shared element mapping
                    setExitSharedElementCallback(object : SharedElementCallback() {
                        override fun onMapSharedElements(names: MutableList<String>?, sharedElements: MutableMap<String, View>?) {
                            sharedElements?.put(names?.get(0)!!, imageView)
                        }
                    })
                    // Get a stub as fake toolbar since the toolbar belongs to MainActivity and it will disappear during fragment transaction
                    stub.background = (activity as MainActivity).getToolbarViewContent()
                    exitTransition = MaterialElevationScale(false).apply {
                        duration = resources.getInteger(android.R.integer.config_shortAnimTime).toLong()
                        excludeTarget(R.id.stub, true)
                    }
                    //reenterTransition = MaterialElevationScale(true).apply { duration = resources.getInteger(android.R.integer.config_shortAnimTime).toLong() }
                    parentFragmentManager.beginTransaction().setReorderingAllowed(true).addSharedElement(imageView, ViewCompat.getTransitionName(imageView)!!)
                        .replace(R.id.container_root, CameraRollFragment.newInstance(result.photo.id), CameraRollFragment::class.java.canonicalName).addToBackStack(null).commit()
                }
            },
            { photo: Photo, view: ImageView, type: String -> imageLoaderModel.loadPhoto(photo, view, type) }
        ).apply {
            // Get album's name for display
            Thread { setAlbumNameList(albumModel.getAllAlbumName()) }.start()
            stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.fragment_albumdetail, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        searchResultRecyclerView = view.findViewById(R.id.photogrid)
        stub = view.findViewById(R.id.stub)

        searchResultAdapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            init {
                toggleEmptyView()
            }

            private fun toggleEmptyView() {
                if (searchResultAdapter.itemCount == 0) {
                    searchResultRecyclerView.visibility = View.GONE
                    view.findViewById<ImageView>(R.id.emptyview).visibility = View.VISIBLE
                } else {
                    searchResultRecyclerView.visibility = View.VISIBLE
                    view.findViewById<ImageView>(R.id.emptyview).visibility = View.GONE
                }
            }

            override fun onChanged() {
                super.onChanged()
                toggleEmptyView()
            }

            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                super.onItemRangeInserted(positionStart, itemCount)
                toggleEmptyView()
            }

            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
                super.onItemRangeRemoved(positionStart, itemCount)
                toggleEmptyView()
            }
        })

        searchResultRecyclerView.adapter = searchResultAdapter

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
                val photos = if (searchInAlbums) PhotoRepository(app).getAllImage() else Tools.getCameraRoll(app.contentResolver, true)
                val od = ObjectDetectionModel(app.assets)
                val rootPath = "${app.filesDir}${app.getString(R.string.lespas_base_folder_name)}"
                var length: Int
                var size: Int
                val option = BitmapFactory.Options()

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
                        with(od.recognizeImage(it)) {
                            if (this.isNotEmpty()) with(this[0]) {
                                if (this.classId == categoryId) {
                                    resultList.add(Result(photo, this.objectIndex, this.similarity))
                                    result.postValue(resultList)
                                }
                            }
                        }
                    }
                }
                // Inform caller that search is finished
                finished.postValue(true)

                od.close()
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

    class SearchResultAdapter(private val clickListener: (Result, ImageView) -> Unit, private val imageLoader: (Photo, ImageView, String) -> Unit
    ): ListAdapter<Result, SearchResultAdapter.ViewHolder>(SearchResultDiffCallback()) {
        private val albumNames = HashMap<String, String>()

        inner class ViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
            @SuppressLint("SetTextI18n")
            fun bind(item: Result) {
                with(itemView.findViewById<ImageView>(R.id.photo)) {
                    imageLoader(item.photo, this, ImageLoaderViewModel.TYPE_GRID)
                    setOnClickListener { clickListener(item, this) }
                    ViewCompat.setTransitionName(this, item.photo.id)
                }
                //itemView.findViewById<TextView>(R.id.label).text = "${item.subLabel}${String.format("  %.4f", item.similarity)}"
                itemView.findViewById<TextView>(R.id.label).text =
                    if (item.photo.albumId != ImageLoaderViewModel.FROM_CAMERA_ROLL) albumNames[item.photo.albumId]
                    else item.photo.dateTaken.run { "${this.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())}, ${this.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT))}" }
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
        val subLabelIndex: Int,
        val similarity: Float,
    )

    companion object {
        private const val SEARCH_COLLECTION = "SEARCH_COLLECTION"

        private const val CATEGORY_TYPE = "CATEGORY_TYPE"
        private const val CATEGORY_ID = "CATEGORY_ID"
        private const val CATEGORY_LABEL = "CATEGORY_LABEL"

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