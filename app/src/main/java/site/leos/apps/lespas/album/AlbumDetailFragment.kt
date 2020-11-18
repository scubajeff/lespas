package site.leos.apps.lespas.album

import android.app.Application
import android.os.Bundle
import android.view.*
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.core.view.ViewCompat
import androidx.core.view.doOnPreDraw
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.selection.ItemDetailsLookup
import androidx.recyclerview.selection.ItemKeyProvider
import androidx.recyclerview.selection.SelectionTracker
import androidx.recyclerview.selection.StorageStrategy
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import site.leos.apps.lespas.R
import site.leos.apps.lespas.photo.BottomControlsFragment
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.photo.PhotoSlideFragment
import site.leos.apps.lespas.photo.PhotoViewModel
import site.leos.apps.lespas.sync.Action
import site.leos.apps.lespas.sync.ActionViewModel

class AlbumDetailFragment : Fragment(), ActionMode.Callback, ConfirmDialogFragment.OnPositiveConfirmedListener, AlbumRenameDialogFragment.OnFinishListener {
    private lateinit var mAdapter: PhotoGridAdapter
    private lateinit var photoListViewModel: PhotoViewModel
    private val albumModel: AlbumViewModel by activityViewModels()
    private val actionModel: ActionViewModel by viewModels()
    private lateinit var album: Album
    private var selectionTracker: SelectionTracker<Long>? = null
    private var actionMode: ActionMode? = null

    companion object {
        private const val ALBUM = "ALBUM"

        @JvmStatic
        fun newInstance(album: Album) = AlbumDetailFragment().apply { arguments = Bundle().apply{ putParcelable(ALBUM, album) }}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        album = arguments?.getParcelable(ALBUM)!!

        mAdapter = PhotoGridAdapter(object: PhotoGridAdapter.OnItemClickListener {
            override fun onItemClick(view: View, position: Int) {
                parentFragmentManager.beginTransaction()
                    .setReorderingAllowed(true)
                    .addSharedElement(view, "full_image")
                    .replace(R.id.container_root, PhotoSlideFragment.newInstance(album, position)).addToBackStack(PhotoSlideFragment.javaClass.name)
                    .add(R.id.container_bottom_toolbar, BottomControlsFragment.newInstance(album), BottomControlsFragment.javaClass.name)
                    .commit()
            }
        })

        photoListViewModel = ViewModelProvider(this, PhotosViewModelFactory(this.requireActivity().application, album.id)).get(PhotoViewModel::class.java)

        setHasOptionsMenu(true)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_albumdetail, container, false)

        view.findViewById<RecyclerView>(R.id.photogrid).run {
            // Special span size to show cover at the top of the grid
            val defaultSpanCount = (layoutManager as GridLayoutManager).spanCount
            layoutManager = GridLayoutManager(activity?.applicationContext, defaultSpanCount).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int { return if (position == 0) defaultSpanCount else 1 }
                }
            }

            adapter = mAdapter

            selectionTracker = SelectionTracker.Builder<Long> (
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

                        if (selectionTracker?.hasSelection() as Boolean && actionMode == null) {
                            actionMode = (activity as? AppCompatActivity)?.startSupportActionMode(this@AlbumDetailFragment)
                            actionMode?.let { it.title = getString(R.string.selected_count, selectionTracker?.selection?.size())}
                        } else if (!(selectionTracker?.hasSelection() as Boolean) && actionMode != null) {
                            actionMode?.finish()
                            actionMode = null
                        } else actionMode?.title = getString(R.string.selected_count, selectionTracker?.selection?.size())
                    }
                })

                if (savedInstanceState != null) onRestoreInstanceState(savedInstanceState)
            }
        }

        mAdapter.setSelectionTracker(selectionTracker as SelectionTracker<Long>)

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        postponeEnterTransition()

        albumModel.getAlbumByID(album.id).observe(viewLifecycleOwner, { album-> mAdapter.setAlbum(album) })
        photoListViewModel.allPhotoInAlbum.observe(viewLifecycleOwner, { photos ->
            mAdapter.setPhotos(photos)
            (view.parent as? ViewGroup)?.doOnPreDraw { startPostponedEnterTransition() }
        })
    }

    override fun onResume() {
        super.onResume()

        (activity as? AppCompatActivity)?.supportActionBar?.run {
            setDisplayHomeAsUpEnabled(true)
            title = album.name
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        selectionTracker?.onSaveInstanceState(outState)
    }

    // Adpater for photo grid
    class PhotoGridAdapter(private val itemClickListener: OnItemClickListener) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var album: Album? = null
        private var photos = emptyList<Photo>()
        private lateinit var selectionTracker: SelectionTracker<Long>

        init {
            setHasStableIds(true)
        }

        interface OnItemClickListener {
            fun onItemClick(view: View, position: Int)
        }

        inner class CoverViewHolder(private val itemView: View) : RecyclerView.ViewHolder(itemView) {
            fun bindViewItem() {
                itemView.run {
                    findViewById<ImageView>(R.id.cover).run {
                        setImageResource(R.drawable.ic_footprint)
                        scrollTo(0, 200)
                    }
                    findViewById<TextView>(R.id.title).text = album?.name
                }
            }

            fun getItemDetails() = object : ItemDetailsLookup.ItemDetails<Long>() {
                override fun getPosition(): Int = adapterPosition
                override fun getSelectionKey(): Long? = itemId
                //override fun inSelectionHotspot(e: MotionEvent): Boolean = true
            }
        }

        inner class PhotoViewHolder(private val itemView: View) : RecyclerView.ViewHolder(itemView) {
            fun bindViewItem(photo: Photo, clickListener: OnItemClickListener, isActivated: Boolean) {
                itemView.apply {
                    findViewById<TextView>(R.id.title).text = photo.name.substringAfterLast('/')
                    findViewById<ImageView>(R.id.pic).run {
                        setImageResource(R.drawable.ic_baseline_broken_image_24)
                        ViewCompat.setTransitionName(this, photo.id)
                    }

                    this.isActivated = isActivated
                    setOnClickListener {
                        clickListener.onItemClick(findViewById<ImageView>(R.id.pic), adapterPosition - 1)
                    }
                }
            }

            fun getItemDetails() = object : ItemDetailsLookup.ItemDetails<Long>() {
                override fun getPosition(): Int = adapterPosition
                override fun getSelectionKey(): Long? = itemId
                //override fun inSelectionHotspot(e: MotionEvent): Boolean = true
            }
        }

        internal fun setPhotos(photos: List<Photo>) {
            this.photos = photos
            notifyDataSetChanged()
        }

        internal fun setAlbum(album: Album) {
            this.album = album
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == TYPE_COVER) CoverViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_cover, parent, false))
                    else PhotoViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_photo, parent, false))

        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is PhotoViewHolder) holder.bindViewItem(photos[position - 1], itemClickListener, selectionTracker.isSelected(position.toLong()))
            else (holder as CoverViewHolder).bindViewItem()
        }

        override fun getItemCount() = photos.size + 1

        override fun getItemViewType(position: Int): Int {
            return if (position == 0) TYPE_COVER else TYPE_PHOTO
        }

        override fun getItemId(position: Int): Long = position.toLong()

        fun setSelectionTracker(selectionTracker: SelectionTracker<Long>) { this.selectionTracker = selectionTracker }

        class PhotoKeyProvider(): ItemKeyProvider<Long>(ItemKeyProvider.SCOPE_CACHED) {
            override fun getKey(position: Int): Long? = position.toLong()
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

    // ViewModelFactory to pass String parameter to ViewModel object
    class PhotosViewModelFactory(private val application: Application, private val myExtraParam: String) : ViewModelProvider.NewInstanceFactory() {
        override fun <T : ViewModel?> create(modelClass: Class<T>): T = PhotoViewModel(application, myExtraParam) as T
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.album_detail_menu, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when(item.itemId) {
            R.id.option_menu_rename-> {
                AlbumRenameDialogFragment.newInstance(album.name).let {
                    it.setTargetFragment(this, 0)
                    it.show(parentFragmentManager, "")
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
                ConfirmDialogFragment.newInstance(getString(R.string.confirm_delete)).let {
                    it.setTargetFragment(this, 0)
                    it.show(parentFragmentManager, "")
                }

                true
            }
            R.id.share -> {
                for (i in selectionTracker?.selection!!) {}

                selectionTracker?.clearSelection()
                true
            }
            else -> false
        }
    }

    override fun onDestroyActionMode(mode: ActionMode?) {
        selectionTracker?.clearSelection()
        actionMode = null
    }

    override fun onPositiveConfirmed() {
        val photos = mutableListOf<Photo>()
        for (i in selectionTracker?.selection!!) {
            photos.add(photoListViewModel.allPhotoInAlbum.value!![i.toInt() - 1])
        }
        photoListViewModel.deletePhotos(photos, album.name)

        selectionTracker?.clearSelection()
    }

    override fun onRenameFinished(newName: String) {
        if (newName != album.name) {
            if (!albumModel.isAlbumNameExisted(newName)) {
                // Action's filename field is the new directory name
                actionModel.addActions(Action(null, Action.ACTION_RENAME_DIRECTORY, album.id, album.name, newName, System.currentTimeMillis(), 1))
            }
        }
    }
}