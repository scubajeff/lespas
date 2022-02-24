package site.leos.apps.lespas.album

import android.content.*
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.database.ContentObserver
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.TypedValue
import android.view.*
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.core.app.SharedElementCallback
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import androidx.exifinterface.media.ExifInterface
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.asLiveData
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import androidx.recyclerview.selection.ItemDetailsLookup
import androidx.recyclerview.selection.ItemKeyProvider
import androidx.recyclerview.selection.SelectionTracker
import androidx.recyclerview.selection.SelectionTracker.Builder
import androidx.recyclerview.selection.StorageStrategy
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.Fade
import androidx.transition.TransitionManager
import androidx.work.*
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.transition.MaterialContainerTransform
import com.google.android.material.transition.MaterialElevationScale
import com.google.android.material.transition.MaterialSharedAxis
import kotlinx.coroutines.*
import site.leos.apps.lespas.MainActivity
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.*
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.photo.PhotoSlideFragment
import site.leos.apps.lespas.photo.PhotoWithCoordinate
import site.leos.apps.lespas.publication.NCShareViewModel
import site.leos.apps.lespas.search.PhotosInMapFragment
import site.leos.apps.lespas.settings.SettingsFragment
import site.leos.apps.lespas.sync.AcquiringDialogFragment
import site.leos.apps.lespas.sync.ActionViewModel
import site.leos.apps.lespas.sync.DestinationDialogFragment
import site.leos.apps.lespas.sync.ShareReceiverActivity
import java.io.File
import java.lang.Runnable
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*

class AlbumDetailFragment : Fragment(), ActionMode.Callback {
    private lateinit var album: Album
    private var scrollTo = ""

    private var actionMode: ActionMode? = null

    private lateinit var dateIndicator: MaterialButton
    private lateinit var recyclerView: RecyclerView
    private lateinit var mAdapter: PhotoGridAdapter

    private lateinit var selectionTracker: SelectionTracker<String>
    private lateinit var sharedSelection: MutableSet<String>
    private lateinit var lastSelection: MutableSet<String>

    private val albumModel: AlbumViewModel by activityViewModels()
    private val actionModel: ActionViewModel by activityViewModels()
    private val imageLoaderModel: ImageLoaderViewModel by activityViewModels()
    private val remoteImageLoaderModel: NCShareViewModel by activityViewModels()
    private val currentPhotoModel: PhotoSlideFragment.CurrentPhotoViewModel by activityViewModels()
    private val destinationViewModel: DestinationDialogFragment.DestinationViewModel by activityViewModels()

    private lateinit var sharedPhoto: Photo
    private lateinit var snapseedCatcher: BroadcastReceiver
    private lateinit var snapseedOutputObserver: ContentObserver
    private lateinit var removeOriginalBroadcastReceiver: RemoveOriginalBroadcastReceiver

    private val publishModel: NCShareViewModel by activityViewModels()
    private lateinit var sharedByMe: NCShareViewModel.ShareByMe

    private var sortOrderChanged = false

    private lateinit var addFileLauncher: ActivityResultLauncher<String>

    private var stripExif = "2"

    private var isSnapseedEnabled = false
    private var snapseedEditAction: MenuItem? = null

    private var reuseUris = arrayListOf<Uri>()

    private var mapOptionMenu: MenuItem? = null
    private var photosWithCoordinate = mutableListOf<PhotoWithCoordinate>()
    private var getCoordinateJob: Job? = null

    private lateinit var lespasPath: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setHasOptionsMenu(true)

        album = arguments?.getParcelable(KEY_ALBUM)!!
        sharedByMe = NCShareViewModel.ShareByMe(album.id, album.name, arrayListOf())
        lespasPath = getString(R.string.lespas_base_folder_name)

        // Must be restore here
        lastSelection = mutableSetOf()
        sharedSelection = mutableSetOf()
        savedInstanceState?.let {
            lastSelection = it.getStringArray(SELECTION)?.toMutableSet() ?: mutableSetOf()
            sharedSelection = it.getStringArray(SHARED_SELECTION)?.toMutableSet() ?: mutableSetOf()
            sortOrderChanged = it.getBoolean(SORT_ORDER_CHANGED)
        } ?: run { arguments?.getString(KEY_SCROLL_TO)?.apply { scrollTo = this }}

        mAdapter = PhotoGridAdapter(
            { view, position ->
                currentPhotoModel.run {
                    setCurrentPosition(position)
                    setLastPosition(position)
                }

                ViewCompat.setTransitionName(recyclerView, null)
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
                    .replace(R.id.container_root, PhotoSlideFragment.newInstance(album), PhotoSlideFragment::class.java.canonicalName)
                    .addToBackStack(null)
                    .commit()
            },
            //{ photo, view, type -> imageLoaderModel.loadPhoto(photo, view, type) { startPostponedEnterTransition() } }
            { photo, view, type ->
                if (Tools.isRemoteAlbum(album) && photo.eTag != Album.ETAG_NOT_YET_UPLOADED) remoteImageLoaderModel.getPhoto(NCShareViewModel.RemotePhoto(photo.id, "$lespasPath/${album.name}/${photo.name}", photo.mimeType, photo.width, photo.height, photo.shareId, 0L), view, type) { startPostponedEnterTransition() }
                else imageLoaderModel.loadPhoto(photo, view, type) { startPostponedEnterTransition() }
            }
        ).apply { stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY }

        sharedElementEnterTransition = MaterialContainerTransform().apply {
            duration = resources.getInteger(android.R.integer.config_shortAnimTime).toLong()
            scrimColor = Color.TRANSPARENT
        }

        // Adjusting the shared element mapping
        setExitSharedElementCallback(object : SharedElementCallback() {
            override fun onMapSharedElements(names: MutableList<String>?, sharedElements: MutableMap<String, View>?) {
                if (names?.isNotEmpty() == true) recyclerView.findViewHolderForAdapterPosition(currentPhotoModel.getCurrentPosition())?.let {
                   sharedElements?.put(names[0], it.itemView.findViewById(R.id.photo))
                }
            }
        })

        // Broadcast receiver listening on share destination
        snapseedCatcher = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent!!.getParcelableExtra<ComponentName>(Intent.EXTRA_CHOSEN_COMPONENT)?.packageName!!.substringAfterLast('.') == "snapseed") {
                    // Register content observer if integration with snapseed setting is on
                    if (PreferenceManager.getDefaultSharedPreferences(context).getBoolean(getString(R.string.snapseed_pref_key), false)) {
                        context!!.contentResolver.apply {
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
        requireContext().registerReceiver(snapseedCatcher, IntentFilter(CHOOSER_SPY_ACTION))

        // Content observer looking for Snapseed output
        snapseedOutputObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            private val workerName = "${AlbumDetailFragment::class.java.canonicalName}.SNAPSEED_WORKER"
            private var lastId = ""
            private lateinit var snapseedWork: OneTimeWorkRequest

            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)

                // ContentObserver got called twice, once for itself, once for it's descendant, all with same last path segment
                if (uri?.lastPathSegment!! != lastId) {
                    lastId = uri.lastPathSegment!!

                    snapseedWork = OneTimeWorkRequestBuilder<SnapseedResultWorker>().setInputData(
                        workDataOf(SnapseedResultWorker.KEY_IMAGE_URI to uri.toString(), SnapseedResultWorker.KEY_SHARED_PHOTO to sharedPhoto.id, SnapseedResultWorker.KEY_ALBUM to album.id)).build()
                    with(WorkManager.getInstance(requireContext())) {
                        enqueueUniqueWork(workerName, ExistingWorkPolicy.KEEP, snapseedWork)

                        getWorkInfosForUniqueWorkLiveData(workerName).observe(parentFragmentManager.findFragmentById(R.id.container_root)!!) { workInfo ->
                            if (workInfo != null) {
                                // If replace original is on, remove old bitmaps from cache and take care of cover too
                                if (PreferenceManager.getDefaultSharedPreferences(requireContext()).getBoolean(requireContext().getString(R.string.snapseed_replace_pref_key), false)) {
                                    imageLoaderModel.invalid(sharedPhoto.id)
                                    // Update cover if needed, cover id can be found only in adapter
                                    mAdapter.updateCover(sharedPhoto)
                                }
                            }
                        }
                    }

                    requireContext().contentResolver.unregisterContentObserver(this)
                }
            }
        }

        removeOriginalBroadcastReceiver = RemoveOriginalBroadcastReceiver {
            if (it) {
                val photos = mutableListOf<Photo>()
                for (photoId in sharedSelection) mAdapter.getPhotoBy(photoId).run { if (id != album.cover) photos.add(this) }
                if (photos.isNotEmpty()) actionModel.deletePhotos(photos, album)
            }
            sharedSelection.clear()
        }

        addFileLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            if (uris.isNotEmpty()) {
                parentFragmentManager.findFragmentByTag(TAG_ACQUIRING_DIALOG) ?: run {
                    AcquiringDialogFragment.newInstance(uris as ArrayList<Uri>, album,false).show(parentFragmentManager, TAG_ACQUIRING_DIALOG)
                }
            }
        }

        requireActivity().onBackPressedDispatcher.addCallback(this, object: OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Cancel EXIF stripping job if it's running
                shareOutJob?.let {
                    if (it.isActive) {
                        it.cancel(cause = null)
                        return
                    }
                }

                if (parentFragmentManager.backStackEntryCount == 0) requireActivity().finish()
                else parentFragmentManager.popBackStack()
            }
        })
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? = inflater.inflate(R.layout.fragment_albumdetail, container, false)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        dateIndicator = view.findViewById(R.id.date_indicator)
        recyclerView = view.findViewById(R.id.photogrid)

        postponeEnterTransition()
        ViewCompat.setTransitionName(recyclerView, album.id)
        recyclerView.doOnLayout { startPostponedEnterTransition() }

        with(recyclerView) {
            // Special span size to show cover at the top of the grid
            val defaultSpanCount = (layoutManager as GridLayoutManager).spanCount
            layoutManager = GridLayoutManager(context, defaultSpanCount).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int { return if (position == 0) defaultSpanCount else 1 }
                }
            }

            adapter = mAdapter

            selectionTracker = Builder(
                "photoSelection",
                this,
                PhotoGridAdapter.PhotoKeyProvider(mAdapter),
                PhotoGridAdapter.PhotoDetailsLookup(this),
                StorageStrategy.createStringStorage()
            ).withSelectionPredicate(object : SelectionTracker.SelectionPredicate<String>() {
                override fun canSetStateForKey(key: String, nextState: Boolean): Boolean = key.isNotEmpty()
                override fun canSetStateAtPosition(position: Int, nextState: Boolean): Boolean = position > 0
                override fun canSelectMultiple(): Boolean = true
            }).build().apply {
                addObserver(object : SelectionTracker.SelectionObserver<String>() {
                    override fun onSelectionChanged() {
                        super.onSelectionChanged()

                        val selectionSize = selectionTracker.selection.size()

                        snapseedEditAction?.isVisible = selectionSize == 1 && isSnapseedEnabled && !Tools.isMediaPlayable(mAdapter.getPhotoBy(selectionTracker.selection.first()).mimeType)

                        if (selectionTracker.hasSelection() && actionMode == null) {
                            actionMode = (activity as? AppCompatActivity)?.startSupportActionMode(this@AlbumDetailFragment)
                            actionMode?.let { it.title = getString(R.string.selected_count, selectionSize) }
                        } else if (!(selectionTracker.hasSelection()) && actionMode != null) {
                            actionMode?.finish()
                            actionMode = null
                        } else actionMode?.title = getString(R.string.selected_count, selectionSize)
                    }

                    override fun onItemStateChanged(key: String, selected: Boolean) {
                        super.onItemStateChanged(key, selected)
                        if (selected) lastSelection.add(key)
                        else lastSelection.remove(key)
                    }
                })
            }
            mAdapter.setSelectionTracker(selectionTracker)

            // Get scroll position after scroll idle
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                private val hideHandler = Handler(Looper.getMainLooper())
                private val hideDateIndicator = Runnable {
                    TransitionManager.beginDelayedTransition(recyclerView.parent as ViewGroup, Fade().apply { duration = 800 })
                    dateIndicator.visibility = View.GONE
                }
                private val titleBar = (activity as? AppCompatActivity)?.supportActionBar
                // Title text use TextAppearance.MaterialComponents.Headline5 style, which has textSize of 24sp
                private val titleTextSizeInPixel = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 24f, requireContext().resources.displayMetrics).toInt()
                private val lm = recyclerView.layoutManager as GridLayoutManager

                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    super.onScrollStateChanged(recyclerView, newState)

                    when(newState) {
                        RecyclerView.SCROLL_STATE_IDLE-> {
                            // Hide the date indicator after showing it for 1 minute
                            if (dateIndicator.visibility == View.VISIBLE) hideHandler.postDelayed(hideDateIndicator, 1000)
                            if (lm.findFirstVisibleItemPosition() > 0) titleBar?.setDisplayShowTitleEnabled(true)
                        }
                    }
                }

                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)

                    if (dx == 0 && dy == 0) {
                        // First entry or fragment resume false call, by layout re calculation, hide dataIndicator and title
                        dateIndicator.isVisible = false
                        showTitleText()
                    } else {
                        lm.run {
                            // Hints the date (or 1st character of the name if sorting order is by name) of last photo shown in the list
                            if ((findLastCompletelyVisibleItemPosition() < mAdapter.itemCount - 1) || (findFirstCompletelyVisibleItemPosition() > 0)) {
                                hideHandler.removeCallbacksAndMessages(null)
                                dateIndicator.let {
                                    it.text = if (album.sortOrder == Album.BY_NAME_ASC || album.sortOrder == Album.BY_NAME_DESC) mAdapter.getPhotoAt(findLastVisibleItemPosition()).name.take(1)
                                    else mAdapter.getPhotoAt(findLastVisibleItemPosition()).dateTaken.format(DateTimeFormatter.ISO_LOCAL_DATE)

                                    it.isVisible = true
                                }
                            }

                            showTitleText()
                        }
                    }
                }

                private fun showTitleText() {
                    // Show/hide title text in titleBar base on visibility of cover view's title
                    if (lm.findFirstVisibleItemPosition() == 0) {
                        val rect = Rect()
                        (recyclerView.findViewHolderForAdapterPosition(0) as PhotoGridAdapter.CoverViewHolder).itemView.findViewById<TextView>(R.id.title).getGlobalVisibleRect(rect)

                        if (rect.bottom <= 0) titleBar?.setDisplayShowTitleEnabled(true)
                        else if (rect.bottom - rect.top > titleTextSizeInPixel) titleBar?.setDisplayShowTitleEnabled(false)
                    }
                }
            })
        }

        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(removeOriginalBroadcastReceiver, IntentFilter(AcquiringDialogFragment.BROADCAST_REMOVE_ORIGINAL))

        albumModel.getAlbumDetail(album.id).observe(viewLifecycleOwner) {
            // Cover might changed, photo might be deleted, so get updates from latest here
            this.album = it.album

            mAdapter.setAlbum(it)
            (activity as? AppCompatActivity)?.supportActionBar?.title = it.album.name

            // Scroll to reveal the new position, e.g. the position where PhotoSliderFragment left
            if (currentPhotoModel.getCurrentPosition() != currentPhotoModel.getLastPosition()) {
                (recyclerView.layoutManager as GridLayoutManager).scrollToPosition(currentPhotoModel.getCurrentPosition())
                currentPhotoModel.setLastPosition(currentPhotoModel.getCurrentPosition())
            }

            // Scroll to designated photo at first run
            if (scrollTo.isNotEmpty()) {
                (recyclerView.layoutManager as GridLayoutManager).scrollToPosition(with(mAdapter.getPhotoPosition(scrollTo)) { if (this >= 0) this else 0 })
                scrollTo = ""
            }

            // Restore selection state
            if (lastSelection.isNotEmpty()) lastSelection.forEach { selected -> selectionTracker.select(selected) }

            // Search for location in photos, enable 'show in map' option menu
            getCoordinateJob?.cancel()
            if (!Tools.isRemoteAlbum(album)) {
                // TODO enable after db migration
                getCoordinateJob = lifecycleScope.launch(Dispatchers.IO) {
                    val baseFolder = Tools.getLocalRoot(requireContext())
                    var coordinate: DoubleArray
                    var hit = false

                    photosWithCoordinate.clear()
                    mAdapter.getPhotos().forEach { photo ->
                        coordinate = doubleArrayOf(0.0, 0.0)
                        if (Tools.hasExif(photo.mimeType)) try {
                            ExifInterface("$baseFolder/${if (File(baseFolder, photo.id).exists()) photo.id else photo.name}")
                        } catch (e: Exception) {
                            null
                        }?.latLong?.apply {
                            hit = true
                            coordinate = this
                        }
                        photosWithCoordinate.add(PhotoWithCoordinate(photo, coordinate[0], coordinate[1]))
                    }

                    withContext(Dispatchers.Main) {
                        mapOptionMenu?.apply {
                            isEnabled = hit
                            isVisible = hit
                        }
                    }
                }
            }
        }

        publishModel.shareByMe.asLiveData().observe(viewLifecycleOwner) { shares ->
            sharedByMe = shares.find { it.fileId == album.id } ?: NCShareViewModel.ShareByMe(album.id, album.name, arrayListOf())
            mAdapter.setRecipient(sharedByMe)
        }

        destinationViewModel.getDestination().observe(viewLifecycleOwner) { album ->
            // Acquire files
            album?.apply {
                if (parentFragmentManager.findFragmentByTag(TAG_ACQUIRING_DIALOG) == null) AcquiringDialogFragment.newInstance(reuseUris, album, destinationViewModel.shouldRemoveOriginal()).show(parentFragmentManager, TAG_ACQUIRING_DIALOG)
            }
        }

        // Rename result handler
        parentFragmentManager.setFragmentResultListener(AlbumRenameDialogFragment.RESULT_KEY_NEW_NAME, viewLifecycleOwner) { key, bundle->
            if (key == AlbumRenameDialogFragment.RESULT_KEY_NEW_NAME) {
                bundle.getString(AlbumRenameDialogFragment.RESULT_KEY_NEW_NAME)?.let { newName->
                    with(sharedByMe.with.isNotEmpty()) {
                        actionModel.renameAlbum(album.id, album.name, newName, this)

                        // Nextcloud server won't propagate folder name changes to shares for a reason, see https://github.com/nextcloud/server/issues/2063
                        // In our case, I think it's a better UX to do it because name is a key aspect of album, so...
                        // TODO What if sharedByMe is not available when working offline
                        if (this) publishModel.renameShare(sharedByMe, newName)
                    }

                    // Set title to new name
                    (activity as? AppCompatActivity)?.supportActionBar?.title = newName
                    album.name = newName
                }
            }
        }

        // Confirm dialog result handler
        parentFragmentManager.setFragmentResultListener(ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY, viewLifecycleOwner) { key, bundle ->
            if (key == ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY) {
                when(bundle.getString(ConfirmDialogFragment.INDIVIDUAL_REQUEST_KEY)) {
                    DELETE_REQUEST_KEY-> {
                        if (bundle.getBoolean(ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY, false)) {
                            val photos = mutableListOf<Photo>()
                            for (photoId in selectionTracker.selection) mAdapter.getPhotoBy(photoId).run { if (id != album.cover) photos.add(this) }
                            if (photos.isNotEmpty()) actionModel.deletePhotos(photos, album)
                        }
                        selectionTracker.clearSelection()
                    }
                    STRIP_REQUEST_KEY-> shareOut(bundle.getBoolean(ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY, true))
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()

        (activity as? AppCompatActivity)?.supportActionBar?.setDisplayHomeAsUpEnabled(true)
        PreferenceManager.getDefaultSharedPreferences(requireContext()).apply {
            stripExif = getString(getString(R.string.strip_exif_pref_key), getString(R.string.strip_ask_value))!!
            isSnapseedEnabled = getBoolean(getString(R.string.snapseed_pref_key), false)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArray(SELECTION, lastSelection.toTypedArray())
        outState.putStringArray(SHARED_SELECTION, sharedSelection.toTypedArray())
        outState.putBoolean(SORT_ORDER_CHANGED, sortOrderChanged)
    }

    override fun onDestroyView() {
        recyclerView.clearOnScrollListeners()
        recyclerView.adapter = null

        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(removeOriginalBroadcastReceiver)

        super.onDestroyView()
    }

    override fun onDestroy() {
        // Time to update album meta file if sort order changed in this session, if cover is not uploaded yet, meta will be maintained in SyncAdapter when cover fileId is available
        if (sortOrderChanged && !album.cover.contains('.')) actionModel.updateAlbumMeta(album)

        requireContext().apply {
            unregisterReceiver(snapseedCatcher)
            contentResolver.unregisterContentObserver(snapseedOutputObserver)
        }

        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.album_detail_menu, menu)
        mapOptionMenu = menu.findItem(R.id.option_menu_in_map)
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

        // Disable publish function when this is a newly created album which does not exist on server yet
        if (album.eTag == Album.ETAG_NOT_YET_UPLOADED) menu.findItem(R.id.option_menu_publish).isEnabled = false

        super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when(item.itemId) {
            R.id.option_menu_add_photo-> {
                addFileLauncher.launch("*/*")
                true
            }
            R.id.option_menu_rename-> {
                lifecycleScope.launch(Dispatchers.IO) {
                    albumModel.getAllAlbumName().also {
                        val names = mutableListOf<String>()
                        // albumModel.getAllAlbumName return all album names including hidden ones, in case of name collision when user change name to an hidden one and later hide this album, existing
                        // name check should include hidden ones
                        it.forEach { name -> names.add(if (name.startsWith('.')) name.substring(1) else name) }
                        if (parentFragmentManager.findFragmentByTag(RENAME_DIALOG) == null) AlbumRenameDialogFragment.newInstance(album.name, names).show(parentFragmentManager, RENAME_DIALOG)
                    }
                }
                true
            }
            R.id.option_menu_settings-> {
                exitTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
                reenterTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
                parentFragmentManager.beginTransaction().replace(R.id.container_root, SettingsFragment()).addToBackStack(null).commit()
                true
            }
            R.id.option_menu_sortbydateasc-> {
                updateSortOrder(Album.BY_DATE_TAKEN_ASC)
                true
            }
            R.id.option_menu_sortbydatedesc-> {
                updateSortOrder(Album.BY_DATE_TAKEN_DESC)
                true
            }
            R.id.option_menu_sortbynameasc-> {
                updateSortOrder(Album.BY_NAME_ASC)
                true
            }
            R.id.option_menu_sortbynamedesc-> {
                updateSortOrder(Album.BY_NAME_DESC)
                true
            }
            R.id.option_menu_publish-> {
                // Check for album meta file existence, create it if needed
                if (!File(Tools.getLocalRoot(requireContext()), "${album.id}.json").exists()) WorkManager.getInstance(requireContext()).enqueueUniqueWork(MainActivity.MetaFileMaintenanceWorker.WORKER_NAME, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<MainActivity.MetaFileMaintenanceWorker>().build())

                // Get meaningful label for each recipient
                publishModel.sharees.value.let { sharees->
                    sharedByMe.with.forEach { recipient-> sharees.find { it.name == recipient.sharee.name && it.type == recipient.sharee.type}?.let { recipient.sharee.label = it.label }}
                }

                if (parentFragmentManager.findFragmentByTag(PUBLISH_DIALOG) == null) AlbumPublishDialogFragment.newInstance(sharedByMe).show(parentFragmentManager, PUBLISH_DIALOG)

                true
            }
            R.id.option_menu_in_map-> {
                reenterTransition = MaterialSharedAxis(MaterialSharedAxis.Z, false).apply { duration = resources.getInteger(android.R.integer.config_longAnimTime).toLong() }
                exitTransition = MaterialSharedAxis(MaterialSharedAxis.Z, true).apply { duration = resources.getInteger(android.R.integer.config_longAnimTime).toLong() }
                ViewCompat.setTransitionName(recyclerView, null)
                parentFragmentManager.beginTransaction().replace(R.id.container_root, PhotosInMapFragment.newInstance(album, photosWithCoordinate), PhotosInMapFragment::class.java.canonicalName).addToBackStack(null).commit()
                true
            }
            R.id.option_menu_bgm-> {
                if (parentFragmentManager.findFragmentByTag(BGM_DIALOG) == null) BGMDialogFragment.newInstance(album).show(parentFragmentManager, BGM_DIALOG)
                true
            }
            else-> false
        }
    }

    // On special Actions of this fragment
    override fun onCreateActionMode(mode: ActionMode?, menu: Menu): Boolean {
        mode?.menuInflater?.inflate(R.menu.album_detail_actions_mode, menu)

        snapseedEditAction = menu.findItem(R.id.snapseed_edit)

        // Disable snapseed edit action menu if Snapseed is not installed, update snapseed action menu icon too
        with(PreferenceManager.getDefaultSharedPreferences(context)) {
            isSnapseedEnabled = getBoolean(getString(R.string.snapseed_pref_key), false)
            snapseedEditAction?.isVisible = isSnapseedEnabled
            if (isSnapseedEnabled) snapseedEditAction?.icon = ContextCompat.getDrawable(requireContext(), if (getBoolean(getString(R.string.snapseed_replace_pref_key), false)) R.drawable.ic_baseline_snapseed_24 else R.drawable.ic_baseline_snapseed_add_24)
        }

        return true
    }

    override fun onPrepareActionMode(mode: ActionMode?, menu: Menu): Boolean = false

    override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
        return when(item?.itemId) {
            R.id.remove -> {
                if (parentFragmentManager.findFragmentByTag(CONFIRM_DIALOG) == null) ConfirmDialogFragment.newInstance(getString(R.string.confirm_delete), getString(R.string.yes_delete), true, DELETE_REQUEST_KEY).show(parentFragmentManager, CONFIRM_DIALOG)
                true
            }
            R.id.share -> {
                if (stripExif == getString(R.string.strip_ask_value)) {
                    if (hasExifInSelection()) {
                        if (parentFragmentManager.findFragmentByTag(CONFIRM_DIALOG) == null) YesNoDialogFragment.newInstance(getString(R.string.strip_exif_msg, getString(R.string.strip_exif_title)), STRIP_REQUEST_KEY).show(parentFragmentManager, CONFIRM_DIALOG)
                    } else shareOut(false)
                }
                else shareOut(stripExif == getString(R.string.strip_on_value))

                true
            }
            R.id.select_all -> {
                for (i in 1 until mAdapter.itemCount) selectionTracker.select(mAdapter.getPhotoId(i))
                true
            }
            R.id.snapseed_edit-> {
                shareOut(false, SHARE_TO_SNAPSEED)
                true
            }
            R.id.lespas_reuse-> {
                shareOut(false, SHARE_TO_LESPAS)
                true
            }
            else -> false
        }
    }

    override fun onDestroyActionMode(mode: ActionMode?) {
        selectionTracker.clearSelection()
        actionMode = null
    }

    private fun updateSortOrder(newOrder: Int) {
        albumModel.setSortOrder(album.id, newOrder)
        sortOrderChanged = true
    }

    private fun hasExifInSelection(): Boolean {
        for (photoId in selectionTracker.selection) {
            if (Tools.hasExif(mAdapter.getPhotoBy(photoId).mimeType)) return true
        }

        return false
    }

    private fun prepareShares(strip: Boolean, job: Job?): ArrayList<Uri> {
        val uris = arrayListOf<Uri>()
        var sourceFile: File
        var destFile: File
        val isRemote = Tools.isRemoteAlbum(album)
        val serverPath = "${getString(R.string.lespas_base_folder_name)}/${album.name}"

        sharedSelection.clear()
        for (photoId in selectionTracker.selection) sharedSelection.add(photoId)

        for (photoId in sharedSelection) {
            // Quit asap when job cancelled
            job?.let { if (it.isCancelled) return arrayListOf() }

            if (mAdapter.getPhotoBy(photoId).let { photo ->
                // Synced file is named after id, not yet synced file is named after file's name
                destFile = File(requireActivity().cacheDir, if (strip) "${UUID.randomUUID()}.${photo.name.substringAfterLast('.')}" else photo.name)

                if (isRemote && photo.eTag != Photo.ETAG_NOT_YET_UPLOADED) {
                    remoteImageLoaderModel.downloadFile("${serverPath}/${photo.name}", destFile, strip && Tools.hasExif(photo.mimeType))
                } else {
                    //sourceFile = File(Tools.getLocalRoot(requireContext()), if (eTag != Photo.ETAG_NOT_YET_UPLOADED) id else name)
                    sourceFile = File(Tools.getLocalRoot(requireContext()), photo.id)
                    // This TEMP_CACHE_FOLDER is created by MainActivity

                    // Copy the file from fileDir/id to cacheDir/name, strip EXIF base on setting
                    if (strip && Tools.hasExif(photo.mimeType)) BitmapFactory.decodeFile(sourceFile.canonicalPath)?.compress(Bitmap.CompressFormat.JPEG, 95, destFile.outputStream())
                    else sourceFile.copyTo(destFile, true, 4096)
                    true
                }
            }) uris.add(FileProvider.getUriForFile(requireContext(), getString(R.string.file_authority), destFile))
        }

        return uris
    }

    private var shareOutJob: Job? = null
    private fun shareOut(strip: Boolean, shareType: Int = GENERAL_SHARE) {
        val handler = Handler(Looper.getMainLooper())
        val waitingMsg = Tools.getPreparingSharesSnackBar(recyclerView, strip) { shareOutJob?.cancel(cause = null) }

        shareOutJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Temporarily prevent screen rotation
                requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED

                //sharedPhoto = mAdapter.getPhotoAt(selectionTracker.selection.first().toInt())
                sharedPhoto = mAdapter.getPhotoBy(selectionTracker.selection.first())

                // Show a SnackBar if it takes too long (more than 500ms) preparing shares
                withContext(Dispatchers.Main) {
                    handler.removeCallbacksAndMessages(null)
                    handler.postDelayed({ waitingMsg.show() }, 500)
                }

                val uris = prepareShares(strip, shareOutJob!!)

                withContext(Dispatchers.Main) {
                    if (uris.isNotEmpty()) {
                        when (shareType) {
                            GENERAL_SHARE -> {
                                // Call system share chooser
                                val clipData = ClipData.newUri(requireActivity().contentResolver, "", uris[0])
                                for (i in 1 until uris.size) {
                                    if (isActive) {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) clipData.addItem(requireActivity().contentResolver, ClipData.Item(uris[i]))
                                        else clipData.addItem(ClipData.Item(uris[i]))
                                    }
                                }

                                // Dismiss Snackbar before showing system share chooser, avoid unpleasant screen flicker
                                if (waitingMsg.isShownOrQueued) waitingMsg.dismiss()

                                if (isActive) startActivity(Intent.createChooser(Intent().apply {
                                    if (uris.size == 1) {
                                        // If sharing only one picture, use ACTION_SEND instead, so that other apps which won't accept ACTION_SEND_MULTIPLE will work
                                        action = Intent.ACTION_SEND
                                        putExtra(Intent.EXTRA_STREAM, uris[0])
                                    } else {
                                        action = Intent.ACTION_SEND_MULTIPLE
                                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                                    }
                                    //type = sharedPhoto.mimeType
                                    type = if (sharedPhoto.mimeType.startsWith("image")) "image/*" else sharedPhoto.mimeType
                                    this.clipData = clipData
                                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    putExtra(ShareReceiverActivity.KEY_SHOW_REMOVE_OPTION, true)
                                }, null))
                            }
                            SHARE_TO_SNAPSEED -> {
                                startActivity(Intent().apply {
                                    action = Intent.ACTION_SEND
                                    data = uris[0]
                                    putExtra(Intent.EXTRA_STREAM, uris[0])
                                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    setClassName(SettingsFragment.SNAPSEED_PACKAGE_NAME, SettingsFragment.SNAPSEED_MAIN_ACTIVITY_CLASS_NAME)
                                })

                                // Send broadcast just like system share does when user chooses Snapseed, so that we can catch editing result
                                requireContext().sendBroadcast(Intent().apply {
                                    action = CHOOSER_SPY_ACTION
                                    putExtra(Intent.EXTRA_CHOSEN_COMPONENT, ComponentName(SettingsFragment.SNAPSEED_PACKAGE_NAME, SettingsFragment.SNAPSEED_MAIN_ACTIVITY_CLASS_NAME))
                                })
                            }
                            SHARE_TO_LESPAS -> {
                                reuseUris = uris
                                if (parentFragmentManager.findFragmentByTag(TAG_DESTINATION_DIALOG) == null) DestinationDialogFragment.newInstance(reuseUris, true).show(parentFragmentManager, TAG_DESTINATION_DIALOG)
                            }
                        }
                    } else {
                        var msg = getString(R.string.msg_error_preparing_share_out_files)
                        if (Tools.isRemoteAlbum(album)) msg += " ${getString(R.string.msg_check_network)}"
                        Snackbar.make(recyclerView, msg, Snackbar.LENGTH_LONG).apply {
                            animationMode = Snackbar.ANIMATION_MODE_FADE
                            setBackgroundTint(ContextCompat.getColor(recyclerView.context, R.color.color_primary))
                            setTextColor(ContextCompat.getColor(recyclerView.context, R.color.color_text_light))
                        }.show()
                    }
                }
            } catch (e: CancellationException) {
                e.printStackTrace()
            } finally {
                withContext(NonCancellable) {
                    withContext(Dispatchers.Main) { selectionTracker.clearSelection() }
                }
            }
        }

        shareOutJob?.invokeOnCompletion {
            // Make sure we dismiss waiting SnackBar
            handler.removeCallbacksAndMessages(null)
            if (waitingMsg.isShownOrQueued) waitingMsg.dismiss()

            requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // Adapter for photo grid
    class PhotoGridAdapter(private val clickListener: (View, Int) -> Unit, private val imageLoader: (Photo, ImageView, String) -> Unit
    ) : ListAdapter<Photo, RecyclerView.ViewHolder>(PhotoDiffCallback()) {
        private lateinit var album: Album
        var photos = mutableListOf<Photo>()
        private lateinit var selectionTracker: SelectionTracker<String>
        private val selectedFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0.0f) })
        private var recipients = mutableListOf<NCShareViewModel.Recipient>()
        private var recipientText = ""

        inner class CoverViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val ivCover = itemView.findViewById<ImageView>(R.id.cover)
            private val tvTitle = itemView.findViewById<TextView>(R.id.title)
            private val tvDuration = itemView.findViewById<TextView>(R.id.duration)
            private val tvTotal = itemView.findViewById<TextView>(R.id.total)
            private val tvRecipients = itemView.findViewById<TextView>(R.id.recipients)
            private val titleDrawableSize = tvTitle.textSize.toInt()

            fun bindViewItem(cover: Photo) {
                with(itemView) {
                    imageLoader(cover.copy(id = album.cover), ivCover, ImageLoaderViewModel.TYPE_COVER)

                    tvTitle.apply {
                        text = album.name

                        setCompoundDrawables(
                            if (Tools.isRemoteAlbum(album)) ContextCompat.getDrawable(context, R.drawable.ic_baseline_wb_cloudy_24)?.apply { setBounds(0, 0, titleDrawableSize, titleDrawableSize) } else null,
                            null, null, null
                        )
                    }

                    val days = Duration.between(
                        album.startDate.atZone(ZoneId.systemDefault()).toInstant(),
                        album.endDate.atZone(ZoneId.systemDefault()).toInstant()
                    ).toDays().toInt()
                    tvDuration.text = when (days) {
                        in 0..21 -> resources.getString(R.string.duration_days, days + 1)
                        in 22..56 -> resources.getString(R.string.duration_weeks, days / 7)
                        in 57..365 -> resources.getString(R.string.duration_months, days / 30)
                        else -> resources.getString(R.string.duration_years, days / 365)
                    }

                    tvTotal.text = resources.getString(R.string.total_photo, currentList.size - 1)

                    if (recipients.size > 0) {
                        var names = recipients[0].sharee.label
                        for (i in 1 until recipients.size) names += ", ${recipients[i].sharee.label}"
                        tvRecipients.apply {
                            text = String.format(recipientText, names)
                            visibility = View.VISIBLE
                        }
                    } else tvRecipients.visibility = View.GONE
                }
            }
        }

        inner class PhotoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val ivPhoto = itemView.findViewById<ImageView>(R.id.photo)
            private val ivSelectionMark = itemView.findViewById<ImageView>(R.id.selection_mark)
            private val ivPlayMark = itemView.findViewById<ImageView>(R.id.play_mark)

            fun bindViewItem(photo: Photo, isActivated: Boolean) {
                itemView.let {
                    it.isActivated = isActivated

                    with(ivPhoto) {
                        imageLoader(photo, this, ImageLoaderViewModel.TYPE_GRID)

                        if (this.isActivated) {
                            colorFilter = selectedFilter
                            ivSelectionMark.visibility = View.VISIBLE
                        } else {
                            clearColorFilter()
                            ivSelectionMark.visibility = View.GONE
                        }

                        ViewCompat.setTransitionName(this, photo.id)

                        setOnClickListener { if (!selectionTracker.hasSelection()) clickListener(this, bindingAdapterPosition) }

                        ivPlayMark.visibility = if (Tools.isMediaPlayable(photo.mimeType) && !this.isActivated) View.VISIBLE else View.GONE
                    }
                }
            }

            fun getItemDetails() = object : ItemDetailsLookup.ItemDetails<String>() {
                override fun getPosition(): Int = bindingAdapterPosition
                override fun getSelectionKey(): String = getPhotoId(bindingAdapterPosition)
            }
        }

        override fun getItemViewType(position: Int): Int = if (position == 0) TYPE_COVER else TYPE_PHOTO

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            recipientText = parent.context.getString(R.string.published_to)
            return if (viewType == TYPE_COVER) {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_cover, parent, false)
                view.findViewById<TextView>(R.id.title)?.apply {
                    compoundDrawablePadding = 16
                    TextViewCompat.setCompoundDrawableTintList(this, ColorStateList.valueOf(currentTextColor))
                }
                CoverViewHolder(view)
            }
            else PhotoViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_photo, parent, false))

        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is PhotoViewHolder) holder.bindViewItem(currentList[position], selectionTracker.isSelected(currentList[position].id))
            else (holder as CoverViewHolder).bindViewItem(currentList.first())  // List will never be empty, no need to check for NoSuchElementException
        }

        internal fun setAlbum(album: AlbumWithPhotos) {
            this.album = album.album
            photos = mutableListOf()
            photos.addAll(
                when(album.album.sortOrder) {
                    Album.BY_DATE_TAKEN_ASC-> album.photos.sortedWith(compareBy { it.dateTaken })
                    Album.BY_DATE_TAKEN_DESC-> album.photos.sortedWith(compareByDescending { it.dateTaken })
                    Album.BY_DATE_MODIFIED_ASC-> album.photos.sortedWith(compareBy { it.lastModified })
                    Album.BY_DATE_MODIFIED_DESC-> album.photos.sortedWith(compareByDescending { it.lastModified })
                    Album.BY_NAME_ASC-> album.photos.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                    Album.BY_NAME_DESC-> album.photos.sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.name })
                    else-> album.photos
                }
            )
            // Add album cover at the top of photo list
            album.album.run { photos.add(0, album.photos.find { it.id == album.album.cover }!!.copy(id = album.album.id, shareId = album.album.coverBaseline)) }
            submitList(photos)
        }

        //internal fun getRecipient(): List<NCShareViewModel.Recipient> = recipients
        internal fun setRecipient(share: NCShareViewModel.ShareByMe) {
            this.recipients = share.with
            notifyItemChanged(0)
        }

        internal fun getPhotos(): List<Photo> = photos.drop(1)
        internal fun getPhotoAt(position: Int): Photo = currentList[position]
        internal fun getPhotoBy(photoId: String): Photo = currentList.last { it.id == photoId }
        internal fun updateCover(sharedPhoto: Photo) {
            //notifyItemChanged(currentList.indexOfLast { it.id == sharedPhoto.id })
            if (sharedPhoto.id == currentList[0].id) notifyItemChanged(0)
        }

        internal fun setSelectionTracker(selectionTracker: SelectionTracker<String>) { this.selectionTracker = selectionTracker }
        internal fun getPhotoId(position: Int): String = currentList[position].id
        internal fun getPhotoPosition(photoId: String): Int = currentList.indexOfLast { it.id == photoId }
        class PhotoKeyProvider(private val adapter: PhotoGridAdapter): ItemKeyProvider<String>(SCOPE_CACHED) {
            override fun getKey(position: Int): String = adapter.getPhotoId(position)
            override fun getPosition(key: String): Int = adapter.getPhotoPosition(key)
        }
        class PhotoDetailsLookup(private val recyclerView: RecyclerView): ItemDetailsLookup<String>() {
            override fun getItemDetails(e: MotionEvent): ItemDetails<String>? {
                recyclerView.findChildViewUnder(e.x, e.y)?.let {
                    val holder = recyclerView.getChildViewHolder(it)
                    return if (holder is PhotoViewHolder) holder.getItemDetails() else null
                }
                return null
            }
        }

        companion object {
            private const val TYPE_COVER = 0
            private const val TYPE_PHOTO = 1
        }
    }

    class PhotoDiffCallback: DiffUtil.ItemCallback<Photo>() {
        override fun areItemsTheSame(oldItem: Photo, newItem: Photo): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Photo, newItem: Photo): Boolean = oldItem.lastModified == newItem.lastModified && oldItem.name == newItem.name && oldItem.shareId == newItem.shareId && oldItem.eTag == newItem.eTag
    }

    companion object {
        private const val RENAME_DIALOG = "RENAME_DIALOG"
        private const val PUBLISH_DIALOG = "PUBLISH_DIALOG"
        private const val CONFIRM_DIALOG = "CONFIRM_DIALOG"
        private const val BGM_DIALOG = "BGM_DIALOG"
        private const val SELECTION = "SELECTION"
        private const val SHARED_SELECTION = "SHARED_SELECTION"
        private const val SORT_ORDER_CHANGED = "SORT_ORDER_CHANGED"

        private const val DELETE_REQUEST_KEY = "ALBUMDETAIL_DELETE_REQUEST_KEY"
        private const val STRIP_REQUEST_KEY = "ALBUMDETAIL_STRIP_REQUEST_KEY"

        private const val TAG_DESTINATION_DIALOG = "ALBUM_DETAIL_DESTINATION_DIALOG"
        private const val TAG_ACQUIRING_DIALOG = "ALBUM_DETAIL_ACQUIRING_DIALOG"

        private const val GENERAL_SHARE = 0
        private const val SHARE_TO_SNAPSEED = 1
        private const val SHARE_TO_LESPAS = 2

        const val CHOOSER_SPY_ACTION = "site.leos.apps.lespas.CHOOSER_ALBUMDETAIL"

        const val KEY_ALBUM = "ALBUM"
        const val KEY_SCROLL_TO = "KEY_SCROLL_TO"   // SearchResultFragment use this for scrolling to designed photo

        @JvmStatic
        fun newInstance(album: Album, photoId: String) = AlbumDetailFragment().apply {
            arguments = Bundle().apply {
                putParcelable(KEY_ALBUM, album)
                putString(KEY_SCROLL_TO, photoId)
            }
        }
    }
}