package site.leos.apps.lespas.search

import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.*
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButtonToggleGroup
import site.leos.apps.lespas.R

class SearchFragment : Fragment() {
    private lateinit var categoryAdapter: CategoryAdapter
    private var destinationToggleGroup: MaterialButtonToggleGroup? = null
    private var lastSelection = 0

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
        lastSelection = destinationToggleGroup?.checkedButtonId!!
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(LAST_SELECTION, destinationToggleGroup?.checkedButtonId!!)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.search_menu, menu)
        destinationToggleGroup = menu.findItem(R.id.option_menu_search_destination).actionView.findViewById(R.id.search_destination_toogle_group)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)
        if (lastSelection != 0) destinationToggleGroup?.check(lastSelection)
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

        @JvmStatic
        fun newInstance(searchCollection: Boolean) = SearchFragment().apply { arguments = Bundle().apply { putBoolean(SEARCH_COLLECTION, searchCollection) }}
    }
}