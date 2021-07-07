package site.leos.apps.lespas.publication

import android.graphics.Color
import android.os.Bundle
import android.view.*
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.app.SharedElementCallback
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.google.android.material.transition.MaterialContainerTransform
import com.google.android.material.transition.MaterialElevationScale
import kotlinx.coroutines.launch
import site.leos.apps.lespas.MainActivity
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.ImageLoaderViewModel
import site.leos.apps.lespas.helper.Tools
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.*

class PublicationDetailFragment: Fragment() {
    private lateinit var share: NCShareViewModel.ShareWithMe

    private lateinit var photoListAdapter: PhotoListAdapter
    private lateinit var photoList: RecyclerView
    private lateinit var stub: View

    private val shareModel: NCShareViewModel by activityViewModels()

    private var loadingIndicator: MenuItem? = null
    private var showMetaMenuItem: MenuItem? = null
    private var reloadPublicationMenuItem: MenuItem? = null

    private var clickedItem = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        share = arguments?.getParcelable(SHARE)!!

        savedInstanceState?.apply { clickedItem = getInt(CLICKED_ITEM) }

        photoListAdapter = PhotoListAdapter(
            { view, photo, position->
                clickedItem = position

                // Get a stub as fake toolbar since the toolbar belongs to MainActivity and it will disappear during fragment transaction
                stub.background = (activity as MainActivity).getToolbarViewContent()

                reenterTransition = MaterialElevationScale(true).apply { duration = resources.getInteger(android.R.integer.config_mediumAnimTime).toLong() }
                exitTransition = MaterialElevationScale(false).apply {
                    duration = resources.getInteger(android.R.integer.config_mediumAnimTime).toLong()
                    excludeTarget(R.id.stub, true)
                    excludeTarget(view, true)
                }

                parentFragmentManager.beginTransaction()
                    .setReorderingAllowed(true)
                    .addSharedElement(view, view.transitionName)
                    .replace(R.id.container_root, RemoteMediaFragment.newInstance(arrayListOf(photo)), RemoteMediaFragment::class.java.canonicalName)
                    .addToBackStack(null)
                    .commit()
            },
            { photo, view-> shareModel.getPhoto(photo, view, ImageLoaderViewModel.TYPE_GRID) { startPostponedEnterTransition() } },
            { view-> shareModel.cancelGetPhoto(view) }
        ).apply {
            stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            if (savedInstanceState?.run { getBoolean(SHOW_META, false) } == true) {
                toggleMetaDisplay()
            }
        }

        lifecycleScope.launch {
            shareModel.getRemotePhotoList(share, false).toMutableList().apply {
                photoListAdapter.submitList(this)

                loadingIndicator?.run {
                    isEnabled = false
                    isVisible = false
                }
                showMetaMenuItem?.run {
                    isVisible = true
                    isEnabled = true
                }
                reloadPublicationMenuItem?.run {
                    isVisible = true
                    isEnabled = true
                }
            }
        }

        sharedElementEnterTransition = MaterialContainerTransform().apply {
            duration = resources.getInteger(android.R.integer.config_shortAnimTime).toLong()
            scrimColor = Color.TRANSPARENT
        }

        // Adjusting the shared element mapping
        setExitSharedElementCallback(object : SharedElementCallback() {
            override fun onMapSharedElements(names: MutableList<String>?, sharedElements: MutableMap<String, View>?) {
                photoList.findViewHolderForAdapterPosition(clickedItem)?.let {
                    sharedElements?.put(names?.get(0)!!, it.itemView.findViewById(R.id.media))
                }
            }
        })

        setHasOptionsMenu(true)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val vg = inflater.inflate(R.layout.fragment_publication_detail, container, false)

        stub = vg.findViewById(R.id.stub)
        photoList = vg.findViewById<RecyclerView>(R.id.photo_list).apply {
            layoutManager = StaggeredGridLayoutManager(resources.getInteger(R.integer.publication_detail_grid_span_count), StaggeredGridLayoutManager.VERTICAL).apply { gapStrategy = StaggeredGridLayoutManager.GAP_HANDLING_MOVE_ITEMS_BETWEEN_SPANS }
            adapter = photoListAdapter
        }

        postponeEnterTransition()

        return vg
    }

    override fun onResume() {
        super.onResume()

        (activity as? AppCompatActivity)?.supportActionBar?.run {
            title = String.format(getString(R.string.publication_detail_fragment_title), share.albumName, share.shareByLabel)
            setDisplayHomeAsUpEnabled(true)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(CLICKED_ITEM, clickedItem)
        outState.putBoolean(SHOW_META, photoListAdapter.isMetaDisplayed())
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.publication_detail_menu, menu)

        loadingIndicator = menu.findItem(R.id.option_menu_search_progress)
        reloadPublicationMenuItem = menu.findItem(R.id.option_menu_reload_publication)
        showMetaMenuItem = menu.findItem(R.id.option_menu_show_meta).apply {
            icon = ContextCompat.getDrawable(requireContext(), if (photoListAdapter.isMetaDisplayed()) R.drawable.ic_baseline_meta_on_24 else R.drawable.ic_baseline_meta_off_24)
        }

        if (photoListAdapter.itemCount > 0) {
            loadingIndicator?.isEnabled = false
            loadingIndicator?.isVisible = false
            showMetaMenuItem?.isEnabled = true
            showMetaMenuItem?.isVisible = true
            reloadPublicationMenuItem?.isEnabled = true
            reloadPublicationMenuItem?.isVisible = true
        }

    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when(item.itemId) {
            R.id.option_menu_show_meta-> {
                photoListAdapter.toggleMetaDisplay()
                item.icon = ContextCompat.getDrawable(requireContext(), if (photoListAdapter.isMetaDisplayed()) R.drawable.ic_baseline_meta_on_24 else R.drawable.ic_baseline_meta_off_24)
                true
            }
            R.id.option_menu_reload_publication-> {
                lifecycleScope.launch {
                    reloadPublicationMenuItem?.isEnabled = false
                    shareModel.updateShareWithMe()
                    shareModel.getRemotePhotoList(share, true).toMutableList().apply {
                        photoListAdapter.submitList(this)
                        reloadPublicationMenuItem?.isEnabled = true
                    }
                }
                true
            }
            else-> false
        }

    class PhotoListAdapter(private val clickListener: (ImageView, NCShareViewModel.RemotePhoto, Int) -> Unit, private val imageLoader: (NCShareViewModel.RemotePhoto, ImageView) -> Unit, private val cancelLoading: (View) -> Unit
    ): ListAdapter<NCShareViewModel.RemotePhoto, PhotoListAdapter.ViewHolder>(PhotoDiffCallback()) {
        private val mBoundViewHolders = mutableSetOf<ViewHolder>()
        private var displayMeta = false

        inner class ViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
            fun bind(item: NCShareViewModel.RemotePhoto, position: Int) {
                (itemView.findViewById(R.id.media) as ImageView).apply {
                    imageLoader(item, this)
                    ConstraintSet().apply {
                        clone(itemView as ConstraintLayout)
                        setDimensionRatio(R.id.media, "H,${item.width}:${item.height}")
                        applyTo(itemView)
                    }
                    setOnClickListener { clickListener(this, item, position) }

                    ViewCompat.setTransitionName(this, item.fileId)
                }

                (itemView.findViewById<ImageView>(R.id.play_mark)).visibility = if (Tools.isMediaPlayable(item.mimeType)) View.VISIBLE else View.GONE

                (itemView.findViewById<TextView>(R.id.meta)).apply {
                    LocalDateTime.ofInstant(Instant.ofEpochSecond(item.timestamp), ZoneOffset.systemDefault()).apply {
                        text = "${this.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())}, ${this.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT))}"
                    }
                    visibility = if (displayMeta) View.VISIBLE else View.GONE
                }
            }

            fun toggleMeta() {
                (itemView.findViewById<TextView>(R.id.meta)).visibility = if (displayMeta) View.VISIBLE else View.GONE
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoListAdapter.ViewHolder =
            ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_remote_media, parent, false))

        override fun onBindViewHolder(holder: PhotoListAdapter.ViewHolder, position: Int) {
            holder.bind(getItem(position), position)
            mBoundViewHolders.add(holder)
        }

        override fun onViewRecycled(holder: ViewHolder) {
            mBoundViewHolders.remove(holder)
            cancelLoading(holder.itemView.findViewById(R.id.media) as View)
            super.onViewRecycled(holder)
        }

        fun toggleMetaDisplay() {
            displayMeta = !displayMeta
            for (holder in mBoundViewHolders) holder.toggleMeta()
        }

        fun isMetaDisplayed(): Boolean = displayMeta
    }

    class PhotoDiffCallback: DiffUtil.ItemCallback<NCShareViewModel.RemotePhoto>() {
        override fun areItemsTheSame(oldItem: NCShareViewModel.RemotePhoto, newItem: NCShareViewModel.RemotePhoto): Boolean = oldItem.fileId == newItem.fileId
        override fun areContentsTheSame(oldItem: NCShareViewModel.RemotePhoto, newItem: NCShareViewModel.RemotePhoto): Boolean = oldItem.fileId == newItem.fileId
    }

    companion object {
        private const val SHARE = "SHARE"
        private const val CLICKED_ITEM = "CLICKED_ITEM"
        private const val SHOW_META = "SHOW_META"

        @JvmStatic
        fun newInstance(share: NCShareViewModel.ShareWithMe) = PublicationDetailFragment().apply {
            arguments = Bundle().apply {
                putParcelable(SHARE, share)
            }
        }
    }
}