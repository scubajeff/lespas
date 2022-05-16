package site.leos.apps.lespas.publication

import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.app.SharedElementCallback
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.google.android.material.transition.MaterialContainerTransform
import com.google.android.material.transition.MaterialElevationScale
import com.google.android.material.transition.MaterialSharedAxis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.Album
import site.leos.apps.lespas.album.BGMDialogFragment
import site.leos.apps.lespas.helper.SingleLiveEvent
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.search.PhotosInMapFragment
import site.leos.apps.lespas.sync.AcquiringDialogFragment
import site.leos.apps.lespas.sync.SyncAdapter
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.*

class PublicationDetailFragment: Fragment() {
    private lateinit var share: NCShareViewModel.ShareWithMe

    private lateinit var photoListAdapter: PhotoListAdapter
    private lateinit var photoList: RecyclerView

    private val shareModel: NCShareViewModel by activityViewModels()
    private val currentPositionModel: CurrentPublicationViewModel by activityViewModels()

    private var loadingIndicator: MenuItem? = null
    private var showMetaMenuItem: MenuItem? = null
    private var addPhotoMenuItem: MenuItem? = null
    private var mapMenuItem: MenuItem? = null

    private var currentItem = -1

    private lateinit var addFileLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        share = arguments?.getParcelable(SHARE)!!

        savedInstanceState?.apply { currentItem = getInt(CURRENT_ITEM) }

        photoListAdapter = PhotoListAdapter(
            { view, mediaList, position->
                currentItem = position

                with (photoList.layoutManager as StaggeredGridLayoutManager) {
                    currentPositionModel.saveCurrentRange(findFirstVisibleItemPositions(null)[0], findLastVisibleItemPositions(null)[spanCount-1])
                }

                reenterTransition = MaterialElevationScale(true).apply { duration = resources.getInteger(android.R.integer.config_mediumAnimTime).toLong() }
                exitTransition = MaterialElevationScale(false).apply {
                    duration = resources.getInteger(android.R.integer.config_mediumAnimTime).toLong()
                    excludeTarget(view, true)
                    excludeTarget(android.R.id.statusBarBackground, true)
                    excludeTarget(android.R.id.navigationBarBackground, true)
                }

                parentFragmentManager.beginTransaction()
                    .setReorderingAllowed(true)
                    .addSharedElement(view, view.transitionName)
                    .replace(R.id.container_root, RemoteMediaFragment.newInstance(mediaList, position, share.albumId), RemoteMediaFragment::class.java.canonicalName)
                    .addToBackStack(null)
                    .commit()
            },
            { photo, view-> shareModel.setImagePhoto(photo, view, NCShareViewModel.TYPE_GRID) { startPostponedEnterTransition() }},
            { view-> shareModel.cancelSetImagePhoto(view) }
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
                if (names?.isNotEmpty() == true) photoList.findViewHolderForAdapterPosition(currentItem)?.let {
                    sharedElements?.put(names[0], it.itemView.findViewById(R.id.media))
                }
            }
        })

        setHasOptionsMenu(true)

        addFileLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            if (uris.isNotEmpty()) {
                parentFragmentManager.findFragmentByTag(TAG_ACQUIRING_DIALOG) ?: run {
                    AcquiringDialogFragment.newInstance(
                        uris as ArrayList<Uri>,
                        //Album(JOINT_ALBUM_ID, share.sharePath, LocalDateTime.MIN, LocalDateTime.MAX, "", 0, 0, 0, LocalDateTime.now(), 0, share.albumId, 0, 1F),
                        Album(
                            lastModified = LocalDateTime.now(),
                            id = JOINT_ALBUM_ID, name = share.albumName,
                            coverFileName = "${share.sharePath}/",
                            eTag = share.albumId,
                        ),
                        false
                    ).show(parentFragmentManager, TAG_ACQUIRING_DIALOG)
                }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val vg = inflater.inflate(R.layout.fragment_publication_detail, container, false)

        photoList = vg.findViewById<RecyclerView>(R.id.photo_list).apply {
            val defaultSpanCount = resources.getInteger(R.integer.publication_detail_grid_span_count)
            layoutManager = StaggeredGridLayoutManager(defaultSpanCount, StaggeredGridLayoutManager.VERTICAL).apply { gapStrategy = StaggeredGridLayoutManager.GAP_HANDLING_MOVE_ITEMS_BETWEEN_SPANS }
            adapter = photoListAdapter
            photoListAdapter.setPlayMarkDrawable(Tools.getPlayMarkDrawable(requireActivity(), 0.25f / defaultSpanCount))
        }

        return vg
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        currentPositionModel.getCurrentPosition().observe(viewLifecycleOwner) { currentItem = it }
        shareModel.publicationContentMeta.asLiveData().observe(viewLifecycleOwner) {
            photoListAdapter.submitList(it) {
                // Setup UI in this submitList commitCallback
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

                it.forEach { remotePhoto ->
                    if (remotePhoto.photo.latitude != Photo.NO_GPS_DATA) {
                        mapMenuItem?.isVisible = true
                        mapMenuItem?.isEnabled = true

                        return@submitList
                    }
                }
            }

            if (currentItem != -1) with(currentPositionModel.getLastRange()) {
                if (currentItem < this.first || currentItem > this.second) (photoList.layoutManager as StaggeredGridLayoutManager).scrollToPosition(currentItem)
            }
        }

        lifecycleScope.launch(Dispatchers.IO) {
            shareModel.getRemotePhotoList(share, false)
            // TODO download publication's BGM here and remove it in onDestroy everytime, better way??
            shareModel.downloadFile("${share.sharePath}/${SyncAdapter.BGM_FILENAME_ON_SERVER}", File(requireContext().cacheDir, "${share.albumId}${BGMDialogFragment.BGM_FILE_SUFFIX}"), stripExif = false, useCache = false)
        }

        if (currentItem != -1 && photoListAdapter.itemCount > 0) postponeEnterTransition()
    }

    override fun onResume() {
        super.onResume()

        val titleView = layoutInflater.inflate(R.layout.textview_actionbar_title_with_icon, null)
        titleView.findViewById<TextView>(R.id.title).apply {
            text = share.albumName
            shareModel.getAvatar(NCShareViewModel.Sharee(share.shareBy, share.shareByLabel, NCShareViewModel.SHARE_TYPE_USER), this, null)
            compoundDrawablePadding = context.resources.getDimension(R.dimen.small_padding).toInt()
        }
        (requireActivity() as AppCompatActivity).supportActionBar?.run {
            customView = titleView
            displayOptions = androidx.appcompat.app.ActionBar.DISPLAY_HOME_AS_UP or androidx.appcompat.app.ActionBar.DISPLAY_SHOW_CUSTOM
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(CURRENT_ITEM, currentItem)
        outState.putBoolean(SHOW_META, photoListAdapter.isMetaDisplayed())
    }

    override fun onDestroy() {
        shareModel.resetPublicationContentMeta()
        try { File(requireContext().cacheDir, "${share.albumId}${BGMDialogFragment.BGM_FILE_SUFFIX}").delete() } catch (e:Exception) {}

        (requireActivity() as AppCompatActivity).supportActionBar?.run {
            displayOptions = androidx.appcompat.app.ActionBar.DISPLAY_HOME_AS_UP or androidx.appcompat.app.ActionBar.DISPLAY_SHOW_TITLE
            customView = null
        }

        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.publication_detail_menu, menu)

        loadingIndicator = menu.findItem(R.id.option_menu_search_progress)
        addPhotoMenuItem = menu.findItem(R.id.option_menu_add_photo)
        mapMenuItem = menu.findItem(R.id.option_menu_in_map)
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

            run map@{
                mutableListOf<NCShareViewModel.RemotePhoto>().apply { addAll(photoListAdapter.currentList) }.forEach {
                    if (it.photo.latitude != Photo.NO_GPS_DATA) {
                        mapMenuItem?.isEnabled = true
                        mapMenuItem?.isVisible = true

                        return@map
                    }
                }
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
                addFileLauncher.launch("*/*")
                true
            }
            R.id.option_menu_in_map-> {
                reenterTransition = MaterialSharedAxis(MaterialSharedAxis.Z, false).apply { duration = resources.getInteger(android.R.integer.config_longAnimTime).toLong() }
                exitTransition = MaterialSharedAxis(MaterialSharedAxis.Z, true).apply { duration = resources.getInteger(android.R.integer.config_longAnimTime).toLong() }
                parentFragmentManager.beginTransaction().replace(R.id.container_root, PhotosInMapFragment.newInstance(
                    Album(
                        id = share.albumId,
                        name = share.albumName,
                        eTag = Photo.ETAG_FAKE,
                        shareId = Album.REMOTE_ALBUM,
                        lastModified = LocalDateTime.MIN
                    ),
                    Tools.getPhotosWithCoordinate(
                        mutableListOf<Photo>().apply { photoListAdapter.currentList.forEach { add(it.photo) }},
                        PreferenceManager.getDefaultSharedPreferences(requireContext()).getBoolean(getString(R.string.nearby_convergence_pref_key), true),
                        share.sortOrder
                    )
                ), PhotosInMapFragment::class.java.canonicalName).addToBackStack(null).commit()
                true
            }
            else-> false
        }

    class PhotoListAdapter(private val clickListener: (ImageView, List<NCShareViewModel.RemotePhoto>, Int) -> Unit, private val imageLoader: (NCShareViewModel.RemotePhoto, ImageView) -> Unit, private val cancelLoading: (View) -> Unit
    ): ListAdapter<NCShareViewModel.RemotePhoto, PhotoListAdapter.ViewHolder>(PhotoDiffCallback()) {
        private val mBoundViewHolders = mutableSetOf<ViewHolder>()
        private var displayMeta = false
        private var playMark: Drawable? = null

        inner class ViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
            private var currentPhotoId = ""
            private val ivPhoto = itemView.findViewById<ImageView>(R.id.media).apply { foregroundGravity = Gravity.CENTER }
            private val tvMeta = itemView.findViewById<TextView>(R.id.meta)

            fun bind(item: NCShareViewModel.RemotePhoto, position: Int) {
                ivPhoto.apply {
                    if (currentPhotoId != item.photo.id) {
                        this.setImageResource(0)
                        imageLoader(item, this)
                        ViewCompat.setTransitionName(this, item.photo.id)
                        currentPhotoId = item.photo.id
                    }
                    (itemView as ConstraintLayout).let {
                        ConstraintSet().apply {
                            clone(it)
                            setDimensionRatio(R.id.media, "H,${item.photo.width}:${item.photo.height}")
                            applyTo(it)
                        }
                    }
                    foreground = if (Tools.isMediaPlayable(item.photo.mimeType)) playMark else null
                    setOnClickListener { clickListener(this, currentList, position) }
                }

                tvMeta.apply {
                    text = String.format("%s, %s", item.photo.dateTaken.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()), item.photo.dateTaken.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)))
                    visibility = if (displayMeta) View.VISIBLE else View.GONE
                }
            }

            fun toggleMeta() {
                tvMeta.visibility = if (displayMeta) View.VISIBLE else View.GONE
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
        fun setPlayMarkDrawable(newDrawable: Drawable) { playMark = newDrawable }
    }

    class PhotoDiffCallback: DiffUtil.ItemCallback<NCShareViewModel.RemotePhoto>() {
        override fun areItemsTheSame(oldItem: NCShareViewModel.RemotePhoto, newItem: NCShareViewModel.RemotePhoto): Boolean = oldItem.photo.id == newItem.photo.id
        override fun areContentsTheSame(oldItem: NCShareViewModel.RemotePhoto, newItem: NCShareViewModel.RemotePhoto): Boolean = oldItem.photo.id == newItem.photo.id
    }

    class CurrentPublicationViewModel: ViewModel() {
        private val currentPosition = SingleLiveEvent<Int>()
        private var firstItem = -1
        private var lastItem = -1

        fun setCurrentPosition(pos: Int) { currentPosition.value = pos }
        fun getCurrentPosition(): SingleLiveEvent<Int> = currentPosition
        fun getCurrentPositionValue(): Int = currentPosition.value ?: -1

        fun saveCurrentRange(start: Int, end: Int) {
            firstItem = start
            lastItem = end
        }
        fun getLastRange(): Pair<Int, Int> = Pair(firstItem, lastItem)
    }

    companion object {
        private const val TAG_ACQUIRING_DIALOG = "JOINT_ALBUM_ACQUIRING_DIALOG"

        const val JOINT_ALBUM_ID = "joint"

        private const val SHARE = "SHARE"
        private const val CURRENT_ITEM = "CLICKED_ITEM"
        private const val SHOW_META = "SHOW_META"

        @JvmStatic
        fun newInstance(share: NCShareViewModel.ShareWithMe) = PublicationDetailFragment().apply { arguments = Bundle().apply { putParcelable(SHARE, share) }}
    }
}