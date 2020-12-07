package site.leos.apps.lespas.album

import android.content.Intent
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.recyclerview.selection.*
import androidx.recyclerview.selection.SelectionTracker.Builder
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.google.android.material.transition.MaterialContainerTransform
import com.google.android.material.transition.MaterialElevationScale
import kotlinx.android.synthetic.main.recyclerview_item_album.view.*
import kotlinx.android.synthetic.main.recyclerview_item_cover.view.*
import kotlinx.android.synthetic.main.recyclerview_item_photo.*
import kotlinx.android.synthetic.main.recyclerview_item_photo.view.*
import site.leos.apps.lespas.MainActivity
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.ImageLoaderViewModel
import site.leos.apps.lespas.photo.BottomControlsFragment
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.photo.PhotoSlideFragment
import site.leos.apps.lespas.sync.ActionViewModel
import java.io.File
import java.time.Duration
import java.time.ZoneId

class AlbumDetailFragment : Fragment(), ActionMode.Callback, ConfirmDialogFragment.OnPositiveConfirmedListener, AlbumRenameDialogFragment.OnFinishListener {
    private lateinit var album: Album
    private var actionMode: ActionMode? = null
    private lateinit var stub: View
    private lateinit var recyclerView: RecyclerView
    private lateinit var mAdapter: PhotoGridAdapter

    private lateinit var selectionTracker: SelectionTracker<Long>
    private lateinit var lastSelection: MutableSet<Long>
    private var lastScrollPosition = -1

    private val albumModel: AlbumViewModel by activityViewModels()
    private val actionModel: ActionViewModel by viewModels()
    private val imageLoaderModel: ImageLoaderViewModel by activityViewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        album = arguments?.getParcelable(ALBUM)!!

        // Must be restore here
        lastScrollPosition = savedInstanceState?.getInt(SCROLL_POSITION) ?: -1
        lastSelection = savedInstanceState?.getLongArray(SELECTION)?.toMutableSet() ?: mutableSetOf()

        sharedElementEnterTransition = MaterialContainerTransform().apply {
            duration = resources.getInteger(android.R.integer.config_shortAnimTime).toLong()
            scrimColor = Color.TRANSPARENT
        }

        setHasOptionsMenu(true)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        // View might not be destroy at all, reuse it here
        val vg = view ?: inflater.inflate(R.layout.fragment_albumdetail, container, false)

        stub = vg.findViewById(R.id.stub)
        recyclerView = vg.findViewById<RecyclerView>(R.id.photogrid).apply {
            // Stop item from blinking when notifying changes
            (itemAnimator as SimpleItemAnimator).supportsChangeAnimations = false

            // Special span size to show cover at the top of the grid
            val defaultSpanCount = (layoutManager as GridLayoutManager).spanCount
            layoutManager = GridLayoutManager(activity?.applicationContext, defaultSpanCount).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int { return if (position == 0) defaultSpanCount else 1 }
                }
            }
        }

        mAdapter = PhotoGridAdapter(
            { view, position ->
                stub.background = (activity as MainActivity).getToolbarViewContent()
                exitTransition = MaterialElevationScale(false).apply { duration = resources.getInteger(android.R.integer.config_mediumAnimTime).toLong() }
                reenterTransition = MaterialElevationScale(true).apply { duration = resources.getInteger(android.R.integer.config_mediumAnimTime).toLong() }
                parentFragmentManager.beginTransaction()
                    .setReorderingAllowed(true)
                    .addSharedElement(view, view.transitionName)
                    .replace(R.id.container_root, PhotoSlideFragment.newInstance(album.id, position - 1)).addToBackStack(PhotoSlideFragment::class.simpleName)
                    .add(R.id.container_bottom_toolbar, BottomControlsFragment.newInstance(album.id), BottomControlsFragment::class.simpleName)
                    .commit()
            },
            { photo, view, type -> imageLoaderModel.loadPhoto(photo, view, type) { startPostponedEnterTransition() } }
        ) { visible -> (activity as? AppCompatActivity)?.supportActionBar?.setDisplayShowTitleEnabled(visible) }

        return vg
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        postponeEnterTransition()
        ViewCompat.setTransitionName(recyclerView, album.id)

        // Register data observer first, try feeding adapter with lastest data asap
        albumModel.getAlbumDetail(album.id).observe(viewLifecycleOwner, {
            // Cover might changed, photo might be deleted, so get updates from latest here
            this.album = it.album

            mAdapter.setAlbum(it)
            (activity as? AppCompatActivity)?.supportActionBar?.title = it.album.name

            if (lastScrollPosition != -1) {
                (recyclerView.layoutManager as GridLayoutManager).scrollToPosition(lastScrollPosition)
            }
        })

        with(recyclerView) {
            adapter = mAdapter

            selectionTracker = Builder(
                "photoSelection",
                this,
                PhotoGridAdapter.PhotoKeyProvider(),
                PhotoGridAdapter.PhotoDetailsLookup(this),
                StorageStrategy.createLongStorage()
            ).withSelectionPredicate(object : SelectionTracker.SelectionPredicate<Long>() {
                override fun canSetStateForKey(key: Long, nextState: Boolean): Boolean = (key != 0L)
                override fun canSetStateAtPosition(position: Int, nextState: Boolean): Boolean = (position != 0)
                override fun canSelectMultiple(): Boolean = true
            }).build().apply {
                addObserver(object : SelectionTracker.SelectionObserver<Long>() {
                    override fun onSelectionChanged() {
                        super.onSelectionChanged()

                        if (selectionTracker.hasSelection() && actionMode == null) {
                            actionMode = (activity as? AppCompatActivity)?.startSupportActionMode(this@AlbumDetailFragment)
                            actionMode?.let { it.title = getString(R.string.selected_count, selectionTracker.selection.size())}
                        } else if (!(selectionTracker.hasSelection()) && actionMode != null) {
                            actionMode?.finish()
                            actionMode = null
                        } else actionMode?.title = getString(R.string.selected_count, selectionTracker.selection.size())
                    }

                    override fun onItemStateChanged(key: Long, selected: Boolean) {
                        super.onItemStateChanged(key, selected)
                        if (selected) lastSelection.add(key)
                        else lastSelection.remove(key)
                    }
                })
            }
            mAdapter.setSelectionTracker(selectionTracker)

            // Restore selection state
            if (lastSelection.isNotEmpty()) lastSelection.forEach { selectionTracker.select(it) }

            // Get scroll position after scroll idle
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    super.onScrollStateChanged(recyclerView, newState)
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) lastScrollPosition = (layoutManager as GridLayoutManager).findFirstCompletelyVisibleItemPosition()
                }
            })
        }
    }

    override fun onResume() {
        super.onResume()

        (activity as? AppCompatActivity)?.supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(SCROLL_POSITION, lastScrollPosition)
        outState.putLongArray(SELECTION, lastSelection.toLongArray())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // TODO right place to do this?
        recyclerView.clearOnScrollListeners()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.album_detail_menu, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when(item.itemId) {
            R.id.option_menu_rename-> {
                if (parentFragmentManager.findFragmentByTag(RENAME_DIALOG) == null) AlbumRenameDialogFragment.newInstance(album.name).let {
                    it.setTargetFragment(this, 0)
                    it.show(parentFragmentManager, RENAME_DIALOG)
                }
                return true
            }
        }
        return false
    }

    // On special Actions of this fragment
    override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
        mode?.menuInflater?.inflate(R.menu.actions_delete_and_share, menu)

        return true
    }

    override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean = false

    override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
        return when(item?.itemId) {
            R.id.remove -> {
                if (parentFragmentManager.findFragmentByTag(CONFIRM_DIALOG) == null) ConfirmDialogFragment.newInstance(getString(R.string.confirm_delete)).let {
                    it.setTargetFragment(this, 0)
                    it.show(parentFragmentManager, CONFIRM_DIALOG)
                }

                true
            }
            R.id.share -> {
                val uris = arrayListOf<Uri>()
                val filePath = "${requireActivity().filesDir}${getString(R.string.lespas_base_folder_name)}"
                val cachePath = requireActivity().cacheDir
                val authority = getString(R.string.file_authority)
                for (i in selectionTracker.selection) {
                    with(mAdapter.getPhotoAt(i.toInt())) {
                        File(filePath, id).copyTo(File(cachePath, name), true, 4096)
                        uris.add(FileProvider.getUriForFile(requireContext(), authority, File(cachePath, name)))
                        //uris.add(FileProvider.getUriForFile(requireContext(), authority, File(filePath, id)))
                    }
                }

                startActivity(Intent.createChooser(
                    Intent().apply{
                        action = Intent.ACTION_SEND_MULTIPLE
                        type = "image/*"
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    }, null)
                )

                selectionTracker.clearSelection()
                true
            }
            else -> false
        }
    }

    override fun onDestroyActionMode(mode: ActionMode?) {
        selectionTracker.clearSelection()
        actionMode = null
    }

    override fun onPositiveConfirmed() {
        val photos = mutableListOf<Photo>()
        for (i in selectionTracker.selection)
            mAdapter.getPhotoAt(i.toInt()).run { if (id != album.cover) photos.add(this) }
        if (photos.isNotEmpty()) actionModel.deletePhotos(photos, album.name)

        selectionTracker.clearSelection()
    }

    override fun onRenameFinished(newName: String) {
        if (newName != album.name) {
            actionModel.renameAlbum(album.id, album.name, newName)
        }
    }

    // Adpater for photo grid
    class PhotoGridAdapter(private val itemClickListener: OnItemClick, private val imageLoader: OnLoadImage, private val titleUpdator: OnTitleVisibility) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var photos = mutableListOf<Photo>()
        private lateinit var selectionTracker: SelectionTracker<Long>
        private val selectedFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0.0f) })
        private var currentHolder = 0

        init {
            setHasStableIds(true)
        }

        fun interface OnItemClick {
            fun onItemClick(view: View, position: Int)
        }

        fun interface OnLoadImage {
            fun loadImage(photo: Photo, view: ImageView, type: String)
        }

        fun interface OnTitleVisibility {
            fun setTitle(visible: Boolean)
        }

        inner class CoverViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            fun bindViewItem() {
                with(itemView) {
                    photos.firstOrNull()?.let {
                        imageLoader.loadImage(it, findViewById<ImageView>(R.id.cover), ImageLoaderViewModel.TYPE_COVER)
                    }

                    findViewById<TextView>(R.id.title).text = photos[0].name

                    val days = Duration.between(
                        photos[0].dateTaken.atZone(ZoneId.systemDefault()).toInstant(),
                        photos[0].lastModified.atZone(ZoneId.systemDefault()).toInstant()
                    ).toDays().toInt()
                    findViewById<TextView>(R.id.duration).text = when (days) {
                        in 0..21 -> resources.getString(R.string.duration_days, days + 1)
                        in 22..56 -> resources.getString(R.string.duration_weeks, days / 7)
                        in 57..365 -> resources.getString(R.string.duration_months, days / 30)
                        else -> resources.getString(R.string.duration_years, days / 365)
                    }

                    findViewById<TextView>(R.id.total).text = resources.getString(R.string.total_photo, photos.size - 1)
                }
            }

            fun getItemDetails() = object : ItemDetailsLookup.ItemDetails<Long>() {
                override fun getPosition(): Int = adapterPosition
                override fun getSelectionKey(): Long = itemId
                //override fun inSelectionHotspot(e: MotionEvent): Boolean = true
            }
        }

        inner class PhotoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            fun bindViewItem(photo: Photo, clickListener: OnItemClick, isActivated: Boolean) {
                itemView.let {
                    it.isActivated = isActivated

                    with(it.findViewById<ImageView>(R.id.photo)) {
                        /*
                        setPadding(
                            if (((adapterPosition - 1) % resources.getInteger(R.integer.photo_grid_span_count))!= 0) 2 else 0,
                            2, 0, 0)

                         */
                        imageLoader.loadImage(photo, this, ImageLoaderViewModel.TYPE_VIEW)

                        if (this.isActivated) {
                            colorFilter = selectedFilter
                            it.findViewById<ImageView>(R.id.selection_mark).visibility = View.VISIBLE
                        } else {
                            clearColorFilter()
                            it.findViewById<ImageView>(R.id.selection_mark).visibility = View.GONE
                        }

                        ViewCompat.setTransitionName(this, photo.id)

                        setOnClickListener { if (!selectionTracker.hasSelection()) clickListener.onItemClick(this, adapterPosition) }

                        /*
                        startAnimation(AlphaAnimation(0.5f, 1f).apply {
                            duration = 300
                            interpolator = AccelerateDecelerateInterpolator()
                        })

                         */
                    }

                }
            }

            fun getItemDetails() = object : ItemDetailsLookup.ItemDetails<Long>() {
                override fun getPosition(): Int = adapterPosition
                override fun getSelectionKey(): Long = itemId
            }
        }

        internal fun setAlbum(album: AlbumWithPhotos) {
            val oldPhotos = mutableListOf<Photo>()
            oldPhotos.addAll(0, photos)
            photos.clear()
            album.album.run { photos.add(Photo(cover, id, name, "", startDate, endDate, coverWidth, coverHeight, coverBaseline)) }
            this.photos.addAll(1, album.photos.sortedWith(compareBy { it.dateTaken }))

            DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = oldPhotos.size
                override fun getNewListSize() = photos.size
                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) = oldPhotos[oldItemPosition].id == photos[newItemPosition].id
                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                    if (oldItemPosition == 0) (oldPhotos[oldItemPosition] == photos[newItemPosition]) && oldPhotos.size == photos.size
                    else oldPhotos[oldItemPosition] == photos[newItemPosition]
            }).dispatchUpdatesTo(this)

            //Log.e("----", "setAlbum called ${photos[0].id}-${photos[0].shareId}")
        }

        internal fun getPhotoAt(position: Int): Photo {
            return photos[position]
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == TYPE_COVER) CoverViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_cover, parent, false))
                    else PhotoViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_photo, parent, false))

        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is PhotoViewHolder) holder.bindViewItem(photos[position], itemClickListener, selectionTracker.isSelected(position.toLong()))
            else (holder as CoverViewHolder).bindViewItem()
        }

        override fun getItemCount() = photos.size

        override fun getItemViewType(position: Int): Int {
            return if (position == 0) TYPE_COVER else TYPE_PHOTO
        }

        override fun getItemId(position: Int): Long = position.toLong()

        override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
            super.onViewAttachedToWindow(holder)
            if (holder is CoverViewHolder) {
                currentHolder = System.identityHashCode(holder)
                titleUpdator.setTitle(false)
            }
        }

        override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
            super.onViewDetachedFromWindow(holder)
            if (holder is CoverViewHolder)
                if (System.identityHashCode(holder) == currentHolder) titleUpdator.setTitle(true)
        }

        fun setSelectionTracker(selectionTracker: SelectionTracker<Long>) { this.selectionTracker = selectionTracker }

        class PhotoKeyProvider: ItemKeyProvider<Long>(SCOPE_CACHED) {
            override fun getKey(position: Int): Long = position.toLong()
            override fun getPosition(key: Long): Int = key.toInt()
        }

        class PhotoDetailsLookup(private val recyclerView: RecyclerView): ItemDetailsLookup<Long>() {
            override fun getItemDetails(e: MotionEvent): ItemDetails<Long>? {
                recyclerView.findChildViewUnder(e.x, e.y)?.let {
                    val holder = recyclerView.getChildViewHolder(it)
                    return if (holder is PhotoViewHolder) holder.getItemDetails() else (holder as CoverViewHolder).getItemDetails()
                }
                return null
            }
        }

        companion object {
            private const val TYPE_COVER = 0
            private const val TYPE_PHOTO = 1
        }
    }

    companion object {
        private const val ALBUM = "ALBUM"
        private const val RENAME_DIALOG = "RENAME_DIALOG"
        private const val CONFIRM_DIALOG = "CONFIRM_DIALOG"
        private const val SCROLL_POSITION = "SCROLL_POSITION"
        private const val SELECTION = "SELECTION"

        fun newInstance(album: Album) = AlbumDetailFragment().apply { arguments = Bundle().apply{ putParcelable(ALBUM, album) }}
    }
}