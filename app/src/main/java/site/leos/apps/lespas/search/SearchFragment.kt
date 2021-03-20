package site.leos.apps.lespas.search

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.*
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.AlbumViewModel
import site.leos.apps.lespas.helper.ConfirmDialogFragment

class SearchFragment : Fragment(), ConfirmDialogFragment.OnResultListener {
    private lateinit var categoryAdapter: CategoryAdapter
    private var destinationToggleGroup: MaterialButtonToggleGroup? = null
    private var lastSelection = 0
    private var noAlbum = false

    private val albumViewModel: AlbumViewModel by activityViewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)

        categoryAdapter = CategoryAdapter { category ->
            parentFragmentManager.beginTransaction()
                .replace(R.id.container_root, SearchResultFragment.newInstance(category.type, category.id, category.label, destinationToggleGroup?.checkedButtonId == R.id.search_album), SearchResultFragment::class.java.canonicalName)
                .addToBackStack(null)
                .commit()
        }

        val objectLabels = resources.getStringArray(R.array.objects)
        val objectDrawableIds = resources.obtainTypedArray(R.array.object_drawable_ids)
        val categories = mutableListOf<SearchCategory>()
        for(i in 1 until objectLabels.size) {
            categories.add(SearchCategory(""+i, objectLabels[i], Classification.TYPE_OBJECT, ResourcesCompat.getDrawable(resources, objectDrawableIds.getResourceId(i, 0), null)!!))
        }
        categoryAdapter.submitList(categories)
        objectDrawableIds.recycle()

        savedInstanceState?.apply { lastSelection = getInt(LAST_SELECTION) }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.fragment_search, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<RecyclerView>(R.id.category_list).apply {
            adapter = categoryAdapter
        }
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
        super.onPause()
        destinationToggleGroup?.let { lastSelection = it.checkedButtonId }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        destinationToggleGroup?.let { outState.putInt(LAST_SELECTION, it.checkedButtonId) }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.search_menu, menu)
        destinationToggleGroup = menu.findItem(R.id.option_menu_search_destination).actionView.findViewById(R.id.search_destination_toogle_group)

        destinationToggleGroup?.apply {
            clearOnButtonCheckedListeners()
            lifecycleScope.launch(Dispatchers.IO) {
                if (albumViewModel.getAllAlbumName().isEmpty())
                    noAlbum = true
                    withContext(Dispatchers.Main) {
                        destinationToggleGroup?.findViewById<MaterialButton>(R.id.search_album)?.isEnabled = false
                        if (ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                            requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), WRITE_STORAGE_PERMISSION_REQUEST)
                        }
                    }
            }
            addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (isChecked && checkedId == R.id.search_cameraroll) {
                    if (ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), WRITE_STORAGE_PERMISSION_REQUEST)
                        this.check(R.id.search_album)
                    }
                }
            }
        }
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)
        if (lastSelection != 0) destinationToggleGroup?.check(lastSelection)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == WRITE_STORAGE_PERMISSION_REQUEST && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) destinationToggleGroup?.check(R.id.search_cameraroll)
        else if (noAlbum)
            parentFragmentManager.findFragmentByTag(CONFIRM_DIALOG) ?: run {
                ConfirmDialogFragment.newInstance(getString(R.string.condition_to_perform_search), getString(R.string.button_text_leave), false).let {
                    it.setTargetFragment(this@SearchFragment, LEAVE_REQUEST_CODE)
                    it.show(parentFragmentManager, CONFIRM_DIALOG)
                }
            }
    }

    override fun onResult(positive: Boolean, requestCode: Int) {
        if (positive && requestCode == LEAVE_REQUEST_CODE) parentFragmentManager.popBackStack()
    }

    class CategoryAdapter(private val clickListener: (SearchCategory) -> Unit): ListAdapter<SearchCategory, CategoryAdapter.ViewHolder>(CategoryDiffCallback()) {
        inner class ViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
            fun bind(category: SearchCategory) {
                itemView.findViewById<ImageView>(R.id.category).setImageDrawable(category.drawable)
                itemView.setOnClickListener { clickListener(category) }
            }
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
            ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_search_category, parent, false))

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

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
        const val SEARCH_COLLECTION = "SEARCH_COLLECTION"
        private const val LAST_SELECTION = "LAST_SELECTION"

        private const val WRITE_STORAGE_PERMISSION_REQUEST = 8900
        private const val CONFIRM_DIALOG = "CONFIRM_DIALOG"
        private const val LEAVE_REQUEST_CODE = 1

        @JvmStatic
        fun newInstance(searchCollection: Boolean) = SearchFragment().apply { arguments = Bundle().apply { putBoolean(SEARCH_COLLECTION, searchCollection) }}
    }
}