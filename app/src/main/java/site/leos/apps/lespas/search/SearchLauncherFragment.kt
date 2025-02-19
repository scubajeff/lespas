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
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.MenuProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnPreDraw
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.snackbar.Snackbar
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.publication.NCShareViewModel
import site.leos.apps.lespas.sync.ActionViewModel

class SearchLauncherFragment : Fragment() {
    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var categoryView: RecyclerView
    private var scopeToggleGroup: MaterialButtonToggleGroup? = null

    private var savedScope = View.NO_ID
    // Flag indicating if we have existing albums or not
    private var noAlbum = true

    private lateinit var storagePermissionRequestLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var accessMediaLocationPermissionRequestLauncher: ActivityResultLauncher<String>

    private val archiveModel: NCShareViewModel by activityViewModels()
    private val actionModel: ActionViewModel by viewModels()
    private val searchModel: SearchFragment.SearchModel by viewModels(ownerProducer = { requireParentFragment() }) { SearchFragment.SearchModelFactory(requireActivity().application, archiveModel, actionModel)}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        categoryAdapter = CategoryAdapter { category ->
            if (scopeToggleGroup?.checkedButtonId == R.id.search_album && noAlbum) {
                Snackbar.make(categoryView, getString(R.string.need_albums), Snackbar.LENGTH_SHORT).show()
                return@CategoryAdapter
            }
            if (scopeToggleGroup?.checkedButtonId == R.id.search_gallery) {
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

        savedInstanceState?.apply { savedScope = getInt(LAST_SELECTION) }

        noAlbum = requireArguments().getBoolean(NO_ALBUM) == true

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
    }

    private fun performSearch(category: SearchCategory) {
        val searchScope = scopeToggleGroup?.checkedButtonId ?: R.id.search_album
        when (category.id.toInt()) {
            in 1..4 -> {
                searchModel.objectDetect(category.id, searchScope)
                parentFragmentManager.beginTransaction().setCustomAnimations(R.anim.enter_from_right, R.anim.exit_to_left, R.anim.enter_from_left, R.anim.exit_to_right)
                    .replace(R.id.container_child_fragment, ObjectSearchFragment.newInstance(category.label, searchScope), ObjectSearchFragment::class.java.canonicalName).addToBackStack(null).commit()
            }
            5 -> {
                searchModel.locationSearch(searchScope)
                parentFragmentManager.beginTransaction().setCustomAnimations(R.anim.enter_from_right, R.anim.exit_to_left, R.anim.enter_from_left, R.anim.exit_to_right)
                    .replace(R.id.container_child_fragment, LocationResultByLocalitiesFragment.newInstance(searchScope), LocationResultByLocalitiesFragment::class.java.canonicalName).addToBackStack(null).commit()
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? = inflater.inflate(R.layout.fragment_search_launcher, container, false)
    @SuppressLint("InlinedApi")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        postponeEnterTransition()
        view.doOnPreDraw { startPostponedEnterTransition() }

        categoryView = view.findViewById<RecyclerView?>(R.id.category_list).apply {
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
        categoryView.adapter = categoryAdapter

        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
                inflater.inflate(R.menu.search_launcher_menu, menu)
                scopeToggleGroup = menu.findItem(R.id.option_menu_search_scope).actionView?.findViewById<MaterialButtonToggleGroup>(R.id.search_scope_toogle_group)?.apply {
                    //addOnButtonCheckedListener { group, checkedId, isChecked -> if (checkedId == R.id.search_gallery && isChecked) searchModel.preloadGallery() }
                    if (requireArguments().getInt(SEARCH_SCOPE) == SearchFragment.SEARCH_GALLERY) check(R.id.search_gallery)
                }

                // Hide search progress indicator
                menu.findItem(R.id.option_menu_search_progress)?.run {
                    isVisible = false
                    isEnabled = false
                }
            }

            override fun onPrepareMenu(menu: Menu) {
                if (savedScope != View.NO_ID) scopeToggleGroup?.check(savedScope)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean = true
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    override fun onResume() {
        super.onResume()

        // Whenever search launcher resumes, the running search job should be cancelled
        searchModel.stopSearching()

        (activity as? AppCompatActivity)?.supportActionBar?.run {
            title = getString(R.string.item_search)
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowTitleEnabled(true)
        }
    }

    override fun onPause() {
        scopeToggleGroup?.let { savedScope = it.checkedButtonId }
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(LAST_SELECTION, savedScope)
    }

    override fun onDestroyView() {
        categoryView.adapter = null
        super.onDestroyView()
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
        private const val SEARCH_SCOPE = "SEARCH_SCOPE"
        private const val LAST_SELECTION = "LAST_SELECTION"

        @JvmStatic
        fun newInstance(noAlbum: Boolean, searchScope: Int) = SearchLauncherFragment().apply {
            arguments = Bundle().apply {
                putBoolean(NO_ALBUM, noAlbum)
                putInt(SEARCH_SCOPE, searchScope)
            }
        }
    }
}