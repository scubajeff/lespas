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

package site.leos.apps.lespas.sync

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.TextView
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.core.widget.ContentLoadingProgressBar
import androidx.exifinterface.media.ExifInterface
import androidx.fragment.app.viewModels
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import androidx.transition.TransitionInflater
import androidx.transition.TransitionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import site.leos.apps.lespas.BuildConfig
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.Album
import site.leos.apps.lespas.album.AlbumRepository
import site.leos.apps.lespas.gallery.GalleryFragment
import site.leos.apps.lespas.helper.LesPasDialogFragment
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.helper.Tools.parcelable
import site.leos.apps.lespas.helper.Tools.parcelableArrayList
import site.leos.apps.lespas.photo.AlbumPhotoName
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.photo.PhotoRepository
import java.io.File
import java.io.FileNotFoundException
import java.time.LocalDateTime
import java.time.ZoneOffset


class AcquiringDialogFragment: LesPasDialogFragment(R.layout.fragment_acquiring_dialog) {
    private var total = -1
    private lateinit var album: Album

    //private val acquiringModel: AcquiringViewModel by viewModels { AcquiringViewModelFactory(requireActivity().application, arguments?.getParcelableArrayList(KEY_URIS)!!, arguments?.getParcelable(KEY_ALBUM)!!) }
    private val acquiringModel: AcquiringViewModel by viewModels { AcquiringViewModelFactory(requireActivity().application, requireArguments().parcelableArrayList(KEY_URIS)!!, requireArguments().parcelable(KEY_ALBUM)!!, requireArguments().getBoolean(KEY_REMOVE_ORIGINAL, false)) }

    private lateinit var progressLinearLayout: LinearLayoutCompat
    private lateinit var dialogTitleTextView: TextView
    private lateinit var messageTextView: TextView
    private lateinit var fileNameTextView: TextView
    private lateinit var background: LinearLayoutCompat
    private lateinit var contentLoadingProgressBar: ContentLoadingProgressBar

    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        //total = arguments?.getParcelableArrayList<Uri>(KEY_URIS)!!.size
        //album = arguments?.getParcelable(KEY_ALBUM)!!
        requireArguments().run {
            total = parcelableArrayList<Uri>(KEY_URIS)!!.size
            album = parcelable(KEY_ALBUM)!!
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        background = view.findViewById(R.id.background)
        progressLinearLayout = view.findViewById(R.id.progress_linearlayout)
        dialogTitleTextView = view.findViewById(R.id.dialog_title_textview)
        messageTextView = view.findViewById(R.id.message)
        fileNameTextView = view.findViewById(R.id.filename_textview)
        contentLoadingProgressBar = view.findViewById(R.id.current_progress)

        acquiringModel.getProgress().observe(viewLifecycleOwner) { progress ->
            when {
                progress >= total -> {
                    finished = true

                    TransitionManager.beginDelayedTransition(background, TransitionInflater.from(requireContext()).inflateTransition(R.transition.destination_dialog_new_album))
                    progressLinearLayout.visibility = View.GONE
                    dialogTitleTextView.text = getString(R.string.finished_preparing_files)
                    var note = getString(R.string.it_takes_time)
                    if (PreferenceManager.getDefaultSharedPreferences(context).getBoolean(requireContext().getString(R.string.wifionly_pref_key), true)) {
                        if ((requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered) {
                            note += requireContext().getString(R.string.mind_network_setting)
                        }
                    }
                    messageTextView.text = note
                    messageTextView.visibility = View.VISIBLE
                    dialog?.setCanceledOnTouchOutside(true)
                }

                progress >= 0 -> {
                    dialogTitleTextView.text = getString(R.string.preparing_files_progress, progress + 1, total)
                    fileNameTextView.text = acquiringModel.getCurrentName()
                    contentLoadingProgressBar.progress = progress
                }

                progress < 0 -> {
                    TransitionManager.beginDelayedTransition(background, TransitionInflater.from(requireContext()).inflateTransition(R.transition.destination_dialog_new_album))
                    progressLinearLayout.visibility = View.GONE
                    dialogTitleTextView.text = getString(R.string.error_preparing_files)
                    messageTextView.text = getString(
                        when (progress) {
                            AcquiringViewModel.ACCESS_RIGHT_EXCEPTION -> R.string.access_right_violation
                            AcquiringViewModel.NO_MEDIA_FILE_FOUND -> R.string.no_media_file_found
                            AcquiringViewModel.SAME_FILE_EXISTED -> R.string.same_file_found
                            else -> 0
                        }
                    )
                    messageTextView.visibility = View.VISIBLE
                    dialog?.setCanceledOnTouchOutside(true)
                }
            }
        }

        contentLoadingProgressBar.max = total
    }

    override fun onStart() {
        super.onStart()

        dialog?.setCanceledOnTouchOutside(false)
    }


    override fun onDestroy() {
        if (album.id != Album.JOINT_ALBUM_ID) {
            LocalBroadcastManager.getInstance(requireContext()).sendBroadcast(Intent().apply {
                action = BROADCAST_REMOVE_ORIGINAL
                putExtra(BROADCAST_REMOVE_ORIGINAL_EXTRA, if (finished) arguments?.getBoolean(KEY_REMOVE_ORIGINAL) == true else false)
            })
        }

        super.onDestroy()

        // If called by ShareReceiverActivity, quit immediately, otherwise return normally
        if (tag == ShareReceiverActivity.TAG_ACQUIRING_DIALOG) activity?.finish()
    }

    class AcquiringViewModelFactory(private val application: Application, private val uris: ArrayList<Uri>, private val album: Album, private val removeOriginal: Boolean): ViewModelProvider.NewInstanceFactory() {
        override fun <T : ViewModel> create(modelClass: Class<T>): T = modelClass.cast(AcquiringViewModel(application, uris, album, removeOriginal))!!
    }

    class AcquiringViewModel(application: Application, private val uris: ArrayList<Uri>, private val album: Album, private val removeOriginal: Boolean): AndroidViewModel(application) {
        private var currentProgress = MutableLiveData<Int>()
        private var currentName: String = ""
        private val newPhotos = mutableListOf<Photo>()
        private val actions = mutableListOf<Action>()
        private val photoRepository = PhotoRepository(application)

        init {
            viewModelScope.launch(Dispatchers.IO) {
                var fileId = ""
                var mimeType = ""
                var meta: Photo

                val appRootFolder = Tools.getLocalRoot(application)
                val allPhotoName = photoRepository.getAllPhotoNameMap()
                var date: LocalDateTime
                val fakeAlbumId = System.currentTimeMillis().toString()
                val contentResolver = application.contentResolver
                val metadataRetriever = MediaMetadataRetriever()
                var exifInterface: ExifInterface?

                var sourceIsRemote: Boolean
                val remoteAction = if (removeOriginal) Action.ACTION_MOVE_ON_SERVER else Action.ACTION_COPY_ON_SERVER
                val remoteTargetFolder = if (album.isJoint()) album.coverFileName.substringBeforeLast('/') else "${Tools.getRemoteHome(application)}/${album.name}"


                if (album.id.isEmpty()) {
                    // New album, set a fake ID, sync adapter will correct it when real id is available
                    album.id = fakeAlbumId
                }
                uris.forEachIndexed { index, uri ->
                    sourceIsRemote = uri.scheme == GalleryFragment.ARCHIVE_SCHEME

                    // find out the real name
                    if (sourceIsRemote) {
                        fileId = uri.lastPathSegment ?: run { return@forEachIndexed }
                    } else {
                        contentResolver.query(uri, null, null, null, null)?.apply {
                            fileId = ""
                            val columnIndex = getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            moveToFirst()
                            if (columnIndex != -1) {
                                try { fileId = getString(columnIndex) } catch (e: NullPointerException) { if ("twidere.share".equals(uri.authority, true)) fileId = uri.path!!.substringAfterLast('/') }
                            } else { if ("twidere.share".equals(uri.authority, true)) fileId = uri.path!!.substringAfterLast('/') }
                            close()
                        } ?: run { if ("file".equals(uri.scheme, true)) fileId = uri.path!!.substringAfterLast("/") }
                        if (fileId.isEmpty()) return@forEachIndexed
                    }

                    // If no photo with same name exists in album (always true in case of joint album), create new photo
                    if (!(allPhotoName.contains(AlbumPhotoName(album.id, fileId)))) {
                        //val fileName = "${fileId.substringBeforeLast('.')}_${System.currentTimeMillis()}.${fileId.substringAfterLast('.')}"

                        // Update progress in UI
                        setProgress(index, fileId)

                        if (sourceIsRemote) {
                            mimeType = uri.getQueryParameter("mimetype") ?: run { return@forEachIndexed }

                            // Get photo meta from special uri
                            try {
                                meta = Photo(
                                    name = fileId,
                                    lastModified = LocalDateTime.now(),
                                    dateTaken = Tools.epochToLocalDateTime(uri.getQueryParameter("datetaken")?.toLong() ?: System.currentTimeMillis(), true),
                                    mimeType = uri.getQueryParameter("mimetype") ?: run { return@forEachIndexed },
                                    //caption = uri.getQueryParameter("caption") ?: run { return@forEachIndexed },      // caption property value is file size in the case of remote archive item
                                    width = uri.getQueryParameter("width")?.toInt() ?: run { return@forEachIndexed },
                                    height = uri.getQueryParameter("height")?.toInt() ?: run { return@forEachIndexed },
                                    orientation = uri.getQueryParameter("orientation")?.toInt() ?: run { return@forEachIndexed },
                                    latitude = uri.getQueryParameter("lat")?.toDouble() ?: run { return@forEachIndexed },
                                    longitude = uri.getQueryParameter("long")?.toDouble() ?: run { return@forEachIndexed },
                                    altitude = uri.getQueryParameter("alt")?.toDouble() ?: run { return@forEachIndexed },
                                    bearing = uri.getQueryParameter("bearing")?.toDouble() ?: run { return@forEachIndexed },
                                )
                            } catch (e: Exception) { return@forEachIndexed }

                            // Pause to properly display progress
                            delay(150L)
                        } else {
                            // TODO: Default type set to jpeg
                            mimeType = contentResolver.getType(uri)?.let { Intent.normalizeMimeType(it) } ?: run {
                                MimeTypeMap.getSingleton().getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(uri.toString()).lowercase()) ?: "image/jpeg"
                            }

                            // If it's not image, skip it
                            if (!(mimeType.substringAfter("image/", "") in Tools.SUPPORTED_PICTURE_FORMATS || mimeType.startsWith("video/", true))) return@forEachIndexed

                            // Copy source file to our private storage if it's local
                            try {
                                try {
                                    application.contentResolver.openInputStream(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri.host == "media") MediaStore.setRequireOriginal(uri) else uri)
                                } catch (e: SecurityException) {
                                    application.contentResolver.openInputStream(uri)
                                } catch (e: UnsupportedOperationException) {
                                    application.contentResolver.openInputStream(uri)
                                }?.use { input ->
                                    //File(if (album.id == Album.JOINT_ALBUM_ID) cacheFolder else appRootFolder, fileId).outputStream().use { output ->
                                    File(appRootFolder, fileId).outputStream().use { output ->
                                        input.copyTo(output, 8192)
                                    }
                                }
                            } catch (e: FileNotFoundException) {
                                // without access right to uri, will throw FileNotFoundException
                                setProgress(ACCESS_RIGHT_EXCEPTION, "")
                                return@launch   // TODO shall we loop to next file?
                            } catch (e: Exception) {
                                e.printStackTrace()
                                return@launch
                            }

                            // Get media's metadata
                            try { metadataRetriever.setDataSource("$appRootFolder/$fileId") } catch (_: Exception) {}
                            exifInterface = try { ExifInterface("$appRootFolder/$fileId") } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }
                            meta = Tools.getPhotoParams(metadataRetriever, exifInterface, "$appRootFolder/$fileId", mimeType, fileId)
                            // Skip those image file we can't handle, like SVG
                            if (meta.width == -1 || meta.height == -1) return@forEachIndexed
                        }


                        if (album.isJoint()) {
                            if (sourceIsRemote) {
                                actions.add(
                                    Action(
                                    null, remoteAction,
                                        uri.path?.substringBeforeLast('/') ?: "", remoteTargetFolder,
                                        "${album.eTag}|${meta.dateTaken.toInstant(ZoneOffset.UTC).toEpochMilli()}|${meta.mimeType}|${meta.width}|${meta.height}|${meta.orientation}|${meta.caption}|${meta.latitude}|${meta.longitude}|${meta.altitude}|${meta.bearing}",
                                        "${fileId}|${album.isJoint()}|${album.isRemote()}",
                                        System.currentTimeMillis(), 1
                                    )
                                )
                            } else {
                                // DestinationDialogFragment pass joint album's albumId in property album.eTag, share path in coverFileName
                                actions.add(
                                    Action(
                                        null, Action.ACTION_ADD_FILES_TO_JOINT_ALBUM,
                                        meta.mimeType,
                                        remoteTargetFolder,
                                        "${album.eTag}|${meta.dateTaken.toInstant(ZoneOffset.UTC).toEpochMilli()}|${meta.mimeType}|${meta.width}|${meta.height}|${meta.orientation}|${meta.caption}|${meta.latitude}|${meta.longitude}|${meta.altitude}|${meta.bearing}",
                                        fileId,
                                        System.currentTimeMillis(), 1
                                    )
                                )
                            }
                        } else {
                            //newPhotos.add(meta.copy(id = fileId, albumId = album.id, name = fileId, shareId = Photo.DEFAULT_PHOTO_FLAG or Photo.NOT_YET_UPLOADED))
                            newPhotos.add(meta.copy(id = fileId, albumId = album.id, name = fileId))

                            // Update album start and end dates accordingly
                            date = newPhotos.last().dateTaken
                            if (date < album.startDate) album.startDate = date
                            if (date > album.endDate) album.endDate = date

                            if (sourceIsRemote) {
                                //Log.e(">>>>>>>>", "${uri.path?.substringBeforeLast('/')}    ${uri.lastPathSegment}",)
                                //Log.e(">>>>>>>>", "$meta", )
                                actions.add(Action(null, remoteAction, uri.path?.substringBeforeLast('/') ?: "", remoteTargetFolder, "${album.id}|${meta.dateTaken.toInstant(ZoneOffset.UTC).toEpochMilli()}|${meta.mimeType}|${meta.width}|${meta.height}|${meta.orientation}|${meta.caption}|${meta.latitude}|${meta.longitude}|${meta.altitude}|${meta.bearing}", "${fileId}|${album.isJoint()}|${album.isRemote()}", System.currentTimeMillis(), 1))
                            }
                            else {
                                // Pass photo mimeType in Action's folderId property, fileId is the same as fileName, reflecting what it's in local Room table, also pass flags shareId in retry property
                                actions.add(Action(null, Action.ACTION_ADD_FILES_ON_SERVER, mimeType, album.name, fileId, fileId, System.currentTimeMillis(), album.shareId))
                            }
                        }
                    } else {
                        // TODO show special error message when there are just some duplicate in uris
                        if (uris.size == 1) {
                            setProgress(SAME_FILE_EXISTED, "")
                            return@launch
                        } else setProgress(index, fileId)
                    }

                }

                metadataRetriever.release()

                if (actions.isEmpty()) setProgress(NO_MEDIA_FILE_FOUND, "")
                else {
                    if (album.isJoint()) {
                        // Update joint album content meta, DestinationDialogFragment pass joint album's albumId in property album.eTag, share path in coverFileName
                        actions.add(Action(null, Action.ACTION_UPDATE_JOINT_ALBUM_PHOTO_META, album.eTag, album.coverFileName.substringBeforeLast('/'), "", "", System.currentTimeMillis(), 1))
                    } else {
                        if (album.id == fakeAlbumId) {
/*
                            // Get first JPEG or PNG file, only these two format can be set as coverart because they are supported by BitmapRegionDecoder
                            // If we just can't find one single photo of these two formats in this new album, fall back to the first one in the list, cover will be shown as placeholder
                            var validCover = newPhotos.indexOfFirst { it.mimeType == "image/jpeg" || it.mimeType == "image/png" }
                            if (validCover == -1) validCover = 0

                            // New album, update cover information but leaving cover column empty as the sign of local added new album
                            newPhotos[validCover].run {
                                album.coverBaseline = (height - (width * 9 / 21)) / 2
                                album.coverWidth = width
                                album.coverHeight = height
                                album.cover = id
                                album.coverFileName = name
                                album.coverMimeType = mimeType
                                album.coverOrientation = orientation
                            }
*/
                            newPhotos[0].run {
                                album.coverBaseline = if (mimeType == "image/jpeg" || mimeType == "image/png") (height - (width * 9 / 21)) / 2 else Album.SPECIAL_COVER_BASELINE
                                album.coverWidth = width
                                album.coverHeight = height
                                album.cover = id
                                album.coverFileName = name
                                album.coverMimeType = mimeType
                                album.coverOrientation = orientation
                            }
                            album.sortOrder = PreferenceManager.getDefaultSharedPreferences(application).getString(application.getString(R.string.default_sort_order_pref_key), "0")?.toInt() ?: Album.BY_DATE_TAKEN_ASC

                            // Create new album first, store cover, e.g. first photo in new album, in property filename
                            //actions.add(0, Action(null, Action.ACTION_ADD_DIRECTORY_ON_SERVER, album.id, album.name, "", newPhotos[validCover].id, System.currentTimeMillis(), 1))
                            actions.add(0, Action(null, Action.ACTION_ADD_DIRECTORY_ON_SERVER, album.id, album.name, "", newPhotos[0].id, System.currentTimeMillis(), 1))
                        }

                        photoRepository.insert(newPhotos)
                        AlbumRepository(application).upsert(album)
                    }
                    ActionRepository(application).addActions(actions)

                    // By setting progress to more than 100%, signaling the calling fragment/activity
                    setProgress(uris.size, fileId)
                }
            }
        }

        private fun setProgress(progress: Int, name: String) {
            currentName = name
            currentProgress.postValue(progress)
        }

        fun getProgress(): LiveData<Int> = currentProgress
        fun getCurrentName() = currentName

        companion object {
            const val ACCESS_RIGHT_EXCEPTION = -100
            const val NO_MEDIA_FILE_FOUND = -200
            const val SAME_FILE_EXISTED = -300
        }
    }

    companion object {
        const val KEY_URIS = "KEY_URIS"
        const val KEY_ALBUM = "KEY_ALBUM"
        const val KEY_REMOVE_ORIGINAL = "KEY_REMOVE_ORIGINAL"

        const val BROADCAST_REMOVE_ORIGINAL = "${BuildConfig.APPLICATION_ID}.BROADCAST_REMOVE_ORIGINAL"
        const val BROADCAST_REMOVE_ORIGINAL_EXTRA = "${BuildConfig.APPLICATION_ID}.BROADCAST_REMOVE_ORIGINAL_EXTRA"

        @JvmStatic
        fun newInstance(uris: ArrayList<Uri>, album: Album, removeOriginal: Boolean) = AcquiringDialogFragment().apply {
            arguments = Bundle().apply {
                putParcelableArrayList(KEY_URIS, uris)
                putParcelable(KEY_ALBUM, album)
                putBoolean(KEY_REMOVE_ORIGINAL, removeOriginal)
            }
        }
    }
}