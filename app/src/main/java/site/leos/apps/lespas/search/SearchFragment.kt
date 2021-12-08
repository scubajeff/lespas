package site.leos.apps.lespas.search

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
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.snackbar.Snackbar
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.ConfirmDialogFragment

class SearchFragment : Fragment() {
    private lateinit var categoryAdapter: CategoryAdapter
    private var destinationToggleGroup: MaterialButtonToggleGroup? = null
    private var lastSelection = 0
    // Flag indicating if we have exsting albums or not
    private var noAlbum = true
    // Flag indicating showing Snackbar when clicking search album button
    private var onMenuCreation = true

    private lateinit var storagePermissionRequestLauncher: ActivityResultLauncher<String>
    private lateinit var exifPermissionRequestLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)

        categoryAdapter = CategoryAdapter { category ->
            when (category.id.toInt()) {
                in 1..4 -> parentFragmentManager.beginTransaction().replace(R.id.container_root, SearchResultFragment.newInstance(category.type, category.id, category.label, destinationToggleGroup?.checkedButtonId == R.id.search_album), SearchResultFragment::class.java.canonicalName).addToBackStack(null).commit()
                5 -> {
                    when {
                        destinationToggleGroup?.checkedButtonId == R.id.search_album -> launchLocationSearch()
                        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> launchLocationSearch()
                        else -> {
                            val permission = android.Manifest.permission.ACCESS_MEDIA_LOCATION
                            if (ContextCompat.checkSelfPermission(requireContext(), permission) == PackageManager.PERMISSION_GRANTED) launchLocationSearch()
                            else {
                                requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
                                exifPermissionRequestLauncher.launch(permission)
                            }
                        }
                    }
                }
            }
        }.apply { stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY }

        val objectLabels = resources.getStringArray(R.array.objects)
        val objectDrawableIds = resources.obtainTypedArray(R.array.object_drawable_ids)
        val categories = mutableListOf<SearchCategory>()
        for (i in 1 until objectLabels.size) categories.add(SearchCategory("" + i, objectLabels[i], Classification.TYPE_OBJECT, ResourcesCompat.getDrawable(resources, objectDrawableIds.getResourceId(i, 0), null)!!))
        categoryAdapter.submitList(categories)
        objectDrawableIds.recycle()

        savedInstanceState?.apply { lastSelection = getInt(LAST_SELECTION) }

        noAlbum = arguments?.getBoolean(NO_ALBUM) == true

        storagePermissionRequestLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

            if (isGranted) destinationToggleGroup?.check(R.id.search_cameraroll)
            else if (noAlbum) parentFragmentManager.findFragmentByTag(CONFIRM_DIALOG) ?: run {
                ConfirmDialogFragment.newInstance(getString(R.string.condition_to_perform_search), getString(R.string.button_text_leave), false).show(parentFragmentManager, CONFIRM_DIALOG)
            }
        }
        exifPermissionRequestLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            if (isGranted) launchLocationSearch()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.fragment_search, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<RecyclerView>(R.id.category_list).adapter = categoryAdapter

        // Not ready to search confirm exit dialog result handler
        parentFragmentManager.setFragmentResultListener(ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY, viewLifecycleOwner) { key, bundle ->
            if (key == ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY && bundle.getBoolean(ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY, false)) parentFragmentManager.popBackStack()
        }
    }

    override fun onResume() {
        super.onResume()

        onMenuCreation = true

        (activity as? AppCompatActivity)?.supportActionBar?.run {
            title = getString(R.string.item_search)
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowTitleEnabled(true)
        }
    }

    override fun onPause() {
        destinationToggleGroup?.let { lastSelection = it.checkedButtonId }
        super.onPause()
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
            addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (isChecked) when(checkedId) {
                    R.id.search_cameraroll-> {
                        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) android.Manifest.permission.READ_EXTERNAL_STORAGE else android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                        if (ContextCompat.checkSelfPermission(requireContext(), permission) != PackageManager.PERMISSION_GRANTED) {
                            requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED

                            storagePermissionRequestLauncher.launch(permission)
                            if (!noAlbum) this.check(R.id.search_album)
                        }
                    }
                    R.id.search_album-> {
                        if (noAlbum) {
                            if (!onMenuCreation) Snackbar.make(destinationToggleGroup!!, getString(R.string.need_albums), Snackbar.LENGTH_SHORT).setAnimationMode(Snackbar.ANIMATION_MODE_FADE).setBackgroundTint(resources.getColor(R.color.color_primary, null)).setTextColor(resources.getColor(R.color.color_text_light, null)).show()
                            this.check(R.id.search_cameraroll)
                        }
                        onMenuCreation = false
                    }
                }
            }

            // This will trigger all the button state setting in above addOnButtonCheckedListener
            check(R.id.search_album)
        }
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)
        if (lastSelection != 0) destinationToggleGroup?.check(lastSelection)
    }

    private fun launchLocationSearch() {
        parentFragmentManager.beginTransaction().replace(R.id.container_root, LocationSearchHostFragment.newInstance(destinationToggleGroup?.checkedButtonId == R.id.search_album), LocationSearchHostFragment::class.java.canonicalName).addToBackStack(null).commit()
    }

    class CategoryAdapter(private val clickListener: (SearchCategory) -> Unit): ListAdapter<SearchCategory, CategoryAdapter.ViewHolder>(CategoryDiffCallback()) {
        inner class ViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
            private val ivCat = itemView.findViewById<ImageView>(R.id.category)
            fun bind(category: SearchCategory) {
                ivCat.setImageDrawable(category.drawable)
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
        private const val NO_ALBUM = "NO_ALBUM"
        private const val LAST_SELECTION = "LAST_SELECTION"

        private const val CONFIRM_DIALOG = "CONFIRM_DIALOG"

        @JvmStatic
        fun newInstance(noAlbum: Boolean) = SearchFragment().apply { arguments = Bundle().apply { putBoolean(NO_ALBUM, noAlbum) }}
    }
}