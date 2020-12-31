package site.leos.apps.lespas.album

import android.app.PendingIntent
import android.content.ClipData
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.*
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.core.app.SharedElementCallback
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.preference.PreferenceManager
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import site.leos.apps.lespas.MainActivity
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.ImageLoaderViewModel
import site.leos.apps.lespas.helper.ShareChooserBroadcastReceiver
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.photo.BottomControlsFragment
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.photo.PhotoSlideFragment
import site.leos.apps.lespas.settings.SettingsFragment
import site.leos.apps.lespas.sync.Action
import site.leos.apps.lespas.sync.ActionViewModel
import java.io.File
import java.time.Duration
import java.time.ZoneId

class AlbumDetailFragment : Fragment(), ActionMode.Callback, ConfirmDialogFragment.OnResultListener, AlbumRenameDialogFragment.OnFinishListener {
    private lateinit var album: Album
    private var actionMode: ActionMode? = null
    private lateinit var stub: View
    private lateinit var recyclerView: RecyclerView
    private lateinit var mAdapter: PhotoGridAdapter

    private lateinit var selectionTracker: SelectionTracker<Long>
    private lateinit var lastSelection: MutableSet<Long>
    private var isScrolling = false

    private val albumModel: AlbumViewModel by activityViewModels()
    private val actionModel: ActionViewModel by viewModels()
    private val imageLoaderModel: ImageLoaderViewModel by activityViewModels()
    private val currentPhotoModel: PhotoSlideFragment.CurrentPhotoViewModel by activityViewModels()

    private lateinit var sp: SharedPreferences
    private lateinit var sharedPhoto: Photo
    private val snapseedCatcher = ShareChooserBroadcastReceiver()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        album = arguments?.getParcelable(ALBUM)!!

        // Must be restore here
        lastSelection = mutableSetOf()
        savedInstanceState?.let {
            lastSelection = it.getLongArray(SELECTION)?.toMutableSet()!!
        } ?: run {
            with(currentPhotoModel) {
                setCurrentPosition(0)
                setFirstPosition(0)
                setLastPosition(1)
            }
        }

        sharedElementEnterTransition = MaterialContainerTransform().apply {
            duration = resources.getInteger(android.R.integer.config_shortAnimTime).toLong()
            scrimColor = Color.TRANSPARENT
        }

        // Adjusting the shared element mapping
        setExitSharedElementCallback(object : SharedElementCallback() {
            override fun onMapSharedElements(names: MutableList<String>?, sharedElements: MutableMap<String, View>?) {
                recyclerView.findViewHolderForAdapterPosition(currentPhotoModel.getCurrentPosition())?.let {
                    sharedElements?.put(names?.get(0)!!, it.itemView.findViewById(R.id.photo))
                }
            }
        })

        setHasOptionsMenu(true)

        sp = PreferenceManager.getDefaultSharedPreferences(requireContext())
        context?.registerReceiver(snapseedCatcher, IntentFilter(CHOOSER_SPY_ACTION))
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
                currentPhotoModel.run {
                    setCurrentPosition(position)
                    setFirstPosition((recyclerView.layoutManager as GridLayoutManager).findFirstCompletelyVisibleItemPosition())
                    setLastPosition((recyclerView.layoutManager as GridLayoutManager).findLastVisibleItemPosition())
                }

                // Get a stub as fake toolbar since the toolbar belongs to MainActivity and it will disappear during fragment transaction
                stub.background = (activity as MainActivity).getToolbarViewContent()

                reenterTransition = MaterialElevationScale(true).apply { duration = resources.getInteger(android.R.integer.config_mediumAnimTime).toLong() }
                exitTransition = MaterialElevationScale(false).apply {
                    duration = resources.getInteger(android.R.integer.config_mediumAnimTime).toLong()
                    excludeTarget(R.id.stub, true)
                }

                parentFragmentManager.beginTransaction()
                    .setReorderingAllowed(true)
                    .addSharedElement(view, view.transitionName)
                    .replace(R.id.container_root, PhotoSlideFragment.newInstance(album, album.sortOrder)).addToBackStack(PhotoSlideFragment::class.simpleName)
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

            // Scroll to the correct position
            with(currentPhotoModel) {
                val cp = getCurrentPosition()
                val fp = getFirstPosition()
                if (!isScrolling) (recyclerView.layoutManager as GridLayoutManager).scrollToPosition( if ((cp > getLastPosition()) || (cp < fp)) cp else fp )
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
                    when(newState) {
                        RecyclerView.SCROLL_STATE_IDLE-> {
                            with(currentPhotoModel) {
                                setCurrentPosition((layoutManager as GridLayoutManager).findFirstCompletelyVisibleItemPosition())
                                setFirstPosition((layoutManager as GridLayoutManager).findFirstCompletelyVisibleItemPosition())
                                setLastPosition((layoutManager as GridLayoutManager).findLastVisibleItemPosition())
                            }
                            isScrolling = false
                        }
                        else-> isScrolling = true
                    }
                }
            })
        }
    }

    override fun onResume() {
        super.onResume()

        (activity as? AppCompatActivity)?.supportActionBar?.setDisplayHomeAsUpEnabled(true)
        if (sp.getBoolean(getString(R.string.snapseed_pref_key), false) && snapseedCatcher.getDest() == "snapseed") checkSnapseed()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLongArray(SELECTION, lastSelection.toLongArray())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // TODO right place to do this?
        recyclerView.clearOnScrollListeners()
    }

    override fun onDestroy() {
        super.onDestroy()

        context?.unregisterReceiver(snapseedCatcher)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.album_detail_menu, menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        menu.findItem(R.id.option_menu_sortbydateasc).isChecked = false
        menu.findItem(R.id.option_menu_sortbydatedesc).isChecked = false
        menu.findItem(R.id.option_menu_sortbynameasc).isChecked = false
        menu.findItem(R.id.option_menu_sortbynamedesc).isChecked = false

        when(album.sortOrder) {
            Album.BY_DATE_TAKEN_ASC-> menu.findItem(R.id.option_menu_sortbydateasc).isChecked = true
            Album.BY_DATE_TAKEN_DESC-> menu.findItem(R.id.option_menu_sortbydatedesc).isChecked = true
            Album.BY_NAME_ASC-> menu.findItem(R.id.option_menu_sortbynameasc).isChecked = true
            Album.BY_NAME_DESC-> menu.findItem(R.id.option_menu_sortbynamedesc).isChecked = true
        }

        super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when(item.itemId) {
            R.id.option_menu_rename-> {
                if (parentFragmentManager.findFragmentByTag(RENAME_DIALOG) == null) AlbumRenameDialogFragment.newInstance(album.name).let {
                    it.setTargetFragment(this, 0)
                    it.show(parentFragmentManager, RENAME_DIALOG)
                }
                true
            }
            R.id.option_menu_settings-> {
                exitTransition = null
                reenterTransition = null
                parentFragmentManager.beginTransaction().replace(R.id.container_root, SettingsFragment()).addToBackStack(null).commit()
                true
            }
            R.id.option_menu_sortbydateasc-> {
                albumModel.setSortOrder(album.id, Album.BY_DATE_TAKEN_ASC)
                true
            }
            R.id.option_menu_sortbydatedesc-> {
                albumModel.setSortOrder(album.id, Album.BY_DATE_TAKEN_DESC)
                true
            }
            R.id.option_menu_sortbynameasc-> {
                albumModel.setSortOrder(album.id, Album.BY_NAME_ASC)
                true
            }
            R.id.option_menu_sortbynamedesc-> {
                albumModel.setSortOrder(album.id, Album.BY_NAME_DESC)
                true
            }
            else-> false
        }
    }

    // On special Actions of this fragment
    override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
        mode?.menuInflater?.inflate(R.menu.actions_mode, menu)

        return true
    }

    override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean = false

    override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
        return when(item?.itemId) {
            R.id.remove -> {
                if (parentFragmentManager.findFragmentByTag(CONFIRM_DIALOG) == null) ConfirmDialogFragment.newInstance(getString(R.string.confirm_delete), getString(R.string.yes_delete)).let {
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
                    }
                }

                val clipData = ClipData.newUri(requireActivity().contentResolver, "", uris[0])
                for (uri in uris) clipData.addItem(ClipData.Item(uri))

                sharedPhoto = mAdapter.getPhotoAt(selectionTracker.selection.first().toInt())
                if (selectionTracker.selection.size() > 1) {
                    startActivity(
                        Intent.createChooser(
                            Intent().apply {
                                action = Intent.ACTION_SEND_MULTIPLE
                                type = sharedPhoto.mimeType
                                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                                this.clipData = clipData
                                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                            }, null
                        )
                    )
                } else {
                    // If sharing only one picture, use ACTION_SEND instead, so that other apps which won't accept ACTION_SEND_MULTIPLE will work
                    startActivity(
                        Intent.createChooser(
                            Intent().apply {
                                action = Intent.ACTION_SEND
                                type = sharedPhoto.mimeType
                                putExtra(Intent.EXTRA_STREAM, uris[0])
                                this.clipData = clipData
                                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                            }, null, PendingIntent.getBroadcast(context, 1, Intent(CHOOSER_SPY_ACTION), PendingIntent.FLAG_UPDATE_CURRENT).intentSender
                        )
                    )
                }

                selectionTracker.clearSelection()
                true
            }
            R.id.select_all -> {
                for (i in 0..mAdapter.itemCount) selectionTracker.select(i.toLong())
                true
            }
            else -> false
        }
    }

    override fun onDestroyActionMode(mode: ActionMode?) {
        selectionTracker.clearSelection()
        actionMode = null
    }

    override fun onResult(positive: Boolean, requestCode: Int) {
        if (positive) {
            val photos = mutableListOf<Photo>()
            for (i in selectionTracker.selection)
                mAdapter.getPhotoAt(i.toInt()).run { if (id != album.cover) photos.add(this) }
            if (photos.isNotEmpty()) actionModel.deletePhotos(photos, album.name)
        }
        selectionTracker.clearSelection()
    }

    override fun onRenameFinished(newName: String) {
        if (newName != album.name) {
            actionModel.renameAlbum(album.id, album.name, newName)
        }
    }

    private fun checkSnapseed() {
        CoroutineScope(Dispatchers.Default).launch(Dispatchers.IO) {
            val snapseedFile = File("${Environment.getExternalStorageDirectory().absolutePath}/Snapseed/${sharedPhoto.name.substringBeforeLast('.')}-01.jpeg")
            val appRootFolder = "${requireActivity().filesDir}${getString(R.string.lespas_base_folder_name)}"

            // Clear flag
            snapseedCatcher.clearFlag()

            if (snapseedFile.exists()) {
                if (sp.getBoolean(getString(R.string.snapseed_replace_pref_key), false)) {
                    // Replace the original

                    // Compare file size, make sure it's a different edition
                    if (snapseedFile.length() != File(appRootFolder, sharedPhoto.id).length()) {
                        try {
                            snapseedFile.inputStream().use { input->
                                // Name new photo filename after Snapseed's output name
                                File(appRootFolder, snapseedFile.name).outputStream().use { output->
                                    input.copyTo(output)
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                            // Quit when exception happens during file copy
                            return@launch
                        }

                        // Add newPhoto, delete old photo locally
                        val newPhoto = with(snapseedFile.name) { Tools.getPhotoParams("$appRootFolder/$this", PhotoSlideFragment.JPEG, this).copy(id = this, albumId = album.id, name = this) }
                        albumModel.replacePhoto(sharedPhoto, newPhoto)
                        // Fix album cover Id if required
                        if (album.cover == sharedPhoto.id)
                            albumModel.replaceCover(album.id, newPhoto.id, newPhoto.width, newPhoto.height, (album.coverBaseline.toFloat() * newPhoto.height / album.coverHeight).toInt())
                        // Invalid image cache
                        imageLoaderModel.invalid(sharedPhoto)
                        // Delete old image file, TODO: the file might be using by some other process, like uploading to server
                        try {
                            File(appRootFolder, sharedPhoto.id).delete()
                        } catch (e: Exception) { e.printStackTrace() }


                        // Add newPhoto, delete old photo remotely
                        with(mutableListOf<Action>()) {
                            // Pass photo mimeType in Action's folderId property
                            add(Action(null, Action.ACTION_ADD_FILES_ON_SERVER, newPhoto.mimeType, album.name, newPhoto.id, newPhoto.name, System.currentTimeMillis(), 1))
                            add(Action(null, Action.ACTION_DELETE_FILES_ON_SERVER, album.id, album.name, sharedPhoto.id, sharedPhoto.name, System.currentTimeMillis(), 1))
                            actionModel.addActions(this)
                        }
                    }
                } else {
                    // Copy Snapseed output

                    // Append timestamp suffix to make a unique filename
                    val fileName = "${snapseedFile.name.substringBeforeLast('.')}_${System.currentTimeMillis()}.${snapseedFile.name.substringAfterLast('.')}"

                    try {
                        snapseedFile.inputStream().use { input ->
                            File(appRootFolder, fileName).outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        return@launch
                    }

                    // Create new photo
                    albumModel.addPhoto(Tools.getPhotoParams("$appRootFolder/$fileName", PhotoSlideFragment.JPEG, fileName).copy(id = fileName, albumId = album.id, name = fileName))

                    // Upload changes to server, mimetype passed in folderId property
                    actionModel.addAction(Action(null, Action.ACTION_ADD_FILES_ON_SERVER, PhotoSlideFragment.JPEG, album.name, fileName, fileName, System.currentTimeMillis(), 1))
                }

                // Repeat editing of same source will generate multiple files with sequential suffix, remove Snapseed output to avoid tedious filename parsing
                try {
                    snapseedFile.delete()
                } catch (e: Exception) { e.printStackTrace() }
            }

            // Remove cache copy too
            try {
                File(requireContext().cacheDir, sharedPhoto.name).delete()
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    // Adpater for photo grid
    class PhotoGridAdapter(private val itemClickListener: OnItemClick, private val imageLoader: OnLoadImage, private val titleUpdator: OnTitleVisibility) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var oldSortOrder = Album.BY_DATE_TAKEN_ASC
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
                        imageLoader.loadImage(photo, this, ImageLoaderViewModel.TYPE_GRID)

                        if (this.isActivated) {
                            colorFilter = selectedFilter
                            it.findViewById<ImageView>(R.id.selection_mark).visibility = View.VISIBLE
                        } else {
                            clearColorFilter()
                            it.findViewById<ImageView>(R.id.selection_mark).visibility = View.GONE
                        }

                        ViewCompat.setTransitionName(this, photo.id)

                        setOnClickListener { if (!selectionTracker.hasSelection()) clickListener.onItemClick(this, adapterPosition) }
                    }

                    it.findViewById<ImageView>(R.id.play_mark).visibility = if (Tools.isMediaPlayable(photo.mimeType)) View.VISIBLE else View.GONE
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
            album.album.run { photos.add(Photo(cover, id, "", "", startDate, endDate, coverWidth, coverHeight, "", coverBaseline)) }
            this.photos.addAll(1,
                when(album.album.sortOrder) {
                    Album.BY_DATE_TAKEN_ASC-> album.photos.sortedWith(compareBy { it.dateTaken })
                    Album.BY_DATE_TAKEN_DESC-> album.photos.sortedWith(compareByDescending { it.dateTaken })
                    Album.BY_DATE_MODIFIED_ASC-> album.photos.sortedWith(compareBy { it.lastModified })
                    Album.BY_DATE_MODIFIED_DESC-> album.photos.sortedWith(compareByDescending { it.lastModified })
                    Album.BY_NAME_ASC-> album.photos.sortedWith(compareBy { it.name })
                    Album.BY_NAME_DESC-> album.photos.sortedWith(compareByDescending { it.name })
                    else-> album.photos
                }
            )
            if (oldSortOrder != album.album.sortOrder) {
                // sort order changes will change nearly all the position, so no need to use DiffUtil
                notifyDataSetChanged()
                oldSortOrder = album.album.sortOrder
            } else {
                DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                    override fun getOldListSize() = oldPhotos.size
                    override fun getNewListSize() = photos.size
                    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                        ((oldPhotos[oldItemPosition].id == photos[newItemPosition].id) && (oldPhotos[oldItemPosition].name == photos[newItemPosition].name))

                    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                        if (oldItemPosition == 0 && newItemPosition == 0)
                            //oldPhotos[oldItemPosition].id == photos[newItemPosition].id &&              // cover's photo id, already checked in areItemsTheSame?
                            oldPhotos[oldItemPosition].shareId == photos[newItemPosition].shareId &&    // cover baseline
                            oldPhotos.size == photos.size                                               // photo added or deleted, photo count and duration affected
                        else oldPhotos[oldItemPosition] == photos[newItemPosition]
                }).dispatchUpdatesTo(this)
            }
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
        private const val SELECTION = "SELECTION"
        const val CHOOSER_SPY_ACTION = "site.leos.apps.lespas.CHOOSER_ALBUMDETAIL"

        fun newInstance(album: Album) = AlbumDetailFragment().apply { arguments = Bundle().apply{ putParcelable(ALBUM, album) }}
    }
}