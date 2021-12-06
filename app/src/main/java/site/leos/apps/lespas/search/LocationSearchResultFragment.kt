package site.leos.apps.lespas.search

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.transition.MaterialContainerTransform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.AlbumViewModel
import site.leos.apps.lespas.album.IDandName
import site.leos.apps.lespas.helper.ImageLoaderViewModel
import site.leos.apps.lespas.photo.Photo
import java.util.*

class LocationSearchResultFragment: Fragment() {
    private lateinit var locality: String
    private lateinit var country: String

    private lateinit var photoAdapter: PhotoAdapter
    private val albumModel: AlbumViewModel by activityViewModels()
    private val imageLoaderModel: ImageLoaderViewModel by activityViewModels()
    private val searchViewModel: LocationSearchHostFragment.LocationSearchViewModel by viewModels({requireParentFragment()}) { LocationSearchHostFragment.LocationSearchViewModelFactory(requireActivity().application) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)

        photoAdapter = PhotoAdapter(
            { photo, view ->
                parentFragmentManager.beginTransaction()
                    .setReorderingAllowed(true)
                    .addSharedElement(view, view.transitionName)
                    .replace(R.id.container_child_fragment, PhotoWithMapFragment.newInstance(photo), PhotoWithMapFragment::class.java.canonicalName).addToBackStack(null).commit()
            },
            { photo, imageView -> imageLoaderModel.loadPhoto(photo, imageView, ImageLoaderViewModel.TYPE_GRID) { startPostponedEnterTransition() }}
        ).apply {
            lifecycleScope.launch(Dispatchers.IO) { setAlbumNameList(albumModel.getAllAlbumIdName()) }
            stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        }

        requireArguments().apply {
            locality = getString(KEY_LOCALITY)!!
            country = getString(KEY_COUNTRY)!!
        }

        sharedElementEnterTransition = MaterialContainerTransform().apply {
            duration = resources.getInteger(android.R.integer.config_mediumAnimTime).toLong()
            scrimColor = Color.TRANSPARENT
            //fadeMode = MaterialContainerTransform.FADE_MODE_CROSS
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? = inflater.inflate(R.layout.fragment_location_search_result, container, false)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        postponeEnterTransition()

        view.findViewById<RecyclerView>(R.id.photogrid)?.apply {
            adapter = photoAdapter
            ViewCompat.setTransitionName(this, locality)
        }

        searchViewModel.getResult().observe(viewLifecycleOwner, { resultList->
            resultList.find { it.locality == locality && it.country == country }?.photos?.apply { photoAdapter.submitList(toMutableList()) }
        })
    }

    override fun onResume() {
        super.onResume()
        (requireActivity() as AppCompatActivity).supportActionBar?.run {
            title = locality
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowTitleEnabled(true)
        }
    }

    override fun onPause() {
        super.onPause()
        (parentFragment as LocationSearchHostFragment).disableMenuItem(R.id.option_menu_in_map)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)
        (parentFragment as LocationSearchHostFragment).enableMenuItem(R.id.option_menu_in_map)
    }

    class PhotoAdapter(private val clickListener: (LocationSearchFragment.PhotoWithCoordinate, View) -> Unit, private val imageLoader: (Photo, ImageView) -> Unit
    ): ListAdapter<LocationSearchFragment.PhotoWithCoordinate, PhotoAdapter.ViewHolder>(PhotoDiffCallback()) {
        private val albumNames = HashMap<String, String>()

        inner class ViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
            private val ivPhoto = itemView.findViewById<ImageView>(R.id.photo)
            private val tvLabel = itemView.findViewById<TextView>(R.id.label)

            fun bind(item: LocationSearchFragment.PhotoWithCoordinate) {
                with(item.photo) {
                    imageLoader(this, ivPhoto)
                    ivPhoto.setOnClickListener { clickListener(item, ivPhoto) }
                    ViewCompat.setTransitionName(ivPhoto, this.id)

                    tvLabel.text = albumNames[this.albumId]
                }

            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_search_result, parent, false))
        override fun onBindViewHolder(holder: ViewHolder, position: Int) { holder.bind(getItem(position)) }

        fun setAlbumNameList(list: List<IDandName>) { for (album in list) { albumNames[album.id] = album.name }}
    }

    class PhotoDiffCallback: DiffUtil.ItemCallback<LocationSearchFragment.PhotoWithCoordinate>() {
        override fun areItemsTheSame(oldItem: LocationSearchFragment.PhotoWithCoordinate, newItem: LocationSearchFragment.PhotoWithCoordinate): Boolean = oldItem.photo.id == newItem.photo.id
        override fun areContentsTheSame(oldItem: LocationSearchFragment.PhotoWithCoordinate, newItem: LocationSearchFragment.PhotoWithCoordinate): Boolean = oldItem.photo.eTag == newItem.photo.eTag
    }

    companion object {
        const val KEY_LOCALITY = "KEY_LOCALITY"
        const val KEY_COUNTRY = "KEY_COUNTRY"

        @JvmStatic
        fun newInstance(locality: String, country: String) = LocationSearchResultFragment().apply {
            arguments = Bundle().apply {
                putString(KEY_LOCALITY, locality)
                putString(KEY_COUNTRY, country)
            }
        }
    }
}