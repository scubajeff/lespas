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

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.view.*
import android.widget.ImageView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.MenuProvider
import androidx.core.view.doOnPreDraw
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.transition.MaterialSharedAxis
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.Tools

class SearchFragment : Fragment() {
    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var categoryView: RecyclerView
    private var destinationToggleGroup: MaterialButtonToggleGroup? = null

    private var savedDestination = 0
    // Flag indicating if we have existing albums or not
    private var noAlbum = true

    private lateinit var storagePermissionRequestLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var accessMediaLocationPermissionRequestLauncher: ActivityResultLauncher<String>
    //private lateinit var exifPermissionRequestLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        categoryAdapter = CategoryAdapter { category ->
            if (destinationToggleGroup?.checkedButtonId == R.id.search_album && noAlbum) {
                Snackbar.make(categoryView, getString(R.string.need_albums), Snackbar.LENGTH_SHORT).show()
                return@CategoryAdapter
            }
            if (destinationToggleGroup?.checkedButtonId == R.id.search_cameraroll) {
                if (Tools.shouldRequestStoragePermission(requireContext())) {
                    requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED

                    storagePermissionRequestLauncher.launch(Tools.getStoragePermissionsArray())
                    return@CategoryAdapter
                }
            }
            performSearch(category)
        }.apply { stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY }

        val objectLabels = resources.getStringArray(R.array.objects)
        val objectDrawableIds = resources.obtainTypedArray(R.array.object_drawable_ids)
        val categories = mutableListOf<SearchCategory>()
        for (i in 1 until objectLabels.size) categories.add(SearchCategory("" + i, objectLabels[i], Classification.TYPE_OBJECT, ResourcesCompat.getDrawable(resources, objectDrawableIds.getResourceId(i, 0), null)!!))
        categoryAdapter.submitList(categories)
        objectDrawableIds.recycle()

        savedInstanceState?.apply { savedDestination = getInt(LAST_SELECTION) }

        noAlbum = arguments?.getBoolean(NO_ALBUM) == true

        accessMediaLocationPermissionRequestLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
        storagePermissionRequestLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

            var isGranted = true
            for(result in results) isGranted = isGranted && result.value
            if (isGranted) {
                // Explicitly request ACCESS_MEDIA_LOCATION permission
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) accessMediaLocationPermissionRequestLauncher.launch(android.Manifest.permission.ACCESS_MEDIA_LOCATION)
                performSearch(categoryAdapter.getCurrentCategory())
            }
        }
/*
        exifPermissionRequestLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            if (isGranted) launchLocationSearch()
        }
*/

        enterTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
        returnTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
    }

    private fun performSearch(category: SearchCategory) {
        when (category.id.toInt()) {
            in 1..4 -> {
                exitTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
                reenterTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
                parentFragmentManager.beginTransaction().replace(R.id.container_root, SearchResultFragment.newInstance(category.type, category.id, category.label, destinationToggleGroup?.checkedButtonId ?: R.id.search_album), SearchResultFragment::class.java.canonicalName).addToBackStack(null).commit()
            }
            5 -> {
/*
                when {
                    destinationToggleGroup?.checkedButtonId == R.id.search_album -> launchLocationSearch()
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> launchLocationSearch()
                    else -> {
                        @SuppressLint("InlinedApi")
                        val permission = android.Manifest.permission.ACCESS_MEDIA_LOCATION
                        if (ContextCompat.checkSelfPermission(requireContext(), permission) == PackageManager.PERMISSION_GRANTED) launchLocationSearch()
                        else {
                            if (shouldShowRequestPermissionRationale(permission))
                                ConfirmDialogFragment.newInstance(getString(R.string.search_exif_location_permission_rationale) + getString(R.string.need_access_media_location_permission), null, true, EXIF_PERMISSION_RATIONALE_REQUEST_DIALOG).show(parentFragmentManager, CONFIRM_DIALOG)
                            else {
                                requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
                                exifPermissionRequestLauncher.launch(permission)
                            }
                        }
                    }
                }
*/
                launchLocationSearch()
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? = inflater.inflate(R.layout.fragment_search, container, false)
    @SuppressLint("InlinedApi")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        postponeEnterTransition()
        view.doOnPreDraw { startPostponedEnterTransition() }

        categoryView = view.findViewById(R.id.category_list)
        categoryView.adapter = categoryAdapter

/*
        // Not ready to search confirm exit dialog result handler
        parentFragmentManager.setFragmentResultListener(ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY, viewLifecycleOwner) { _, bundle ->
            if (bundle.getBoolean(ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY, false)) {
                when(bundle.getString(ConfirmDialogFragment.INDIVIDUAL_REQUEST_KEY, "")) {
                    LEAVE_REQUEST_DIALOG -> parentFragmentManager.popBackStack()
                    EXIF_PERMISSION_RATIONALE_REQUEST_DIALOG -> {
                        requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
                        exifPermissionRequestLauncher.launch(android.Manifest.permission.ACCESS_MEDIA_LOCATION)
                    }
                }
            }
        }
*/

        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
                inflater.inflate(R.menu.search_menu, menu)
                destinationToggleGroup = menu.findItem(R.id.option_menu_search_destination).actionView?.findViewById(R.id.search_destination_toogle_group)
            }

            override fun onPrepareMenu(menu: Menu) {
                if (savedDestination != 0) destinationToggleGroup?.check(savedDestination)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean = true
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    override fun onResume() {
        super.onResume()

        (activity as? AppCompatActivity)?.supportActionBar?.run {
            title = getString(R.string.item_search)
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowTitleEnabled(true)
        }
    }

    override fun onPause() {
        destinationToggleGroup?.let { savedDestination = it.checkedButtonId }
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        destinationToggleGroup?.let { outState.putInt(LAST_SELECTION, it.checkedButtonId) }
    }

    override fun onDestroyView() {
        categoryView.adapter = null
        super.onDestroyView()
    }

    private fun launchLocationSearch() {
        parentFragmentManager.beginTransaction().replace(R.id.container_root, LocationSearchHostFragment.newInstance(destinationToggleGroup?.checkedButtonId ?: R.id.search_album), LocationSearchHostFragment::class.java.canonicalName).addToBackStack(null).commit()
    }

    class CategoryAdapter(private val clickListener: (SearchCategory) -> Unit): ListAdapter<SearchCategory, CategoryAdapter.ViewHolder>(CategoryDiffCallback()) {
        private lateinit var currentCategory: SearchCategory

        inner class ViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
            private val ivCat = itemView.findViewById<ImageView>(R.id.category)
            fun bind(category: SearchCategory) {
                ivCat.setImageDrawable(category.drawable)
                itemView.setOnClickListener {
                    currentCategory = category
                    clickListener(category)
                }
            }
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
            ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_search_category, parent, false))

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        fun getCurrentCategory(): SearchCategory = currentCategory
    }

    class CategoryDiffCallback : DiffUtil.ItemCallback<SearchCategory>() {
        override fun areItemsTheSame(oldItem: SearchCategory, newItem: SearchCategory): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: SearchCategory, newItem: SearchCategory): Boolean {
            return oldItem.label == newItem.label
        }
    }

    data class SearchCategory(
        val id: String,
        val label: String,
        val type: Int,
        val drawable: Drawable,
    )

    companion object {
        private const val NO_ALBUM = "NO_ALBUM"
        private const val LAST_SELECTION = "LAST_SELECTION"

/*
        private const val LEAVE_REQUEST_DIALOG = "LEAVE_REQUEST_DIALOG"
        private const val CONFIRM_DIALOG = "CONFIRM_DIALOG"
        private const val EXIF_PERMISSION_RATIONALE_REQUEST_DIALOG = "EXIF_PERMISSION_RATIONALE_REQUEST_DIALOG"
*/

        @JvmStatic
        fun newInstance(noAlbum: Boolean) = SearchFragment().apply { arguments = Bundle().apply { putBoolean(NO_ALBUM, noAlbum) }}
    }
}