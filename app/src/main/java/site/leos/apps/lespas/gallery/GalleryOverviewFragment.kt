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

package site.leos.apps.lespas.gallery

import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.Html
import android.text.method.LinkMovementMethod
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.core.app.SharedElementCallback
import androidx.core.content.ContextCompat
import androidx.core.view.MenuProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceManager
import androidx.recyclerview.selection.ItemDetailsLookup
import androidx.recyclerview.selection.ItemKeyProvider
import androidx.recyclerview.selection.SelectionTracker
import androidx.recyclerview.selection.StorageStrategy
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.transition.MaterialElevationScale
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.ConfirmDialogFragment
import site.leos.apps.lespas.helper.LesPasEmptyView
import site.leos.apps.lespas.helper.ShareOutDialogFragment
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.publication.NCShareViewModel
import site.leos.apps.lespas.settings.SettingsFragment
import site.leos.apps.lespas.sync.ActionViewModel
import site.leos.apps.lespas.sync.BackupSetting
import site.leos.apps.lespas.sync.BackupSettingViewModel
import site.leos.apps.lespas.sync.SyncAdapter
import java.time.LocalDateTime
import java.util.Locale

class GalleryOverviewFragment : Fragment(), ActionMode.Callback {
    private var spanCount = 0
    private var actionMode: ActionMode? = null
    private lateinit var selectionTracker: SelectionTracker<String>
    private lateinit var selectionBackPressedCallback: OnBackPressedCallback

    private lateinit var overviewAdapter: OverviewAdapter
    private lateinit var overviewList: RecyclerView

    private var trashMenuItem: MenuItem? = null
    private var downloadMenuItem: MenuItem? = null
    private var uploadMenuItem: MenuItem? = null

    private val actionModel: ActionViewModel by viewModels(ownerProducer = { requireParentFragment() })
    private val imageLoaderModel: NCShareViewModel by activityViewModels()
    private val galleryModel: GalleryFragment.GalleryViewModel by viewModels(ownerProducer = { requireParentFragment() }) { GalleryFragment.GalleryViewModelFactory(requireActivity(), imageLoaderModel, actionModel) }
    private val backupSettingModel: BackupSettingViewModel by viewModels(ownerProducer = { requireParentFragment() })

    private var syncRequired = false
    private var selectionPendingRestored = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        spanCount = resources.getInteger(R.integer.cameraroll_grid_span_count)
        overviewAdapter = OverviewAdapter(
            getString(R.string.camera_roll_name),
            getString(R.string.trash_name),
            ("${Tools.getDeviceArchiveBase(requireContext())}/").let { getString(R.string.msg_archive_location, "<br><a href=\"${imageLoaderModel.getServerBaseUrl()}/apps/files/?dir=${Uri.encode(it)}\">$it</a>") },
            spanCount * 2,
            { folder, isEnabled, lastBackup ->
                if (isEnabled) {
                    backupSettingModel.enableBackup(folder)
                    if (lastBackup == BackupSetting.NOT_YET.toInt() && parentFragmentManager.findFragmentByTag(CONFIRM_DIALOG) == null) {
                        viewLifecycleOwner.lifecycleScope.launch {
                            Tools.getFolderStatistic(requireContext().contentResolver, folder).let {
                                if (it.first > 0) {
                                    // If there are existing photos in camera roll, offer choice to backup those too
                                    ConfirmDialogFragment.newInstance(getString(R.string.msg_backup_existing, folder, it.first, Tools.humanReadableByteCountSI(it.second)), positiveButtonText = getString(R.string.yes), negativeButtonText = getString(R.string.no), cancelable = false, individualKey = "${BACKUP_EXISTING_REQUEST_KEY}${folder}", requestKey = GALLERY_OVERVIEW_REQUEST_KEY).show(parentFragmentManager, CONFIRM_DIALOG)
                                } else backupSettingModel.updateLastBackupTimestamp(folder, System.currentTimeMillis() / 1000)
                            }
                        }
                    }

                    syncRequired = true
                } else backupSettingModel.disableBackup(folder)
            },
            { folder -> if (parentFragmentManager.findFragmentByTag(BACKUP_OPTION_DIALOG) == null) GalleryBackupSettingDialogFragment.newInstance(folder).show(parentFragmentManager, BACKUP_OPTION_DIALOG) },
            { folder ->
                galleryModel.setCurrentPhotoId("")
                galleryModel.resetCurrentSubFolder()

                exitTransition = null
                reenterTransition = null
                parentFragmentManager.beginTransaction().setCustomAnimations(R.anim.enter_from_right, R.anim.exit_to_left, R.anim.enter_from_left, R.anim.exit_to_right)
                    .replace(R.id.container_child_fragment, GalleryFolderViewFragment.newInstance(folder), GalleryFolderViewFragment::class.java.canonicalName).addToBackStack(null).commit()
            },
            { view, photoId, mimeType, folder ->
                galleryModel.setCurrentPhotoId(photoId)

                if (mimeType.startsWith("video")) {
                    // Transition to surface view might crash some OEM phones, like Xiaomi
                    parentFragmentManager.beginTransaction().replace(R.id.container_child_fragment, GallerySlideFragment.newInstance(folder), GallerySlideFragment::class.java.canonicalName).addToBackStack(null).commit()
                } else {
                    reenterTransition = MaterialElevationScale(false).apply {
                        duration = resources.getInteger(android.R.integer.config_mediumAnimTime).toLong()
                        //excludeTarget(view, true)
                    }
                    exitTransition = MaterialElevationScale(false).apply {
                        duration = resources.getInteger(android.R.integer.config_mediumAnimTime).toLong()
                        //excludeTarget(view, true)
                        //excludeTarget(android.R.id.statusBarBackground, true)
                        //excludeTarget(android.R.id.navigationBarBackground, true)
                    }

                    parentFragmentManager.beginTransaction()
                        .setReorderingAllowed(true)
                        .addSharedElement(view, view.transitionName)
                        .replace(R.id.container_child_fragment, GallerySlideFragment.newInstance(folder), GallerySlideFragment::class.java.canonicalName)
                        .addToBackStack(null)
                        .commit()
                }
            },
            { remotePhoto, imageView ->
                imageLoaderModel.setImagePhoto(remotePhoto, imageView, NCShareViewModel.TYPE_GRID) { startPostponedEnterTransition() }},
            { view -> imageLoaderModel.cancelSetImagePhoto(view) },
        ).apply { stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY }

        selectionBackPressedCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                if (selectionTracker.hasSelection()) {
                    selectionTracker.clearSelection()
                }
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(this, selectionBackPressedCallback)

        // Adjusting the shared element mapping
        setExitSharedElementCallback(object : SharedElementCallback() {
            override fun onMapSharedElements(names: MutableList<String>?, sharedElements: MutableMap<String, View>?) {
                galleryModel.getCurrentPhotoId().let { photoId ->
                    if (photoId.isNotEmpty() && names?.isNotEmpty() == true) overviewList.findViewHolderForAdapterPosition(overviewAdapter.getPhotoPosition(photoId))?.let { viewHolder ->
                        sharedElements?.put(names[0], viewHolder.itemView.findViewById(R.id.photo))
                    }
                }
            }
        })
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? = inflater.inflate(R.layout.fragment_gallery_overview, container, false)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        postponeEnterTransition()

        overviewAdapter.setMarks(galleryModel.getPlayMark(), galleryModel.getSelectedMark())
        overviewList = view.findViewById<RecyclerView?>(R.id.gallery_list).apply {
            adapter = overviewAdapter

            itemAnimator = null     // Disable recyclerview item animation to avoid ANR in AdapterHelper.findPositionOffset() when DiffResult applying at the moment that the list is scrolling

            (layoutManager as GridLayoutManager).spanSizeLookup = object: GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    return if (overviewAdapter.toSpan(position)) spanCount else 1
                }
            }

            selectionTracker = SelectionTracker.Builder(
                "galleryOverviewFragmentSelection",
                this,
                OverviewAdapter.PhotoKeyProvider(overviewAdapter),
                OverviewAdapter.PhotoDetailsLookup(this),
                StorageStrategy.createStringStorage()
            ).withSelectionPredicate(object : SelectionTracker.SelectionPredicate<String>() {
                override fun canSetStateForKey(key: String, nextState: Boolean): Boolean = !galleryModel.isPreparingShareOut() && key.isNotEmpty()
                override fun canSetStateAtPosition(position: Int, nextState: Boolean): Boolean = !galleryModel.isPreparingShareOut() && position > 0
                override fun canSelectMultiple(): Boolean = true
            }).build()
            selectionTracker.addObserver(object : SelectionTracker.SelectionObserver<String>() {
                override fun onSelectionChanged() {
                    super.onSelectionChanged()
                    updateUI()
                }

                override fun onSelectionRestored() {
                    super.onSelectionRestored()
                    updateUI()
                }

                private fun updateUI() {
                    val selectionSize = selectionTracker.selection.size()
                    if (selectionTracker.hasSelection() && actionMode == null) {
                        actionMode = (requireActivity() as AppCompatActivity).startSupportActionMode(this@GalleryOverviewFragment)
                        actionMode?.run {
                            title = resources.getQuantityString(R.plurals.selected_count, selectionSize, selectionSize)
                            subtitle = overviewAdapter.getSelectionFileSize()
                        }
                        selectionBackPressedCallback.isEnabled = true
                    } else if (!(selectionTracker.hasSelection()) && actionMode != null) {
                        actionMode?.subtitle = ""
                        actionMode?.finish()
                        actionMode = null
                        selectionBackPressedCallback.isEnabled = false
                    } else {
                        actionMode?.run {
                            title = resources.getQuantityString(R.plurals.selected_count, selectionSize, selectionSize)
                            subtitle = overviewAdapter.getSelectionFileSize()
                        }
                    }
                }
            })
            overviewAdapter.setSelectionTracker(selectionTracker)

            addItemDecoration(LesPasEmptyView(ContextCompat.getDrawable(this.context, R.drawable.ic_baseline_device_24)!!))

            // Avoid window inset overlapping
            ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
                val displayCutoutInset = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
                val navigationBarInset = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
                v.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    rightMargin = displayCutoutInset.right + navigationBarInset.right
                    leftMargin = displayCutoutInset.left + navigationBarInset.left
                }
                insets
            }
        }

        parentFragmentManager.setFragmentResultListener(GalleryDeletionDialogFragment.GALLERY_DELETION_DIALOG_RESULT_KEY, viewLifecycleOwner) { _, bundle ->
            if (bundle.getBoolean(GalleryDeletionDialogFragment.GALLERY_DELETION_DIALOG_RESULT_KEY)) galleryModel.remove(getSelectedPhotoIDs(), removeLocal = bundle.getBoolean(GalleryDeletionDialogFragment.DELETE_LOCAL_RESULT_KEY), removeArchive = bundle.getBoolean(GalleryDeletionDialogFragment.DELETE_REMOTE_RESULT_KEY))
        }

        parentFragmentManager.setFragmentResultListener(GALLERY_OVERVIEW_REQUEST_KEY, viewLifecycleOwner) { _, bundle ->
            bundle.getString(ConfirmDialogFragment.INDIVIDUAL_REQUEST_KEY)?.let { requestKey ->
                when {
                    // When ConfirmDialogFragment launched to confirm backup existing media files, INDIVIDUAL_REQUEST_KEY is the folder name. TODO hope that no body use 'DELETE_REQUEST_KEY' as their folder name
                    requestKey.startsWith(BACKUP_EXISTING_REQUEST_KEY) -> backupSettingModel.updateLastBackupTimestamp(requestKey.substringAfter(BACKUP_EXISTING_REQUEST_KEY), if (bundle.getBoolean(ConfirmDialogFragment.CONFIRM_DIALOG_RESULT_KEY, false)) 0L else System.currentTimeMillis() / 1000)
                }
            }
        }

        // Share out dialog result handler
        parentFragmentManager.setFragmentResultListener(ShareOutDialogFragment.SHARE_OUT_DIALOG_RESULT_KEY, viewLifecycleOwner) { _, bundle ->
            if (bundle.getBoolean(ShareOutDialogFragment.SHARE_OUT_DIALOG_RESULT_KEY, true))
                galleryModel.shareOut(
                    photoIds = getSelectedPhotoIDs(),
                    strip = bundle.getBoolean(ShareOutDialogFragment.STRIP_RESULT_KEY, false),
                    lowResolution = bundle.getBoolean(ShareOutDialogFragment.LOW_RESOLUTION_RESULT_KEY, false),
                    removeAfterwards = bundle.getBoolean(ShareOutDialogFragment.REMOVE_AFTERWARDS_RESULT_KEY, false),
                )
            else selectionTracker.clearSelection()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    val max = spanCount * 2

                    combine(galleryModel.medias, backupSettingModel.getSettings()) { galleryMedias, backupSettings ->
                        galleryMedias?.let {
                            var attachFootNote = false

                            //if (galleryMedias.isEmpty()) parentFragmentManager.popBackStack()

                            val overviewItems = mutableListOf<GalleryFragment.GalleryMedia>()
                            var isEnabled = false
                            var lastBackupDate = BackupSetting.NOT_YET
                            var totalSize: Long
                            galleryMedias.groupBy { it.folder }.run {
                                forEach { group ->
                                    backupSettings.find { it.folder == group.key }?.let {
                                        isEnabled = it.enabled
                                        if (isEnabled) attachFootNote = true

                                        lastBackupDate = it.lastBackup
                                    } ?: run {
                                        isEnabled = false
                                        lastBackupDate = BackupSetting.NOT_YET
                                    }

                                    totalSize = 0L
                                    group.value.forEach { totalSize += it.media.photo.caption.toLong() }

                                    overviewItems.add(
                                        GalleryFragment.GalleryMedia(
                                            GalleryFragment.GalleryMedia.IS_NOT_MEDIA,
                                            group.key,
                                            NCShareViewModel.RemotePhoto(
                                                // Property mimeType is empty means it's folder header, and this folder's media count is stored in property height, total size stored in property caption
                                                // last backup time saved in property width (just need to check if it's 0), backup enable or not is saved in property coverBaseLine
                                                Photo(id = group.key, mimeType = "", caption = totalSize.toString(), height = group.value.size, width = lastBackupDate.toInt(), dateTaken = LocalDateTime.MIN, lastModified = LocalDateTime.MIN),
                                                coverBaseLine = when {
                                                    //group.key == GalleryFragment.TRASH_FOLDER -> BACKUP_NOT_AVAILABLE
                                                    isEnabled -> BACKUP_ENABLED
                                                    else -> BACKUP_DISABLED
                                                }
                                            )
                                        )
                                    )
                                    // Maximum 2 lines of media items in overview list
                                    overviewItems.addAll(group.value.take(max))
                                }
                            }
                            if (attachFootNote) {
                                // TODO auto remove on Android 11
                                if (Build.VERSION.SDK_INT > Build.VERSION_CODES.R) galleryModel.autoRemove(requireActivity(), backupSettings)
                                overviewItems.plus(GalleryFragment.GalleryMedia(GalleryFragment.GalleryMedia.IS_NOT_MEDIA, FOOTNOTE, NCShareViewModel.RemotePhoto(Photo(id = FOOTNOTE, mimeType = "", dateTaken = LocalDateTime.MIN, lastModified = LocalDateTime.MIN), coverBaseLine = BACKUP_NOT_AVAILABLE)))
                            } else overviewItems
                        }
                    }.collect { list ->
                        list?.let {
                            overviewAdapter.submitList(list) {
                                galleryModel.stopArchiveLoadingIndicator()
                                savedInstanceState?.let {
                                    if (selectionPendingRestored) {
                                        selectionTracker.onRestoreInstanceState(it)
                                        selectionPendingRestored = false
                                    }
                                }
                            }
                        }

                        if (list?.isEmpty() == true) startPostponedEnterTransition()
                    }
                }
                launch { galleryModel.trash.collect { trashMenuItem?.isEnabled = !it.isNullOrEmpty() }}
            }
        }

        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.gallery_overview_menu, menu)
                trashMenuItem = menu.findItem(R.id.trash)
                trashMenuItem?.isVisible = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            }

            override fun onPrepareMenu(menu: Menu) {
                super.onPrepareMenu(menu)
                // Collecting on trash might have already done when menu created
                trashMenuItem?.isEnabled = !galleryModel.trash.value.isNullOrEmpty()
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                return when(menuItem.itemId) {
                    R.id.show_all -> {
                        galleryModel.setCurrentPhotoId("")
                        galleryModel.resetCurrentSubFolder()
                        exitTransition = null
                        reenterTransition = null
                        parentFragmentManager.beginTransaction().setCustomAnimations(R.anim.enter_from_right, R.anim.exit_to_left, R.anim.enter_from_left, R.anim.exit_to_right)
                            .replace(R.id.container_child_fragment, GalleryFolderViewFragment.newInstance(GalleryFragment.ALL_FOLDER), GalleryFolderViewFragment::class.java.canonicalName).addToBackStack(null).commit()
                        true
                    }
                    R.id.trash -> {
                        galleryModel.setCurrentPhotoId("")
                        exitTransition = null
                        reenterTransition = null
                        parentFragmentManager.beginTransaction().setCustomAnimations(R.anim.enter_from_right, R.anim.exit_to_left, R.anim.enter_from_left, R.anim.exit_to_right)
                            .replace(R.id.container_child_fragment, GalleryFolderViewFragment.newInstance(GalleryFragment.TRASH_FOLDER), GalleryFolderViewFragment::class.java.canonicalName).addToBackStack(null).commit()
                        true
                    }
                    R.id.option_menu_settings-> {
                        exitTransition = null
                        reenterTransition = null
                        parentFragmentManager.beginTransaction().setCustomAnimations(R.anim.enter_from_right, R.anim.exit_to_left, R.anim.enter_from_left, R.anim.exit_to_right)
                            .replace(R.id.container_child_fragment, SettingsFragment(), LAUNCH_BY_GALLERY).addToBackStack(null).commit()
                        return true
                    }
                    else -> false
                }
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    override fun onResume() {
        super.onResume()

        (requireActivity() as AppCompatActivity).supportActionBar?.run {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.gallery_name)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        try { selectionTracker.onSaveInstanceState(outState) } catch (_: UninitializedPropertyAccessException) {}
    }

    override fun onDestroyView() {
        overviewList.adapter = null

        if (syncRequired) {
            val accounts = AccountManager.get(requireContext()).getAccountsByType(getString(R.string.account_type_nc))
            if (accounts.isNotEmpty()) ContentResolver.requestSync(accounts[0], getString(R.string.sync_authority), Bundle().apply {
                putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                putInt(SyncAdapter.ACTION, SyncAdapter.SYNC_LOCAL_CHANGES)
            })
        }
        super.onDestroyView()
    }

    override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
        mode?.menuInflater?.inflate(R.menu.action_mode_gallery, menu)
        downloadMenuItem = menu?.findItem(R.id.download_to_device)
        uploadMenuItem = menu?.findItem(R.id.upload_to_archive)

        return true
    }
    override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
        // Disable "Select All" in Gallery Overview list, since it makes no sense when user can see all the photos
        menu?.findItem(R.id.select_all)?.run {
            isVisible = false
            isEnabled = false
        }

        downloadMenuItem?.apply {
            isEnabled = false
            run breaking@ {
                selectionTracker.selection.forEach { photoId ->
                    if (overviewAdapter.atRemote(photoId)) {
                        isEnabled = true
                        return@breaking
                    }
                }
            }
        }

        uploadMenuItem?.apply {
            isEnabled = false
            run breaking@ {
                selectionTracker.selection.forEach { photoId ->
                    if (overviewAdapter.atLocal(photoId)) {
                        isEnabled = true
                        return@breaking
                    }
                }
            }
        }

        return true
    }

    override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
        return when(item?.itemId) {
            R.id.add -> {
                galleryModel.add(getSelectedPhotoIDs())
                true
            }
            R.id.remove -> {
                if (parentFragmentManager.findFragmentByTag(GalleryDeletionDialogFragment.GALLERY_DELETION_DIALOG_RESULT_KEY) == null) {
                    val location = overviewAdapter.locationOfSelected()
                    GalleryDeletionDialogFragment.newInstance(
                        GALLERY_OVERVIEW_REQUEST_KEY,
                        location != GalleryFragment.GalleryMedia.IS_REMOTE, true,
                        location != GalleryFragment.GalleryMedia.IS_LOCAL, PreferenceManager.getDefaultSharedPreferences(requireContext()).getBoolean(getString(R.string.sync_deletion_perf_key) , false)
                    ).show(parentFragmentManager, GalleryDeletionDialogFragment.GALLERY_DELETION_DIALOG_RESULT_KEY)
                }

                true
            }
            R.id.share -> {
                val photoIds = getSelectedPhotoIDs(false)
                if (parentFragmentManager.findFragmentByTag(SHARE_OUT_DIALOG) == null)
                    ShareOutDialogFragment.newInstance(mimeTypes = galleryModel.getMimeTypes(photoIds), showRemoveAfterwards = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaStore.canManageMedia(requireContext()) else false)?.show(parentFragmentManager, SHARE_OUT_DIALOG)
                    ?: run {
                        selectionTracker.clearSelection()
                        galleryModel.shareOut(photoIds, strip = false, lowResolution = false, removeAfterwards = false)
                    }

                true
            }
            R.id.download_to_device -> {
                val photos = mutableListOf<NCShareViewModel.RemotePhoto>()
                selectionTracker.selection.forEach { photoId -> overviewAdapter.getRemotePhoto(photoId)?.let { photos.add(it) }}
                if (photos.isNotEmpty()) galleryModel.download(requireContext(), photos)

                selectionTracker.clearSelection()
                true
            }
            R.id.upload_to_archive -> {
                val photos = mutableListOf<GalleryFragment.GalleryMedia>()
                selectionTracker.selection.forEach { photoId -> overviewAdapter.getGalleryMedia(photoId)?.let { photos.add(it) }}
                if (photos.isNotEmpty()) galleryModel.upload(photos)

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

    private fun getSelectedPhotoIDs(clearSelectionAfterward: Boolean = true): List<String> = mutableListOf<String>().apply {
        selectionTracker.selection.forEach { add(it) }
        if (clearSelectionAfterward) selectionTracker.clearSelection()
    }

    class OverviewAdapter(
        private val cameraRollName: String, private val trashName: String, private val footNote: String,
        private val max: Int, private val enableBackupClickListener: (String, Boolean, Int) -> Unit,  private val backupOptionClickListener: (String) -> Unit, private val folderClickListener: (String) -> Unit, private val photoClickListener: (View, String, String, String) -> Unit,
        private val imageLoader: (NCShareViewModel.RemotePhoto, ImageView) -> Unit, private val cancelLoader: (View) -> Unit
    ) : ListAdapter<GalleryFragment.GalleryMedia, RecyclerView.ViewHolder>(OverviewDiffCallback()) {
        private lateinit var selectionTracker: SelectionTracker<String>
        private val selectedFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0.0f) })
        private var playMark: Drawable? = null
        private var selectedMark: Drawable? = null

        inner class MediaViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val ivPhoto: ImageView = itemView.findViewById<ImageView>(R.id.photo).apply {
                foregroundGravity = Gravity.CENTER
                setOnClickListener { if (!selectionTracker.hasSelection()) currentList[bindingAdapterPosition].let { item -> photoClickListener(this, item.media.photo.id, item.media.photo.mimeType, item.folder) }}
            }
            private val ivLocal: View = itemView.findViewById(R.id.local_media)
            private val ivArchive: View = itemView.findViewById(R.id.archive_media)

            fun bind(item: GalleryFragment.GalleryMedia) {
                val photo = item.media.photo

                itemView.let {
                    with(ivPhoto) {
                        // Prevent re-loading image again which result in a flickering
                        if (getTag(R.id.PHOTO_ID) != item.media.photo.id) imageLoader(item.media, this)

                        ViewCompat.setTransitionName(this, photo.id)

                        it.isSelected = selectionTracker.isSelected(photo.id)
                        foreground = when {
                            it.isSelected -> selectedMark
                            Tools.isMediaPlayable(photo.mimeType) -> playMark
                            else -> null
                        }

                        if (it.isSelected) colorFilter = selectedFilter
                        else clearColorFilter()
                    }
                }

                bindLocationIndicator(item)
            }

            fun getItemDetails() = object : ItemDetailsLookup.ItemDetails<String>() {
                override fun getPosition(): Int = bindingAdapterPosition
                override fun getSelectionKey(): String = getPhotoId(bindingAdapterPosition)
            }

            fun bindLocationIndicator(item: GalleryFragment.GalleryMedia) {
                ivLocal.isActivated = item.atLocal()
                ivArchive.isActivated = item.atRemote()
            }
        }

        inner class OverflowHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private var overflow = true
            private val selectedFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0.0f) })
            val ivPhoto: ImageView = itemView.findViewById<ImageView>(R.id.photo).apply {
                foregroundGravity = Gravity.CENTER
                setOnClickListener {
                    if (!selectionTracker.hasSelection()) {
                        if (overflow) folderClickListener(currentList[bindingAdapterPosition].folder)
                        else currentList[bindingAdapterPosition].let { item -> photoClickListener(this, item.media.photo.id, item.media.photo.mimeType, item.folder) }
                    }
                }
            }
            private val tvCount: TextView = itemView.findViewById(R.id.count)
            private val ivLocal: View = itemView.findViewById(R.id.local_media)
            private val ivArchive: View = itemView.findViewById(R.id.archive_media)

            fun bind(item: GalleryFragment.GalleryMedia, count: Int) {
                val photo = item.media.photo
                overflow = count > max

                ivPhoto.run {
                    // Prevent re-loading image again which result in a flickering
                    if (getTag(R.id.PHOTO_ID) != item.media.photo.id) imageLoader(item.media, this)

                    ViewCompat.setTransitionName(this, photo.id)

                    itemView.isSelected = selectionTracker.isSelected(photo.id)
                    if (overflow) {
                        foreground = null
                        imageTintList = ColorStateList.valueOf(Color.argb(0x70, 0x00, 0x00, 0x00))
                        imageTintMode = PorterDuff.Mode.SRC_ATOP
                        clearColorFilter()
                    } else {
                        imageTintList = null
                        imageTintMode = null
                        foreground = when {
                            itemView.isSelected -> selectedMark
                            Tools.isMediaPlayable(photo.mimeType) -> playMark
                            else -> null
                        }

                        if (itemView.isSelected) colorFilter = selectedFilter
                        else clearColorFilter()
                    }
                }

                bindLocationIndicator(item, count)
            }

            fun bindLocationIndicator(item: GalleryFragment.GalleryMedia, count: Int) {
                ivLocal.isActivated = item.atLocal()
                ivArchive.isActivated = item.atRemote()
                tvCount.isVisible = overflow
                tvCount.text = String.format(Locale.getDefault(), "+ %d", count - max)
            }

            fun getItemDetails() = object : ItemDetailsLookup.ItemDetails<String>() {
                override fun getPosition(): Int = bindingAdapterPosition
                override fun getSelectionKey(): String = getPhotoId(bindingAdapterPosition)
            }

            fun canSelect(): Boolean = !overflow
        }

        inner class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvName = itemView.findViewById<TextView>(R.id.name).apply { setOnClickListener { folderClickListener(currentList[bindingAdapterPosition].folder) }}
            private val cbEnableBackup = itemView.findViewById<CheckBox>(R.id.enable_backup).apply { setOnCheckedChangeListener { _, isChecked -> currentList[bindingAdapterPosition].let { item -> enableBackupClickListener(item.folder, isChecked, item.media.photo.width) }}}
            private val ivBackupSetting = itemView.findViewById<TextView>(R.id.backup_setting).apply { setOnClickListener { backupOptionClickListener(currentList[bindingAdapterPosition].folder) }}

            fun bind(item: GalleryFragment.GalleryMedia) {
                tvName.text = String.format(
                    "%s (%s)",
                    when(item.folder) {
                        "" -> "/"
                        "DCIM" -> cameraRollName
                        GalleryFragment.TRASH_FOLDER -> trashName
                        else -> item.folder
                    },
                    Tools.humanReadableByteCountSI(item.media.photo.caption.toLong())
                )

                bindBackupState(item)
            }

            fun bindBackupState(item: GalleryFragment.GalleryMedia) {
                cbEnableBackup.isChecked = item.media.coverBaseLine == BACKUP_ENABLED
                ivBackupSetting.isVisible = item.media.coverBaseLine == BACKUP_ENABLED
            }
        }

        inner class FootNoteViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvMessage = itemView.findViewById<TextView>(R.id.message).apply { movementMethod = LinkMovementMethod.getInstance() }
            fun bind() { tvMessage.text = Html.fromHtml(footNote, Html.FROM_HTML_MODE_LEGACY) }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            when(viewType) {
                TYPE_MEDIA -> MediaViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_gallery, parent, false))
                TYPE_OVERFLOW -> OverflowHolder(LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_gallery_overview_overflow, parent, false))
                TYPE_HEADER -> HeaderViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_gallery_overview_header, parent, false))
                else -> FootNoteViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_gallery_overview_foot_note, parent, false))
            }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when(holder) {
                is MediaViewHolder -> holder.bind(currentList[position])
                is OverviewAdapter.OverflowHolder -> holder.bind(currentList[position], getCount(currentList[position].folder))
                is HeaderViewHolder -> holder.bind(currentList[position])
                else -> (holder as FootNoteViewHolder).bind()
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
            if (payloads.isEmpty()) onBindViewHolder(holder, position)
            else when(holder) {
                is MediaViewHolder -> if (payloads[0] == DIFF_PAYLOAD_LOCATION_CHANGED) holder.bindLocationIndicator(currentList[position]) else holder.bind(currentList[position])
                is OverviewAdapter.OverflowHolder -> if (payloads[0] == DIFF_PAYLOAD_LOCATION_CHANGED) holder.bindLocationIndicator(currentList[position], getCount(currentList[position].folder)) else holder.bind(currentList[position], getCount(currentList[position].folder))
                is HeaderViewHolder -> if (payloads[0] == DIFF_PAYLOAD_BACKUP_STATE_CHANGED) holder.bindBackupState(currentList[position]) else holder.bind(currentList[position])
                else -> (holder as FootNoteViewHolder).bind()
            }
        }

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            if (holder is MediaViewHolder) cancelLoader(holder.ivPhoto)
            if (holder is OverflowHolder) cancelLoader(holder.ivPhoto)
            super.onViewRecycled(holder)
        }

/*

        override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
            for (i in 0 until currentList.size) {
                recyclerView.findViewHolderForAdapterPosition(i)?.let { holder ->
                    if (holder is MediaViewHolder) cancelLoader(holder.ivPhoto)
                    if (holder is OverflowHolder) cancelLoader(holder.ivPhoto)
                }
            }
            super.onDetachedFromRecyclerView(recyclerView)
        }
*/

        override fun getItemViewType(position: Int): Int = when {
            // Footnote view item's folder property should be 'TYPE_FOOTNOTE', for others it should be item's folder name
            currentList[position].folder == FOOTNOTE -> TYPE_FOOTNOTE
            // Header view item's mimeType should be empty
            currentList[position].media.photo.mimeType.isEmpty() -> TYPE_HEADER
            else ->
                // If it's the last item in the list of it's next item is a Header, then this item should be a Overflow view
                if (position == currentList.size - 1 || currentList[position + 1].media.photo.mimeType.isEmpty()) TYPE_OVERFLOW
                // Otherwise, it's a normal media view
                else TYPE_MEDIA
        }

        internal fun getSelectionFileSize(): String {
            var size = 0L
            selectionTracker.selection.forEach { selected -> currentList.find { it.media.photo.id == selected }?.let { size += it.media.photo.caption.toLong() } ?: run { Log.wtf(">>>>>>>>", "getSelectionFileSize: can't find", ) }}

            return Tools.humanReadableByteCountSI(size)
        }
        internal fun toSpan(position: Int): Boolean = getItemViewType(position) == TYPE_HEADER || getItemViewType(position) == TYPE_FOOTNOTE
        internal fun setMarks(playMark: Drawable, selectedMark: Drawable) {
            this.playMark = playMark
            this.selectedMark = selectedMark
        }
        internal fun setSelectionTracker(selectionTracker: SelectionTracker<String>) { this.selectionTracker = selectionTracker }
        internal fun getPhotoId(position: Int): String = currentList[position].media.photo.id
        internal fun getPhotoPosition(photoId: String): Int = currentList.indexOfFirst { it.media.photo.id == photoId }
        private fun getCount(folder: String): Int = currentList.find { it.folder == folder && it.media.photo.mimeType.isEmpty() }?.media?.photo?.height ?: 0

        internal fun atRemote(photoId: String): Boolean = currentList.find { it.media.photo.id == photoId }?.atRemote() ?: false
        internal fun atLocal(photoId: String): Boolean = currentList.find { it.media.photo.id == photoId }?.let { it.atLocal() || it.isLocal() }?: false
        internal fun getRemotePhoto(photoId: String): NCShareViewModel.RemotePhoto? =  currentList.find { it.media.photo.id == photoId }?.let { item -> if (item.atRemote()) item.media else null }
        internal fun getGalleryMedia(photoId: String?): GalleryFragment.GalleryMedia? = currentList.find { it.media.photo.id == photoId }?.let { item -> if (item.isLocal() || item.atLocal()) item else null }

        internal fun locationOfSelected(): Int {
            val x: Int = currentList.find { it.media.photo.id == selectionTracker.selection.elementAt(0) }?.location ?: GalleryFragment.GalleryMedia.IS_NOT_MEDIA

            for (i in 1 until selectionTracker.selection.size()) {
                if (x == (currentList.find { it.media.photo.id == selectionTracker.selection.elementAt(i) }?.location ?: GalleryFragment.GalleryMedia.IS_NOT_MEDIA)) continue
                else return GalleryFragment.GalleryMedia.IS_BOTH
            }

            return x
        }

        class PhotoKeyProvider(private val adapter: OverviewAdapter): ItemKeyProvider<String>(SCOPE_CACHED) {
            override fun getKey(position: Int): String = adapter.getPhotoId(position)
            override fun getPosition(key: String): Int = adapter.getPhotoPosition(key)
        }
        class PhotoDetailsLookup(private val recyclerView: RecyclerView): ItemDetailsLookup<String>() {
            override fun getItemDetails(e: MotionEvent): ItemDetails<String> {
                recyclerView.findChildViewUnder(e.x, e.y)?.let {
                    return when(val holder = recyclerView.getChildViewHolder(it)) {
                        is MediaViewHolder -> holder.getItemDetails()
                        is OverflowHolder -> if (holder.canSelect()) holder.getItemDetails() else stubItemDetails()
                        else -> stubItemDetails()
                    }
                }
                return stubItemDetails()
            }

            // Default ItemDetailsLookup stub, to avoid clearing selection by clicking the empty area in the list
            private fun stubItemDetails() = object : ItemDetails<String>() {
                override fun getPosition(): Int = Int.MIN_VALUE
                override fun getSelectionKey(): String = ""
            }
        }

        companion object {
            private const val TYPE_MEDIA = 1
            private const val TYPE_OVERFLOW = 2
            const val TYPE_HEADER = 3
            private const val TYPE_FOOTNOTE = 4

            const val DIFF_PAYLOAD_BACKUP_STATE_CHANGED = 1
            const val DIFF_PAYLOAD_MEDIA_COUNT_CHANGED = 2
            const val DIFF_PAYLOAD_LOCATION_CHANGED = 3
        }
    }

    class OverviewDiffCallback : DiffUtil.ItemCallback<GalleryFragment.GalleryMedia>() {
        override fun areItemsTheSame(oldItem: GalleryFragment.GalleryMedia, newItem: GalleryFragment.GalleryMedia): Boolean = oldItem.media.photo.id == newItem.media.photo.id
        override fun areContentsTheSame(oldItem: GalleryFragment.GalleryMedia, newItem: GalleryFragment.GalleryMedia): Boolean =
            if (newItem.isNotMedia()) {
                // Header or Footnote, check for changes of media count saved in property 'height', backup enable state saved in property 'coverBaseLine', no need to check folder size saved in property 'caption' since it changed with media count
                oldItem.media.coverBaseLine == newItem.media.coverBaseLine && oldItem.media.photo.height == newItem.media.photo.height
            } else {
                // Normal media view or Overflow view item, check for archived state saved in property 'location'
                oldItem.location == newItem.location
            }

        override fun getChangePayload(oldItem: GalleryFragment.GalleryMedia, newItem: GalleryFragment.GalleryMedia): Any? {
            return if (newItem.isNotMedia()) {
                when {
                    oldItem.media.coverBaseLine != newItem.media.coverBaseLine -> OverviewAdapter.DIFF_PAYLOAD_BACKUP_STATE_CHANGED
                    oldItem.media.photo.height != newItem.media.photo.height -> OverviewAdapter.DIFF_PAYLOAD_MEDIA_COUNT_CHANGED
                    else -> null
                }
            } else {
                when {
                    oldItem.location != newItem.location -> OverviewAdapter.DIFF_PAYLOAD_LOCATION_CHANGED
                    else -> null
                }
            }
        }
    }

    companion object {
        private const val FOOTNOTE = "\uE83A\uE83A"   // This private character make sure the Trash is at the bottom of folder list

        private const val CONFIRM_DIALOG = "CONFIRM_DIALOG"
        private const val BACKUP_OPTION_DIALOG = "BACKUP_OPTION_DIALOG"
        private const val SHARE_OUT_DIALOG = "SHARE_OUT_DIALOG"

        private const val GALLERY_OVERVIEW_REQUEST_KEY = "GALLERY_OVERVIEW_REQUEST_KEY"
        private const val BACKUP_EXISTING_REQUEST_KEY = "BACKUP_EXISTING_REQUEST_KEY"

        private const val BACKUP_NOT_AVAILABLE = -1
        private const val BACKUP_DISABLED = 0
        private const val BACKUP_ENABLED = 1

        const val LAUNCH_BY_GALLERY = "LAUNCH_BY_GALLERY"
    }
}