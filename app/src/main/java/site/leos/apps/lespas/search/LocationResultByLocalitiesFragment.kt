package site.leos.apps.lespas.search

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.doOnPreDraw
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.transition.MaterialElevationScale
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.ImageLoaderViewModel
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.photo.PhotoWithCoordinate

class LocationResultByLocalitiesFragment: Fragment() {
    private val imageLoaderModel: ImageLoaderViewModel by activityViewModels()
    private val searchViewModel: LocationSearchHostFragment.LocationSearchViewModel by viewModels({requireParentFragment()}) { LocationSearchHostFragment.LocationSearchViewModelFactory(requireActivity().application, true) }

    private lateinit var resultAdapter: LocationSearchResultAdapter
    private lateinit var resultView: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        resultAdapter = LocationSearchResultAdapter(
            { result, view ->
                reenterTransition = MaterialElevationScale(true).apply { duration = resources.getInteger(android.R.integer.config_mediumAnimTime).toLong() }
                exitTransition = MaterialElevationScale(false).apply { duration = resources.getInteger(android.R.integer.config_mediumAnimTime).toLong() }
                parentFragmentManager.beginTransaction()
                    .setReorderingAllowed(true)
                    .addSharedElement(view, view.transitionName)
                    .replace(R.id.container_child_fragment, LocationResultSingleLocalityFragment.newInstance(result.locality, result.country), LocationResultSingleLocalityFragment::class.java.canonicalName).addToBackStack(null).commit()
            },
            { photo, imageView -> imageLoaderModel.loadPhoto(photo, imageView, ImageLoaderViewModel.TYPE_GRID) },
        ).apply { stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View = inflater.inflate(R.layout.fragment_location_result_by_localities, container, false)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        postponeEnterTransition()
        view.doOnPreDraw { startPostponedEnterTransition() }

        resultView = view.findViewById<RecyclerView>(R.id.category_list).apply {
            adapter = resultAdapter
        }

        searchViewModel.getResult().observe(viewLifecycleOwner, { result ->
            val items = mutableListOf<LocationSearchHostFragment.LocationSearchResult>()
            var photoList: List<PhotoWithCoordinate>

            // General a new result list, this is crucial for DiffUtil to detect changes
            result.forEach {
                // Take the last 4 since we only show 4, this also create a new list which is crucial for DiffUtil to detect changes in nested list
                photoList = it.photos.takeLast(4)
                items.add(LocationSearchHostFragment.LocationSearchResult(photoList.toMutableList(), it.total, it.country, it.locality))
            }

            resultAdapter.submitList(items.sortedWith(compareBy({it.country}, {it.locality})))
            //resultAdapter.submitList(result.map { it.copy() }.sortedWith(compareBy({it.country}, {it.locality})))
        })
    }

    override fun onResume() {
        super.onResume()
        (requireActivity() as AppCompatActivity).supportActionBar?.run {
            title = getString(R.string.item_location_search)
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowTitleEnabled(true)
        }
    }

    class LocationSearchResultAdapter(private val clickListener: (LocationSearchHostFragment.LocationSearchResult, View) -> Unit, private val imageLoader: (Photo, ImageView) -> Unit
    ): ListAdapter<LocationSearchHostFragment.LocationSearchResult, LocationSearchResultAdapter.ViewHolder>(LocationSearchResultDiffCallback()) {
        inner class ViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
            private var photoAdapter = PhotoAdapter { photo, imageView ->  imageLoader(photo, imageView) }
            private val tvCountry = itemView.findViewById<TextView>(R.id.country)
            private val tvLocality = itemView.findViewById<TextView>(R.id.locality)
            private val tvCount = itemView.findViewById<TextView>(R.id.count)
            private val rvPhoto = itemView.findViewById<RecyclerView>(R.id.items)
            private val photoRVViewPool = RecyclerView.RecycledViewPool()

            init {
                rvPhoto.apply {
                    adapter = photoAdapter
                    setRecycledViewPool(photoRVViewPool)
                    layoutManager = object : GridLayoutManager(this.context, 2) {
                        override fun canScrollHorizontally(): Boolean = false
                        override fun canScrollVertically(): Boolean = false
                    }.apply {
                        isItemPrefetchEnabled = false
                    }
                }
            }

            fun bind(item: LocationSearchHostFragment.LocationSearchResult) {
                tvLocality.text = item.locality
                tvCountry.text = item.country
                tvCount.text = if (item.total > 4) item.total.toString() else ""

                photoAdapter.submitList(item.photos)

                itemView.setOnClickListener { clickListener(item, rvPhoto) }
                ViewCompat.setTransitionName(rvPhoto, item.locality)
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_location_search, parent, false))
        override fun onBindViewHolder(holder: ViewHolder, position: Int) { holder.bind(getItem(position)) }
    }

    class LocationSearchResultDiffCallback: DiffUtil.ItemCallback<LocationSearchHostFragment.LocationSearchResult>() {
        override fun areItemsTheSame(oldItem: LocationSearchHostFragment.LocationSearchResult, newItem: LocationSearchHostFragment.LocationSearchResult): Boolean = oldItem.country == newItem.country && oldItem.locality == newItem.locality
        override fun areContentsTheSame(oldItem: LocationSearchHostFragment.LocationSearchResult, newItem: LocationSearchHostFragment.LocationSearchResult): Boolean = oldItem.photos.last().photo.id == newItem.photos.last().photo.id
    }

    class PhotoAdapter(private val imageLoader: (Photo, ImageView) -> Unit): ListAdapter<PhotoWithCoordinate, PhotoAdapter.ViewHolder>(PhotoDiffCallback()) {
        inner class ViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
            private val ivPhoto = itemView.findViewById<ImageView>(R.id.photo)

            fun bind(item: PhotoWithCoordinate) {
                imageLoader(item.photo, ivPhoto)
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_photo, parent, false))
        override fun onBindViewHolder(holder: ViewHolder, position: Int) { holder.bind(getItem(position)) }
    }

    class PhotoDiffCallback: DiffUtil.ItemCallback<PhotoWithCoordinate>() {
        override fun areItemsTheSame(oldItem: PhotoWithCoordinate, newItem: PhotoWithCoordinate): Boolean = oldItem.photo.id == newItem.photo.id
        override fun areContentsTheSame(oldItem: PhotoWithCoordinate, newItem: PhotoWithCoordinate): Boolean = oldItem.photo.eTag == newItem.photo.eTag
    }
}