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

package site.leos.apps.lespas.search

import android.app.Application
import android.content.ContentResolver
import android.content.ContentUris
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.zip
import kotlinx.coroutines.launch
import org.osmdroid.bonuspack.location.GeocoderNominatim
import site.leos.apps.lespas.BuildConfig
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.Album
import site.leos.apps.lespas.album.AlbumRepository
import site.leos.apps.lespas.album.IDandAttribute
import site.leos.apps.lespas.gallery.GalleryFragment.Companion.FROM_DEVICE_GALLERY
import site.leos.apps.lespas.gallery.GalleryFragment.Companion.STORAGE_EMULATED
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.photo.PhotoRepository
import site.leos.apps.lespas.publication.NCShareViewModel
import site.leos.apps.lespas.tflite.ObjectDetectionModel
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

class SearchFragment: Fragment() {
    private var searchProgressIndicator: MenuItem? = null
    private var searchProgressBar: CircularProgressIndicator? = null

    private val archiveModel: NCShareViewModel by activityViewModels()
    private val searchModel: SearchModel by viewModels { SearchModelFactory(requireActivity().application, archiveModel) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requireActivity().onBackPressedDispatcher.addCallback(this, object: OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (childFragmentManager.backStackEntryCount > 1) childFragmentManager.popBackStack()
                else parentFragmentManager.popBackStack()
            }
        })

        // Launch Search launcher fragment
        savedInstanceState ?: run { childFragmentManager.beginTransaction().replace(R.id.container_child_fragment, SearchLauncherFragment.newInstance(requireArguments().getBoolean(NO_ALBUM)), SearchLauncherFragment::class.java.canonicalName).addToBackStack(null).commit() }
    }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? = inflater.inflate(R.layout.fragment_container, container, false)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) { menuInflater.inflate(R.menu.search_progress_menu, menu) }
            override fun onPrepareMenu(menu: Menu) {
                super.onPrepareMenu(menu)
                searchProgressIndicator = menu.findItem(R.id.option_menu_search_progress)
                searchProgressBar = searchProgressIndicator?.actionView?.findViewById<CircularProgressIndicator>(R.id.search_progress)?.apply {
                    if (searchModel.maxProgress.value != SearchModel.PROGRESS_INDETERMINATE) max = searchModel.maxProgress.value
                    if (searchModel.progress.value == SearchModel.PROGRESS_FINISH) searchProgressIndicator?.run {
                        isVisible = false
                        isEnabled = false
                    }
                }
            }
            override fun onMenuItemSelected(menuItem: MenuItem): Boolean = true
        }, viewLifecycleOwner, Lifecycle.State.STARTED)

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    searchModel.progress.collect { progress -> searchProgressBar?.setProgressCompat(progress, true) }
                }
                launch {
                    searchModel.maxProgress.collect { maxProgress -> searchProgressBar?.run {
                        if (maxProgress == SearchModel.PROGRESS_INDETERMINATE) isIndeterminate = true
                        else {
                            isIndeterminate = false
                            max = maxProgress
                        }
                    }}
                }
            }
        }
    }

    class SearchModelFactory(private val application: Application, private val archiveModel: NCShareViewModel): ViewModelProvider.NewInstanceFactory() {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SearchModel(application, archiveModel) as T
    }

    class SearchModel(val application: Application, private val archiveModel: NCShareViewModel): ViewModel() {
        private val albumRepository = AlbumRepository(application)
        private val photoRepository = PhotoRepository(application)
        private val cr = application.contentResolver
        private val rootPath = Tools.getLocalRoot(application)
        private val lespasBasePath = Tools.getRemoteHome(application)
        private lateinit var albumTable: List<IDandAttribute>
        private val od = ObjectDetectionModel(application.assets)
        private var searchJob: Job? = null

        private val localGallery = MutableStateFlow<List<NCShareViewModel.RemotePhoto>>(mutableListOf())
        private val _albumPhotos = MutableStateFlow<List<NCShareViewModel.RemotePhoto>>(mutableListOf())
        val albumPhotos: StateFlow<List<NCShareViewModel.RemotePhoto>> = _albumPhotos
        private val _galleryPhotos = MutableStateFlow<List<NCShareViewModel.RemotePhoto>>(mutableListOf())
        val galleryPhotos: StateFlow<List<NCShareViewModel.RemotePhoto>> = _galleryPhotos
        private val _progress = MutableStateFlow(0)
        val progress: StateFlow<Int> = _progress
        private val _maxProgress = MutableStateFlow(PROGRESS_INDETERMINATE)
        val maxProgress: StateFlow<Int> = _maxProgress
        private val _objectDetectResult = MutableStateFlow<List<ObjectDetectResult>>(mutableListOf())
        val objectDetectResult: StateFlow<List<ObjectDetectResult>> = _objectDetectResult
        private val _locationSearchResult = MutableStateFlow<List<LocationSearchResult>>(mutableListOf())
        val locationSearchResult: StateFlow<List<LocationSearchResult>> = _locationSearchResult

        private var deviceGalleryLoadingJob: Job? = null

        init {
            viewModelScope.launch(Dispatchers.IO) {
                launch {
                    _albumPhotos.emit(
                        photoRepository.getAllImageNotHidden().map { photo ->
                            NCShareViewModel.RemotePhoto(photo,
                                albumTable.find { album -> album.id == photo.albumId }?.let { album -> if ((album.shareId and Album.REMOTE_ALBUM) == Album.REMOTE_ALBUM && photo.eTag != Photo.ETAG_NOT_YET_UPLOADED) "${lespasBasePath}/${album.name}" else ""} ?: ""
                            )
                        }
                    )
                }
                launch {
                    localGallery.zip(archiveModel.archive) { local, archiveMedia ->
                        val combinedList = local.map { it.copy(remotePath = "") }.toMutableList()

                        ensureActive()
                        archiveMedia?.let {
                            if (archiveMedia.isNotEmpty()) {
                                // Create a map of archive items from this device for matching local medias later
                                val searchMap = local.associateBy { item -> item.remotePath + item.photo.name }

                                ensureActive()
                                archiveMedia.partition { it.media.photo.lastModified >= if (local.isEmpty()) LocalDateTime.now() else local.last().photo.lastModified }.let {
                                    ensureActive()
                                    combinedList.addAll(it.first.filter { item -> searchMap[item.fullPath + item.media.photo.name] == null }.map { item ->  item.media })   // Add all archive media which date falls in device Gallery date range and does not exit in device

                                    ensureActive()
                                    combinedList.addAll(it.second.map { item -> item.media })   // Add all archive media which date is earlier than the oldest date of device Gallery
                                }

                                ensureActive()
                                combinedList.sortedByDescending { item -> item.photo.lastModified }
                            }
                        }

                        ensureActive()
                        combinedList
                    }.collect { result -> _galleryPhotos.emit(result) }
                }
                launch {
                    loadDeviceGallery()
                    archiveModel.refreshArchive(false)
                }
                albumTable = albumRepository.getAllAlbumAttribute()
            }
        }

        private fun loadDeviceGallery() {
            deviceGalleryLoadingJob = viewModelScope.launch(Dispatchers.IO) {
                val localMedias = mutableListOf<NCShareViewModel.RemotePhoto>()

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
                val sortOrder = "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"
                val queryBundle = Bundle()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    projection = projection.plus(MediaStore.Files.FileColumns.VOLUME_NAME)
                    queryBundle.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                    queryBundle.putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder)
                    queryBundle.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_EXCLUDE)
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
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) volumeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.VOLUME_NAME)

                        cursorLoop@ while (cursor.moveToNext()) {
                            ensureActive()

                            mimeType = cursor.getString(typeColumn)
                            // Make sure image type is supported
                            if (mimeType.startsWith("image") && mimeType.substringAfter("image/", "") !in Tools.SUPPORTED_PICTURE_FORMATS) continue@cursorLoop
                            if (cursor.getLong(sizeColumn) == 0L) continue@cursorLoop

                            dateAdded = cursor.getLong(dateAddedColumn)
                            dateTaken = cursor.getLong(dateTakenColumn)
                            // Sometimes dateTaken is not available from system, use DATE_ADDED instead, DATE_ADDED does not has nano adjustment
                            if (dateTaken == 0L) dateTaken = dateAdded * 1000

                            relativePath = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) cursor.getString(pathColumn) else cursor.getString(pathColumn).substringAfter(STORAGE_EMULATED).substringAfter("/").substringBeforeLast('/') + "/"
                            localMedias.add(
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
                                    remotePath = relativePath,      // For combining local and archive purpose
                                    coverBaseLine = 0,              // Backup is disable by default
                                )
                            )
                        }
                    }
                } catch (_: Exception) { }

                // List is now sorted when querying the content store
                //ensureActive()
                //localMedias.sortWith(compareBy<LocalMedia, String>(Collator.getInstance().apply { strength = Collator.PRIMARY }) { it.folder }.thenByDescending { it.media.photo.dateTaken })

                localGallery.emit(localMedias)
            }.apply { invokeOnCompletion { deviceGalleryLoadingJob = null }}
        }

        fun objectDetect(categoryId: String, searchScope: Int) {
            var resultList: MutableList<ObjectDetectResult>

            searchJob?.cancel()
            searchJob = viewModelScope.launch(Dispatchers.IO) {
                _maxProgress.emit(PROGRESS_INDETERMINATE)

                (if (searchScope == R.id.search_album) albumPhotos else galleryPhotos).collect { remotePhotos ->
                    if (remotePhotos.isNotEmpty()) {
                        var length: Int
                        var size: Int
                        val option = BitmapFactory.Options()

                        resultList = mutableListOf()
                        _objectDetectResult.emit(resultList)
                        _maxProgress.emit(remotePhotos.size)

                        remotePhotos.forEachIndexed { i, remotePhoto ->
                            remotePhoto.photo.let { photo ->
                                ensureActive()
                                _progress.emit(i)

                                // Decode file with dimension just above 300
                                size = 1
                                length = Integer.min(photo.width, photo.height)
                                while (length / size > 600) {
                                    size *= 2
                                }
                                option.inSampleSize = size

                                try {
                                    ensureActive()
                                    when {
                                        photo.id.startsWith("content") -> BitmapFactory.decodeStream(cr.openInputStream(Uri.parse(photo.id)), null, option)
                                        remotePhoto.remotePath.isNotEmpty() -> archiveModel.getPreview(remotePhoto)
                                        else -> BitmapFactory.decodeFile("$rootPath/${photo.id}", option)
                                    }?.let {
                                        // Inference
                                        ensureActive()
                                        with(od.recognizeImage(it)) {
                                            ensureActive()
                                            if (this.isNotEmpty()) with(this[0]) {
                                                if (this.classId == categoryId) {
                                                    resultList.add(ObjectDetectResult(remotePhoto, this.objectIndex, this.similarity))
                                                    _objectDetectResult.emit(resultList.toList())
                                                }
                                            }
                                        }
                                    }
                                } catch (_: Exception) {
                                }
                            }
                        }

                        _progress.emit(PROGRESS_FINISH)
                    }
                }
            }
        }

        fun locationSearch(searchScope: Int) {
            var resultList: MutableList<LocationSearchResult>

            searchJob?.cancel()
            searchJob = viewModelScope.launch(Dispatchers.IO) {
                _maxProgress.emit(PROGRESS_INDETERMINATE)

                ensureActive()
                (if (searchScope == R.id.search_album) albumPhotos else galleryPhotos).collect { remotePhotos ->
                    if (remotePhotos.isNotEmpty()) {
                        val nominatim = GeocoderNominatim(Locale.getDefault(), BuildConfig.APPLICATION_ID)
/*
                        val remoteBaseFolder = Tools.getRemoteHome(application)
                        val remoteCameraArchiveFolder = Tools.getArchiveBase(application)
                        val cr = application.contentResolver
                        val albums = AlbumRepository(application).getAllAlbumAttribute()

                        var latitude = Photo.GPS_DATA_UNKNOWN
                        var longitude = Photo.GPS_DATA_UNKNOWN
                        var altitude = Photo.GPS_DATA_UNKNOWN
                        var bearing = Photo.GPS_DATA_UNKNOWN
*/

                        resultList = mutableListOf()
                        _locationSearchResult.emit(resultList)
                        _maxProgress.emit(remotePhotos.size)

                        remotePhotos.asReversed().forEachIndexed { i, remotePhoto ->
                            remotePhoto.photo.let { photo ->
                                ensureActive()
                                _progress.emit(i)

                                if (photo.latitude == Photo.NO_GPS_DATA) return@forEachIndexed
                                else doubleArrayOf(photo.latitude, photo.longitude).let { latLong ->
                                    if (photo.country.isEmpty()) {
                                        ensureActive()
                                        try {
                                            nominatim.getFromLocation(latLong[0], latLong[1], 1)
                                        } catch (e: Exception) {
                                            null
                                        }?.let { result ->
                                            ensureActive()
                                            if (result.isEmpty()) null
                                            else {
                                                result[0]?.let { address ->
                                                    if (address.countryName != null) {
                                                        val locality = address.locality ?: address.adminArea ?: Photo.NO_ADDRESS
                                                        if (searchScope == R.id.search_album) photoRepository.updateAddress(photo.id, locality, address.countryName, address.countryCode ?: Photo.NO_ADDRESS)
                                                        //Pair(it.countryName, locality)
                                                        LocationAddress(address.countryName, locality, address.countryCode)
                                                    } else null
                                                }
                                            }
                                        }
                                    } else {
                                        LocationAddress(photo.country, photo.locality, photo.countryCode)
                                    }?.apply {
                                        ensureActive()
                                        resultList.find { result -> result.country == this.country && result.locality == this.locality }
                                            ?.let { existed ->
                                                existed.photos.add(remotePhoto)
                                                existed.total++
                                            }
                                            ?: run { resultList.add(LocationSearchResult(arrayListOf(remotePhoto), 1, this.country, this.locality, getFlagEmoji(this.countryCode.uppercase()))) }

                                        // Update UI
                                        _locationSearchResult.emit(resultList.toList())
                                    }
                                }

/*
                                if (Tools.hasExif(photo.mimeType)) {
                                    when (searchScope) {
                                        R.id.search_album -> {
                                            if (photo.latitude != Photo.NO_GPS_DATA)
                                            else return@forEachIndexed
                                        }
                                        R.id.search_gallery -> {
                                            try {
                                                cr.openInputStream(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.setRequireOriginal(Uri.parse(photo.id)) else Uri.parse(photo.id))
                                            } catch (e: SecurityException) {
                                                cr.openInputStream(Uri.parse(photo.id))
                                            } catch (e: UnsupportedOperationException) {
                                                cr.openInputStream(Uri.parse(photo.id))
                                            }?.let {
                                                try {
                                                    ExifInterface(it).latLong
                                                } catch (_: NullPointerException) {
                                                    return@forEachIndexed
                                                } catch (_: OutOfMemoryError) {
                                                    null
                                                }
                                            } ?: run { return@forEachIndexed }
                                        }
*/
/*
                        R.id.search_archive -> {
                        when(photo.latitude) {
                            Photo.NO_GPS_DATA -> return@forEachIndexed
                            Photo.GPS_DATA_UNKNOWN -> remoteImageModel.getMediaExif(NCShareViewModel.RemotePhoto(photo, remoteCameraArchiveFolder))?.let { exif ->
                                exif.latLong?.run {
                                    latitude = this[0]
                                    longitude = this[1]
                                    altitude = exif.getAltitude(Photo.NO_GPS_DATA)
                                    bearing = Tools.getBearing(exif)
                                } ?: run {
                                    latitude = Photo.NO_GPS_DATA
                                    longitude = Photo.NO_GPS_DATA
                                    altitude = Photo.NO_GPS_DATA
                                    bearing = Photo.NO_GPS_DATA
                                }

                                // Patch WebDAV properties in archive
                                // exif.dateTimeOriginal, exif.dateTimeDigitized both return timestamp in UTC time zone
                                // Property folder name should "/DCIM" here, SyncAdapter will handle user base correctly
                                // FIXME foldername under new backup folder structure
                                patchActions.add(Action(null, Action.ACTION_PATCH_PROPERTIES, "","/DCIM",
                                    "<oc:${OkHttpWebDav.LESPAS_LATITUDE}>" + latitude + "</oc:${OkHttpWebDav.LESPAS_LATITUDE}>" +
                                            "<oc:${OkHttpWebDav.LESPAS_LONGITUDE}>" + longitude + "</oc:${OkHttpWebDav.LESPAS_LONGITUDE}>" +
                                            "<oc:${OkHttpWebDav.LESPAS_ALTITUDE}>" + altitude + "</oc:${OkHttpWebDav.LESPAS_ALTITUDE}>" +
                                            "<oc:${OkHttpWebDav.LESPAS_BEARING}>" + bearing + "</oc:${OkHttpWebDav.LESPAS_BEARING}>" +
                                            "<oc:${OkHttpWebDav.LESPAS_ORIENTATION}>" + exif.rotationDegrees + "</oc:${OkHttpWebDav.LESPAS_ORIENTATION}>" +
                                            "<oc:${OkHttpWebDav.LESPAS_WIDTH}>" + exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0) + "</oc:${OkHttpWebDav.LESPAS_WIDTH}>" +
                                            "<oc:${OkHttpWebDav.LESPAS_HEIGHT}>" + exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0) + "</oc:${OkHttpWebDav.LESPAS_HEIGHT}>" +
                                            //"<oc:${OkHttpWebDav.LESPAS_DATE_TAKEN}>" + (exif.dateTimeOriginal ?: exif.dateTimeDigitized ?: try { (photo.dateTaken.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()) } catch (e: Exception) { System.currentTimeMillis() }) + "</oc:${OkHttpWebDav.LESPAS_DATE_TAKEN}>",
                                            "<oc:${OkHttpWebDav.LESPAS_DATE_TAKEN}>" + (exif.dateTimeOriginal ?: exif.dateTimeDigitized ?: try { (photo.dateTaken.atZone(ZoneId.of("Z")).toInstant().toEpochMilli()) } catch (e: Exception) { System.currentTimeMillis() }) + "</oc:${OkHttpWebDav.LESPAS_DATE_TAKEN}>",
                                    photo.name, System.currentTimeMillis(), 1)
                                )

                                exif.latLong
                            }
                            else -> doubleArrayOf(photo.latitude, photo.longitude)
                        }
                        }
*/
                            //else -> null
                            //}?.also { latLong ->
                            }
                        }

                        _progress.emit(PROGRESS_FINISH)
                    }
                }
            }
        }

        private fun getFlagEmoji(countryCode: String): String = String(Character.toChars(Character.codePointAt(countryCode, 0) - 0x41 + 0x1F1E6)) + String(Character.toChars(Character.codePointAt(countryCode, 1) - 0x41 + 0x1F1E6))

        fun stopSearching() {
            searchJob?.cancel()
            viewModelScope.launch {
                _progress.emit(PROGRESS_FINISH)
                _objectDetectResult.emit(mutableListOf())
                _locationSearchResult.emit(mutableListOf())
            }
        }

        override fun onCleared() {
            deviceGalleryLoadingJob?.cancel()
            od.close()

            super.onCleared()
        }

        data class ObjectDetectResult(
            val remotePhoto: NCShareViewModel.RemotePhoto,
            val subLabelIndex: Int,
            val similarity: Float,
        )

        data class LocationSearchResult(
            var photos: MutableList<NCShareViewModel.RemotePhoto>,
            var total: Int,
            val country: String,
            val locality: String,
            val flag: String,
        )

        data class LocationAddress(
            val country: String,
            val locality: String,
            val countryCode: String,
        )

        companion object {
            const val PROGRESS_INDETERMINATE = 0
            const val PROGRESS_FINISH = Int.MAX_VALUE
        }
    }
    
    companion object {
        private const val NO_ALBUM = "NO_ALBUM"

        @JvmStatic
        fun newInstance(noAlbum: Boolean) = SearchFragment().apply { arguments = Bundle().apply { putBoolean(NO_ALBUM, noAlbum) }}
    }
}