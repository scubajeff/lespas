package site.leos.apps.lespas.publication

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
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
import androidx.lifecycle.asLiveData
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.transition.MaterialContainerTransform
import com.google.android.material.transition.MaterialElevationScale
import kotlinx.coroutines.launch
import site.leos.apps.lespas.MainActivity
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.Album
import site.leos.apps.lespas.helper.ImageLoaderViewModel
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.sync.AcquiringDialogFragment
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
    private var addPhotoMenuItem: MenuItem? = null

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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        shareModel.publicationContentMeta.asLiveData().observe(viewLifecycleOwner, {
            photoListAdapter.submitList(it) {
                loadingIndicator?.run {
                    isEnabled = false
                    isVisible = false
                }
                showMetaMenuItem?.run {
                    isVisible = true
                    isEnabled = true
                }
                if (share.permission == NCShareViewModel.PERMISSION_JOINT) addPhotoMenuItem?.run {
                    isVisible = true
                    isEnabled = true
                }
            }
        })

        lifecycleScope.launch { shareModel.getRemotePhotoList(share, false) }
    }

    override fun onResume() {
        super.onResume()

        (requireActivity() as AppCompatActivity).supportActionBar?.run {
            title = share.albumName
            setDisplayHomeAsUpEnabled(true)
        }

        // TODO dirty hack to get title view
        try {
            (requireActivity().findViewById<MaterialToolbar>(R.id.toolbar).getChildAt(0) as TextView)
        } catch (e: ClassCastException) {
            e.printStackTrace()
            try {
                (requireActivity().findViewById<MaterialToolbar>(R.id.toolbar).getChildAt(1) as TextView)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }?.run {
            shareModel.getAvatar(NCShareViewModel.Sharee(share.shareBy, share.shareByLabel, NCShareViewModel.SHARE_TYPE_USER), this, null)
            compoundDrawablePadding = context.resources.getDimension(R.dimen.small_padding).toInt()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(CLICKED_ITEM, clickedItem)
        outState.putBoolean(SHOW_META, photoListAdapter.isMetaDisplayed())
    }

    override fun onStop() {
        // TODO dirty hack to get title view
        try {
            (requireActivity().findViewById<MaterialToolbar>(R.id.toolbar).getChildAt(0) as TextView)
        } catch (e: ClassCastException) {
            e.printStackTrace()
            try {
                (requireActivity().findViewById<MaterialToolbar>(R.id.toolbar).getChildAt(1) as TextView)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }?.run {
            setCompoundDrawablesWithIntrinsicBounds(null, null, null, null)
        }
        super.onStop()
    }

    override fun onDestroy() {
        shareModel.resetPublicationContentMeta()
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.publication_detail_menu, menu)

        loadingIndicator = menu.findItem(R.id.option_menu_search_progress)
        addPhotoMenuItem = menu.findItem(R.id.option_menu_add_photo)
        showMetaMenuItem = menu.findItem(R.id.option_menu_show_meta).apply {
            icon = ContextCompat.getDrawable(requireContext(), if (photoListAdapter.isMetaDisplayed()) R.drawable.ic_baseline_meta_on_24 else R.drawable.ic_baseline_meta_off_24)
        }

        if (!photoListAdapter.currentList.isNullOrEmpty()) {
            loadingIndicator?.isEnabled = false
            loadingIndicator?.isVisible = false
            showMetaMenuItem?.isEnabled = true
            showMetaMenuItem?.isVisible = true
            if (share.permission == NCShareViewModel.PERMISSION_JOINT) {
                addPhotoMenuItem?.isEnabled = true
                addPhotoMenuItem?.isVisible = true
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when(item.itemId) {
            R.id.option_menu_show_meta-> {
                photoListAdapter.toggleMetaDisplay()
                item.icon = ContextCompat.getDrawable(requireContext(), if (photoListAdapter.isMetaDisplayed()) R.drawable.ic_baseline_meta_on_24 else R.drawable.ic_baseline_meta_off_24)
                true
            }
            R.id.option_menu_add_photo-> {
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    putExtra(Intent.EXTRA_LOCAL_ONLY, true)
                }
                startActivityForResult(intent, REQUEST_ADD_PHOTOS)
                true
            }
            else-> false
        }

    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        super.onActivityResult(requestCode, resultCode, intent)

        val uris = arrayListOf<Uri>()
        if (resultCode == Activity.RESULT_OK && requestCode == REQUEST_ADD_PHOTOS) {
            intent?.clipData?.apply { for (i in 0 until itemCount) uris.add(getItemAt(i).uri) } ?: uris.add(intent?.data!!)

            if (uris.isNotEmpty()) {
                // Save joint album's content meta file
                shareModel.createJointAlbumContentMetaFile(share.albumId, photoListAdapter.currentList)

                parentFragmentManager.findFragmentByTag(TAG_ACQUIRING_DIALOG) ?: run {
                    AcquiringDialogFragment.newInstance(
                        uris,
                        Album(JOINT_ALBUM_ID, share.sharePath, LocalDateTime.MIN, LocalDateTime.MAX, "", 0, 0, 0, LocalDateTime.now(), 0, share.albumId, 0, 1F),
                        false
                    ).show(parentFragmentManager, TAG_ACQUIRING_DIALOG)
                }
            }
        }
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
                        text = String.format("%s, %s", this.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()), this.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)))
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
        private const val REQUEST_ADD_PHOTOS = 3333
        private const val TAG_ACQUIRING_DIALOG = "JOINT_ALBUM_ACQUIRING_DIALOG"

        const val JOINT_ALBUM_ID = "joint"

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