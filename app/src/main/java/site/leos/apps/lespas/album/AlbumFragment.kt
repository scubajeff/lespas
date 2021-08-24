package site.leos.apps.lespas.album

import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.AnimatedVectorDrawable
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.doOnPreDraw
import androidx.core.widget.ContentLoadingProgressBar
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.asLiveData
import androidx.preference.PreferenceManager
import androidx.recyclerview.selection.*
import androidx.recyclerview.widget.*
import androidx.work.*
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.transition.MaterialContainerTransform
import com.google.android.material.transition.MaterialElevationScale
import kotlinx.coroutines.*
import site.leos.apps.lespas.R
import site.leos.apps.lespas.cameraroll.CameraRollFragment
import site.leos.apps.lespas.helper.ConfirmDialogFragment
import site.leos.apps.lespas.helper.ImageLoaderViewModel
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.publication.NCShareViewModel
import site.leos.apps.lespas.publication.PublicationListFragment
import site.leos.apps.lespas.search.SearchFragment
import site.leos.apps.lespas.settings.SettingsFragment
import site.leos.apps.lespas.sync.AcquiringDialogFragment
import site.leos.apps.lespas.sync.ActionViewModel
import site.leos.apps.lespas.sync.DestinationDialogFragment
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class AlbumFragment : Fragment(), ActionMode.Callback {
    private var actionMode: ActionMode? = null
    private lateinit var mAdapter: AlbumListAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var fab: FloatingActionButton

    private lateinit var selectionTracker: SelectionTracker<Long>
    private lateinit var lastSelection: MutableSet<Long>
    private val uris = arrayListOf<Uri>()

    private val publishViewModel: NCShareViewModel by activityViewModels()
    private val albumsModel: AlbumViewModel by activityViewModels()
    private val actionModel: ActionViewModel by activityViewModels()
    private val destinationModel: DestinationDialogFragment.DestinationViewModel by activityViewModels()
    private val imageLoaderModel: ImageLoaderViewModel by activityViewModels()

    private var receivedShareMenu: MenuItem? = null
    private var cameraRollAsAlbumMenu: MenuItem? = null
    private var newTimestamp: Long = System.currentTimeMillis() / 1000

    private lateinit var addFileLauncher: ActivityResultLauncher<String>

    private var showCameraRoll = true
    private val showCameraRollPreferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
        if (key == getString(R.string.cameraroll_as_album_perf_key)) sharedPreferences.getBoolean(key, false).apply {
            showCameraRoll = this
            cameraRollAsAlbumMenu?.isEnabled = !this
            cameraRollAsAlbumMenu?.isVisible = !this

            // Selection based on bindingAdapterPosition, which will be changed
            selectionTracker.clearSelection()
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lastSelection = savedInstanceState?.getLongArray(SELECTION)?.toMutableSet() ?: mutableSetOf()

        setHasOptionsMenu(true)

        addFileLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) {
            if (it.isNotEmpty()) {
                uris.clear()
                uris.addAll(it)
                if (parentFragmentManager.findFragmentByTag(TAG_DESTINATION_DIALOG) == null)
                    DestinationDialogFragment.newInstance(uris,false).show(parentFragmentManager, TAG_DESTINATION_DIALOG)
            }
        }
        destinationModel.getDestination().observe (this, { album->
            // Acquire files
            album?.apply {
                if (parentFragmentManager.findFragmentByTag(TAG_ACQUIRING_DIALOG) == null)
                    AcquiringDialogFragment.newInstance(uris, album, destinationModel.shouldRemoveOriginal()).show(parentFragmentManager, TAG_ACQUIRING_DIALOG)
            }
        })

        mAdapter = AlbumListAdapter(
            { album, imageView ->
                if (album.id != FAKE_ALBUM_ID) {
                    exitTransition = MaterialElevationScale(false).apply { duration = resources.getInteger(android.R.integer.config_shortAnimTime).toLong() }
                    //reenterTransition = MaterialElevationScale(true).apply { duration = resources.getInteger(android.R.integer.config_shortAnimTime).toLong() }
                    parentFragmentManager.beginTransaction()
                        .setReorderingAllowed(true)
                        .addSharedElement(imageView, ViewCompat.getTransitionName(imageView)!!)
                        .replace(R.id.container_root, AlbumDetailFragment.newInstance(album, ""), AlbumDetailFragment::class.java.canonicalName).addToBackStack(null).commit()
                } else {
                    // Camera roll album's cover mime type is passed in property eTag
                    if (album.eTag.startsWith("video")) {
                        // Don't do transition for video cover
                        parentFragmentManager.beginTransaction().replace(R.id.container_root, CameraRollFragment(), CameraRollFragment::class.java.canonicalName).addToBackStack(null).commit()
                    }
                    else {
                        exitTransition = MaterialContainerTransform().apply {
                            duration = resources.getInteger(android.R.integer.config_shortAnimTime).toLong()
                            scrimColor = Color.TRANSPARENT
                        }
                        reenterTransition = MaterialElevationScale(true).apply { duration = resources.getInteger(android.R.integer.config_shortAnimTime).toLong() }

                        parentFragmentManager.beginTransaction()
                            .setReorderingAllowed(true)
                            .addSharedElement(imageView, ViewCompat.getTransitionName(imageView)!!)
                            .replace(R.id.container_root, CameraRollFragment(), CameraRollFragment::class.java.canonicalName).addToBackStack(null).commit()
                    }
                }
            },
            { user, view -> publishViewModel.getAvatar(user, view, null) }
        ) { photo, imageView, type -> imageLoaderModel.loadPhoto(photo, imageView, type) }.apply {
            stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        }

        showCameraRoll = PreferenceManager.getDefaultSharedPreferences(requireContext()).getBoolean(getString(R.string.cameraroll_as_album_perf_key), true)
        PreferenceManager.getDefaultSharedPreferences(requireContext()).registerOnSharedPreferenceChangeListener(showCameraRollPreferenceListener)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_album, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.albumlist)
        fab = view.findViewById(R.id.fab)

        postponeEnterTransition()
        view.doOnPreDraw { startPostponedEnterTransition() }

        albumsModel.allAlbumsByEndDate.observe(viewLifecycleOwner, { albums->
            val albumWithCameraRoll = albums.toMutableList()
            if (showCameraRoll) Tools.getCameraRollAlbum(requireContext().contentResolver, requireContext().getString(R.string.item_camera_roll))?.let { albumWithCameraRoll.add(0, it) }
            mAdapter.setAlbums(albumWithCameraRoll)
        })

        publishViewModel.shareByMe.asLiveData().observe(viewLifecycleOwner, { mAdapter.setRecipients(it) })
        publishViewModel.shareWithMe.asLiveData().observe(viewLifecycleOwner, { fixMenuIcon(it) })

        mAdapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            init {
                toggleEmptyView()
            }

            private fun toggleEmptyView() {
                if (mAdapter.itemCount == 0) {
                    recyclerView.visibility = View.GONE
                    view.findViewById<ImageView>(R.id.emptyview).visibility = View.VISIBLE
                } else {
                    recyclerView.visibility = View.VISIBLE
                    view.findViewById<ImageView>(R.id.emptyview).visibility = View.GONE
                }
            }

            override fun onChanged() {
                super.onChanged()
                toggleEmptyView()
            }

            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                super.onItemRangeInserted(positionStart, itemCount)
                toggleEmptyView()
            }

            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
                super.onItemRangeRemoved(positionStart, itemCount)
                toggleEmptyView()
            }
        })

        with(recyclerView) {
            // Stop item from blinking when notifying changes
            (itemAnimator as SimpleItemAnimator).supportsChangeAnimations = false

            adapter = mAdapter

            selectionTracker = SelectionTracker.Builder(
                "albumSelection",
                this,
                AlbumListAdapter.AlbumKeyProvider(mAdapter),
                AlbumListAdapter.AlbumDetailsLookup(this),
                StorageStrategy.createLongStorage()
            ).withSelectionPredicate(object : SelectionTracker.SelectionPredicate<Long>() {
                override fun canSetStateForKey(key: Long, nextState: Boolean): Boolean = key != FAKE_ALBUM_ID_LONG
                override fun canSetStateAtPosition(position: Int, nextState: Boolean): Boolean = mAdapter.getItemId(position) != FAKE_ALBUM_ID_LONG
                override fun canSelectMultiple(): Boolean = true
            }).build().apply {
                addObserver(object : SelectionTracker.SelectionObserver<Long>() {
                    override fun onSelectionChanged() {
                        super.onSelectionChanged()

                        if (selectionTracker.hasSelection() && actionMode == null) {
                            actionMode = (activity as? AppCompatActivity)?.startSupportActionMode(this@AlbumFragment)
                            actionMode?.let { it.title = getString(R.string.selected_count, selectionTracker.selection.size())}
                        } else if (!selectionTracker.hasSelection() && actionMode != null) {
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
        }


        fab.setOnClickListener { addFileLauncher.launch("*/*") }

        // Delete album confirm dialog result handler
        parentFragmentManager.setFragmentResultListener(ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY, viewLifecycleOwner) { key, bundle ->
            if (key == ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY) {
                if (bundle.getBoolean(ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY, false)) {
                    val albums = mutableListOf<Album>()
                    // Selection key is Album.id
                    for (i in selectionTracker.selection) albums.add(mAdapter.getItemBySelectionKey(i))
                    actionModel.deleteAlbums(albums)
                }
                selectionTracker.clearSelection()
            }
        }
    }

    override fun onResume() {
        super.onResume()

        (activity as? AppCompatActivity)?.supportActionBar?.run {
            setDisplayHomeAsUpEnabled(false)
            setDisplayShowTitleEnabled(true)
            title = getString(R.string.app_name)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLongArray(SELECTION, lastSelection.toLongArray())
    }

    override fun onDestroyView() {
        recyclerView.clearOnScrollListeners()
        recyclerView.adapter = null

        super.onDestroyView()
    }

    override fun onDestroy() {
        PreferenceManager.getDefaultSharedPreferences(requireContext()).unregisterOnSharedPreferenceChangeListener(showCameraRollPreferenceListener)
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.album_menu, menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)

        receivedShareMenu = menu.findItem(R.id.option_menu_received_shares)
        publishViewModel.shareWithMe.value.let { fixMenuIcon(it) }

        cameraRollAsAlbumMenu = menu.findItem(R.id.option_menu_camera_roll)
        cameraRollAsAlbumMenu?.isEnabled = !showCameraRoll
        cameraRollAsAlbumMenu?.isVisible = !showCameraRoll
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when(item.itemId) {
            R.id.option_menu_camera_roll-> {
                exitTransition = null
                reenterTransition = null
                parentFragmentManager.beginTransaction().replace(R.id.container_root, CameraRollFragment(), CameraRollFragment::class.java.canonicalName).addToBackStack(null).commit()
                return true
            }
            R.id.option_menu_settings-> {
                exitTransition = null
                reenterTransition = null
                parentFragmentManager.beginTransaction().replace(R.id.container_root, SettingsFragment(), SettingsFragment::class.java.canonicalName).addToBackStack(null).commit()
                return true
            }
            R.id.option_menu_search-> {
                exitTransition = null
                reenterTransition = null
                parentFragmentManager.beginTransaction().replace(R.id.container_root, SearchFragment.newInstance(mAdapter.itemCount == 0 ), SearchFragment::class.java.canonicalName).addToBackStack(null).commit()
                return true
            }
            R.id.option_menu_received_shares-> {
                receivedShareMenu?.icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_baseline_shared_with_me_24)
                PreferenceManager.getDefaultSharedPreferences(requireContext()).edit().putLong(KEY_RECEIVED_SHARE_TIMESTAMP, newTimestamp).apply()

                exitTransition = null
                reenterTransition = null
                parentFragmentManager.beginTransaction().replace(R.id.container_root, PublicationListFragment(), PublicationListFragment::class.java.canonicalName).addToBackStack(null).commit()
                return true
            }
            else-> {
                return false
            }
        }
    }

    override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
        mode?.menuInflater?.inflate(R.menu.actions_mode, menu)
        fab.isEnabled = false

        return true
    }

    override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
        menu?.let {
            it.removeItem(R.id.share)
            it.removeItem(R.id.select_all)
        }
        return true
    }

    override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
        return when(item?.itemId) {
            R.id.remove -> {
                if (parentFragmentManager.findFragmentByTag(CONFIRM_DIALOG) == null) ConfirmDialogFragment.newInstance(getString(R.string.confirm_delete), getString(R.string.yes_delete)).show(parentFragmentManager, "CONFIRM_DIALOG")
                true
            }
            R.id.share -> {
                selectionTracker.selection.forEach { _ -> }
                selectionTracker.clearSelection()
                true
            }
            else -> false
        }
    }

    override fun onDestroyActionMode(mode: ActionMode?) {
        selectionTracker.clearSelection()
        actionMode = null
        fab.isEnabled = true
    }

    private fun fixMenuIcon(shareList: List<NCShareViewModel.ShareWithMe>) {
        if (shareList.isNotEmpty()) {
            receivedShareMenu?.isEnabled = true

            // Show notification badge
            newTimestamp = shareList[0].lastModified
            if (PreferenceManager.getDefaultSharedPreferences(requireContext()).getLong(KEY_RECEIVED_SHARE_TIMESTAMP, 0L) < newTimestamp)
                with(ContextCompat.getDrawable(requireContext(), R.drawable.ic_baseline_new_share_coming_24) as AnimatedVectorDrawable) {
                    receivedShareMenu?.icon = this
                    this.start()
                }
        }
    }

    // List adapter for Albums' recyclerView
    class AlbumListAdapter(private val clickListener: (Album, ImageView) -> Unit, private val avatarLoader: (NCShareViewModel.Sharee, View) -> Unit, private val imageLoader: (Photo, ImageView, String) -> Unit
    ): ListAdapter<Album, AlbumListAdapter.AlbumViewHolder>(AlbumDiffCallback()) {
        private var covers = mutableListOf<Photo>()
        private var recipients = emptyList<NCShareViewModel.ShareByMe>()
        private lateinit var selectionTracker: SelectionTracker<Long>
        //private val selectedFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0.0f) })

        inner class AlbumViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
            fun bindViewItems(album: Album, isActivated: Boolean) {
                itemView.apply {
                    this.isActivated = isActivated
                    findViewById<ImageView>(R.id.coverart).let {coverImageview ->
                        //imageLoader(covers[bindingAdapterPosition], coverImageview, ImageLoaderViewModel.TYPE_COVER)
                        covers.find { it.id == album.cover }?.let { imageLoader(it, coverImageview, ImageLoaderViewModel.TYPE_COVER) }
                        /*
                        if (this.isActivated) coverImageview.colorFilter = selectedFilter
                        else coverImageview.clearColorFilter()
                         */
                        ViewCompat.setTransitionName(coverImageview, album.id)
                        setOnClickListener { if (!selectionTracker.hasSelection()) clickListener(album, coverImageview) }
                        if (album.syncProgress < 1.0f) {
                            coverImageview.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(album.syncProgress) })
                            with(findViewById<ContentLoadingProgressBar>(R.id.sync_progress)) {
                                visibility = View.VISIBLE
                                progress = (album.syncProgress * 100).toInt()
                            }
                        } else {
                            coverImageview.clearColorFilter()
                            findViewById<ContentLoadingProgressBar>(R.id.sync_progress).visibility = View.GONE
                        }
                    }
                    with(findViewById<TextView>(R.id.title)) {
                        text = album.name

                        val size = context.resources.getDimension(R.dimen.big_padding).toInt()
                        compoundDrawablePadding = 16
                        TextViewCompat.setCompoundDrawableTintList(this, ColorStateList.valueOf(currentTextColor))
                        setCompoundDrawables(if (album.id == FAKE_ALBUM_ID) ContextCompat.getDrawable(context, R.drawable.ic_baseline_camera_roll_24)?.apply { setBounds(0, 0, size, size) } else null, null, null, null)
                    }
                    findViewById<TextView>(R.id.duration).text = String.format(
                        "%s  -  %s",
                        album.startDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
                        album.endDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
                    )

                    findViewById<LinearLayoutCompat>(R.id.recipients).also { chipGroup->
                        chipGroup.removeAllViews()
                        recipients.find { it.fileId == album.id }?.let {
                            val ctx = chipGroup.context
                            for (recipient in it.with) chipGroup.addView((LayoutInflater.from(ctx).inflate(R.layout.textview_sharee, null) as TextView).also {
                                recipient.sharee.run {
                                    if (type == NCShareViewModel.SHARE_TYPE_GROUP) {
                                        it.text = label
                                        it.compoundDrawablePadding = ctx.resources.getDimension(R.dimen.mini_padding).toInt()
                                    }
                                    avatarLoader(this, it)
                                }
                            })
                        }
                    }
                }
            }

            fun getItemDetails() = object : ItemDetailsLookup.ItemDetails<Long>() {
                override fun getPosition(): Int = bindingAdapterPosition
                override fun getSelectionKey(): Long = getItemId(bindingAdapterPosition)
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlbumListAdapter.AlbumViewHolder  =
            AlbumViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_album, parent,false))

        override fun onBindViewHolder(holder: AlbumListAdapter.AlbumViewHolder, position: Int) {
            holder.bindViewItems(currentList[position], selectionTracker.isSelected(getItemId(position)))
        }

        internal fun setAlbums(albums: MutableList<Album>) {
            this.covers.apply {
                clear()
                albums.forEach { album ->
                    if (album.id == ImageLoaderViewModel.FROM_CAMERA_ROLL) {
                        album.id = FAKE_ALBUM_ID
                        // Pass cover orientation in property eTag
                        this.add(Photo(album.cover, ImageLoaderViewModel.FROM_CAMERA_ROLL, album.name, album.shareId.toString(), LocalDateTime.now(), LocalDateTime.now(), album.coverWidth, album.coverHeight, "", album.coverBaseline))
                    }
                    else this.add(Photo(album.cover, album.id, album.name, "", LocalDateTime.now(), LocalDateTime.now(), album.coverWidth, album.coverHeight, "", album.coverBaseline))
                }
            }
            submitList(albums)
        }

        internal fun setRecipients(recipients: List<NCShareViewModel.ShareByMe>) {
            this.recipients = recipients
            for (recipient in recipients) { notifyItemChanged(currentList.indexOfFirst { it.id == recipient.fileId }) }
        }

        internal fun getItemBySelectionKey(key: Long): Album = (currentList.find { it.id.toLong() == key })!!
        override fun getItemId(position: Int): Long = currentList[position].id.toLong()
        fun getPosition(key: Long): Int = currentList.indexOfFirst { it.id.toLong() == key}
        fun setSelectionTracker(selectionTracker: SelectionTracker<Long>) { this.selectionTracker = selectionTracker }
        class AlbumKeyProvider(private val adapter: AlbumListAdapter): ItemKeyProvider<Long>(SCOPE_CACHED) {
            override fun getKey(position: Int): Long = adapter.getItemId(position)
            override fun getPosition(key: Long): Int = adapter.getPosition(key)
        }
        class AlbumDetailsLookup(private val recyclerView: RecyclerView) : ItemDetailsLookup<Long>() {
            override fun getItemDetails(e: MotionEvent): ItemDetails<Long>? {
                recyclerView.findChildViewUnder(e.x, e.y)?.let {
                    return (recyclerView.getChildViewHolder(it) as AlbumViewHolder).getItemDetails()
                }
                return null
            }
        }
    }

    class AlbumDiffCallback: DiffUtil.ItemCallback<Album>() {
        override fun areItemsTheSame(oldItem: Album, newItem: Album): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Album, newItem: Album): Boolean = oldItem.cover == newItem.cover && oldItem.name == newItem.name && oldItem.coverBaseline == newItem.coverBaseline && oldItem.startDate == newItem.startDate && oldItem.endDate == newItem.endDate && oldItem.syncProgress == newItem.syncProgress
    }

    companion object {
        const val TAG_ACQUIRING_DIALOG = "ALBUMFRAGMENT_TAG_ACQUIRING_DIALOG"
        const val TAG_DESTINATION_DIALOG = "ALBUMFRAGMENT_TAG_DESTINATION_DIALOG"
        private const val CONFIRM_DIALOG = "CONFIRM_DIALOG"
        private const val SELECTION = "SELECTION"
        private const val FAKE_ALBUM_ID = "0"
        private const val FAKE_ALBUM_ID_LONG = 0L

        private const val KEY_RECEIVED_SHARE_TIMESTAMP = "KEY_RECEIVED_SHARE_TIMESTAMP"

        @JvmStatic
        fun newInstance() = AlbumFragment()
    }
}