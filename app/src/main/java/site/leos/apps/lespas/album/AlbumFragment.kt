/*
 *   Copyright 2019 Jeffrey Liu (scubajeffrey@protonmail.ch)
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package site.leos.apps.lespas.album

import android.accounts.AccountManager
import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.*
import android.widget.CheckedTextView
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.MenuProvider
import androidx.core.view.ViewCompat
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.core.widget.ContentLoadingProgressBar
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.asLiveData
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.selection.ItemDetailsLookup
import androidx.recyclerview.selection.ItemKeyProvider
import androidx.recyclerview.selection.SelectionTracker
import androidx.recyclerview.selection.StorageStrategy
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import androidx.transition.Fade
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.transition.MaterialElevationScale
import com.google.android.material.transition.MaterialSharedAxis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import site.leos.apps.lespas.R
import site.leos.apps.lespas.cameraroll.CameraRollFragment
import site.leos.apps.lespas.helper.*
import site.leos.apps.lespas.helper.Tools.parcelableArrayList
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.publication.NCShareViewModel
import site.leos.apps.lespas.publication.PublicationListFragment
import site.leos.apps.lespas.search.SearchFragment
import site.leos.apps.lespas.settings.SettingsFragment
import site.leos.apps.lespas.sync.AcquiringDialogFragment
import site.leos.apps.lespas.sync.ActionViewModel
import site.leos.apps.lespas.sync.DestinationDialogFragment
import site.leos.apps.lespas.sync.SyncAdapter
import java.text.Collator
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

    private var receivedShareMenu: MenuItem? = null
    private var cameraRollAsAlbumMenu: MenuItem? = null
    private var unhideMenu: MenuItem? = null
    private var toggleRemoteMenu: MenuItem? = null
    private var sortByMenu: MenuItem? = null

    private var scrollTo = -1
    private var currentSortOrder = Album.BY_DATE_TAKEN_DESC
    private var newTimestamp: Long = System.currentTimeMillis() / 1000

    private lateinit var addFileLauncher: ActivityResultLauncher<String>

    private var showCameraRoll = true
    private lateinit var cameraRollAlbum: Album
    private var mediaStoreVersion = ""
    private var mediaStoreGeneration = 0L

    private var doSync = true

    private lateinit var remoteBasePath: String

    private lateinit var showCameraRollPreferenceListener: SharedPreferences.OnSharedPreferenceChangeListener

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        remoteBasePath = Tools.getRemoteHome(requireContext())

        lastSelection = savedInstanceState?.getStringArray(KEY_SELECTION)?.toMutableSet() ?: mutableSetOf()

        addFileLauncher = registerForActivityResult(LesPasGetMediaContract(arrayOf("image/*", "video/*"))) {
            if (it.isNotEmpty()) {
                uris.clear()
                uris.addAll(it)
                if (parentFragmentManager.findFragmentByTag(TAG_DESTINATION_DIALOG) == null)
                    DestinationDialogFragment.newInstance(uris,false).show(parentFragmentManager, TAG_DESTINATION_DIALOG)
            }
        }
        destinationModel.getDestination().observe (this) { album ->
            // Acquire files
            album?.apply {
                if (parentFragmentManager.findFragmentByTag(TAG_ACQUIRING_DIALOG) == null)
                    AcquiringDialogFragment.newInstance(uris, album, destinationModel.shouldRemoveOriginal()).show(parentFragmentManager, TAG_ACQUIRING_DIALOG)
            }
        }

        mAdapter = AlbumListAdapter(
            { album, imageView ->
                if (album.id != CameraRollFragment.FROM_CAMERA_ROLL) {
                    exitTransition = MaterialElevationScale(false).apply { duration = resources.getInteger(android.R.integer.config_shortAnimTime).toLong() }
                    reenterTransition = MaterialElevationScale(false).apply { duration = resources.getInteger(android.R.integer.config_shortAnimTime).toLong() }
                    parentFragmentManager.beginTransaction()
                        .setReorderingAllowed(true)
                        .addSharedElement(imageView, ViewCompat.getTransitionName(imageView)!!)
                        .replace(R.id.container_root, AlbumDetailFragment.newInstance(album, ""), AlbumDetailFragment::class.java.canonicalName).addToBackStack(null).commit()
                } else {
                    // Camera roll album's cover mime type is passed in property eTag
                    if (album.eTag.startsWith("video")) {
                        // Don't do transition for video cover
                        // Set tag to null so that CameraRollFragment will hide the action bar
                        parentFragmentManager.beginTransaction().replace(R.id.container_root, CameraRollFragment.newInstance(), null).addToBackStack(null).commit()
                    }
                    else {
                        exitTransition = MaterialElevationScale(false).apply {
                            duration = resources.getInteger(android.R.integer.config_shortAnimTime).toLong()
                            excludeTarget(imageView, true)
                            //excludeTarget(android.R.id.statusBarBackground, true)
                            //excludeTarget(android.R.id.navigationBarBackground, true)
                        }
                        reenterTransition = MaterialElevationScale(true).apply { duration = resources.getInteger(android.R.integer.config_shortAnimTime).toLong() }

                        parentFragmentManager.beginTransaction()
                            .setReorderingAllowed(true)
                            .addSharedElement(imageView, ViewCompat.getTransitionName(imageView)!!)
                            .replace(R.id.container_root, CameraRollFragment.newInstance(), CameraRollFragment::class.java.canonicalName).addToBackStack(null).commit()
                    }
                }
            },
            { user, view -> publishViewModel.getAvatar(user, view, null) },
            { album, imageView ->
                album.run {
                    publishViewModel.setImagePhoto(NCShareViewModel.RemotePhoto(Photo(
                        id = cover, albumId = id,
                        name = coverFileName, width = coverWidth, height = coverHeight, mimeType = coverMimeType, orientation = coverOrientation,
                        dateTaken = LocalDateTime.MIN, lastModified = LocalDateTime.MIN,
                        // TODO dirty hack, can't fetch cover photo's eTag here, hence by comparing it's id to name, for not yet uploaded file these two should be the same, otherwise use a fake one as long as it's not empty
                        eTag = if (cover == coverFileName) Photo.ETAG_NOT_YET_UPLOADED else Photo.ETAG_FAKE,
                    ), if (Tools.isRemoteAlbum(album) && cover != coverFileName) "${remoteBasePath}/${name}" else "", coverBaseline), imageView, if (cover == CameraRollFragment.EMPTY_ROLL_COVER_ID) NCShareViewModel.TYPE_EMPTY_ROLL_COVER else NCShareViewModel.TYPE_COVER)
                }
            },
            { view -> publishViewModel.cancelSetImagePhoto(view) },
        ).apply {
            stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        }

        requireContext().run {
            showCameraRollPreferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
                if (key == getString(R.string.cameraroll_as_album_perf_key)) sharedPreferences.getBoolean(key, true).run {
                    // Changed this flag accordingly. When popping back from Setting fragment, album list livedata observer will be triggered again
                    showCameraRoll = this
                    // Move album list to the top when popping back from Setting fragment, actual scrolling will happen after setAlbum in livedata observer
                    // Only scroll to top when setting being turned on
                    if (showCameraRoll) scrollTo = 0

                    // Maintain option menu
                    cameraRollAsAlbumMenu?.isEnabled = !this
                    cameraRollAsAlbumMenu?.isVisible = !this

                    // Selection based on bindingAdapterPosition, must be cleared
                    try { selectionTracker.clearSelection() } catch (_: UninitializedPropertyAccessException) {}
                }
            }

            // TODO only check first volume
            getCameraRoll(MediaStore.getVersion(this), if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) MediaStore.getGeneration(this, MediaStore.getExternalVolumeNames(this).first()) else 0L)

            with(PreferenceManager.getDefaultSharedPreferences(this)) {
                registerOnSharedPreferenceChangeListener(showCameraRollPreferenceListener)
                showCameraRoll = getBoolean(getString(R.string.cameraroll_as_album_perf_key), true)
                currentSortOrder = getInt(ALBUM_LIST_SORT_ORDER, Album.BY_DATE_TAKEN_DESC)
            }
        }

        requireActivity().onBackPressedDispatcher.addCallback(this, object: OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (selectionTracker.hasSelection()) {
                    selectionTracker.clearSelection()
                    lastSelection.clear()
                }
                else requireActivity().finish()
            }
        })
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View = inflater.inflate(R.layout.fragment_album, container, false)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.albumlist)
        fab = view.findViewById(R.id.fab)

        if (!(savedInstanceState == null && doSync)) postponeEnterTransition()
        view.doOnPreDraw {
            startPostponedEnterTransition()
            if (savedInstanceState == null && doSync) {
                // TODO: seems like flooding the server
                publishViewModel.refresh()

                // Sync with server at startup
                requestSync(SyncAdapter.SYNC_BOTH_WAY)
            }
        }

        albumsModel.allAlbumsByEndDate.observe(viewLifecycleOwner) {
            val list = mutableListOf<Album>().apply {
                if (showCameraRoll) add(cameraRollAlbum)
                addAll(it)
            }

            mAdapter.setAlbums(list, currentSortOrder) {
                if (scrollTo != -1) {
                    recyclerView.scrollToPosition(scrollTo)
                    scrollTo = -1
                }
            }

            sortByMenu?.run { isEnabled = it.isNotEmpty() }
        }
        albumsModel.allHiddenAlbums.observe(viewLifecycleOwner) { hidden -> unhideMenu?.isEnabled = hidden.isNotEmpty() }

        publishViewModel.shareByMe.asLiveData().observe(viewLifecycleOwner) { mAdapter.setRecipients(it) }
        publishViewModel.shareWithMe.asLiveData().observe(viewLifecycleOwner) { fixMenuIcon(it, true) }

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
                override fun canSetStateForKey(key: String, nextState: Boolean): Boolean = key != CameraRollFragment.FROM_CAMERA_ROLL && mAdapter.getItemBySelectionKey(key)?.let { it.syncProgress >= 1.0 } ?: run { true }
                override fun canSetStateAtPosition(position: Int, nextState: Boolean): Boolean = position > 0 && mAdapter.currentList[position].syncProgress >= 1.0
                override fun canSelectMultiple(): Boolean = true
            }).build().apply {
                addObserver(object : SelectionTracker.SelectionObserver<String>() {
                    override fun onSelectionChanged() {
                        super.onSelectionChanged()
                        val selectionSize = selectionTracker.selection.size()

                        if (selectionTracker.hasSelection() && actionMode == null) {
                            actionMode = (activity as? AppCompatActivity)?.startSupportActionMode(this@AlbumFragment)
                            actionMode?.let { it.title = resources.getQuantityString(R.plurals.selected_count, selectionSize, selectionSize) }
                        } else if (!selectionTracker.hasSelection() && actionMode != null) {
                            actionMode?.finish()
                            actionMode = null
                        } else actionMode?.title = resources.getQuantityString(R.plurals.selected_count, selectionSize, selectionSize)
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

            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    super.onScrollStateChanged(recyclerView, newState)

                    androidx.transition.TransitionManager.beginDelayedTransition(recyclerView.parent as ViewGroup, Fade().apply { duration = 300 })
                    fab.isVisible = newState == RecyclerView.SCROLL_STATE_IDLE
                }
            })

            addItemDecoration(LesPasEmptyView(ContextCompat.getDrawable(this.context, R.drawable.ic_baseline_footprint_24)!!))
        }
        LesPasFastScroller(
            recyclerView,
            ContextCompat.getDrawable(recyclerView.context, R.drawable.fast_scroll_thumb) as StateListDrawable, ContextCompat.getDrawable(recyclerView.context, R.drawable.fast_scroll_track)!!,
            ContextCompat.getDrawable(recyclerView.context, R.drawable.fast_scroll_thumb) as StateListDrawable, ContextCompat.getDrawable(recyclerView.context, R.drawable.fast_scroll_track)!!,
            resources.getDimensionPixelSize(R.dimen.fast_scroll_thumb_width), 0, 0, resources.getDimensionPixelSize(R.dimen.fast_scroll_thumb_height)
        )

        fab.setOnClickListener { addFileLauncher.launch("*/*") }

        // Confirm dialog result handler
        parentFragmentManager.setFragmentResultListener(ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY, viewLifecycleOwner) { _, bundle ->
            if (bundle.getBoolean(ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY, false)) {
                when(bundle.getString(ConfirmDialogFragment.INDIVIDUAL_REQUEST_KEY)) {
                    CONFIRM_DELETE_REQUEST -> {
                        val albums = mutableListOf<Album>()
                        // Selection key is Album.id
                        for (id in selectionTracker.selection) mAdapter.getItemBySelectionKey(id)?.let { albums.add(it) }
                        actionModel.deleteAlbums(albums)
                    }
                    CONFIRM_TOGGLE_REMOTE_REQUEST -> {
                        val selection = mutableListOf<String>().apply { for (id in selectionTracker.selection) add(id) }
                        val remote = Tools.isRemoteAlbum(mAdapter.getItemBySelectionKey(selectionTracker.selection.first())!!)
                        lifecycleScope.launch(Dispatchers.IO) {
                            albumsModel.setAsRemote(selection, !remote)

                            // If changing from remote to local, kick start a sync with server
                            if (remote) withContext(Dispatchers.Main) { requestSync(SyncAdapter.SYNC_REMOTE_CHANGES) }
                        }
                    }
                }
            }
            selectionTracker.clearSelection()
        }
        // Unhide dialog result handler
        parentFragmentManager.setFragmentResultListener(UnhideDialogFragment.UNHIDE_DIALOG_REQUEST_KEY, viewLifecycleOwner) { _, bundle ->
            //bundle.getParcelableArrayList<Album>(UnhideDialogFragment.KEY_UNHIDE_THESE)?.apply {
            bundle.parcelableArrayList<Album>(UnhideDialogFragment.KEY_UNHIDE_THESE)?.apply {
                if (this.isNotEmpty()) actionModel.unhideAlbums(this)
            }
        }

        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
                inflater.inflate(R.menu.album_menu, menu)
                receivedShareMenu = menu.findItem(R.id.option_menu_received_shares)
                cameraRollAsAlbumMenu = menu.findItem(R.id.option_menu_camera_roll)
                unhideMenu = menu.findItem(R.id.option_menu_unhide)
                sortByMenu = menu.findItem(R.id.option_menu_sortby)
            }

            override fun onPrepareMenu(menu: Menu) {
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

            override fun onMenuItemSelected(item: MenuItem): Boolean {
                when(item.itemId) {
                    R.id.option_menu_camera_roll-> {
                        exitTransition = null
                        reenterTransition = null
                        // Set tag to null so that CameraRollFragment will hide the action bar
                        parentFragmentManager.beginTransaction().replace(R.id.container_root, CameraRollFragment.newInstance(), null).addToBackStack(null).commit()
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
                        parentFragmentManager.beginTransaction().replace(R.id.container_root, SearchFragment.newInstance(mAdapter.itemCount == 0 || (mAdapter.itemCount == 1 && mAdapter.currentList[0].id == CameraRollFragment.FROM_CAMERA_ROLL)), SearchFragment::class.java.canonicalName).addToBackStack(null).commit()
                        return true
                    }
                    R.id.option_menu_received_shares-> {
                        PreferenceManager.getDefaultSharedPreferences(requireContext()).edit().putLong(KEY_RECEIVED_SHARE_TIMESTAMP, newTimestamp).apply()

                        exitTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
                        reenterTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
                        parentFragmentManager.beginTransaction().replace(R.id.container_root, PublicationListFragment(), PublicationListFragment::class.java.canonicalName).addToBackStack(null).commit()
                        receivedShareMenu?.icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_baseline_shared_with_me_24)?.apply { setTint(ContextCompat.getColor(requireContext(), R.color.bottom_control_button)) }
                        return true
                    }
                    R.id.option_menu_sortbydateasc, R.id.option_menu_sortbydatedesc, R.id.option_menu_sortbynameasc, R.id.option_menu_sortbynamedesc-> {
                        currentSortOrder = when(item.itemId) {
                            R.id.option_menu_sortbydateasc-> Album.BY_DATE_TAKEN_ASC
                            R.id.option_menu_sortbydatedesc-> Album.BY_DATE_TAKEN_DESC
                            R.id.option_menu_sortbynameasc-> Album.BY_NAME_ASC
                            R.id.option_menu_sortbynamedesc-> Album.BY_NAME_DESC
                            else-> -1
                        }

                        mAdapter.setAlbums(null, currentSortOrder) { recyclerView.scrollToPosition(0) }

                        PreferenceManager.getDefaultSharedPreferences(requireContext()).edit().putInt(ALBUM_LIST_SORT_ORDER, currentSortOrder).apply()

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
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    override fun onResume() {
        super.onResume()

        (activity as? AppCompatActivity)?.run {
            supportActionBar?.run {
                setDisplayHomeAsUpEnabled(false)
                setDisplayShowTitleEnabled(true)
                title = getString(R.string.app_name)
            }
            window.statusBarColor = Tools.getAttributeColor(this, android.R.attr.colorPrimary)
        }

        if (showCameraRoll) {
            requireContext().apply {
                val newVersion = MediaStore.getVersion(this)
                val newGeneration = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) MediaStore.getGeneration(this, MediaStore.getExternalVolumeNames(this).first()) else 0L
                if (newVersion != mediaStoreVersion) getCameraRoll(newVersion, newGeneration).apply { mAdapter.setCameraRollAlbum(this) }
                else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && newGeneration != mediaStoreGeneration) getCameraRoll(newVersion, newGeneration).apply { mAdapter.setCameraRollAlbum(this) }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArray(KEY_SELECTION, lastSelection.toTypedArray())
    }

    override fun onDestroyView() {
        doSync = false
        recyclerView.clearOnScrollListeners()
        recyclerView.adapter = null

        super.onDestroyView()
    }

    override fun onDestroy() {
        PreferenceManager.getDefaultSharedPreferences(requireContext()).unregisterOnSharedPreferenceChangeListener(showCameraRollPreferenceListener)

        super.onDestroy()
    }

    override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
        mode?.menuInflater?.inflate(R.menu.album_actions_mode, menu)
        toggleRemoteMenu = menu?.findItem(R.id.toggle_remote)

        fab.isEnabled = false

        return true
    }

    override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean  {
        var allRemote = false
        var allLocal = false

        selectionTracker.selection.forEach { key ->
            mAdapter.getItemBySelectionKey(key)?.apply { if (Tools.isRemoteAlbum(this)) allRemote = true else allLocal = true }
            toggleRemoteMenu?.apply {
                isEnabled = !(allLocal && allRemote)
                title = getString(if (allRemote) R.string.action_set_local else R.string.action_set_remote)
            }
        }

        return true
    }

    override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
        return when(item?.itemId) {
            R.id.remove -> {
                if (parentFragmentManager.findFragmentByTag(CONFIRM_DIALOG) == null) ConfirmDialogFragment.newInstance(getString(R.string.confirm_delete), getString(R.string.yes_delete), requestKey = CONFIRM_DELETE_REQUEST).show(parentFragmentManager, CONFIRM_DIALOG)
                true
            }
            R.id.hide -> {
                val refused = mutableListOf<String>()
                val hidden = mutableListOf<String>()
                albumsModel.allHiddenAlbums.value?.let { for (album in it) hidden.add(album.name.substring(1)) }

                mutableListOf<Album>().let { albums ->
                    selectionTracker.selection.forEach { id->
                        mAdapter.getItemBySelectionKey(id)?.let { album->
                            hidden.find { it == album.name }?.let { refused.add(it) } ?: albums.add(album)
                        }
                    }
                    selectionTracker.clearSelection()

                    if (albums.isNotEmpty()) {
                        actionModel.hideAlbums(albums)
                        publishViewModel.unPublish(albums)
                        actionModel.deleteBlogPosts(albums)
                    }
                    if (refused.isNotEmpty()) {
                        Snackbar.make(recyclerView, getString(R.string.not_hiding, refused.joinToString()), Snackbar.LENGTH_LONG).setAnchorView(fab).show()
                    }
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
            R.id.toggle_remote -> {
                if (parentFragmentManager.findFragmentByTag(CONFIRM_DIALOG) == null)
                    ConfirmDialogFragment.newInstance(getString(if (item.title == getString(R.string.action_set_remote)) R.string.msg_set_as_remote else R.string.msg_set_as_local), requestKey = CONFIRM_TOGGLE_REMOTE_REQUEST).show(parentFragmentManager, CONFIRM_DIALOG)
                true
            }
            R.id.select_all -> {
                mAdapter.currentList.forEach {
                    if (it.id != CameraRollFragment.FROM_CAMERA_ROLL) selectionTracker.select(it.id)
                }
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

    private fun requestSync(syncAction: Int) {
        // Sync with server at startup
        ContentResolver.requestSync(AccountManager.get(requireContext()).getAccountsByType(getString(R.string.account_type_nc))[0], getString(R.string.sync_authority), Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            //putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
            putInt(SyncAdapter.ACTION, syncAction)
        })
    }

    private fun unhide() {
        if (parentFragmentManager.findFragmentByTag(UNHIDE_DIALOG) == null) {
            val hidden = mutableListOf<Album>()

            albumsModel.allHiddenAlbums.value?.let { hidden.addAll(it) }
            for (album in mAdapter.currentList) {
                // If there is same name existed in album list, mark this hidden album's name with 2 dots prefix
                hidden.find { it.name.substring(1) == album.name}?.let { it.name = ".${it.name}" }
            }

            UnhideDialogFragment.newInstance(hidden).show(parentFragmentManager, UNHIDE_DIALOG)
        }
    }

    private fun fixMenuIcon(shareList: List<NCShareViewModel.ShareWithMe>, animate: Boolean = false) {
        if (shareList.isNotEmpty()) {
            receivedShareMenu?.isEnabled = true

            // Show notification badge animation and/or change tint
            newTimestamp = shareList[0].lastModified
            if (PreferenceManager.getDefaultSharedPreferences(requireContext()).getLong(KEY_RECEIVED_SHARE_TIMESTAMP, 0L) < newTimestamp)
                if (animate) {
                    with(ContextCompat.getDrawable(requireContext(), R.drawable.ic_baseline_new_share_coming_24) as AnimatedVectorDrawable) {
                        receivedShareMenu?.icon = this
                        this.start()
                    }
                } else receivedShareMenu?.icon?.setTint(ContextCompat.getColor(requireContext(), R.color.color_secondary))

        }
    }

    private fun getCameraRoll(version: String, generation: Long): Album {
        cameraRollAlbum = Tools.getCameraRollAlbum(requireContext().contentResolver, getString(R.string.item_camera_roll))
        mediaStoreVersion = version
        mediaStoreGeneration = generation

        return cameraRollAlbum
    }

    // List adapter for Albums' recyclerView
    class AlbumListAdapter(private val clickListener: (Album, ImageView) -> Unit, private val avatarLoader: (NCShareViewModel.Sharee, View) -> Unit, private val imageLoader: (Album, ImageView) -> Unit, private val cancelLoader: (View) -> Unit
    ): ListAdapter<Album, AlbumListAdapter.AlbumViewHolder>(AlbumDiffCallback()) {
        private var recipients = emptyList<NCShareViewModel.ShareByMe>()
        private lateinit var selectionTracker: SelectionTracker<String>

        inner class AlbumViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
            private var currentAlbum = Album(lastModified = LocalDateTime.MIN)
            private var withThese = mutableListOf<NCShareViewModel.Recipient>()
            private val ivCover = itemView.findViewById<ImageView>(R.id.coverart)
            private val pbSync = itemView.findViewById<ContentLoadingProgressBar>(R.id.sync_progress)
            private val tvTitle = itemView.findViewById<TextView>(R.id.title)
            private val tvDuration = itemView.findViewById<TextView>(R.id.duration)
            private val llRecipients = itemView.findViewById<LinearLayoutCompat>(R.id.recipients)
            private val cameraDrawable: Drawable?
            private val cloudDrawable: Drawable?

            init {
                val titleDrawableSize = tvTitle.textSize.toInt()
                cameraDrawable = ContextCompat.getDrawable(tvTitle.context, R.drawable.ic_baseline_camera_roll_24)?.apply { setBounds(0, 0, titleDrawableSize, titleDrawableSize) }
                cloudDrawable = ContextCompat.getDrawable(tvTitle.context, R.drawable.ic_baseline_wb_cloudy_24)?.apply { setBounds(0, 0, titleDrawableSize, titleDrawableSize) }
            }

            @SuppressLint("InflateParams")
            fun bindViewItems(album: Album) {
                val new = if (currentAlbum.id != album.id || currentAlbum.cover != album.cover || currentAlbum.coverBaseline != album.coverBaseline) {
                    currentAlbum = album
                    withThese = mutableListOf()
                    true
                } else { false }

                itemView.apply {
                    // Background color adhere to selection state
                    isActivated = selectionTracker.isSelected(album.id)

                    ivCover.let {coverImageview ->
                        if (new) {
                            // When syncing with server, don't repeatedly load the same image
                            imageLoader(album, coverImageview)
                            ViewCompat.setTransitionName(coverImageview, if (album.id == CameraRollFragment.FROM_CAMERA_ROLL) album.cover else album.id)
                        }
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

                        setCompoundDrawables(when {
                            album.id == CameraRollFragment.FROM_CAMERA_ROLL -> cameraDrawable
                            Tools.isRemoteAlbum(album) -> cloudDrawable
                            else -> null
                        }, null, null, null)
                    }
                    tvDuration.text = String.format(
                        "%s  -  %s",
                        album.startDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
                        album.endDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
                    )

                    llRecipients.also { chipGroup->
                        if (new) chipGroup.removeAllViews()
                        recipients.find { it.fileId == album.id }?.let {
                            if (withThese != it.with) {
                                // When syncing with server, don't repeatedly load the same recipient list
                                withThese = it.with
                                chipGroup.removeAllViews()
                                val ctx = chipGroup.context
                                for (recipient in it.with) chipGroup.addView((LayoutInflater.from(ctx).inflate(R.layout.textview_sharee, null) as TextView).also {
                                    recipient.sharee.run {
                                        if (type != NCShareViewModel.SHARE_TYPE_USER) {
                                            if (type != NCShareViewModel.SHARE_TYPE_PUBLIC) it.text = label
                                            it.compoundDrawablePadding = ctx.resources.getDimension(R.dimen.mini_padding).toInt()
                                        }
                                        avatarLoader(this, it)
                                    }
                                })
                            }
                        }
                    }
                }
            }

            fun getItemDetails() = object : ItemDetailsLookup.ItemDetails<String>() {
                override fun getPosition(): Int = bindingAdapterPosition
                override fun getSelectionKey(): String = getAlbumId(bindingAdapterPosition)
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlbumListAdapter.AlbumViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_album, parent,false)
            view.findViewById<TextView>(R.id.title)?.apply {
                compoundDrawablePadding = 16
                TextViewCompat.setCompoundDrawableTintList(this, ColorStateList.valueOf(currentTextColor))
            }
            return AlbumViewHolder(view)
        }

        override fun onBindViewHolder(holder: AlbumListAdapter.AlbumViewHolder, position: Int) {
            holder.bindViewItems(currentList[position])
        }

        override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
            for (i in 0 until currentList.size) {
                recyclerView.findViewHolderForAdapterPosition(i)?.let { holder ->
                    holder.itemView.findViewById<View>(R.id.coverart)?.let { cancelLoader(it) }
                }
            }
            super.onDetachedFromRecyclerView(recyclerView)
        }

        internal fun setAlbums(albums: MutableList<Album>?, sortOrder: Int, callback: () -> Unit) {
            val albumList = mutableListOf<Album>().apply { addAll(albums ?: currentList) }

            if (albumList.isNotEmpty()) {
                // save camera roll album
                val firstAlbum = albumList.first()
                if (firstAlbum.id == CameraRollFragment.FROM_CAMERA_ROLL) albumList.removeAt(0)

                when (sortOrder) {
                    Album.BY_DATE_TAKEN_ASC -> albumList.sortWith(compareBy { it.endDate })
                    Album.BY_DATE_TAKEN_DESC -> albumList.sortWith(compareByDescending { it.endDate })
                    Album.BY_NAME_ASC -> albumList.sortWith(compareBy(Collator.getInstance().apply { strength = Collator.PRIMARY }) { it.name })
                    Album.BY_NAME_DESC -> albumList.sortWith(compareByDescending(Collator.getInstance().apply { strength = Collator.PRIMARY }) { it.name })
                }

                // restore camera roll album
                if (firstAlbum.id == CameraRollFragment.FROM_CAMERA_ROLL) albumList.add(0, firstAlbum)
            }

            submitList(albumList) { callback() }
        }

        internal fun setRecipients(recipients: List<NCShareViewModel.ShareByMe>) {
            this.recipients = recipients
            for (recipient in recipients) { notifyItemChanged(currentList.indexOfFirst { it.id == recipient.fileId }) }
        }

        internal fun setCameraRollAlbum(cameraRollAlbum: Album) {
            mutableListOf<Album>().run {
                addAll(currentList)
                if (size > 0) removeAt(0)
                add(0, cameraRollAlbum)
                submitList(this)
            }
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
        override fun areContentsTheSame(oldItem: Album, newItem: Album): Boolean = oldItem.cover == newItem.cover && oldItem.name == newItem.name && oldItem.coverBaseline == newItem.coverBaseline && oldItem.coverFileName == newItem.coverFileName && oldItem.startDate == newItem.startDate && oldItem.endDate == newItem.endDate && oldItem.syncProgress == newItem.syncProgress && oldItem.shareId == newItem.shareId
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
            //requireArguments().getParcelableArrayList<Album>(KEY_ALBUMS)?.apply { hiddenAdapter.submitList(this.toMutableList()) }
            requireArguments().parcelableArrayList<Album>(KEY_ALBUMS)?.apply { hiddenAdapter.submitList(this.toMutableList()) }

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
                    if (album.name.startsWith("..")) {
                        // There is an album with same name existed, disable this item
                        tvName.text = album.name.substring(2)
                        tvName.isEnabled = false
                    } else {
                        tvName.text = album.name.substring(1)
                        tvName.isEnabled = true
                        tvName.setOnClickListener {
                            tvName.toggle()
                            updateChoice(album, tvName.isChecked)
                        }
                    }
                }
            }
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_multiple_choice, parent, false))
            override fun onBindViewHolder(holder: ViewHolder, position: Int) { holder.bind(currentList[position]) }
        }

        class NameDiffCallback: DiffUtil.ItemCallback<Album>() {
            override fun areItemsTheSame(oldItem: Album, newItem: Album): Boolean = oldItem.id == newItem.id
            override fun areContentsTheSame(oldItem: Album, newItem: Album): Boolean = oldItem.shareId == newItem.shareId && oldItem.eTag == newItem.eTag
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
        const val TAG_ACQUIRING_DIALOG = "ALBUM_FRAGMENT_TAG_ACQUIRING_DIALOG"
        const val TAG_DESTINATION_DIALOG = "ALBUM_FRAGMENT_TAG_DESTINATION_DIALOG"
        private const val CONFIRM_DIALOG = "CONFIRM_DIALOG"
        private const val CONFIRM_DELETE_REQUEST = "CONFIRM_DELETE_REQUEST"
        private const val CONFIRM_TOGGLE_REMOTE_REQUEST = "CONFIRM_TOGGLE_REMOTE_REQUEST"
        private const val UNHIDE_DIALOG = "UNHIDE_DIALOG"
        private const val KEY_SELECTION = "KEY_SELECTION"

        const val KEY_RECEIVED_SHARE_TIMESTAMP = "KEY_RECEIVED_SHARE_TIMESTAMP"

        private const val ALBUM_LIST_SORT_ORDER = "ALBUM_LIST_SORT_ORDER"

        @JvmStatic
        fun newInstance() = AlbumFragment()
    }
}