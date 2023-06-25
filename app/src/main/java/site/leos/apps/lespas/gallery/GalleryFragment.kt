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
import android.app.Activity
import android.content.ClipData
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.database.ContentObserver
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.exifinterface.media.ExifInterface
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.Album
import site.leos.apps.lespas.helper.RemoveOriginalBroadcastReceiver
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.helper.Tools.parcelable
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.publication.NCShareViewModel
import site.leos.apps.lespas.sync.AcquiringDialogFragment
import site.leos.apps.lespas.sync.Action
import site.leos.apps.lespas.sync.ActionViewModel
import site.leos.apps.lespas.sync.BackupSetting
import site.leos.apps.lespas.sync.DestinationDialogFragment
import site.leos.apps.lespas.sync.ShareReceiverActivity
import site.leos.apps.lespas.sync.SyncAdapter
import java.lang.Long.min
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class GalleryFragment: Fragment() {
    private val actionModel: ActionViewModel by viewModels()
    private val destinationModel: DestinationDialogFragment.DestinationViewModel by activityViewModels()
    private val imageLoaderModel: NCShareViewModel by activityViewModels()
    private val galleryModel: GalleryViewModel by viewModels { GalleryViewModelFactory(requireActivity().contentResolver, imageLoaderModel) }

    private lateinit var mediaStoreObserver: ContentObserver
    private lateinit var removeOriginalBroadcastReceiver: RemoveOriginalBroadcastReceiver
    private lateinit var deleteMediaLauncher: ActivityResultLauncher<IntentSenderRequest>
    private lateinit var storagePermissionRequestLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var accessMediaLocationPermissionRequestLauncher: ActivityResultLauncher<String>

    private var selectedUris = arrayListOf<Uri>()
    private var waitingMsg: Snackbar? = null
    private val handler = Handler(Looper.getMainLooper())

    //private lateinit var childFragmentBackPressedCallback: OnBackPressedCallback
    private lateinit var shareOutBackPressedCallback: OnBackPressedCallback

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mediaStoreObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                galleryModel.reload()
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
                        getFolderFromUri(uri)?.let {
                            galleryModel.asGallery()
                            // Launched as viewer from system file manager
                            galleryModel.setCurrentPhotoId(it.second)
                            childFragmentManager.beginTransaction().replace(R.id.container_child_fragment, GallerySlideFragment.newInstance(it.first)).addToBackStack(null).commit()
                        } ?: run {
                            // Launched as viewer from other apps, like Joplin, Conversation
                            galleryModel.asViewer(uri, requireContext())
                            childFragmentManager.beginTransaction().replace(R.id.container_child_fragment, GallerySlideFragment.newInstance(uri.toString())).addToBackStack(null).commit()
                        }
                    } ?: run {
                        // Launcher as Gallery
                        galleryModel.asGallery()
                        childFragmentManager.beginTransaction().replace(R.id.container_child_fragment, GalleryOverviewFragment(), GalleryOverviewFragment::class.java.canonicalName).addToBackStack(null).commit()
                    }
                }
                else -> finish()
            }
        }

        // Removing item from MediaStore for Android 11 or above
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) deleteMediaLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) galleryModel.setNextInLine()
        }

        removeOriginalBroadcastReceiver = RemoveOriginalBroadcastReceiver { delete ->
            if (delete) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) deleteMediaLauncher.launch(IntentSenderRequest.Builder(MediaStore.createDeleteRequest(requireContext().contentResolver, selectedUris)).setFillInIntent(null).build())
                else { galleryModel.delete(selectedUris) }
            }
            selectedUris = arrayListOf()

            // Immediately sync with server after adding photo to local album
            AccountManager.get(requireContext()).getAccountsByType(getString(R.string.account_type_nc)).let { accounts ->
                if (accounts.isNotEmpty()) ContentResolver.requestSync(accounts[0], getString(R.string.sync_authority), Bundle().apply {
                    putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                    putInt(SyncAdapter.ACTION, SyncAdapter.SYNC_LOCAL_CHANGES)
                })
            }
        }

        requireActivity().onBackPressedDispatcher.addCallback(this, object: OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (childFragmentManager.backStackEntryCount > 1) childFragmentManager.popBackStack()
                else finish()
            }
        })
/*
        childFragmentBackPressedCallback = object: OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                childFragmentManager.popBackStack()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(this, childFragmentBackPressedCallback)
*/

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
/*

        childFragmentManager.addOnBackStackChangedListener {
            childFragmentBackPressedCallback.isEnabled = childFragmentManager.backStackEntryCount > 1
        }
*/
        childFragmentManager.addOnBackStackChangedListener {
            if (childFragmentManager.backStackEntryCount == 0) {
                // When all medias deleted
                Toast.makeText(requireContext(), getString(R.string.msg_no_media_found), Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? = inflater.inflate(R.layout.fragment_container, container, false)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        resources.getInteger(R.integer.cameraroll_grid_span_count).let { spanCount -> galleryModel.setDrawables(Tools.getPlayMarkDrawable(requireActivity(), (0.32f / spanCount)), Tools.getSelectedMarkDrawable(requireActivity(), 0.25f / spanCount)) }
        savedInstanceState ?: run { storagePermissionRequestLauncher.launch(Tools.getStoragePermissionsArray()) }
        requireActivity().contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, mediaStoreObserver)
        requireActivity().contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, mediaStoreObserver)
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(removeOriginalBroadcastReceiver, IntentFilter(AcquiringDialogFragment.BROADCAST_REMOVE_ORIGINAL))

        viewLifecycleOwner.lifecycleScope.launch {
            launch {
                galleryModel.additions.collect { ids ->
                    selectedUris = arrayListOf<Uri>().apply { ids.forEach { add(Uri.parse(it)) } }
                    if (parentFragmentManager.findFragmentByTag(TAG_DESTINATION_DIALOG) == null) DestinationDialogFragment.newInstance(selectedUris, galleryModel.getPhotoById(ids[0])?.lastModified != LocalDateTime.MAX)
                        .show(parentFragmentManager, if (tag == TAG_FROM_LAUNCHER) TAG_FROM_LAUNCHER else TAG_DESTINATION_DIALOG)
                }
            }
            launch {
                galleryModel.deletions.collect { deletions ->
                    if (deletions.isNotEmpty()) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            val trashItems = arrayListOf<Uri>()
                            val deleteItems = arrayListOf<Uri>()
                            deletions.forEach {
                                if (galleryModel.getVolumeName(it) == MediaStore.VOLUME_EXTERNAL_PRIMARY) trashItems.add(Uri.parse(it))
                                else deleteItems.add(Uri.parse(it))
                            }
                            if (deleteItems.isNotEmpty()) deleteMediaLauncher.launch(IntentSenderRequest.Builder(MediaStore.createDeleteRequest(requireContext().contentResolver, deleteItems)).setFillInIntent(null).build())
                            if (trashItems.isNotEmpty()) deleteMediaLauncher.launch(IntentSenderRequest.Builder(MediaStore.createTrashRequest(requireContext().contentResolver, trashItems, true)).setFillInIntent(null).build())
                        }
                        else galleryModel.delete(arrayListOf<Uri>().apply { deletions.forEach { add(Uri.parse(it)) } })
                    }
                }
            }
            launch {
                galleryModel.restorations.collect { restorations ->
                    if (restorations.isNotEmpty()) {
                        val uris = arrayListOf<Uri>().apply { restorations.forEach { add(Uri.parse(it)) } }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) deleteMediaLauncher.launch(IntentSenderRequest.Builder(MediaStore.createTrashRequest(requireContext().contentResolver, uris, false)).setFillInIntent(null).build())
                    }
                }
            }
            launch {
                galleryModel.emptyTrash.collect { ids ->
                    if (ids.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val uris = arrayListOf<Uri>().apply { ids.forEach { add(Uri.parse(it)) } }
                        deleteMediaLauncher.launch(IntentSenderRequest.Builder(MediaStore.createDeleteRequest(requireActivity().contentResolver, uris)).setFillInIntent(null).build())
                    }
                }
            }
            launch {
                galleryModel.shareOut.collect { strip ->
                    waitingMsg = Tools.getPreparingSharesSnackBar(requireView(), strip) {
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

                    val cr = requireActivity().contentResolver
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
                        putExtra(ShareReceiverActivity.KEY_SHOW_REMOVE_OPTION, true)
                    }, null))

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

        destinationModel.getDestination().observe(viewLifecycleOwner) {
            it?.let { targetAlbum ->
                if (destinationModel.doOnServer()) {
                    val actions = mutableListOf<Action>()
                    val actionId = if (destinationModel.shouldRemoveOriginal()) Action.ACTION_MOVE_ON_SERVER else Action.ACTION_COPY_ON_SERVER
                    val targetFolder = if (targetAlbum.id != Album.JOINT_ALBUM_ID) "${Tools.getRemoteHome(requireContext())}/${targetAlbum.name}" else targetAlbum.coverFileName.substringBeforeLast('/')
                    val removeList = mutableListOf<String>()

                    when (targetAlbum.id) {
                        "" -> {
                            // Create new album first, since this whole operations will be carried out on server, we don't have to worry about cover here, SyncAdapter will handle all the rest during next sync
                            actions.add(0, Action(null, Action.ACTION_ADD_DIRECTORY_ON_SERVER, "", targetAlbum.name, "", "", System.currentTimeMillis(), 1))
                        }
                        Album.JOINT_ALBUM_ID -> Snackbar.make(requireView(), getString(R.string.msg_joint_album_not_updated_locally), Snackbar.LENGTH_LONG).show()
                    }

                    destinationModel.getRemotePhotos().forEach { remotePhoto ->
                        remotePhoto.photo.let { photo ->
                            actions.add(Action(null, actionId, remotePhoto.remotePath, targetFolder, "", "${photo.name}|${targetAlbum.id == Album.JOINT_ALBUM_ID}", System.currentTimeMillis(), 1))
                            removeList.add(photo.id)
                        }
                    }

                    //if (destinationModel.shouldRemoveOriginal() && removeList.isNotEmpty()) camerarollModel.removeBackup(removeList)

                    if (actions.isNotEmpty()) actionModel.addActions(actions)
                } else {
                    // Acquire files
                    if (parentFragmentManager.findFragmentByTag(TAG_ACQUIRING_DIALOG) == null) AcquiringDialogFragment.newInstance(selectedUris, targetAlbum, destinationModel.shouldRemoveOriginal()).show(parentFragmentManager, TAG_ACQUIRING_DIALOG)
                }
            }
        }

        savedInstanceState?.let {
            if (it.getBoolean(KEY_SHARING_OUT)) {
                waitingMsg = Tools.getPreparingSharesSnackBar(requireView(), galleryModel.getCurrentStripSetting()) {
                    imageLoaderModel.cancelShareOut()
                    shareOutBackPressedCallback.isEnabled = false
                    galleryModel.setIsPreparingShareOut(false)
                }
                waitingMsg?.run {
                    show()
                    shareOutBackPressedCallback.isEnabled = true
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_SHARING_OUT, waitingMsg?.isShownOrQueued == true)

        //childFragmentBackPressedCallback.isEnabled = childFragmentManager.backStackEntryCount > 1
    }

    override fun onDestroyView() {
        requireActivity().contentResolver.unregisterContentObserver(mediaStoreObserver)
        requireActivity().contentResolver.unregisterContentObserver(mediaStoreObserver)
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(removeOriginalBroadcastReceiver)

        super.onDestroyView()
    }

    private fun finish() {
        if (tag == TAG_FROM_LAUNCHER) requireActivity().finish() else parentFragmentManager.popBackStack()
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

    class GalleryViewModelFactory(private val cr: ContentResolver,private val imageModel: NCShareViewModel): ViewModelProvider.NewInstanceFactory() {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = GalleryViewModel(cr, imageModel) as T
    }
    class GalleryViewModel(private val cr: ContentResolver, private val imageModel: NCShareViewModel): ViewModel() {
        private lateinit var playMarkDrawable: Drawable
        private lateinit var selectedMarkDrawable: Drawable
        private var loadJob: Job? = null
        private var autoRemoveDone = false
        private val _medias = MutableStateFlow<List<LocalMedia>?>(null)
        val medias: StateFlow<List<LocalMedia>?> = _medias.map { it?.filter { item -> item.folder != TRASH_FOLDER }}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)
        val trash: StateFlow<List<LocalMedia>?> = _medias.map { it?.filter { item -> item.folder == TRASH_FOLDER }}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)
        fun mediasInFolder(folderName: String): StateFlow<List<LocalMedia>?> = _medias.map { it?.filter { item -> item.folder == folderName }}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

        override fun onCleared() {
            loadJob?.cancel()

            super.onCleared()
        }
        
        fun reload() {
            loadJob?.cancel()
            asGallery(true)
        }

        fun asGallery(delayStart: Boolean = false) {
            loadJob = viewModelScope.launch(Dispatchers.IO) {
                // Delay for 500ms when reload, because content observer will receive multiple notifications of change for a single file operation, like for example, creating a new file will result in 3 change notifications, including 1 NOTIFY_INSERT and 2 NOTIFY_UPDATE
                if (delayStart) delay(300)
                ensureActive()

                val localMedias = mutableListOf<LocalMedia>()

                val contentUri = MediaStore.Files.getContentUri("external")
                val pathSelection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Files.FileColumns.RELATIVE_PATH else MediaStore.Files.FileColumns.DATA
                //val dateSelection = "datetaken"     // MediaStore.MediaColumns.DATE_TAKEN, hardcoded here since it's only available in Android Q or above
                var projection = arrayOf(
                    MediaStore.Files.FileColumns._ID,
                    pathSelection,
                    //dateSelection,
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
                val sortOrder = "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"
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
                        //val dateColumn = cursor.getColumnIndexOrThrow(dateSelection)
                        val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
                        val typeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                        val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                        val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.WIDTH)
                        val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.HEIGHT)
                        val orientationColumn = cursor.getColumnIndexOrThrow("orientation")    // MediaStore.Files.FileColumns.ORIENTATION, hardcoded here since it's only available in Android Q or above
                        val defaultZone = ZoneId.systemDefault()
                        var mimeType: String
                        var date: Long
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

/*
                            date = cursor.getLong(dateColumn)
                            // Sometimes dateTaken is not available from system, use DATE_ADDED instead, DATE_ADDED does not has nano adjustment
                            if (date == 0L) date = cursor.getLong(dateAddedColumn) * 1000
*/
                            date = cursor.getLong(dateAddedColumn)

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
                                LocalMedia(
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && cursor.getInt(isTrashColumn) == 1) TRASH_FOLDER else relativePath.substringBefore('/'),
                                    NCShareViewModel.RemotePhoto(
                                        Photo(
                                            id = ContentUris.withAppendedId(if (mimeType.startsWith("image")) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cursor.getString(idColumn).toLong()).toString(),
                                            albumId = FROM_DEVICE_GALLERY,
                                            name = cursor.getString(nameColumn) ?: "",
                                            //dateTaken = LocalDateTime.ofInstant(Instant.ofEpochMilli(date), defaultZone),     // DATE_TAKEN has nano adjustment
                                            dateTaken = LocalDateTime.ofInstant(Instant.ofEpochSecond(date), defaultZone),      // DATE_ADDED does not have nano adjustment
                                            lastModified = LocalDateTime.MIN,
                                            width = cursor.getInt(widthColumn),
                                            height = cursor.getInt(heightColumn),
                                            mimeType = mimeType,
                                            shareId = cursor.getInt(sizeColumn),                  // Saving photo size value in shareId property
                                            orientation = cursor.getInt(orientationColumn)        // Saving photo orientation value in shareId property, keep original orientation, CameraRollFragment will handle the rotation, TODO video length?
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

                // Emitting
                //ensureActive()
                //localMedias.sortWith(compareBy<LocalMedia, String>(Collator.getInstance().apply { strength = Collator.PRIMARY }) { it.folder }.thenByDescending { it.media.photo.dateTaken })
                ensureActive()
                _medias.value = localMedias
            }.apply {
                invokeOnCompletion { loadJob = null }
            }
        }

        fun asViewer(uri: Uri, ctx: Context) {
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
                shareId = size,                     // Temporarily use shareId for saving file's size TODO maximum 4GB
                dateTaken = LocalDateTime.now(),
                lastModified = LocalDateTime.MAX,   // LocalDateTime.MAX to mark it not removable
            )
            metadataRetriever?.release()

            uri.toString().let { uriString ->
                setCurrentPhotoId(uriString)
                _medias.value = listOf(LocalMedia(uriString, NCShareViewModel.RemotePhoto(photo), "", uriString))
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

        fun getPhotoById(id: String): Photo? = medias.value?.find { it.media.photo.id == id }?.media?.photo

        private val _additions = MutableSharedFlow<List<String>>()
        val additions: SharedFlow<List<String>> = _additions
        fun add(photoIds: List<String>) { viewModelScope.launch { _additions.emit(photoIds) }}

        private val _deletions = MutableSharedFlow<List<String>>()
        val deletions: SharedFlow<List<String>> = _deletions
        fun remove(photoIds: List<String>, nextInLine: String = "") {
            this.nextInLine = nextInLine
            viewModelScope.launch { _deletions.emit(photoIds) }
        }
        fun delete(uris: ArrayList<Uri>) {
            val ids = arrayListOf<String>().apply { uris.forEach { add(it.toString().substringAfterLast('/')) }}.joinToString()
            cr.delete(MediaStore.Files.getContentUri("external"), "${MediaStore.Files.FileColumns._ID} IN (${ids})", null)

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

        private val _shareOut = MutableSharedFlow<Boolean>()
        val shareOut: SharedFlow<Boolean> = _shareOut
        fun shareOut(photoIds: List<String>, strip: Boolean, isRemote: Boolean = false, remotePath: String = "") {
            viewModelScope.launch(Dispatchers.IO) {
                stripOrNot = strip
                _shareOut.emit(stripOrNot)
                setIsPreparingShareOut(true)

                // Collect photos for sharing
                val photos = mutableListOf<Photo>()
                for (id in photoIds) getPhotoById(id)?.let { photos.add(it) }

                // Prepare media files for sharing
                imageModel.prepareFileForShareOut(photos, strip, isRemote, remotePath)
            }
        }

        private var isSharingOut = false
        fun setIsPreparingShareOut(newState: Boolean) { isSharingOut = newState }
        fun isPreparingShareOut(): Boolean = isSharingOut

        private var stripOrNot: Boolean = false
        fun getCurrentStripSetting() = stripOrNot

        fun setDrawables(playMark: Drawable, selectedMark: Drawable) {
            playMarkDrawable = playMark
            selectedMarkDrawable = selectedMark
        }
        fun getPlayMark() = playMarkDrawable
        fun getSelectedMark() = selectedMarkDrawable

        // Current display or clicked photo id, for fragment transition between GallerySlideFragment and GalleryOverviewFragment or GalleryFolderViewFragment
        private var currentPhotoId = ""
        fun setCurrentPhotoId(newId: String) { currentPhotoId = newId }
        fun getCurrentPhotoId(): String = currentPhotoId

        // Next in line to show after current item deleted, for GallerySlideFragment only
        private var nextInLine = ""
        fun setNextInLine() { if (nextInLine.isNotEmpty()) currentPhotoId = nextInLine }

        fun getFullPath(photoId: String): String = _medias.value?.find { it.media.photo.id == photoId }?.fullPath ?: ""
        fun getVolumeName(photoId: String): String = _medias.value?.find { it.media.photo.id == photoId }?.volume ?: ""

        private var currentSubFolder = GalleryFolderViewFragment.CHIP_FOR_ALL_TAG
        fun getCurrentSubFolder(): String = currentSubFolder
        fun saveCurrentSubFolder(name: String) { currentSubFolder = name }
        fun resetCurrentSubFolder() { currentSubFolder = GalleryFolderViewFragment.CHIP_FOR_ALL_TAG }
    }

    data class LocalMedia(
        var folder: String,
        var media: NCShareViewModel.RemotePhoto,
        var volume: String = "",
        var fullPath: String = "",
        var appName: String = "",
    )

    companion object {
        private const val AUTO_REMOVE_OLD_MEDIA_FILES = 6667
        const val STORAGE_EMULATED = "/storage/emulated/"
        const val FROM_DEVICE_GALLERY = "0"
        const val EMPTY_GALLERY_COVER_ID = "0"

        const val TRASH_FOLDER = "\uE83A"   // This private character make sure the Trash is at the bottom of folder list
        const val ALL_FOLDER = ".."

        const val TAG_ACQUIRING_DIALOG = "GALLERY_ACQUIRING_DIALOG"
        private const val TAG_DESTINATION_DIALOG = "GALLERY_DESTINATION_DIALOG"
        const val TAG_FROM_LAUNCHER = "TAG_FROM_LAUNCHER"

        private const val KEY_SHARING_OUT = "KEY_SHARING_OUT"

        private const val ARGUMENT_URI = "ARGUMENT_URI"

        @JvmStatic
        fun newInstance(uri: Uri) = GalleryFragment().apply { arguments = Bundle().apply { putParcelable(ARGUMENT_URI, uri) }}
    }
}