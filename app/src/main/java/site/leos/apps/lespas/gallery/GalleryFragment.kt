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

import android.app.Activity
import android.content.ClipData
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.database.ContentObserver
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.Drawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.core.view.MenuProvider
import androidx.exifinterface.media.ExifInterface
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.Album
import site.leos.apps.lespas.album.AlbumRepository
import site.leos.apps.lespas.helper.RemoveOriginalBroadcastReceiver
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.helper.Tools.parcelable
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.publication.NCShareViewModel
import site.leos.apps.lespas.search.SearchFragment
import site.leos.apps.lespas.sync.AcquiringDialogFragment
import site.leos.apps.lespas.sync.Action
import site.leos.apps.lespas.sync.ActionViewModel
import site.leos.apps.lespas.sync.BackupSetting
import site.leos.apps.lespas.sync.DestinationDialogFragment
import site.leos.apps.lespas.sync.ShareReceiverActivity
import java.lang.Long.min
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class GalleryFragment: Fragment() {
    private val actionModel: ActionViewModel by viewModels()
    private val imageLoaderModel: NCShareViewModel by activityViewModels()
    private val galleryModel: GalleryViewModel by viewModels { GalleryViewModelFactory(requireActivity(), imageLoaderModel, actionModel) }

    private lateinit var mediaStoreObserver: ContentObserver
    private lateinit var removeOriginalBroadcastReceiver: RemoveOriginalBroadcastReceiver
    private lateinit var deleteMediaLauncher: ActivityResultLauncher<IntentSenderRequest>
    private lateinit var storagePermissionRequestLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var accessMediaLocationPermissionRequestLauncher: ActivityResultLauncher<String>

    private var selectedUris = arrayListOf<Uri>()
    private var waitingMsg: Snackbar? = null
    private val handler = Handler(Looper.getMainLooper())

    private var archiveMenuItem: MenuItem? = null
    private var pickedMenuItem: MenuItem? = null

    private lateinit var shareOutBackPressedCallback: OnBackPressedCallback

    private lateinit var sp: SharedPreferences

    private val archiveWorksListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (galleryModel.showArchive.value == GalleryViewModel.ARCHIVE_ON) {
            // If archive mode is On, update archive action status accordingly
            val syncActionKey = getString(R.string.sync_status_local_action_pref_key)
            if (key == syncActionKey) {
                try {
                    sp.getString(syncActionKey, "")?.split("``")?.let { action ->
                        // action is String array of: actionId``folderId``folderName``fileId``fileName``timestamp in millisecond
                        if (action.isNotEmpty()) {
                            when (action[0].toInt()) {
                                Action.ACTION_BACKUP_INDIVIDUAL, Action.ACTION_DELETE_FILE_IN_ARCHIVE -> galleryModel.startArchiveLoadingIndicator(GalleryViewModel.REFRESHING_ARCHIVE)
                                Action.ACTION_MOVE_ON_SERVER ->
                                    // action[1] contains ACTION_MOVE_ON_SERVER source folder
                                    if (action[1].startsWith(Tools.getArchiveBase(requireContext()))) galleryModel.startArchiveLoadingIndicator(GalleryViewModel.REFRESHING_ARCHIVE)
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mediaStoreObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                galleryModel.reloadDeviceMediaStore()
            }
        }

        accessMediaLocationPermissionRequestLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
        storagePermissionRequestLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

            var isGranted = true
            for(result in results) isGranted = isGranted && result.value
            when {
                isGranted -> {
                    // Explicitly request ACCESS_MEDIA_LOCATION permission
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) accessMediaLocationPermissionRequestLauncher.launch(android.Manifest.permission.ACCESS_MEDIA_LOCATION)

                    arguments?.parcelable<Uri>(ARGUMENT_URI)?.let { uri ->
                        if (uri == Uri.EMPTY) {
                            galleryModel.asGallery(delayStart = false, order = "DESC", isPicker = true)
                            childFragmentManager.beginTransaction().replace(R.id.container_child_fragment, GalleryOverviewFragment(), GalleryOverviewFragment::class.java.canonicalName).addToBackStack(null).commit()
                        } else {
                            getFolderFromUri(uri)?.let {
                                galleryModel.asGallery(delayStart = false, order = "ASC")
                                // Launched as viewer from system file manager, set list sort order to date ascending
                                galleryModel.setCurrentPhotoId(it.second)
                                childFragmentManager.beginTransaction().replace(R.id.container_child_fragment, GallerySlideFragment.newInstance(it.first), GallerySlideFragment::class.java.canonicalName).addToBackStack(null).commit()
                            } ?: run {
                                // Can't extract folder name from Uri, launched as a single file viewer
                                galleryModel.asSingleFileViewer(uri, requireContext())
                                childFragmentManager.beginTransaction().replace(R.id.container_child_fragment, GallerySlideFragment.newInstance(uri.toString()), GallerySlideFragment::class.java.canonicalName).addToBackStack(null).commit()
                            }
                        }
                    } ?: run {
                        // Launcher as Gallery within our own app, set list sort order to date descending
                        galleryModel.asGallery(delayStart = false, order = "DESC")
                        childFragmentManager.beginTransaction().replace(R.id.container_child_fragment, GalleryOverviewFragment(), GalleryOverviewFragment::class.java.canonicalName).addToBackStack(null).commit()
                    }
                }
                else -> quit()
            }
        }

        // Removing item from MediaStore for Android 11 or above
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) deleteMediaLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {}
/*
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) deleteMediaLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) { galleryModel.removeFromArchive() }
        }
*/

        // After media files moved to album
        removeOriginalBroadcastReceiver = RemoveOriginalBroadcastReceiver { delete ->
            if (delete) {
                mutableListOf<String>().run {
                    selectedUris.forEach { uri ->
                        if (uri.scheme == ARCHIVE_SCHEME) uri.getQueryParameter("fileid")?.let { add(it) }
                        else add(uri.toString())
                    }
                    galleryModel.remove(this, sp.getBoolean(getString(R.string.sync_deletion_perf_key), false))
                }
            }
        }

        requireActivity().onBackPressedDispatcher.addCallback(this, object: OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (childFragmentManager.backStackEntryCount > 1) childFragmentManager.popBackStack()
                else quit()
            }
        })

        shareOutBackPressedCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                waitingMsg?.let {
                    if (it.isShownOrQueued) {
                        imageLoaderModel.cancelShareOut()
                        galleryModel.setIsPreparingShareOut(false)
                        it.dismiss()
                    }
                }
                isEnabled = false
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(this, shareOutBackPressedCallback)

        childFragmentManager.addOnBackStackChangedListener {
            if (childFragmentManager.backStackEntryCount == 0) {
                // When all medias deleted
                Toast.makeText(requireContext(), getString(R.string.msg_no_media_found), Toast.LENGTH_SHORT).show()
                //finish()
            }
        }

        sp = PreferenceManager.getDefaultSharedPreferences(requireContext())
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? = inflater.inflate(R.layout.fragment_container, container, false)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        savedInstanceState ?: run { storagePermissionRequestLauncher.launch(Tools.getStoragePermissionsArray()) }
        requireActivity().contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, mediaStoreObserver)
        requireActivity().contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, mediaStoreObserver)
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(removeOriginalBroadcastReceiver, IntentFilter(AcquiringDialogFragment.BROADCAST_REMOVE_ORIGINAL))

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    galleryModel.additions.collect { ids ->
                        selectedUris = arrayListOf<Uri>().apply {
                            // Prepare uri of media for the convenience of displaying photo thumbnail in DestinationDialogFragment, especially those of remote only medias
                            ids.forEach { id ->
                                if (id.startsWith("content")) add(id.toUri())
                                else galleryModel.getPhotoById(id)?.let {
                                    // TODO caption property value is file size in this case
                                    add(
                                        "${ARCHIVE_SCHEME}://${it.remotePath}/${it.photo.name}?fileid=${it.photo.id}&datetaken=${
                                            it.photo.dateTaken.toInstant(ZoneOffset.UTC).toEpochMilli()
                                        }&mimetype=${it.photo.mimeType}&width=${it.photo.width}&height=${it.photo.height}&orientation=${it.photo.orientation}&lat=${it.photo.latitude}&long=${it.photo.longitude}&alt=${it.photo.altitude}&bearing=${it.photo.bearing}".toUri()
                                    )
                                }
                            }
                        }
                        // TODO revisit canWrite condition, should be always yes now
                        if (parentFragmentManager.findFragmentByTag(TAG_DESTINATION_DIALOG) == null) DestinationDialogFragment.newInstance(DESTINATION_DIALOG_REQUEST_KEY, selectedUris, galleryModel.getPhotoById(ids[0])?.photo?.lastModified != LocalDateTime.MAX)
                            .show(parentFragmentManager, if (tag == TAG_FROM_LAUNCHER) TAG_FROM_LAUNCHER else TAG_DESTINATION_DIALOG)
                    }
                }
                launch {
                    galleryModel.deletions.collect { deletions -> if (deletions.isNotEmpty()) removeFilesSAF(deletions) }
                }
                launch {
                    galleryModel.restorations.collect { restorations ->
                        if (restorations.isNotEmpty()) {
                            val uris = arrayListOf<Uri>().apply { restorations.forEach { add(it.toUri()) } }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) deleteMediaLauncher.launch(IntentSenderRequest.Builder(MediaStore.createTrashRequest(requireContext().contentResolver, uris, false)).setFillInIntent(null).build())
                        }
                    }
                }
                launch {
                    galleryModel.emptyTrash.collect { ids ->
                        if (ids.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            val uris = arrayListOf<Uri>().apply { ids.forEach { add(it.toUri()) } }
                            deleteMediaLauncher.launch(IntentSenderRequest.Builder(MediaStore.createDeleteRequest(requireActivity().contentResolver, uris)).setFillInIntent(null).build())
                        }
                    }
                }
                launch {
                    galleryModel.strippingEXIF.collect {
                        waitingMsg = Tools.getPreparingSharesSnackBar(requireView()) {
                            imageLoaderModel.cancelShareOut()
                            shareOutBackPressedCallback.isEnabled = false
                            galleryModel.setIsPreparingShareOut(false)
                        }

                        // Show a SnackBar if it takes too long (more than 500ms) preparing shares
                        handler.postDelayed({
                            waitingMsg?.show()
                            shareOutBackPressedCallback.isEnabled = true
                        }, 500)
                    }
                }
                launch {
                    imageLoaderModel.shareOutUris.collect { uris ->
                        handler.removeCallbacksAndMessages(null)
                        if (waitingMsg?.isShownOrQueued == true) {
                            waitingMsg?.dismiss()
                            shareOutBackPressedCallback.isEnabled = false
                        }

                        if (uris.isNotEmpty()) {
                            val cr = requireActivity().contentResolver

                            when(galleryModel.getShareType()) {
                                GalleryViewModel.SHARE_NORMAL -> {
                                    val clipData = ClipData.newUri(cr, "", uris[0])
                                    for (i in 1 until uris.size) {
                                        if (isActive) {
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) clipData.addItem(cr, ClipData.Item(uris[i]))
                                            else clipData.addItem(ClipData.Item(uris[i]))
                                        }
                                    }
                                    startActivity(Intent.createChooser(Intent().apply {
                                        if (uris.size > 1) {
                                            action = Intent.ACTION_SEND_MULTIPLE
                                            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                                        } else {
                                            // If sharing only one picture, use ACTION_SEND instead, so that other apps which won't accept ACTION_SEND_MULTIPLE will work
                                            action = Intent.ACTION_SEND
                                            putExtra(Intent.EXTRA_STREAM, uris[0])
                                        }
                                        type = requireContext().contentResolver.getType(uris[0]) ?: "image/*"
                                        if (type!!.startsWith("image")) type = "image/*"
                                        this.clipData = clipData
                                        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                                        putExtra(ShareReceiverActivity.KEY_SHOW_REMOVE_OPTION, false)
                                    }, null))

                                    // IDs of photos meant to be deleted are saved in GalleryViewModel
                                    galleryModel.deleteAfterShared()
                                }
                                GalleryViewModel.SHARE_USE_AS -> {
                                    startActivity(Intent.createChooser(Intent().apply {
                                        action = Intent.ACTION_ATTACH_DATA
                                        requireContext().contentResolver.getType(uris[0])?.apply {
                                            setDataAndType(uris[0], this)
                                            putExtra("mimeType", this)
                                        }
                                        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    }, null))
                                }
                                GalleryViewModel.SHARE_AS_PICK_RESULT -> {
                                    requireActivity().setResult(Activity.RESULT_OK, Intent().setData(uris[0]).setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                                    requireActivity().finish()
                                }
                            }
                        }

                        galleryModel.setIsPreparingShareOut(false)
                    }
                }.invokeOnCompletion {
                    handler.removeCallbacksAndMessages(null)
                    if (waitingMsg?.isShownOrQueued == true) {
                        waitingMsg?.dismiss()
                        shareOutBackPressedCallback.isEnabled = false
                    }
                    galleryModel.setIsPreparingShareOut(false)
                }
                launch {
                    galleryModel.medias.collect { localMedias ->
                        localMedias?.let {
                            if (localMedias.size == 1 && localMedias[0].media.photo.lastModified == LocalDateTime.MAX) {
                                requireActivity().contentResolver.unregisterContentObserver(mediaStoreObserver)
                                requireActivity().contentResolver.unregisterContentObserver(mediaStoreObserver)
                            }
                        }
                    }
                }
            }
        }

        parentFragmentManager.setFragmentResultListener(DESTINATION_DIALOG_REQUEST_KEY, viewLifecycleOwner) { _, result ->
            // Inform GallerySliderFragment
            childFragmentManager.findFragmentByTag(GallerySlideFragment::class.java.canonicalName)?.let {
                childFragmentManager.setFragmentResult(DestinationDialogFragment.KEY_REMOVE_ORIGINAL, bundleOf(DestinationDialogFragment.KEY_REMOVE_ORIGINAL to result.getBoolean(DestinationDialogFragment.KEY_REMOVE_ORIGINAL)))
            }

            result.parcelable<Album>(DestinationDialogFragment.KEY_TARGET_ALBUM)?.let { targetAlbum ->
                if (targetAlbum.id == Album.JOINT_ALBUM_ID) Snackbar.make(requireView(), getString(R.string.msg_joint_album_not_updated_locally), Snackbar.LENGTH_LONG).show()

                if (parentFragmentManager.findFragmentByTag(TAG_ACQUIRING_DIALOG) == null) {
                    AcquiringDialogFragment.newInstance(
                        ArrayList(selectedUris.map { mediaUri ->
                            if (mediaUri.scheme != ARCHIVE_SCHEME) {
                                // When media item is IS_BOTH, try using the remote version for copy/move, saving network traffic of re-uploading it again
                                galleryModel.getGalleryMediaById(mediaUri.toString())?.let { galleryMedia ->
                                    if (galleryMedia.atRemote()) galleryMedia.media.let {
                                        "${ARCHIVE_SCHEME}://${it.remotePath}/${it.photo.name}?fileid=${it.photo.id}&datetaken=${it.photo.dateTaken.toInstant(ZoneOffset.UTC).toEpochMilli()}&mimetype=${it.photo.mimeType}&width=${it.photo.width}&height=${it.photo.height}&orientation=${it.photo.orientation}&lat=${it.photo.latitude}&long=${it.photo.longitude}&alt=${it.photo.altitude}&bearing=${it.photo.bearing}".toUri()
                                    } else mediaUri
                                } ?: mediaUri
                            } else mediaUri
                        }),
                        targetAlbum, result.getBoolean(DestinationDialogFragment.KEY_REMOVE_ORIGINAL)
                    ).show(parentFragmentManager, TAG_ACQUIRING_DIALOG)
                }
            }
        }

        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(if (galleryModel.isPicker()) R.menu.gallery_picker_menu else R.menu.gallery_menu, menu)
                archiveMenuItem = menu.findItem(R.id.option_menu_archive)
                pickedMenuItem = menu.findItem(R.id.option_menu_picked)
            }

            override fun onPrepareMenu(menu: Menu) {
                super.onPrepareMenu(menu)

                viewLifecycleOwner.lifecycleScope.launch {
                    repeatOnLifecycle(Lifecycle.State.RESUMED) {
                        launch {
                            // Archive toolbar icon management
                            galleryModel.showArchive.collect { currentState ->
                                archiveMenuItem?.let { menuItem ->
                                    when(currentState) {
                                        GalleryViewModel.REFRESHING_ARCHIVE, GalleryViewModel.REFRESHING_GALLERY -> {
                                            menuItem.setIcon(R.drawable.ic_baseline_archive_refreshing_animated_24)
                                            (menuItem.icon as? AnimatedVectorDrawable)?.start()
                                        }
                                        GalleryViewModel.ARCHIVE_ON -> {
                                            menuItem.setIcon(R.drawable.ic_baseline_archive_24)
                                            sp.registerOnSharedPreferenceChangeListener(archiveWorksListener)
                                        }
                                        GalleryViewModel.ARCHIVE_OFF -> {
                                            menuItem.setIcon(R.drawable.ic_baseline_archive_off_24)
                                            sp.unregisterOnSharedPreferenceChangeListener(archiveWorksListener)
                                        }
                                    }
                                }
                            }
                        }
                        // Toggle Picked toolbar icon visibility
                        launch { galleryModel.pickedId.collect { pickedMenuItem?.isVisible = it.isNotEmpty() }}
                    }
                }
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                return when(menuItem.itemId) {
                    R.id.option_menu_archive -> {
                        galleryModel.toggleArchiveShownState()
                        true
                    }
                    R.id.option_menu_archive_forced_refresh -> {
                        galleryModel.toggleArchiveShownState(forcedRefresh = true)
                        true
                    }
                    R.id.option_menu_search_gallery -> {
                        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                            val noAlbum = AlbumRepository(requireActivity().application).getAlbumTotal() == 0
                            withContext(Dispatchers.Main) {
                                parentFragmentManager.beginTransaction().replace(R.id.container_root, SearchFragment.newInstance(noAlbum, SearchFragment.SEARCH_GALLERY), SearchFragment::class.java.canonicalName).addToBackStack(null).commit()
                            }
                        }
                        true
                    }
                    R.id.option_menu_picked -> {
                        galleryModel.shareOut(listOf(galleryModel.pickedId.value), strip = true, lowResolution = false, removeAfterwards = false, shareType = GalleryViewModel.SHARE_AS_PICK_RESULT)
                        true
                    }
                    else -> false
                }
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)

        savedInstanceState?.let {
            if (it.getBoolean(KEY_SHARING_OUT)) {
                waitingMsg = Tools.getPreparingSharesSnackBar(requireView()) {
                    imageLoaderModel.cancelShareOut()
                    shareOutBackPressedCallback.isEnabled = false
                    galleryModel.setIsPreparingShareOut(false)
                }
                waitingMsg?.run {
                    show()
                    shareOutBackPressedCallback.isEnabled = true
                }
            }
        } ?: run {
            if (sp.getBoolean(getString(R.string.show_archive_perf_key), true)) galleryModel.toggleArchiveShownState()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_SHARING_OUT, waitingMsg?.isShownOrQueued == true)
    }

    override fun onDestroyView() {
        requireActivity().contentResolver.unregisterContentObserver(mediaStoreObserver)
        requireActivity().contentResolver.unregisterContentObserver(mediaStoreObserver)
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(removeOriginalBroadcastReceiver)
        sp.unregisterOnSharedPreferenceChangeListener(archiveWorksListener)

        super.onDestroyView()
    }

    private fun quit() {
        if (tag == TAG_FROM_LAUNCHER) requireActivity().finish() else parentFragmentManager.popBackStack()
    }

    private fun removeFilesSAF(deletions: List<Uri>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val trashItems = arrayListOf<Uri>()
            val deleteItems = arrayListOf<Uri>()
            deletions.forEach {
                if (galleryModel.getVolumeName(it.lastPathSegment!!) == MediaStore.VOLUME_EXTERNAL_PRIMARY) trashItems.add(it)
                else deleteItems.add(it)
            }
            if (deleteItems.isNotEmpty()) deleteMediaLauncher.launch(IntentSenderRequest.Builder(MediaStore.createDeleteRequest(requireContext().contentResolver, deleteItems)).setFillInIntent(null).build())
            if (trashItems.isNotEmpty()) deleteMediaLauncher.launch(IntentSenderRequest.Builder(MediaStore.createTrashRequest(requireContext().contentResolver, trashItems, true)).setFillInIntent(null).build())
        }
        // The following will be called in deleteMediaLauncher's callback
        //galleryModel.syncDeletionToArchive()
        //galleryModel.setNextInLine()
    }

    private fun getDocumentId(contentResolver: ContentResolver, externalStorageUri: Uri, pathColumn: String, folder: String, displayName: String): Pair<String, String>? {
        var id: String? = null
        val projection: Array<String>
        val selection: String
        var relativePath = folder

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                pathColumn
            )
            selection = "(${pathColumn} LIKE '${folder}%') AND (${MediaStore.Files.FileColumns.DISPLAY_NAME} = '${displayName}')"
        } else {
            projection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                pathColumn,
            )
            selection = "(${pathColumn} LIKE '${STORAGE_EMULATED}_/${folder}%') AND (${pathColumn} LIKE '%${displayName}')"
        }

        contentResolver.query(externalStorageUri, projection, selection, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                id = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID))

                // Get relative path when working with document provider "com.android.providers.downloads.documents"
                if (folder == "Download") cursor.getString(cursor.getColumnIndexOrThrow(pathColumn)).let {
                    relativePath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) it.dropLast(1) else it.substringBeforeLast('/').substringAfter(STORAGE_EMULATED).substringAfter('/')
                }
            }
        }

        return id?.let { Pair("${relativePath}/", id!!) }
    }

    private fun getFolderFromUri(uri: Uri): Pair<String, String>? {
        val pathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Files.FileColumns.RELATIVE_PATH else MediaStore.Files.FileColumns.DATA
        val externalStorageUri = MediaStore.Files.getContentUri("external")
        val contentResolver = requireActivity().contentResolver

        return try {
            when(uri.authority) {
                "com.android.externalstorage.documents" -> {
                    getDocumentId(contentResolver, externalStorageUri, pathColumn, uri.lastPathSegment!!.substringAfter(':').substringBeforeLast('/'), uri.lastPathSegment!!.substringAfterLast('/'))
                }
                "com.android.providers.downloads.documents" -> {
                    // Download provider does not provide common _ID, we have to find out the display name first, then use Storage provider to do the rest
                    var filename: String? = null
                    val projection = arrayOf(
                        MediaStore.Files.FileColumns.DISPLAY_NAME,
                        MediaStore.Files.FileColumns.DOCUMENT_ID,
                    )
                    contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) filename = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME))
                    }

                    filename?.let { getDocumentId(contentResolver, externalStorageUri, pathColumn, "Download", filename!!) }
                }
                "com.android.providers.media.documents" -> {
                    var folderName: String? = null
                    val id = uri.lastPathSegment!!.substringAfterLast(':')
                    val projection = arrayOf(
                        MediaStore.Files.FileColumns._ID,
                        pathColumn,
                    )
                    val selection = "${MediaStore.Files.FileColumns._ID} = $id"

                    contentResolver.query(externalStorageUri, projection, selection, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) folderName = cursor.getString(cursor.getColumnIndexOrThrow(pathColumn))
                    }

                    folderName?.let { Pair(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) folderName!! else "${folderName!!.substringAfter(STORAGE_EMULATED).substringAfter("/").substringBeforeLast('/')}/", id) }
                }
                "media" -> {
                    val projection = arrayOf(
                        pathColumn,
                    )
                    contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            return Pair(
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) cursor.getString(0).substringBefore('/') else cursor.getString(0).substringAfter(STORAGE_EMULATED).substringAfter('/').substringBeforeLast('/') + "/",
                                uri.toString().substringAfterLast('/')
                            )
                        }
                    }
                    null
                }
                "eu.siacs.conversations.files" -> {
                    getDocumentId(contentResolver, externalStorageUri, pathColumn, uri.path!!.substringBeforeLast('/').substringAfter("/external/"), uri.lastPathSegment!!)
                }
                else -> null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    class GalleryViewModelFactory(private val context: Activity, private val imageModel: NCShareViewModel, private val actionModel: ActionViewModel): ViewModelProvider.NewInstanceFactory() {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            // TODO: won't get correct spanCount in later screen rotations
            val spanCount = context.resources.getInteger(R.integer.cameraroll_grid_span_count)
            return modelClass.cast(GalleryViewModel(
                context.contentResolver, imageModel, actionModel,
                { try { Toast.makeText(context, context.getString(R.string.msg_archive_empty), Toast.LENGTH_LONG).show() } catch (_: Exception) {} },
                Tools.getPlayMarkDrawable(context, 0.32f / spanCount),
                Tools.getSelectedMarkDrawable(context, 0.25f / spanCount),
                Tools.getPanoramaMarkDrawable(context, 0.32f / spanCount),
                Tools.getLocalRoot(context),
            ))!!
        }
    }
    class GalleryViewModel(
        private val cr: ContentResolver, private val imageModel: NCShareViewModel, private val actionModel: ActionViewModel, private val archiveEmptyToast: () -> Unit,
        private val playMarkDrawable: Drawable, private val selectedMarkDrawable: Drawable, private val panoramaMarkDrawable: Drawable,
        private val localRoot: String
    ): ViewModel() {
        private var isPicker = false
        private var defaultSortOrder = "DESC"
        private var loadJob: Job? = null
        private var autoRemoveDone = false
        private val model = Tools.getDeviceModel()
        private var archiveSnapshotFileObserver: FileObserver

        private val _showArchive = MutableStateFlow(ARCHIVE_OFF)
        val showArchive: StateFlow<Int> = _showArchive
        private val _pickedId = MutableStateFlow("")
        val pickedId: StateFlow<String> = _pickedId

        private val _local = MutableStateFlow<List<GalleryMedia>>(mutableListOf())
        private val _medias = MutableStateFlow<List<GalleryMedia>?>(null)
        val medias: StateFlow<List<GalleryMedia>?> = _medias.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)
        val trash: StateFlow<List<GalleryMedia>?> = _local.map { it.filter { item -> item.folder == TRASH_FOLDER }}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)
        fun mediasInFolder(folderName: String): StateFlow<List<GalleryMedia>?> = _medias.map { it?.filter { item -> item.folder == folderName }}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

        init {
            viewModelScope.launch(Dispatchers.IO) {
                launch {
                    combine(_local, imageModel.archive) { local, archiveMedia ->
                        val localMedia = local.filter { it.folder != TRASH_FOLDER }

                        if (_showArchive.value != ARCHIVE_OFF && _showArchive.value != REFRESHING_GALLERY) {
                            archiveMedia?.let {
                                if (archiveMedia.isNotEmpty()) {
                                    // Deep copy to create a brand new list for combining local and archive items
                                    val combinedList = archiveMedia.map { it.copy() }.toMutableList()

                                    if (localMedia.isNotEmpty()) {
                                        // Create a map of archive items from this device for matching local medias later
                                        val searchMap = combinedList.filter { item -> item.volume == model && item.media.photo.lastModified >= localMedia.last().media.photo.lastModified }.associateBy { item -> item.fullPath + item.media.photo.name }

                                        localMedia.forEach { localItem ->
                                            searchMap[localItem.fullPath + localItem.media.photo.name]?.let { existed ->
                                                // If local media existed in archive list, change properties to match local item
                                                existed.location = GalleryMedia.IS_BOTH
                                                //existed.remoteFileId = existed.media.photo.id     // remoteFileId set when archive being refreshed in NCShareViewModel.refreshArchive()
                                                existed.volume = localItem.volume
                                                existed.media.photo.albumId = FROM_DEVICE_GALLERY
                                                existed.media.photo.id = localItem.media.photo.id
                                                existed.media.photo.eTag = Photo.ETAG_NOT_YET_UPLOADED
                                            } ?: run {
                                                // If local media not existed in archive list, add it's deep copy to combined list, change property 'location' to facilitate list adapter diff detection in GalleryOverviewFragment, GalleryFolderViewFragment
                                                combinedList.add(localItem.copy(location = GalleryMedia.IS_LOCAL))
                                            }
                                        }

                                        combinedList.sortedByDescending { item -> item.media.photo.lastModified }
                                    } else combinedList
                                } else {
                                    // No content in archive yet
                                    archiveEmptyToast()
                                    localMedia.map { it.copy() }.toList()
                                }
                            } ?: run {
                                // Archive refreshing job in NCShareViewModel emit NULL list when the job is running
                                _showArchive.value = REFRESHING_ARCHIVE
                                localMedia
                            }
                        } else localMedia
                    }.collect { result -> _medias.emit(result) }
                }
            }

            // Start observing archive snapshot file, which will be updated after new gallery image been backup to archive or files been removed from archive, snapshot file is updated by SyncAdapter after those actions finished successfully
            @Suppress("DEPRECATION")
            archiveSnapshotFileObserver = object : FileObserver("${localRoot}/${NCShareViewModel.ARCHIVE_SNAPSHOT_FILE}", CLOSE_WRITE) {
                override fun onEvent(p0: Int, p1: String?) {
                    if (_showArchive.value == ARCHIVE_ON || _showArchive.value == REFRESHING_ARCHIVE) {
                        startArchiveLoadingIndicator(REFRESHING_ARCHIVE)
                        imageModel.refreshArchive(false)
                    }
                }
            }.apply { startWatching() }
        }

        override fun onCleared() {
            loadJob?.cancel()
            imageModel.stopRefreshingArchive(false)
            archiveSnapshotFileObserver.stopWatching()
            super.onCleared()
        }

        fun reloadDeviceMediaStore() {
            loadJob?.cancel()
            asGallery(delayStart = true, order = defaultSortOrder)
        }

        fun asGallery(delayStart: Boolean, order: String, isPicker: Boolean = false) {
            this.isPicker = isPicker
            defaultSortOrder = order
            loadJob = viewModelScope.launch(Dispatchers.IO) {
                // Delay for 500ms when reload, because content observer will receive multiple notifications of change for a single file operation, like for example, creating a new file will result in 3 change notifications, including 1 NOTIFY_INSERT and 2 NOTIFY_UPDATE
                if (delayStart) delay(300)
                ensureActive()

                val localMedias = mutableListOf<GalleryMedia>()

                val contentUri = MediaStore.Files.getContentUri("external")
                val pathSelection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Files.FileColumns.RELATIVE_PATH else MediaStore.Files.FileColumns.DATA
                val dateSelection = "datetaken"     // MediaStore.MediaColumns.DATE_TAKEN, hardcoded here since it's only available in Android Q or above
                var projection = arrayOf(
                    MediaStore.Files.FileColumns._ID,
                    pathSelection,
                    dateSelection,
                    MediaStore.Files.FileColumns.DATE_ADDED,
                    MediaStore.Files.FileColumns.MEDIA_TYPE,
                    MediaStore.Files.FileColumns.MIME_TYPE,
                    MediaStore.Files.FileColumns.DISPLAY_NAME,
                    MediaStore.Files.FileColumns.SIZE,
                    MediaStore.Files.FileColumns.WIDTH,
                    MediaStore.Files.FileColumns.HEIGHT,
                    "orientation",                  // MediaStore.Files.FileColumns.ORIENTATION, hardcoded here since it's only available in Android Q or above
                )
                val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}"

                // Sort gallery items by DATE_ADDED instead of DATE_TAKEN to address situation like a photo shot a long time ago being added to gallery recently and be placed in the bottom of the gallery list
                val sortOrder = "${MediaStore.Files.FileColumns.DATE_ADDED} $order"
                val queryBundle = Bundle()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    projection = projection.plus(arrayOf(MediaStore.Files.FileColumns.VOLUME_NAME, MediaStore.Files.FileColumns.IS_TRASHED))
                    queryBundle.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                    queryBundle.putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder)
                    queryBundle.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
                }

                try {
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) cr.query(contentUri, projection, queryBundle, null) else cr.query(contentUri, projection, selection, null, sortOrder))?.use { cursor ->
                        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                        val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                        val pathColumn = cursor.getColumnIndexOrThrow(pathSelection)
                        val dateTakenColumn = cursor.getColumnIndexOrThrow(dateSelection)
                        val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
                        val typeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                        val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                        val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.WIDTH)
                        val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.HEIGHT)
                        val orientationColumn = cursor.getColumnIndexOrThrow("orientation")    // MediaStore.Files.FileColumns.ORIENTATION, hardcoded here since it's only available in Android Q or above
                        val defaultZone = ZoneId.systemDefault()
                        var mimeType: String
                        var dateAdded: Long
                        var dateTaken: Long
                        var relativePath: String

                        var volumeColumn = 0
                        var isTrashColumn = 0
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            volumeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.VOLUME_NAME)
                            isTrashColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.IS_TRASHED)
                        }

                        cursorLoop@ while (cursor.moveToNext()) {
                            ensureActive()
                            //if ((strict) && (cursor.getString(cursor.getColumnIndexOrThrow(pathSelection)) ?: folder).substringAfter(folder).contains('/')) continue

                            // Insert media
                            mimeType = cursor.getString(typeColumn)
                            // Make sure image type is supported
                            if (mimeType.startsWith("image") && mimeType.substringAfter("image/", "") !in Tools.SUPPORTED_PICTURE_FORMATS) continue@cursorLoop
                            if (cursor.getLong(sizeColumn) == 0L) continue@cursorLoop

/*
                            date = cursor.getLong(dateColumn)
                            // Sometimes dateTaken is not available from system, use DATE_ADDED instead, DATE_ADDED does not has nano adjustment
                            if (date == 0L) date = cursor.getLong(dateAddedColumn) * 1000
*/
                            dateAdded = cursor.getLong(dateAddedColumn)
                            dateTaken = cursor.getLong(dateTakenColumn)
                            // Sometimes dateTaken is not available from system, use DATE_ADDED instead, DATE_ADDED does not has nano adjustment
                            if (dateTaken == 0L) dateTaken = dateAdded * 1000

                            // TODO might need to put this type checking routine to background
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                try {
                                    if (mimeType.contains("webp")) {
                                        // Set my own image/awebp mimetype for animated WebP
                                        ensureActive()
                                        if (ImageDecoder.decodeDrawable(ImageDecoder.createSource(cr, ContentUris.withAppendedId(contentUri, cursor.getString(idColumn).toLong()))) is AnimatedImageDrawable) mimeType = "image/awebp"
                                    }
                                    if (mimeType.contains("gif")) {
                                        // Set my own image/agif mimetype for animated GIF
                                        ensureActive()
                                        if (ImageDecoder.decodeDrawable(ImageDecoder.createSource(cr, ContentUris.withAppendedId(contentUri, cursor.getString(idColumn).toLong()))) is AnimatedImageDrawable) mimeType = "image/agif"
                                    }
                                } catch (_: Exception) { }
                            }

                            relativePath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) cursor.getString(pathColumn) else cursor.getString(pathColumn).substringAfter(STORAGE_EMULATED).substringAfter("/").substringBeforeLast('/') + "/"
                            localMedias.add(
                                GalleryMedia(
                                    GalleryMedia.IS_IN_GALLERY,
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && cursor.getInt(isTrashColumn) == 1) TRASH_FOLDER else relativePath.substringBefore('/'),
                                    NCShareViewModel.RemotePhoto(
                                        Photo(
                                            id = ContentUris.withAppendedId(if (mimeType.startsWith("image")) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cursor.getString(idColumn).toLong()).toString(),
                                            albumId = FROM_DEVICE_GALLERY,
                                            name = cursor.getString(nameColumn) ?: "",
                                            // Use system default zone for time display, sorting and grouping by date in Gallery list
                                            dateTaken = LocalDateTime.ofInstant(Instant.ofEpochMilli(dateTaken), defaultZone),          // DATE_TAKEN has nano adjustment
                                            lastModified = LocalDateTime.ofInstant(Instant.ofEpochSecond(dateAdded), defaultZone),      // DATE_ADDED does not have nano adjustment
                                            width = cursor.getInt(widthColumn),
                                            height = cursor.getInt(heightColumn),
                                            mimeType = mimeType,
                                            caption = cursor.getString(sizeColumn),               // Saving photo size value in caption property as String to avoid integer overflow
                                            orientation = cursor.getInt(orientationColumn),       // Saving photo orientation value in orientation property, keep original orientation, other fragments will handle the rotation, TODO video length?
                                        ),
                                        remotePath = "",    // Local media
                                        coverBaseLine = 0,  // Backup is disable by default
                                    ),
                                    cursor.getString(volumeColumn),
                                    relativePath,
                                    relativePath.dropLast(1).substringAfterLast('/'),
                                )
                            )
                        }
                    }
                } catch (_: Exception) { }

                // List is now sorted when querying the content store
                //ensureActive()
                //localMedias.sortWith(compareBy<LocalMedia, String>(Collator.getInstance().apply { strength = Collator.PRIMARY }) { it.folder }.thenByDescending { it.media.photo.dateTaken })

                _local.emit(localMedias)
            }.apply { invokeOnCompletion { loadJob = null }}
        }

        fun asSingleFileViewer(uri: Uri, ctx: Context) {
            loadJob?.cancel()

            val mimeType = cr.getType(uri)?.let { Intent.normalizeMimeType(it) } ?: run { MimeTypeMap.getSingleton().getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(uri.toString()).lowercase()) ?: Photo.DEFAULT_MIMETYPE }
            var filename = ""
            var size = 0
            when (uri.scheme) {
                "content" -> {
                    try {
                        cr.query(uri, null, null, null, null)?.use { cursor ->
                            cursor.moveToFirst()
                            try { cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))?.let { filename = it } } catch (_: IllegalArgumentException) {}
                            try { cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE))?.let { size = it.toInt() }} catch (_: Exception) {}
                        }
                    } catch (_: Exception) {}
                }
                "file" -> uri.path?.let { filename = it.substringAfterLast('/') }
            }

            var metadataRetriever: MediaMetadataRetriever? = null
            var exifInterface: ExifInterface? = null
            if (mimeType.startsWith("video/")) metadataRetriever = try { MediaMetadataRetriever().apply { setDataSource(ctx, uri) }} catch (e: SecurityException) { null } catch (e: RuntimeException) { null }
            else if (Tools.hasExif(mimeType)) try { exifInterface = cr.openInputStream(uri)?.use { ExifInterface(it) }} catch (_: Exception) {} catch (_: OutOfMemoryError) {}
            val photo = Tools.getPhotoParams(metadataRetriever, exifInterface,"", mimeType, filename, keepOriginalOrientation = true, uri = uri, cr = cr).copy(
                id = uri.toString(),                // fileUri shared in as photo's id in Camera Roll album
                albumId = FROM_DEVICE_GALLERY,
                name = filename,
                caption = size.toString(),          // Store file size in property caption
            )
            metadataRetriever?.release()

            uri.toString().let { uriString ->
                setCurrentPhotoId(uriString)
                _medias.value = listOf(GalleryMedia(GalleryMedia.IS_LOCAL, uriString, NCShareViewModel.RemotePhoto(photo), "", uriString))
            }
        }

        fun isPicker() = isPicker
        fun setPickedId(newId: String) { _pickedId.value = newId }

        private fun isArchiveOff(): Boolean = _showArchive.value != ARCHIVE_ON
        fun toggleArchiveShownState(forcedRefresh: Boolean = false) {
            if (forcedRefresh) _showArchive.value = ARCHIVE_OFF
            if (_showArchive.value == ARCHIVE_OFF || _showArchive.value == REFRESHING_GALLERY) {
                _showArchive.value = REFRESHING_ARCHIVE
                imageModel.refreshArchive(forcedRefresh)
            } else {
                _showArchive.value = REFRESHING_GALLERY
                imageModel.stopRefreshingArchive(true)
            }
        }

        // TODO auto remove on Android 11
        @RequiresApi(Build.VERSION_CODES.S)
        fun autoRemove(activity: Activity, backSettings: List<BackupSetting>) {
            if (autoRemoveDone) return

            if (MediaStore.canManageMedia(activity)) viewModelScope.launch(Dispatchers.IO) {
                val pathSelection = MediaStore.Files.FileColumns.RELATIVE_PATH
                val projection  = arrayOf(
                    MediaStore.Files.FileColumns._ID,
                    pathSelection,
                    MediaStore.Files.FileColumns.DATE_ADDED,
                    MediaStore.Files.FileColumns.MEDIA_TYPE,
                )

                backSettings.forEach {
                    if (it.autoRemove > 0) {
                        val selection = "(${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})" + " AND " +
                                "($pathSelection LIKE '${it.folder}%')" + " AND " + "(${MediaStore.Files.FileColumns.DATE_ADDED} < ${min(System.currentTimeMillis() / 1000 - it.autoRemove * 86400L, it.lastBackup)})"  // DATE_ADDED is in second

                        val deletion = arrayListOf<Uri>()
                        cr.query(MediaStore.Files.getContentUri("external"), projection, selection, null, null)?.use { cursor ->
                            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                            val pathColumn = cursor.getColumnIndexOrThrow(pathSelection)
                            val typeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
                            var subFolder: String

                            while (cursor.moveToNext()) {
                                subFolder = cursor.getString(pathColumn).substringAfter("${it.folder}/").substringBefore("/")
                                if (subFolder.isEmpty() || subFolder !in it.exclude)
                                    deletion.add(ContentUris.withAppendedId(if (cursor.getInt(typeColumn) == MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cursor.getLong(idColumn)))
                            }
                        }
                        if (deletion.isNotEmpty()) activity.startIntentSenderForResult(MediaStore.createTrashRequest(cr, deletion, true).intentSender, AUTO_REMOVE_OLD_MEDIA_FILES, null, 0, 0, 0)
                        //cr.update(contentUri, ContentValues().apply { put(MediaStore.Files.FileColumns.IS_TRASHED, 1) }, "${MediaStore.Files.FileColumns._ID} IN (${arrayListOf<String>().apply { deletion.forEach { add(it.toString().substringAfterLast('/')) }}.joinToString()})", null)
                    }
                }

                autoRemoveDone = true
            }
        }

        fun getPhotoById(id: String): NCShareViewModel.RemotePhoto? = _medias.value?.find { it.media.photo.id == id }?.media
        fun getGalleryMediaById(id: String): GalleryMedia? = _medias.value?.find { it.media.photo.id == id }

        private val _additions = MutableSharedFlow<List<String>>()
        val additions: SharedFlow<List<String>> = _additions
        fun add(photoIds: List<String>) { viewModelScope.launch { _additions.emit(photoIds) }}

        private val _deletions = MutableSharedFlow<List<Uri>>()
        val deletions: SharedFlow<List<Uri>> = _deletions
//        private val syncDeletionIdList = arrayListOf<String>()
        fun remove(photoIds: List<String>, removeArchive: Boolean = false, removeLocal: Boolean = true) {
            // Prepare deletion list for local and remote
            val localFiles = arrayListOf<String>()
            val archiveFiles = arrayListOf<GalleryMedia>()
            photoIds.forEach { photoId ->
                getGalleryMediaById(photoId)?.let { galleryMedia ->
                    // Collecting local medias, IS_LOCAL and IS_BOTH medias have their photo id as a URI string
                    if (galleryMedia.media.photo.id.startsWith("content://")) localFiles.add(photoId)

                    // Collecting remote medias
                    when {
                        isArchiveOff() -> archiveFiles.add(galleryMedia)            // If archive is not shown, collect them all blindly
                        galleryMedia.atRemote() -> archiveFiles.add(galleryMedia)   // If archive is shown, collect those do have archived copy
                    }
                }
            }

            // Removing local files
            if (removeLocal && localFiles.isNotEmpty()) {
                arrayListOf<Uri>().let { uris ->
                    localFiles.forEach { uris.add(it.toUri()) }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                        // For Android 11 and above, use SAF
                        viewModelScope.launch { _deletions.emit(uris) }
                    else {
                        // For Android 10 and lower, use ContentResolver
                        val ids = arrayListOf<String>().apply { uris.forEach { add(it.toString().substringAfterLast('/')) }}.joinToString()
                        cr.delete(MediaStore.Files.getContentUri("external"), "${MediaStore.Files.FileColumns._ID} IN (${ids})", null)
                    }
                }
            }

            // Removing archived files
            if (removeArchive && archiveFiles.isNotEmpty()) {
                mutableListOf<Pair<String, String>>().run {
                    archiveFiles.forEach { add(Pair(it.fullPath, it.media.photo.name)) }
                    actionModel.deleteFileInArchive(this)
                }
            }

            setNextInLine()
        }

        private val _restorations = MutableSharedFlow<List<String>>()
        val restorations: SharedFlow<List<String>> = _restorations
        fun restore(photoIds: List<String>, nextInLine: String = "") {
            this.nextInLine = nextInLine
            viewModelScope.launch { _restorations.emit(photoIds) }
        }

        private val _emptyTrash = MutableSharedFlow<List<String>>()
        val emptyTrash: SharedFlow<List<String>> = _emptyTrash
        fun emptyTrash(photoIds: List<String>) { viewModelScope.launch { _emptyTrash.emit(photoIds) }}

        private var currentShareType = SHARE_NORMAL
        fun getShareType() = currentShareType
        private val _strippingEXIF = MutableSharedFlow<Boolean>()
        val strippingEXIF: SharedFlow<Boolean> = _strippingEXIF
        fun shareOut(photoIds: List<String>, strip: Boolean, lowResolution: Boolean, removeAfterwards: Boolean, shareType: Int = SHARE_NORMAL) {
            currentShareType = shareType
            viewModelScope.launch(Dispatchers.IO) {
                _strippingEXIF.emit(strip)
                setIsPreparingShareOut(true)

                // Collect photos for sharing
                val photos = mutableListOf<NCShareViewModel.RemotePhoto>()
                for (id in photoIds) getPhotoById(id)?.let { photos.add(it) }

                // Save photo id for deletion after shared
                if (removeAfterwards) idsDeleteAfterwards.addAll(photoIds)
                else idsDeleteAfterwards.clear()

                // Prepare media files for sharing
                imageModel.prepareFileForShareOut(photos, strip, lowResolution)
            }
        }

        private val idsDeleteAfterwards = mutableListOf<String>()
        fun deleteAfterShared() {
            remove(idsDeleteAfterwards, removeLocal = true, removeArchive = true)
        }

        // Flag to disable selection when sharing out is working in the background for GalleryFolderViewFragment and GalleryOverviewFragment
        private var isSharingOut = false
        fun setIsPreparingShareOut(newState: Boolean) { isSharingOut = newState }
        fun isPreparingShareOut(): Boolean = isSharingOut

        fun getPlayMark() = playMarkDrawable
        fun getSelectedMark() = selectedMarkDrawable
        fun getPanoramaMark() = panoramaMarkDrawable

        // Current display or clicked photo id, for fragment transition between GallerySlideFragment and GalleryOverviewFragment or GalleryFolderViewFragment
        private var currentPhotoId = ""
        fun setCurrentPhotoId(newId: String) {
            //Log.e(">>>>>>>>", "setCurrentPhotoId: $newId", )
            currentPhotoId = newId
        }
        fun getCurrentPhotoId(): String = currentPhotoId

        // Next in line to show after current item deleted, for GallerySlideFragment only to maintain it's viewpager position
        private var nextInLine = ""
        fun registerNextInLine(nextInLine: String) { 
            this.nextInLine = nextInLine
            //Log.e(">>>>>>>>", "registerNextInLine: $nextInLine ${getPhotoById(nextInLine)?.photo?.name}", )
        }
        private fun setNextInLine() {
            if (nextInLine.isNotEmpty()) {
                currentPhotoId = nextInLine
                nextInLine = ""
            }
        }

        fun getMimeTypes(photoIds: List<String>): List<String> = mutableListOf<String>().apply { photoIds.forEach { photoId -> getGalleryMediaById(photoId)?.let { add(it.media.photo.mimeType) }}}
        fun getFullPath(photoId: String): String = getGalleryMediaById(photoId)?.fullPath ?: ""
        fun getVolumeName(photoId: String): String = _medias.value?.find { it.media.photo.id.substringAfterLast('/') == photoId }?.volume ?: ""

        private var currentSubFolder = GalleryFolderViewFragment.CHIP_FOR_ALL_TAG
        fun getCurrentSubFolder(): String = currentSubFolder
        fun saveCurrentSubFolder(name: String) { currentSubFolder = name }
        fun resetCurrentSubFolder() { currentSubFolder = GalleryFolderViewFragment.CHIP_FOR_ALL_TAG }

        fun download(context: Context, photos: List<NCShareViewModel.RemotePhoto>) { imageModel.savePhoto(context, photos) }
        fun upload(photos: List<GalleryMedia>) {
            mutableListOf<Action>().let { actions ->
                val ts = System.currentTimeMillis()
                photos.forEach { actions.add(Action(null, Action.ACTION_BACKUP_INDIVIDUAL, folderName = it.folder, fileId = it.media.photo.id, date = ts)) }
                if (actions.isNotEmpty()) actionModel.addActions(actions)
            }
        }

        fun startArchiveLoadingIndicator(state: Int) { _showArchive.value = state }
        fun stopArchiveLoadingIndicator() {
            when(_showArchive.value) {
                REFRESHING_ARCHIVE -> _showArchive.value = ARCHIVE_ON
                REFRESHING_GALLERY -> _showArchive.value = ARCHIVE_OFF
            }
        }

        companion object {
            const val ARCHIVE_OFF = -1
            const val ARCHIVE_ON = 1
            const val REFRESHING_ARCHIVE = 0
            const val REFRESHING_GALLERY = -1000

            const val SHARE_NORMAL = 1
            const val SHARE_USE_AS = 2
            const val SHARE_AS_PICK_RESULT = 3
        }
    }

    @Parcelize
    data class GalleryMedia(
        var location: Int,
        var folder: String,
        var media: NCShareViewModel.RemotePhoto,
        var volume: String = "",
        var fullPath: String = "",
        var appName: String = "",
        var remoteFileId: String = "",
    ) : Parcelable {
        fun isBoth() = location == IS_BOTH
        fun isLocal() = location == IS_LOCAL || location == IS_IN_GALLERY
        fun isRemote() = location == IS_REMOTE
        fun isNotMedia() = location == IS_NOT_MEDIA
        fun atLocal() = location == IS_BOTH || location == IS_LOCAL
        fun atRemote() = location == IS_BOTH || location == IS_REMOTE

        companion object {
            const val IS_IN_GALLERY = 0     // When media is in local gallery and archive is not showing
            const val IS_LOCAL = 1          // When media is in local gallery only and archive is being shown
            const val IS_REMOTE = 2         // When media is in archive only
            const val IS_BOTH = 4           // When media is in local gallery and archive
            const val IS_NOT_MEDIA = 8      // When this is not a media item. For use with special list item like header and footer view
        }
    }

    companion object {
        private const val AUTO_REMOVE_OLD_MEDIA_FILES = 6667
        const val STORAGE_EMULATED = "/storage/emulated/"
        const val FROM_DEVICE_GALLERY = "0"     // Nextcloud server's folder id can't be 0
        const val FROM_ARCHIVE = ""             // Nextcloud server's folder id can't be empty
        const val EMPTY_GALLERY_COVER_ID = "0"

        const val TRASH_FOLDER = "\uE83A"       // This private character make sure the Trash is at the bottom of folder list
        const val ALL_FOLDER = ".."

        const val ARCHIVE_SCHEME = "remote"

        const val TAG_ACQUIRING_DIALOG = "GALLERY_ACQUIRING_DIALOG"
        private const val TAG_DESTINATION_DIALOG = "GALLERY_DESTINATION_DIALOG"
        private const val DESTINATION_DIALOG_REQUEST_KEY = "GALLERY_FRAGMENT_DESTINATION_DIALOG_REQUEST_KEY"
        const val TAG_FROM_LAUNCHER = "TAG_FROM_LAUNCHER"
        private const val KEY_SHARING_OUT = "KEY_SHARING_OUT"

        private const val ARGUMENT_URI = "ARGUMENT_URI"

        @JvmStatic
        fun newInstance(uri: Uri) = GalleryFragment().apply { arguments = Bundle().apply { putParcelable(ARGUMENT_URI, uri) }}
    }
}