package site.leos.apps.lespas.album

import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.view.*
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.core.app.SharedElementCallback
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import androidx.recyclerview.selection.*
import androidx.recyclerview.selection.SelectionTracker.Builder
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import androidx.transition.Fade
import androidx.transition.TransitionManager
import androidx.work.*
import com.google.android.material.button.MaterialButton
import com.google.android.material.transition.MaterialContainerTransform
import com.google.android.material.transition.MaterialElevationScale
import kotlinx.android.synthetic.main.fragment_albumdetail.view.*
import kotlinx.android.synthetic.main.recyclerview_item_album.view.*
import kotlinx.android.synthetic.main.recyclerview_item_cover.view.*
import kotlinx.android.synthetic.main.recyclerview_item_photo.*
import kotlinx.android.synthetic.main.recyclerview_item_photo.view.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import site.leos.apps.lespas.MainActivity
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.*
import site.leos.apps.lespas.photo.BottomControlsFragment
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.photo.PhotoSlideFragment
import site.leos.apps.lespas.settings.SettingsFragment
import site.leos.apps.lespas.sync.AcquiringDialogFragment
import site.leos.apps.lespas.sync.ActionViewModel
import site.leos.apps.lespas.sync.ShareReceiverActivity
import java.io.File
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*

class AlbumDetailFragment : Fragment(), ActionMode.Callback, ConfirmDialogFragment.OnResultListener, AlbumRenameDialogFragment.OnFinishListener {
    private lateinit var album: Album
    private var actionMode: ActionMode? = null
    private lateinit var stub: View
    private lateinit var recyclerView: RecyclerView
    private lateinit var dateIndicator: MaterialButton
    private lateinit var mAdapter: PhotoGridAdapter

    private lateinit var selectionTracker: SelectionTracker<Long>
    private lateinit var lastSelection: MutableSet<Long>
    private var isScrolling = false

    private val albumModel: AlbumViewModel by activityViewModels()
    private val actionModel: ActionViewModel by activityViewModels()
    private val imageLoaderModel: ImageLoaderViewModel by activityViewModels()
    private val currentPhotoModel: PhotoSlideFragment.CurrentPhotoViewModel by activityViewModels()

    private lateinit var sharedPhoto: Photo

    private lateinit var snapseedCatcher: BroadcastReceiver
    private lateinit var snapseedOutputObserver: ContentObserver

    private lateinit var sharedSelection: MutableSet<Long>

    private lateinit var removeOriginalBroadcastReceiver: RemoveOriginalBroadcastReceiver

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        album = arguments?.getParcelable(KEY_ALBUM)!!

        // Must be restore here
        lastSelection = mutableSetOf()
        sharedSelection = mutableSetOf()
        savedInstanceState?.let {
            lastSelection = it.getLongArray(SELECTION)?.toMutableSet()!!
            sharedSelection = it.getLongArray(SHARED_SELECTION)?.toMutableSet()!!
        }
        ?: run {
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

        // Broadcast receiver listening on share destination
        snapseedCatcher = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent!!.getParcelableExtra<ComponentName>(Intent.EXTRA_CHOSEN_COMPONENT)?.packageName!!.substringAfterLast('.') == "snapseed") {
                    // Shared to Snapseed. Register content observer if we have storage permission and integration with snapseed option is on
                    if (ContextCompat.checkSelfPermission(context!!, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) android.Manifest.permission.READ_EXTERNAL_STORAGE else android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(getString(R.string.snapseed_pref_key), false)) {
                        context.contentResolver.apply {
                            unregisterContentObserver(snapseedOutputObserver)
                            registerContentObserver(
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                true,
                                snapseedOutputObserver
                            )
                        }
                    }
                }
            }
        }
        context?.registerReceiver(snapseedCatcher, IntentFilter(CHOOSER_SPY_ACTION))

        // Content observer looking for Snapseed output
        snapseedOutputObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            private var lastId = ""
            private lateinit var snapseedWork: WorkRequest

            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)

                // ContentObserver got called twice, once for itself, once for it's descendant, all with same last path segment
                if (uri?.lastPathSegment!! != lastId) {
                    lastId = uri.lastPathSegment!!

                    snapseedWork = OneTimeWorkRequestBuilder<SnapseedResultWorker>().setInputData(
                        workDataOf(SnapseedResultWorker.KEY_IMAGE_URI to uri.toString(), SnapseedResultWorker.KEY_SHARED_PHOTO to sharedPhoto.id, SnapseedResultWorker.KEY_ALBUM to album.id)).build()
                    WorkManager.getInstance(requireContext()).enqueue(snapseedWork)

                    WorkManager.getInstance(requireContext()).getWorkInfoByIdLiveData(snapseedWork.id).observe(parentFragmentManager.findFragmentById(R.id.container_root)!!, { workInfo->
                        if (workInfo != null) {
                            //if (workInfo.progress.getBoolean(SnapseedResultWorker.KEY_INVALID_OLD_PHOTO_CACHE, false)) imageLoaderModel.invalid(sharedPhoto)
                            workInfo.progress.getString(SnapseedResultWorker.KEY_NEW_PHOTO_NAME)?.let {
                                //sharedPhoto.name = it
                                //sharedPhoto.eTag = ""
                                //imageLoaderModel.reloadPhoto(sharedPhoto)
                                //recyclerView.findViewHolderForAdapterPosition(mAdapter.findPhotoPosition(sharedPhoto))?.itemView?.findViewById<ImageView>(R.id.photo)?.invalidate()
                                //mAdapter.refreshPhoto(sharedPhoto)
                                imageLoaderModel.invalid(sharedPhoto.id)
                                mAdapter.refreshPhoto(sharedPhoto)
                            }
                        }
                        /*
                        if (workInfo != null && workInfo.state.isFinished) {
                            if (workInfo.outputData.getBoolean(SnapseedResultWorker.KEY_INVALID_OLD_PHOTO_CACHE, false)) {
                                imageLoaderModel.invalid(sharedPhoto)
                                mAdapter.refreshPhoto(sharedPhoto)
                            }
                        }
                         */
                    })
                }

                requireContext().contentResolver.unregisterContentObserver(this)
            }
        }

        mAdapter = PhotoGridAdapter(
            { view, position ->
                currentPhotoModel.run {
                    //setCurrentPosition(position)
                    setCurrentPhoto(mAdapter.getPhotoAt(position), position)
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
                    .replace(R.id.container_root, PhotoSlideFragment.newInstance(album), PhotoSlideFragment::class.java.canonicalName).addToBackStack(null)
                    .add(R.id.container_bottom_toolbar, BottomControlsFragment.newInstance(album), BottomControlsFragment::class.java.canonicalName)
                    .commit()
            },
            { photo, view, type -> imageLoaderModel.loadPhoto(photo, view, type) { startPostponedEnterTransition() } }
        ) { visible -> (activity as? AppCompatActivity)?.supportActionBar?.setDisplayShowTitleEnabled(visible) }

        removeOriginalBroadcastReceiver = RemoveOriginalBroadcastReceiver {
            if (it) {
                val photos = mutableListOf<Photo>()
                for (i in sharedSelection) {
                    mAdapter.getPhotoAt(i.toInt()).run { if (id != album.cover) photos.add(this) }
                }
                if (photos.isNotEmpty()) actionModel.deletePhotos(photos, album.name)
                sharedSelection.clear()
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        // View might not be destroy at all, reuse it here
        val vg = view ?: inflater.inflate(R.layout.fragment_albumdetail, container, false)

        stub = vg.findViewById(R.id.stub)
        dateIndicator = vg.findViewById(R.id.date_indicator)
        recyclerView = vg.findViewById<RecyclerView>(R.id.photogrid).apply {
            // Stop item from blinking when notifying changes
            (itemAnimator as SimpleItemAnimator).supportsChangeAnimations = false

            // Special span size to show cover at the top of the grid
            val defaultSpanCount = (layoutManager as GridLayoutManager).spanCount
            layoutManager = GridLayoutManager(context, defaultSpanCount).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int { return if (position == 0) defaultSpanCount else 1 }
                }
            }
        }

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

            // Scroll to designated photo at first run
            savedInstanceState ?: run {
                arguments?.getString(KEY_SCROLL_TO)?.apply {
                    if (this.isNotEmpty()) (recyclerView.layoutManager as GridLayoutManager).scrollToPosition(mAdapter.findPhotoPosition(this))
                }
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
                private val hideHandler = Handler(Looper.getMainLooper())
                private val hideDateIndicator = Runnable {
                    TransitionManager.beginDelayedTransition(recyclerView.parent as ViewGroup, Fade().apply { duration = 500 })
                    dateIndicator.visibility = View.GONE
                }

                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    super.onScrollStateChanged(recyclerView, newState)

                    // Hints the date (or 1st character of the name if sorting order is by name) of last photo shown in the list
                    ((layoutManager as GridLayoutManager)).run {
                        if ((findLastCompletelyVisibleItemPosition() < mAdapter.itemCount - 1) || (findFirstCompletelyVisibleItemPosition() > 0)) {
                            hideHandler.removeCallbacksAndMessages(null)
                            dateIndicator.apply {
                                text = if (album.sortOrder == Album.BY_NAME_ASC || album.sortOrder == Album.BY_NAME_DESC)
                                    mAdapter.getItemByPosition((layoutManager as GridLayoutManager).findLastVisibleItemPosition()).name.take(1)
                                else
                                    mAdapter.getItemByPosition((layoutManager as GridLayoutManager).findLastVisibleItemPosition()).dateTaken.format(DateTimeFormatter.ISO_LOCAL_DATE)
                                visibility = View.VISIBLE
                            }
                        }
                    }

                    when(newState) {
                        RecyclerView.SCROLL_STATE_IDLE-> {
                            with(currentPhotoModel) {
                                setCurrentPosition((layoutManager as GridLayoutManager).findFirstCompletelyVisibleItemPosition())
                                setFirstPosition((layoutManager as GridLayoutManager).findFirstCompletelyVisibleItemPosition())
                                setLastPosition((layoutManager as GridLayoutManager).findLastVisibleItemPosition())
                            }
                            isScrolling = false

                            // Hide the date indicator after showing it for 1 minute
                            if (dateIndicator.visibility == View.VISIBLE) hideHandler.postDelayed(hideDateIndicator, 1000)
                        }
                        else-> isScrolling = true
                    }
                }
            })
        }

        LocalBroadcastManager.getInstance(requireContext().applicationContext).registerReceiver(removeOriginalBroadcastReceiver, IntentFilter(AcquiringDialogFragment.BROADCAST_REMOVE_ORIGINAL))
    }

    override fun onResume() {
        super.onResume()

        (activity as? AppCompatActivity)?.supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLongArray(SELECTION, lastSelection.toLongArray())
        outState.putLongArray(SHARED_SELECTION, sharedSelection.toLongArray())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        recyclerView.clearOnScrollListeners()
        recyclerView.adapter = null

        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(removeOriginalBroadcastReceiver)
    }

    override fun onDestroy() {
        super.onDestroy()

        requireContext().apply {
            unregisterReceiver(snapseedCatcher)
            contentResolver.unregisterContentObserver(snapseedOutputObserver)
        }
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
                    it.setTargetFragment(this, DELETE_REQUEST_CODE)
                    it.show(parentFragmentManager, CONFIRM_DIALOG)
                }

                true
            }
            R.id.share -> {
                val uris = arrayListOf<Uri>()
                val appRootFolder = "${requireActivity().filesDir}${getString(R.string.lespas_base_folder_name)}"
                val cachePath = requireActivity().cacheDir
                val authority = getString(R.string.file_authority)

                sharedSelection.clear()
                for (i in selectionTracker.selection) {
                    sharedSelection.add(i.toLong())
                    with(mAdapter.getPhotoAt(i.toInt())) {
                        // Synced file is named after id, not yet synced file is named after file's name
                        File(appRootFolder, if (eTag.isNotEmpty()) id else name).copyTo(File(cachePath, name), true, 4096)
                        uris.add(FileProvider.getUriForFile(requireContext(), authority, File(cachePath, name)))
                    }
                }

                val clipData = ClipData.newUri(requireActivity().contentResolver, "", uris[0])
                for (i in 1 until uris.size)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) clipData.addItem(requireActivity().contentResolver, ClipData.Item(uris[i]))
                    else clipData.addItem(ClipData.Item(uris[i]))

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
                                putExtra(ShareReceiverActivity.KEY_SHOW_REMOVE_OPTION, true)
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
                                putExtra(ShareReceiverActivity.KEY_SHOW_REMOVE_OPTION, true)
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
            if (requestCode == DELETE_REQUEST_CODE) {
                val photos = mutableListOf<Photo>()
                for (i in selectionTracker.selection)
                    mAdapter.getPhotoAt(i.toInt()).run { if (id != album.cover) photos.add(this) }
                if (photos.isNotEmpty()) actionModel.deletePhotos(photos, album.name)
            }
        }
        selectionTracker.clearSelection()
    }

    override fun onRenameFinished(newName: String) {
        if (newName != album.name) {
            CoroutineScope(Dispatchers.Main).launch(Dispatchers.IO) {
                if (albumModel.isAlbumExisted(newName)) {
                    withContext(Dispatchers.Main) {
                        if (parentFragmentManager.findFragmentByTag(CONFIRM_DIALOG) == null) ConfirmDialogFragment.newInstance(getString(R.string.name_existed, newName), getString(android.R.string.ok)).let {
                            it.setTargetFragment(parentFragmentManager.findFragmentById(R.id.container_root), RENAME_REQUEST_CODE)
                            it.show(parentFragmentManager, CONFIRM_DIALOG)
                        }
                    }
                } else {
                    actionModel.renameAlbum(album.id, album.name, newName)
                }
            }
        }
    }

    // Adpater for photo grid
    class PhotoGridAdapter(private val itemClickListener: OnItemClick, private val imageLoader: OnLoadImage, private val titleUpdater: OnTitleVisibility) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var photos = mutableListOf<Photo>()
        private lateinit var selectionTracker: SelectionTracker<Long>
        private val selectedFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0.0f) })
        private var currentHolder = 0
        //private var oldSortOrder = Album.BY_DATE_TAKEN_ASC

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
                        imageLoader.loadImage(it, findViewById(R.id.cover), ImageLoaderViewModel.TYPE_COVER)
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
            //val oldPhotos = mutableListOf<Photo>().apply { addAll(0, photos) }
            photos.clear()
            album.album.run { photos.add(Photo(cover, id, name, "", startDate, endDate, coverWidth, coverHeight, "", coverBaseline)) }
            photos.addAll(1,
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
            notifyDataSetChanged()
            /*
            if (oldSortOrder != album.album.sortOrder) {
                // sort order changes will change nearly all the position, so no need to use DiffUtil
                notifyDataSetChanged()
                oldSortOrder = album.album.sortOrder
            } else {
                DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                    override fun getOldListSize() = oldPhotos.size
                    override fun getNewListSize() = photos.size
                    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                        if (oldItemPosition == 0 && newItemPosition == 0) true
                        //else ((oldPhotos[oldItemPosition].id == photos[newItemPosition].id) && (oldPhotos[oldItemPosition].name == photos[newItemPosition].name))
                        else oldPhotos[oldItemPosition].name == photos[newItemPosition].name

                    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                        if (oldItemPosition == 0 && newItemPosition == 0)
                            //oldPhotos[oldItemPosition].id == photos[newItemPosition].id &&              // cover's photo id, already checked in areItemsTheSame?
                            oldPhotos[oldItemPosition].shareId == photos[newItemPosition].shareId &&    // cover baseline
                            oldPhotos.size == photos.size                                               // photo added or deleted, photo count and duration affected
                        else oldPhotos[oldItemPosition] == photos[newItemPosition]
                }).dispatchUpdatesTo(this)
            }

             */
        }

        internal fun findPhotoPosition(photoId: String): Int {
            for ((i, photo) in photos.withIndex()) {
                if (photo.id == photoId) return i
            }
            return 0
        }

        internal fun findPhotoPosition(photo: Photo): Int = photos.indexOf(photo)

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

        fun getItemByPosition(position: Int): Photo = photos[position]

        fun refreshPhoto(sharedPhoto: Photo) {
            notifyItemChanged(photos.indexOfLast { it.id == sharedPhoto.id })
            if (sharedPhoto.id == photos[0].id) notifyItemChanged(0)
        }

        override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
            super.onViewAttachedToWindow(holder)
            if (holder is CoverViewHolder) {
                currentHolder = System.identityHashCode(holder)
                titleUpdater.setTitle(false)
            }
        }

        override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
            super.onViewDetachedFromWindow(holder)
            if (holder is CoverViewHolder)
                if (System.identityHashCode(holder) == currentHolder) titleUpdater.setTitle(true)
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
        private const val RENAME_DIALOG = "RENAME_DIALOG"
        private const val CONFIRM_DIALOG = "CONFIRM_DIALOG"
        private const val SELECTION = "SELECTION"
        private const val SHARED_SELECTION = "SHARED_SELECTION"

        private const val DELETE_REQUEST_CODE = 0
        private const val RENAME_REQUEST_CODE = 1

        const val CHOOSER_SPY_ACTION = "site.leos.apps.lespas.CHOOSER_ALBUMDETAIL"

        const val KEY_ALBUM = "ALBUM"
        const val KEY_SCROLL_TO = "KEY_SCROLL_TO"   // SearchResultFragment use this for scrolling to designed photo

        @JvmStatic
        fun newInstance(album: Album, photoId: String) = AlbumDetailFragment().apply {
            arguments = Bundle().apply{
                putParcelable(KEY_ALBUM, album)
                putString(KEY_SCROLL_TO, photoId)
            }
        }
    }
}