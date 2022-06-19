package site.leos.apps.lespas.search

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
import site.leos.apps.lespas.helper.LesPasEmptyView
import site.leos.apps.lespas.publication.NCShareViewModel
import java.text.Collator

class LocationResultByLocalitiesFragment: Fragment() {
    private val imageLoaderModel: NCShareViewModel by activityViewModels()
    private val searchViewModel: LocationSearchHostFragment.LocationSearchViewModel by viewModels({requireParentFragment()}) { LocationSearchHostFragment.LocationSearchViewModelFactory(requireActivity().application, requireArguments().getInt(KEY_SEARCH_TARGET), imageLoaderModel) }

    private lateinit var resultAdapter: LocationSearchResultAdapter
    private lateinit var resultView: RecyclerView

    private var searchTarget = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        searchTarget = requireArguments().getInt(KEY_SEARCH_TARGET)

        resultAdapter = LocationSearchResultAdapter(
            { result, view ->
                reenterTransition = MaterialElevationScale(true).apply {
                    duration = resources.getInteger(android.R.integer.config_mediumAnimTime).toLong()
                    excludeTarget(view, true)
                    excludeChildren(view, true)
                }
                //exitTransition = MaterialElevationScale(false).apply { duration = resources.getInteger(android.R.integer.config_mediumAnimTime).toLong() }
                parentFragmentManager.beginTransaction()
                    .setReorderingAllowed(true)
                    .addSharedElement(view, view.transitionName)
                    .replace(R.id.container_child_fragment, LocationResultSingleLocalityFragment.newInstance(result.locality, result.country, searchTarget), LocationResultSingleLocalityFragment::class.java.canonicalName).addToBackStack(null).commit()
            },
            { remotePhoto, imageView -> imageLoaderModel.setImagePhoto(remotePhoto, imageView, NCShareViewModel.TYPE_GRID) },
            { view -> imageLoaderModel.cancelSetImagePhoto(view) }
        ).apply { stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View = inflater.inflate(R.layout.fragment_location_result_by_localities, container, false)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        postponeEnterTransition()
        view.doOnPreDraw { startPostponedEnterTransition() }

        resultView = view.findViewById<RecyclerView>(R.id.locality_list).apply {
            adapter = resultAdapter
            addItemDecoration(LesPasEmptyView(ContextCompat.getDrawable(this.context,
                when(searchTarget) {
                    R.id.search_album -> R.drawable.ic_baseline_footprint_24
                    R.id.search_archive -> R.drawable.ic_baseline_archive_24
                    else -> R.drawable.ic_baseline_camera_roll_24
                }
            )!!))
        }

        searchViewModel.getResult().observe(viewLifecycleOwner) {
            val result = mutableListOf<LocationSearchHostFragment.LocationSearchResult>().apply { addAll(it) }
            val items = mutableListOf<LocationSearchHostFragment.LocationSearchResult>()
            var photoList: List<NCShareViewModel.RemotePhoto>

            // TODO intermediary
            // General a new result list, this is crucial for DiffUtil to detect changes
            result.forEach {
                // Take the last 4 since we only show 4, this also create a new list which is crucial for DiffUtil to detect changes in nested list
                photoList = it.photos.takeLast(4)
                items.add(LocationSearchHostFragment.LocationSearchResult(photoList.asReversed().toMutableList(), it.total, it.country, it.locality))
            }

            resultAdapter.submitList(items.sortedWith(compareBy<LocationSearchHostFragment.LocationSearchResult, String>(Collator.getInstance().apply { strength = Collator.PRIMARY }) { it.country }.then(compareBy(Collator.getInstance().apply { strength = Collator.PRIMARY }) { it.locality })))
        }
    }

    override fun onResume() {
        super.onResume()
        (requireActivity() as AppCompatActivity).supportActionBar?.run {
            title = getString(
                when(searchTarget) {
                    R.id.search_album -> R.string.title_in_album
                    R.id.search_cameraroll -> R.string.title_in_cameraroll
                    else -> R.string.title_in_archive
                },
                getString(R.string.item_location_search)
            )
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowTitleEnabled(true)
        }
    }

    override fun onDestroyView() {
        resultView.adapter = null
        super.onDestroyView()
    }

    class LocationSearchResultAdapter(private val clickListener: (LocationSearchHostFragment.LocationSearchResult, View) -> Unit, private val imageLoader: (NCShareViewModel.RemotePhoto, ImageView) -> Unit, private val cancelLoader: (View) -> Unit
    ): ListAdapter<LocationSearchHostFragment.LocationSearchResult, LocationSearchResultAdapter.ViewHolder>(LocationSearchResultDiffCallback()) {
        inner class ViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
            private var photoAdapter = PhotoAdapter({ photo, imageView ->  imageLoader(photo, imageView) }, { view -> cancelLoader(view) })
            private val tvCountry = itemView.findViewById<TextView>(R.id.country)
            private val tvLocality = itemView.findViewById<TextView>(R.id.locality)
            private val tvCount = itemView.findViewById<TextView>(R.id.count)
            private val rvPhoto = itemView.findViewById<RecyclerView>(R.id.photos)
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
                tvCount.text = if (item.total >= 4) item.total.toString() else ""

                photoAdapter.submitList(item.photos)

                itemView.setOnClickListener { clickListener(item, rvPhoto) }
                ViewCompat.setTransitionName(rvPhoto, item.locality)
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_location_search, parent, false))
        override fun onBindViewHolder(holder: ViewHolder, position: Int) { holder.bind(getItem(position)) }
        override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
            for (i in 0 until currentList.size) recyclerView.findViewHolderForAdapterPosition(i)?.let { holder -> holder.itemView.findViewById<RecyclerView>(R.id.photos)?.adapter = null }
            super.onDetachedFromRecyclerView(recyclerView)
        }
    }

    class LocationSearchResultDiffCallback: DiffUtil.ItemCallback<LocationSearchHostFragment.LocationSearchResult>() {
        override fun areItemsTheSame(oldItem: LocationSearchHostFragment.LocationSearchResult, newItem: LocationSearchHostFragment.LocationSearchResult): Boolean = oldItem.country == newItem.country && oldItem.locality == newItem.locality
        override fun areContentsTheSame(oldItem: LocationSearchHostFragment.LocationSearchResult, newItem: LocationSearchHostFragment.LocationSearchResult): Boolean = oldItem.photos.first().photo.id == newItem.photos.first().photo.id
    }

    class PhotoAdapter(private val imageLoader: (NCShareViewModel.RemotePhoto, ImageView) -> Unit, private val cancelLoader: (View) -> Unit): ListAdapter<NCShareViewModel.RemotePhoto, PhotoAdapter.ViewHolder>(PhotoDiffCallback()) {
        inner class ViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
            private val ivPhoto = itemView.findViewById<ImageView>(R.id.photo)

            fun bind(item: NCShareViewModel.RemotePhoto) {
                imageLoader(item, ivPhoto)
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_photo, parent, false))
        override fun onBindViewHolder(holder: ViewHolder, position: Int) { holder.bind(getItem(position)) }
        override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
            for (i in 0 until currentList.size) {
                recyclerView.findViewHolderForAdapterPosition(i)?.let { holder -> holder.itemView.findViewById<View>(R.id.photo)?.let { cancelLoader(it) }}
            }
            super.onDetachedFromRecyclerView(recyclerView)
        }
    }

    class PhotoDiffCallback: DiffUtil.ItemCallback<NCShareViewModel.RemotePhoto>() {
        override fun areItemsTheSame(oldItem: NCShareViewModel.RemotePhoto, newItem: NCShareViewModel.RemotePhoto): Boolean = oldItem.photo.id == newItem.photo.id
        override fun areContentsTheSame(oldItem: NCShareViewModel.RemotePhoto, newItem: NCShareViewModel.RemotePhoto): Boolean = oldItem.photo.eTag == newItem.photo.eTag
    }

    companion object {
        private const val KEY_SEARCH_TARGET = "KEY_SEARCH_TARGET"

        @JvmStatic
        fun newInstance(target: Int) = LocationResultByLocalitiesFragment().apply { arguments = Bundle().apply { putInt(KEY_SEARCH_TARGET, target) } }
    }
}