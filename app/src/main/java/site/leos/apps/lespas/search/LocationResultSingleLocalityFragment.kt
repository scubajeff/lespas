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

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.ActionBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.MenuProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.transition.MaterialContainerTransform
import com.google.android.material.transition.MaterialSharedAxis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.AlbumViewModel
import site.leos.apps.lespas.album.IDandName
import site.leos.apps.lespas.gallery.GalleryFragment
import site.leos.apps.lespas.publication.NCShareViewModel
import site.leos.apps.lespas.sync.ActionViewModel
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

class LocationResultSingleLocalityFragment: Fragment() {
    private lateinit var locality: String
    private lateinit var country: String

    private lateinit var photoAdapter: PhotoAdapter
    private lateinit var photoList: RecyclerView
    private val albumModel: AlbumViewModel by activityViewModels()
    private val imageLoaderModel: NCShareViewModel by activityViewModels()
    private val actionModel: ActionViewModel by viewModels()
    private val searchModel: SearchFragment.SearchModel by viewModels(ownerProducer = { requireParentFragment() }) { SearchFragment.SearchModelFactory(requireActivity().application, imageLoaderModel, actionModel)}
    //private val searchViewModel: LocationSearchHostFragment.LocationSearchViewModel by viewModels(ownerProducer = { requireParentFragment() }) { LocationSearchHostFragment.LocationSearchViewModelFactory(requireActivity().application, requireArguments().getInt(KEY_TARGET), imageLoaderModel, searchPayloadModel) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        @Suppress("DEPRECATION")
        setHasOptionsMenu(true)

        photoAdapter = PhotoAdapter(
            { photo, view, labelView ->
                labelView.isVisible = false
/*
                reenterTransition = MaterialElevationScale(true).apply { duration = resources.getInteger(android.R.integer.config_mediumAnimTime).toLong() }
                exitTransition = MaterialElevationScale(true).apply {
                    duration = resources.getInteger(android.R.integer.config_shortAnimTime).toLong()
                    excludeTarget(view, true)
                    excludeTarget(labelView, true)
                }
*/
                parentFragmentManager.beginTransaction()
                    .setReorderingAllowed(true)
                    .addSharedElement(view, view.transitionName)
                    .replace(R.id.container_child_fragment, PhotoWithMapFragment.newInstance(photo, requireArguments().getInt(KEY_TARGET)), PhotoWithMapFragment::class.java.canonicalName).addToBackStack(null).commit()
            },
            { remotePhoto, imageView -> imageLoaderModel.setImagePhoto(remotePhoto, imageView, NCShareViewModel.TYPE_GRID) { startPostponedEnterTransition() }},
            { view -> imageLoaderModel.cancelSetImagePhoto(view) }
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
            fadeMode = MaterialContainerTransform.FADE_MODE_CROSS
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? = inflater.inflate(R.layout.fragment_location_result_single_locality, container, false)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        postponeEnterTransition()

        photoList = view.findViewById(R.id.photo_grid)
        photoList.run {
            adapter = photoAdapter
            ViewCompat.setTransitionName(this, locality)

            // Avoid window inset overlapping
            ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
                val displayCutoutInset = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
                val navigationBarInset = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
                v.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    rightMargin = displayCutoutInset.right + navigationBarInset.right
                    leftMargin = displayCutoutInset.left + navigationBarInset.left
                }
                insets
            }
        }

        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.search_location_on_map_menu, menu)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                return when (menuItem.itemId) {
                    R.id.option_menu_in_map -> {
                        reenterTransition = MaterialSharedAxis(MaterialSharedAxis.Z, false).apply { duration = resources.getInteger(android.R.integer.config_longAnimTime).toLong() }
                        exitTransition = MaterialSharedAxis(MaterialSharedAxis.Z, true).apply { duration = resources.getInteger(android.R.integer.config_longAnimTime).toLong() }
                        parentFragmentManager.beginTransaction().replace(R.id.container_child_fragment, PhotosInMapFragment.newInstance(locality, country, photoAdapter.getAlbumNameList(), requireArguments().getInt(KEY_TARGET)), PhotosInMapFragment::class.java.canonicalName).addToBackStack(null).commit()

                        true
                    }

                    else -> false
                }
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)

        viewLifecycleOwner.lifecycleScope.launch {
            searchModel.locationSearchResult.collect { resultList ->
                resultList.find { it.locality == locality && it.country == country }?.photos?.apply { photoAdapter.submitList(toMutableList()) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        (requireActivity() as AppCompatActivity).supportActionBar?.run {
            title = locality
            displayOptions = ActionBar.DISPLAY_HOME_AS_UP or ActionBar.DISPLAY_SHOW_TITLE
        }
    }

    override fun onDestroyView() {
        photoList.adapter = null
        super.onDestroyView()
    }

    @Deprecated("Deprecated in Java")
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when(item.itemId) {
            R.id.option_menu_in_map-> {
                reenterTransition = MaterialSharedAxis(MaterialSharedAxis.Z, false).apply { duration = resources.getInteger(android.R.integer.config_longAnimTime).toLong() }
                exitTransition = MaterialSharedAxis(MaterialSharedAxis.Z, true).apply { duration = resources.getInteger(android.R.integer.config_longAnimTime).toLong() }
                parentFragmentManager.beginTransaction().replace(R.id.container_child_fragment, PhotosInMapFragment.newInstance(locality, country, photoAdapter.getAlbumNameList(), requireArguments().getInt(KEY_TARGET)), PhotosInMapFragment::class.java.canonicalName).addToBackStack(null).commit()
                true
            }
            else-> false
        }
    }

    class PhotoAdapter(private val clickListener: (NCShareViewModel.RemotePhoto, View, View) -> Unit, private val imageLoader: (NCShareViewModel.RemotePhoto, ImageView) -> Unit, private val cancelLoader: (View) -> Unit
    ): ListAdapter<NCShareViewModel.RemotePhoto, PhotoAdapter.ViewHolder>(PhotoDiffCallback()) {
        private val albumNames = HashMap<String, String>()

        inner class ViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
            private var currentPhotoId = ""
            private val ivPhoto = itemView.findViewById<ImageView>(R.id.photo)
            private val tvLabel = itemView.findViewById<TextView>(R.id.label)

            fun bind(item: NCShareViewModel.RemotePhoto) {
                with(item.photo) {
                    if (currentPhotoId != item.photo.id) {
                        imageLoader(item, ivPhoto)
                        ViewCompat.setTransitionName(ivPhoto, this.id)
                        currentPhotoId = item.photo.id
                    }
                    ivPhoto.setOnClickListener { clickListener(item, ivPhoto, tvLabel) }

                    tvLabel.text =
                        if (this.albumId != GalleryFragment.FROM_DEVICE_GALLERY) albumNames[this.albumId]
                        else this.dateTaken.run { "${this.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())}, ${this.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT))}" }
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_search_result, parent, false))
        override fun onBindViewHolder(holder: ViewHolder, position: Int) { holder.bind(getItem(position)) }
        override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
            for (i in 0 until currentList.size) {
                recyclerView.findViewHolderForAdapterPosition(i)?.let { holder -> holder.itemView.findViewById<View>(R.id.photo)?.let { cancelLoader(it) }}
            }
            super.onDetachedFromRecyclerView(recyclerView)
        }

        fun setAlbumNameList(list: List<IDandName>) { for (album in list) { albumNames[album.id] = album.name }}
        fun getAlbumNameList(): HashMap<String, String> = albumNames
    }

    class PhotoDiffCallback: DiffUtil.ItemCallback<NCShareViewModel.RemotePhoto>() {
        override fun areItemsTheSame(oldItem: NCShareViewModel.RemotePhoto, newItem: NCShareViewModel.RemotePhoto): Boolean = oldItem.photo.id == newItem.photo.id
        override fun areContentsTheSame(oldItem: NCShareViewModel.RemotePhoto, newItem: NCShareViewModel.RemotePhoto): Boolean = oldItem.photo.eTag == newItem.photo.eTag
    }

    companion object {
        private const val KEY_LOCALITY = "KEY_LOCALITY"
        private const val KEY_COUNTRY = "KEY_COUNTRY"
        private const val KEY_TARGET = "KEY_TARGET"

        @JvmStatic
        fun newInstance(locality: String, country: String, target: Int) = LocationResultSingleLocalityFragment().apply {
            arguments = Bundle().apply {
                putString(KEY_LOCALITY, locality)
                putString(KEY_COUNTRY, country)
                putInt(KEY_TARGET, target)
            }
        }
    }
}