package site.leos.apps.lespas.album

import android.accounts.AccountManager
import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.AnimatedVectorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.*
import android.widget.CheckedTextView
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
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
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.transition.MaterialContainerTransform
import com.google.android.material.transition.MaterialElevationScale
import com.google.android.material.transition.MaterialSharedAxis
import kotlinx.coroutines.*
import site.leos.apps.lespas.R
import site.leos.apps.lespas.cameraroll.CameraRollFragment
import site.leos.apps.lespas.helper.ConfirmDialogFragment
import site.leos.apps.lespas.helper.ImageLoaderViewModel
import site.leos.apps.lespas.helper.LesPasDialogFragment
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.publication.NCShareViewModel
import site.leos.apps.lespas.publication.PublicationListFragment
import site.leos.apps.lespas.search.SearchFragment
import site.leos.apps.lespas.settings.SettingsFragment
import site.leos.apps.lespas.sync.AcquiringDialogFragment
import site.leos.apps.lespas.sync.ActionViewModel
import site.leos.apps.lespas.sync.DestinationDialogFragment
import site.leos.apps.lespas.sync.SyncAdapter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class AlbumFragment : Fragment(), ActionMode.Callback {
    private var actionMode: ActionMode? = null
    private lateinit var mAdapter: AlbumListAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var fab: FloatingActionButton

    private lateinit var selectionTracker: SelectionTracker<String>
    private lateinit var lastSelection: MutableSet<String>
    private val uris = arrayListOf<Uri>()

    private val publishViewModel: NCShareViewModel by activityViewModels()
    private val albumsModel: AlbumViewModel by activityViewModels()
    private val actionModel: ActionViewModel by activityViewModels()
    private val destinationModel: DestinationDialogFragment.DestinationViewModel by activityViewModels()
    private val imageLoaderModel: ImageLoaderViewModel by activityViewModels()

    private var currentSortOrder = Album.BY_DATE_TAKEN_DESC
    private var receivedShareMenu: MenuItem? = null
    private var cameraRollAsAlbumMenu: MenuItem? = null
    private var unhideMenu: MenuItem? = null
    private var newTimestamp: Long = System.currentTimeMillis() / 1000

    private lateinit var addFileLauncher: ActivityResultLauncher<String>

    private var showCameraRoll = true
    private var cameraRollAlbum: Album? = null
    private var mediaStoreVersion = ""
    private var mediaStoreGeneration = 0L
    private val showCameraRollPreferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
        if (key == getString(R.string.cameraroll_as_album_perf_key)) sharedPreferences.getBoolean(key, true).apply {
            showCameraRoll = this
            cameraRollAsAlbumMenu?.isEnabled = !this
            cameraRollAsAlbumMenu?.isVisible = !this

            // Selection based on bindingAdapterPosition, which will be changed
            selectionTracker.clearSelection()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lastSelection = savedInstanceState?.getStringArray(KEY_SELECTION)?.toMutableSet() ?: mutableSetOf()
        currentSortOrder = savedInstanceState?.getInt(KEY_SORT_ORDER, Album.BY_DATE_TAKEN_DESC) ?: Album.BY_DATE_TAKEN_DESC

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
                if (album.id != ImageLoaderViewModel.FROM_CAMERA_ROLL) {
                    exitTransition = MaterialElevationScale(false).apply { duration = resources.getInteger(android.R.integer.config_shortAnimTime).toLong() }
                    reenterTransition = MaterialElevationScale(true).apply { duration = resources.getInteger(android.R.integer.config_shortAnimTime).toLong() }
                    parentFragmentManager.beginTransaction()
                        .setReorderingAllowed(true)
                        .addSharedElement(imageView, ViewCompat.getTransitionName(imageView)!!)
                        .replace(R.id.container_root, AlbumDetailFragment.newInstance(album, ""), AlbumDetailFragment::class.java.canonicalName).addToBackStack(null).commit()
                } else {
                    // Camera roll album's cover mime type is passed in property eTag
                    if (album.eTag.startsWith("video")) {
                        // Don't do transition for video cover
                        parentFragmentManager.beginTransaction().replace(R.id.container_root, CameraRollFragment.newInstance(), CameraRollFragment::class.java.canonicalName).addToBackStack(null).commit()
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
                            .replace(R.id.container_root, CameraRollFragment.newInstance(), CameraRollFragment::class.java.canonicalName).addToBackStack(null).commit()
                    }
                }
            },
            { user, view -> publishViewModel.getAvatar(user, view, null) }
        ) { photo, imageView, type -> imageLoaderModel.loadPhoto(photo, imageView, type) }.apply {
            stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        }

        requireContext().run {
            showCameraRoll = PreferenceManager.getDefaultSharedPreferences(this).getBoolean(getString(R.string.cameraroll_as_album_perf_key), true)
            // TODO only check first volume
            getCameraRoll(MediaStore.getVersion(this), if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) MediaStore.getGeneration(this, MediaStore.getExternalVolumeNames(this).first()) else 0L)
            PreferenceManager.getDefaultSharedPreferences(this).registerOnSharedPreferenceChangeListener(showCameraRollPreferenceListener)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View = inflater.inflate(R.layout.fragment_album, container, false)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.albumlist)
        fab = view.findViewById(R.id.fab)

        postponeEnterTransition()
        view.doOnPreDraw {
            startPostponedEnterTransition()
            if (savedInstanceState == null) {
                // TODO: seems like flooding the server
                publishViewModel.refresh()

                // Sync with server at startup
                ContentResolver.requestSync(AccountManager.get(requireContext()).getAccountsByType(getString(R.string.account_type_nc))[0], getString(R.string.sync_authority), Bundle().apply {
                    putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                    //putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
                    putInt(SyncAdapter.ACTION, SyncAdapter.SYNC_BOTH_WAY)
                })
            }
        }

        albumsModel.allAlbumsByEndDate.observe(viewLifecycleOwner, { albums-> sortAndSetAlbums(albums) })
        albumsModel.allHiddenAlbums.observe(viewLifecycleOwner, { hidden-> unhideMenu?.isEnabled = hidden.isNotEmpty() })

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
                StorageStrategy.createStringStorage()
            ).withSelectionPredicate(object : SelectionTracker.SelectionPredicate<String>() {
                override fun canSetStateForKey(key: String, nextState: Boolean): Boolean = key != ImageLoaderViewModel.FROM_CAMERA_ROLL
                override fun canSetStateAtPosition(position: Int, nextState: Boolean): Boolean = position > 0
                override fun canSelectMultiple(): Boolean = true
            }).build().apply {
                addObserver(object : SelectionTracker.SelectionObserver<String>() {
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

                    override fun onItemStateChanged(key: String, selected: Boolean) {
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
        parentFragmentManager.setFragmentResultListener(ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY, viewLifecycleOwner) { _, bundle ->
            if (bundle.getBoolean(ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY, false)) {
                val albums = mutableListOf<Album>()
                // Selection key is Album.id
                for (id in selectionTracker.selection) mAdapter.getItemBySelectionKey(id)?.let { albums.add(it) }
                actionModel.deleteAlbums(albums)
            }
            selectionTracker.clearSelection()
        }
        // Unhide dialog result handler
        parentFragmentManager.setFragmentResultListener(UnhideDialogFragment.UNHIDE_DIALOG_REQUEST_KEY, viewLifecycleOwner) { _, bundle ->
            bundle.getParcelableArrayList<Album>(UnhideDialogFragment.KEY_UNHIDE_THESE)?.apply {
                if (this.isNotEmpty()) actionModel.unhideAlbums(this)
            }
        }

        if (savedInstanceState == null) (requireActivity() as AppCompatActivity).reportFullyDrawn()
    }

    override fun onResume() {
        super.onResume()

        (activity as? AppCompatActivity)?.run {
            supportActionBar?.run {
                setDisplayHomeAsUpEnabled(false)
                setDisplayShowTitleEnabled(true)
                title = getString(R.string.app_name)
            }
            window.statusBarColor = ContextCompat.getColor(requireContext(), R.color.color_primary)
        }

        if (showCameraRoll) {
            requireContext().apply {
                val newVersion = MediaStore.getVersion(this)
                val newGeneration = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) MediaStore.getGeneration(this, MediaStore.getExternalVolumeNames(this).first()) else 0L
                if (newVersion != mediaStoreVersion) getCameraRoll(newVersion, newGeneration)?.apply { mAdapter.setCameraRollAlbum(this) }
                else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && newGeneration != mediaStoreGeneration) getCameraRoll(newVersion, newGeneration)?.apply { mAdapter.setCameraRollAlbum(this) }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArray(KEY_SELECTION, lastSelection.toTypedArray())
        outState.putInt(KEY_SORT_ORDER, currentSortOrder)
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
        receivedShareMenu = menu.findItem(R.id.option_menu_received_shares)
        cameraRollAsAlbumMenu = menu.findItem(R.id.option_menu_camera_roll)
        unhideMenu = menu.findItem(R.id.option_menu_unhide)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)

        albumsModel.allHiddenAlbums.value.let { unhideMenu?.isEnabled = it?.isNotEmpty() ?: false }
        publishViewModel.shareWithMe.value.let { fixMenuIcon(it) }

        cameraRollAsAlbumMenu?.isEnabled = !showCameraRoll
        cameraRollAsAlbumMenu?.isVisible = !showCameraRoll

        menu.findItem(R.id.option_menu_sortbydateasc).isChecked = false
        menu.findItem(R.id.option_menu_sortbydatedesc).isChecked = false
        menu.findItem(R.id.option_menu_sortbynameasc).isChecked = false
        menu.findItem(R.id.option_menu_sortbynamedesc).isChecked = false

        when(currentSortOrder) {
            Album.BY_DATE_TAKEN_ASC-> menu.findItem(R.id.option_menu_sortbydateasc).isChecked = true
            Album.BY_DATE_TAKEN_DESC-> menu.findItem(R.id.option_menu_sortbydatedesc).isChecked = true
            Album.BY_NAME_ASC-> menu.findItem(R.id.option_menu_sortbynameasc).isChecked = true
            Album.BY_NAME_DESC-> menu.findItem(R.id.option_menu_sortbynamedesc).isChecked = true
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when(item.itemId) {
            R.id.option_menu_camera_roll-> {
                exitTransition = null
                reenterTransition = null
                parentFragmentManager.beginTransaction().replace(R.id.container_root, CameraRollFragment.newInstance(), CameraRollFragment::class.java.canonicalName).addToBackStack(null).commit()
                return true
            }
            R.id.option_menu_settings-> {
                exitTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
                reenterTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
                parentFragmentManager.beginTransaction().replace(R.id.container_root, SettingsFragment(), SettingsFragment::class.java.canonicalName).addToBackStack(null).commit()
                return true
            }
            R.id.option_menu_search-> {
                exitTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
                reenterTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
                parentFragmentManager.beginTransaction().replace(R.id.container_root, SearchFragment.newInstance(mAdapter.itemCount == 0 || (mAdapter.itemCount == 1 && mAdapter.currentList[0].id == ImageLoaderViewModel.FROM_CAMERA_ROLL)), SearchFragment::class.java.canonicalName).addToBackStack(null).commit()
                return true
            }
            R.id.option_menu_received_shares-> {
                receivedShareMenu?.icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_baseline_shared_with_me_24)
                PreferenceManager.getDefaultSharedPreferences(requireContext()).edit().putLong(KEY_RECEIVED_SHARE_TIMESTAMP, newTimestamp).apply()

                exitTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
                reenterTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
                parentFragmentManager.beginTransaction().replace(R.id.container_root, PublicationListFragment(), PublicationListFragment::class.java.canonicalName).addToBackStack(null).commit()
                return true
            }
            R.id.option_menu_sortbydateasc, R.id.option_menu_sortbydatedesc, R.id.option_menu_sortbynameasc, R.id.option_menu_sortbynamedesc-> {
                changeSortOrder(item.itemId)
                return true
            }
            R.id.option_menu_unhide-> {
                if (BiometricManager.from(requireContext()).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS) {
                    BiometricPrompt(requireActivity(), ContextCompat.getMainExecutor(requireContext()), object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            super.onAuthenticationSucceeded(result)
                            unhide()
                        }
                    }).authenticate(BiometricPrompt.PromptInfo.Builder()
                        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                        .setConfirmationRequired(false)
                        .setTitle(getString(R.string.unlock_please))
                        .build()
                    )
                } else unhide()

                return true
            }
            else-> {
                return false
            }
        }
    }

    override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
        mode?.menuInflater?.inflate(R.menu.album_actions_mode, menu)
        fab.isEnabled = false

        return true
    }

    override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean = false

    override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
        return when(item?.itemId) {
            R.id.remove -> {
                if (parentFragmentManager.findFragmentByTag(CONFIRM_DIALOG) == null) ConfirmDialogFragment.newInstance(getString(R.string.confirm_delete), getString(R.string.yes_delete)).show(parentFragmentManager, CONFIRM_DIALOG)
                true
            }
            R.id.hide -> {
                mutableListOf<Album>().let { albums ->
                    selectionTracker.selection.forEach { id-> mAdapter.getItemBySelectionKey(id)?.let { albums.add(it) }}
                    selectionTracker.clearSelection()

                    actionModel.hideAlbums(albums)
                    publishViewModel.unPublish(albums)
                }

                true
            }
/*
            R.id.share -> {
                selectionTracker.selection.forEach { _ -> }
                selectionTracker.clearSelection()
                true
            }
*/
            else -> false
        }
    }

    override fun onDestroyActionMode(mode: ActionMode?) {
        selectionTracker.clearSelection()
        actionMode = null
        fab.isEnabled = true
    }

    private fun unhide() {
        albumsModel.allHiddenAlbums.value?.let { if (parentFragmentManager.findFragmentByTag(UNHIDE_DIALOG) == null) UnhideDialogFragment.newInstance(it).show(parentFragmentManager, UNHIDE_DIALOG) }
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

    private fun changeSortOrder(id: Int) {
        currentSortOrder = when(id) {
            R.id.option_menu_sortbydateasc-> Album.BY_DATE_TAKEN_ASC
            R.id.option_menu_sortbydatedesc-> Album.BY_DATE_TAKEN_DESC
            R.id.option_menu_sortbynameasc-> Album.BY_NAME_ASC
            R.id.option_menu_sortbynamedesc-> Album.BY_NAME_DESC
            else-> -1
        }

        sortAndSetAlbums(null)
    }

    private fun sortAndSetAlbums(original: List<Album>?) {
        val albums = mutableListOf<Album>()
        val sortedAlbums = mutableListOf<Album>()

        albums.addAll(original ?: mAdapter.currentList)
        if (showCameraRoll && albums.isNotEmpty() && albums[0].id == ImageLoaderViewModel.FROM_CAMERA_ROLL) albums.removeAt(0)
        sortedAlbums.addAll(when(currentSortOrder) {
            Album.BY_DATE_TAKEN_ASC-> albums.sortedWith(compareBy { it.endDate })
            Album.BY_DATE_TAKEN_DESC-> albums.sortedWith(compareByDescending { it.endDate })
            Album.BY_NAME_ASC-> albums.sortedWith(compareBy { it.name })
            Album.BY_NAME_DESC-> albums.sortedWith(compareByDescending { it.name })
            else-> albums
        })
        if (showCameraRoll) cameraRollAlbum?.let { sortedAlbums.add(0, it) }

        mAdapter.setAlbums(sortedAlbums)
    }

    private fun getCameraRoll(version: String, generation: Long): Album? {
        cameraRollAlbum = Tools.getCameraRollAlbum(requireContext().contentResolver, getString(R.string.item_camera_roll))
        mediaStoreVersion = version
        mediaStoreGeneration = generation

        return cameraRollAlbum
    }

    // List adapter for Albums' recyclerView
    class AlbumListAdapter(private val clickListener: (Album, ImageView) -> Unit, private val avatarLoader: (NCShareViewModel.Sharee, View) -> Unit, private val imageLoader: (Photo, ImageView, String) -> Unit
    ): ListAdapter<Album, AlbumListAdapter.AlbumViewHolder>(AlbumDiffCallback()) {
        private var covers = mutableListOf<Photo>()
        private var recipients = emptyList<NCShareViewModel.ShareByMe>()
        private lateinit var selectionTracker: SelectionTracker<String>
        //private val selectedFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0.0f) })

        inner class AlbumViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
            private val ivCover = itemView.findViewById<ImageView>(R.id.coverart)
            private val pbSync = itemView.findViewById<ContentLoadingProgressBar>(R.id.sync_progress)
            private val tvTitle = itemView.findViewById<TextView>(R.id.title)
            private val tvDuration = itemView.findViewById<TextView>(R.id.duration)
            private val llRecipients = itemView.findViewById<LinearLayoutCompat>(R.id.recipients)

            @SuppressLint("InflateParams")
            fun bindViewItems(album: Album, isActivated: Boolean) {
                itemView.apply {
                    this.isActivated = isActivated
                    ivCover.let {coverImageview ->
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
                            with(pbSync) {
                                visibility = View.VISIBLE
                                progress = (album.syncProgress * 100).toInt()
                            }
                        } else {
                            coverImageview.clearColorFilter()
                            pbSync.visibility = View.GONE
                        }
                    }
                    with(tvTitle) {
                        text = album.name

                        val size = context.resources.getDimension(R.dimen.big_padding).toInt()
                        compoundDrawablePadding = 16
                        TextViewCompat.setCompoundDrawableTintList(this, ColorStateList.valueOf(currentTextColor))
                        setCompoundDrawables(if (album.id == ImageLoaderViewModel.FROM_CAMERA_ROLL) ContextCompat.getDrawable(context, R.drawable.ic_baseline_camera_roll_24)?.apply { setBounds(0, 0, size, size) } else null, null, null, null)
                    }
                    tvDuration.text = String.format(
                        "%s  -  %s",
                        album.startDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
                        album.endDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
                    )

                    llRecipients.also { chipGroup->
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

            fun getItemDetails() = object : ItemDetailsLookup.ItemDetails<String>() {
                override fun getPosition(): Int = bindingAdapterPosition
                override fun getSelectionKey(): String = getAlbumId(bindingAdapterPosition)
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlbumListAdapter.AlbumViewHolder  =
            AlbumViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_album, parent,false))

        override fun onBindViewHolder(holder: AlbumListAdapter.AlbumViewHolder, position: Int) {
            holder.bindViewItems(currentList[position], selectionTracker.isSelected(getAlbumId(position)))
        }

        internal fun setAlbums(albums: MutableList<Album>) {
            this.covers.apply {
                clear()
                albums.forEach { album ->
                    if (album.id == ImageLoaderViewModel.FROM_CAMERA_ROLL) {
                        // Pass cover orientation in property eTag
                        this.add(Photo(album.cover, ImageLoaderViewModel.FROM_CAMERA_ROLL, album.name, album.shareId.toString(), LocalDateTime.now(), LocalDateTime.now(), album.coverWidth, album.coverHeight, album.eTag, album.coverBaseline))
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

        internal fun setCameraRollAlbum(cameraRollAlbum: Album) {
            this.covers[0].apply {
                id = cameraRollAlbum.cover
                mimeType = cameraRollAlbum.eTag
                eTag = cameraRollAlbum.shareId.toString()   // cover rotation
                width = cameraRollAlbum.coverWidth
                height = cameraRollAlbum.coverHeight
                shareId = cameraRollAlbum.coverBaseline
            }

            notifyItemChanged(0)
        }

        internal fun getItemBySelectionKey(key: String): Album? = currentList.find { it.id == key }
        private fun getAlbumId(position: Int): String = currentList[position].id
        private fun getPosition(key: String): Int = currentList.indexOfFirst { it.id == key}
        internal fun setSelectionTracker(selectionTracker: SelectionTracker<String>) { this.selectionTracker = selectionTracker }
        class AlbumKeyProvider(private val adapter: AlbumListAdapter): ItemKeyProvider<String>(SCOPE_CACHED) {
            override fun getKey(position: Int): String = adapter.getAlbumId(position)
            override fun getPosition(key: String): Int = adapter.getPosition(key)
        }
        class AlbumDetailsLookup(private val recyclerView: RecyclerView) : ItemDetailsLookup<String>() {
            override fun getItemDetails(e: MotionEvent): ItemDetails<String>? {
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

    class UnhideDialogFragment: LesPasDialogFragment(R.layout.fragment_unhide_dialog) {
        private lateinit var unhideButton: MaterialButton
        private val choices = arrayListOf<Album>()
        private val hiddenAdapter = NameAdapter { album, checked ->
            if (checked) choices.add(album)
            else choices.remove(album)

            unhideButton.isEnabled = choices.isNotEmpty()
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            requireArguments().getParcelableArrayList<Album>(KEY_ALBUMS)?.apply { hiddenAdapter.submitList(this.toMutableList()) }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            view.findViewById<RecyclerView>(R.id.hidden_albums).adapter = hiddenAdapter
            unhideButton = view.findViewById<MaterialButton>(R.id.unhide_button).apply {
                setOnClickListener {
                    parentFragmentManager.setFragmentResult(UNHIDE_DIALOG_REQUEST_KEY, Bundle().apply {
                        putParcelableArrayList(KEY_UNHIDE_THESE, choices)
                    })
                    dismiss()
                }
            }

            view.findViewById<MaterialButton>(R.id.cancel_button).apply {
                setOnClickListener { dismiss() }
            }
        }

        class NameAdapter(private val updateChoice: (Album, Boolean) -> Unit): ListAdapter<Album, NameAdapter.ViewHolder>(NameDiffCallback()) {
            inner class ViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
                private val tvName = itemView.findViewById<CheckedTextView>(android.R.id.text1)

                fun bind(album: Album) {
                    tvName.text = album.name.substring(1)

                    tvName.setOnClickListener {
                        tvName.isChecked = !tvName.isChecked
                        updateChoice(album, tvName.isChecked)
                    }
                }
            }
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_multiple_choice, parent, false))
            override fun onBindViewHolder(holder: ViewHolder, position: Int) { holder.bind(currentList[position]) }
        }

        class NameDiffCallback: DiffUtil.ItemCallback<Album>() {
            override fun areItemsTheSame(oldItem: Album, newItem: Album): Boolean = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: Album, newItem: Album): Boolean = oldItem.eTag == newItem.eTag
        }

        companion object {
            const val UNHIDE_DIALOG_REQUEST_KEY = "UNHIDE_DIALOG_REQUEST_KEY"
            const val KEY_UNHIDE_THESE = "KEY_UNHIDE_THESE"

            private const val KEY_ALBUMS = "KEY_ALBUMS"

            @JvmStatic
            fun newInstance(albums: List<Album>) = UnhideDialogFragment().apply { arguments = Bundle().apply { putParcelableArrayList(KEY_ALBUMS, ArrayList(albums)) }}
        }
    }

    companion object {
        const val TAG_ACQUIRING_DIALOG = "ALBUMFRAGMENT_TAG_ACQUIRING_DIALOG"
        const val TAG_DESTINATION_DIALOG = "ALBUMFRAGMENT_TAG_DESTINATION_DIALOG"
        private const val CONFIRM_DIALOG = "CONFIRM_DIALOG"
        private const val UNHIDE_DIALOG = "UNHIDE_DIALOG"
        private const val KEY_SELECTION = "KEY_SELECTION"
        private const val KEY_SORT_ORDER = "KEY_SORT_ORDER"

        private const val KEY_RECEIVED_SHARE_TIMESTAMP = "KEY_RECEIVED_SHARE_TIMESTAMP"

        @JvmStatic
        fun newInstance() = AlbumFragment()
    }
}