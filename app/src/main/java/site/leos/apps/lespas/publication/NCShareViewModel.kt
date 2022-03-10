package site.leos.apps.lespas.publication

import android.accounts.AccountManager
import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Parcelable
import android.provider.MediaStore
import android.util.Log
import android.util.LruCache
import android.util.Size
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceManager
import com.google.android.material.chip.Chip
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.parcelize.Parcelize
import okhttp3.CacheControl
import okhttp3.FormBody
import okhttp3.Response
import okhttp3.internal.headersContentLength
import okio.IOException
import okio.buffer
import okio.sink
import org.json.JSONException
import org.json.JSONObject
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.Album
import site.leos.apps.lespas.album.Cover
import site.leos.apps.lespas.cameraroll.CameraRollFragment
import site.leos.apps.lespas.helper.OkHttpWebDav
import site.leos.apps.lespas.helper.OkHttpWebDavException
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.photo.PhotoMeta
import site.leos.apps.lespas.photo.PhotoRepository
import site.leos.apps.lespas.settings.SettingsFragment
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.lang.Thread.sleep
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.concurrent.Executors
import kotlin.math.min
import kotlin.math.roundToInt

class NCShareViewModel(application: Application): AndroidViewModel(application) {
    private val _shareByMe = MutableStateFlow<List<ShareByMe>>(arrayListOf())
    private val _shareWithMe = MutableStateFlow<List<ShareWithMe>>(arrayListOf())
    private val _shareWithMeProgress = MutableStateFlow<Int>(0)
    private val _sharees = MutableStateFlow<List<Sharee>>(arrayListOf())
    private val _publicationContentMeta = MutableStateFlow<List<RemotePhoto>>(arrayListOf())
    val shareByMe: StateFlow<List<ShareByMe>> = _shareByMe
    val shareWithMe: StateFlow<List<ShareWithMe>> = _shareWithMe
    val shareWithMeProgress: StateFlow<Int> = _shareWithMeProgress
    val sharees: StateFlow<List<Sharee>> = _sharees
    val publicationContentMeta: StateFlow<List<RemotePhoto>> = _publicationContentMeta

    private var webDav: OkHttpWebDav

    private val baseUrl: String
    private val userName: String
    private val token: String
    private val resourceRoot: String
    private val lespasBase = application.getString(R.string.lespas_base_folder_name)
    private val localCacheFolder = "${Tools.getLocalRoot(application)}/cache"
    private val localFileFolder = Tools.getLocalRoot(application)

    private val sp = PreferenceManager.getDefaultSharedPreferences(application)
    private val autoReplayKey = application.getString(R.string.auto_replay_perf_key)

    private val photoRepository = PhotoRepository(application)

    fun interface LoadCompleteListener {
        fun onLoadComplete()
    }

    init {
        AccountManager.get(application).run {
            val account = getAccountsByType(application.getString(R.string.account_type_nc))[0]
            userName = getUserData(account, application.getString(R.string.nc_userdata_username))
            token = getUserData(account, application.getString(R.string.nc_userdata_secret))
            baseUrl = getUserData(account, application.getString(R.string.nc_userdata_server))
            resourceRoot = "$baseUrl${application.getString(R.string.dav_files_endpoint)}$userName"
            webDav = OkHttpWebDav(
                userName, peekAuthToken(account, baseUrl), baseUrl, getUserData(account, application.getString(R.string.nc_userdata_selfsigned)).toBoolean(), localCacheFolder,"LesPas_${application.getString(R.string.lespas_version)}",
                PreferenceManager.getDefaultSharedPreferences(application).getInt(SettingsFragment.CACHE_SIZE, 800)
            )
        }
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _sharees.value = refreshSharees()
            _shareByMe.value = refreshShareByMe()
            refreshShareWithMe()
        }
    }

    fun getCallFactory() = webDav.getCallFactory()

    fun getResourceRoot(): String = resourceRoot

    val themeColor: Flow<Int> = flow {
        var color = 0

        try {
            webDav.ocsGet("$baseUrl$CAPABILITIES_ENDPOINT")?.apply {
                color = Integer.parseInt(getJSONObject("data").getJSONObject("capabilities").getJSONObject("theming").getString("color").substringAfter('#'), 16)
            }
            if (color != 0) emit(color)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }.flowOn(Dispatchers.IO)

    private fun refreshShareByMe(): MutableList<ShareByMe> {
        val result = mutableListOf<ShareByMe>()
        var sharee: Recipient

        try {
            webDav.ocsGet("$baseUrl$SHARED_BY_ME_ENDPOINT")?.apply {
                val data = getJSONArray("data")
                for (i in 0 until data.length()) {
                    data.getJSONObject(i).apply {
                        if (getString("item_type") == "folder") {
                            // Only interested in shares of subfolders under lespas/
                            sharee = Recipient(getString("id"), getInt("permissions"), getLong("stime"), Sharee(getString("share_with"), getString("share_with_displayname"), getInt("share_type")))

                            @Suppress("SimpleRedundantLet")
                            result.find { share -> share.fileId == getString("item_source") }?.let { item ->
                                // If this folder existed in result, add new sharee only
                                item.with.add(sharee)
                            } ?: run {
                                // Create new folder share item
                                result.add(ShareByMe(getString("item_source"), getString("path").substringAfterLast('/'), mutableListOf(sharee)))
                            }
                        }
                    }
                }
            }

            return result
        } catch (e: IOException) {
            e.printStackTrace()
        } catch (e: IllegalStateException) {
            e.printStackTrace()
        } catch (e: JSONException) {
            e.printStackTrace()
        }

        return arrayListOf()
    }

    private fun refreshShareWithMe() {
        val result = mutableListOf<ShareWithMe>()

        _shareWithMeProgress.value = 0
        try {
            webDav.ocsGet("$baseUrl$SHARED_WITH_ME_ENDPOINT")?.apply {
                var folderId: String
                var permission: Int

                val data = getJSONArray("data")
                for (i in 0 until data.length()) {
                    data.getJSONObject(i).apply {
                        if (getString("item_type") == "folder") {
                            // Only interested in shares of subfolders under lespas/
                            folderId = getString("item_source")
                            permission = getInt("permissions")
                            result.find { existed -> existed.albumId == folderId }?.let { existed ->
                                // Existing sharedWithMe entry, we should keep the one with more permission bits set
                                if (existed.permission < permission) {
                                    existed.shareId = getString("id")
                                    existed.permission = permission
                                    existed.sharedTime = getLong("stime")
                                }
                            } ?: run {
                                // New sharedWithMe entry
                                result.add(
                                    ShareWithMe(
                                        getString("id"),
                                        getString("file_target"),
                                        folderId,
                                        getString("path").substringAfterLast('/'),
                                        getString("uid_owner"),
                                        getString("displayname_owner"),
                                        permission,
                                        getLong("stime"),
                                        Cover(Album.NO_COVER, 0, 0, 0, Album.NO_COVER, "", 0), Album.BY_DATE_TAKEN_ASC, 0L
                                    )
                                )
                            }
                        }
                    }
                }
            }

            if (result.isNotEmpty()) _shareWithMe.value = getAlbumMetaForShareWithMe(result).apply { sort() }

        } catch (e: IOException) {
            e.printStackTrace()
        } catch (e: IllegalStateException) {
            e.printStackTrace()
        } catch (e: JSONException) {
            e.printStackTrace()
        }
    }

    fun getShareWithMe() {
        viewModelScope.launch(Dispatchers.IO) { refreshShareWithMe() }
    }

    private fun getAlbumMetaForShareWithMe(shares: List<ShareWithMe>): MutableList<ShareWithMe> {
        val result = shares.toMutableList()

        // Get shares' last modified timestamp by PROPFIND each individual share path
        val lastModified = HashMap<String, Long>()
        val offset = OffsetDateTime.now().offset
        var sPath = "."     // A string that could never be a folder's name
        shares.forEach { share ->
            share.sharePath.substringBeforeLast('/').apply {
                if (this != sPath) {
                    sPath = this
                    webDav.list("${resourceRoot}${sPath}", OkHttpWebDav.FOLDER_CONTENT_DEPTH).drop(1).forEach { if (it.isFolder) lastModified[it.fileId] = it.modified.toEpochSecond(offset) }
                }
            }
        }

        // Retrieve share's meta data
        val total = shares.size
        shares.forEachIndexed { i, share ->
            _shareWithMeProgress.value = ((i * 100.0) / total).toInt()
            share.lastModified = lastModified[share.albumId] ?: 0L
            try {
                webDav.getStream("${resourceRoot}${share.sharePath}/${share.albumId}.json", true, CacheControl.FORCE_NETWORK).use {
                    JSONObject(it.bufferedReader().readText()).getJSONObject("lespas").let { meta ->
                        val version = try {
                            meta.getInt("version")
                        } catch (e: JSONException) {
                            1
                        }
                        share.cover = meta.getJSONObject("cover").run {
                            when {
                                // TODO Make sure later version of album meta file downward compatible
                                version >= 2 -> Cover(getString("id"), getInt("baseline"), getInt("width"), getInt("height"), getString("filename"), getString("mimetype"), getInt("orientation"))
                                // Version 1 of album meta json
                                else -> Cover(getString("id"), getInt("baseline"), getInt("width"), getInt("height"), getString("filename"), "image/jpeg", 0)
                            }
                        }
                        share.sortOrder = meta.getInt("sort")
                    }

                }
/*
                webDav.getStream("${resourceRoot}${share.sharePath}/${share.albumId}.json", true, CacheControl.FORCE_NETWORK).use {
                    JSONObject(it.bufferedReader().readText()).getJSONObject("lespas").let { meta ->
                        meta.getJSONObject("cover").apply {
                            share.cover = Cover(getString("id"), getInt("baseline"), getInt("width"), getInt("height"))
                            share.coverFileName = getString("filename")
                        }
                        share.sortOrder = meta.getInt("sort")
                    }

                }
*/
            } catch (e: Exception) {
                // Either there is no album meta json file in the folder, or json parse error means it's not a lespas share
            }
        }
        _shareWithMeProgress.value = 100

        return result.filter { it.cover.cover.isNotEmpty() }.toMutableList()
    }

    private fun refreshSharees(): MutableList<Sharee> {
        val result = mutableListOf<Sharee>()
        var backOff = 2500L

        while (true) {
            try {
                webDav.ocsGet("$baseUrl$SHAREE_LISTING_ENDPOINT")?.apply {
                    //if (getJSONObject("meta").getInt("statuscode") != 100) return null
                    val data = getJSONObject("data")
                    val users = data.getJSONArray("users")
                    for (i in 0 until users.length()) {
                        users.getJSONObject(i).apply {
                            result.add(Sharee(getString("shareWithDisplayNameUnique"), getString("label"), SHARE_TYPE_USER))
                        }
                    }
                    val groups = data.getJSONArray("groups")
                    for (i in 0 until groups.length()) {
                        groups.getJSONObject(i).apply {
                            result.add(Sharee(getJSONObject("value").getString("shareWith"), getString("label"), SHARE_TYPE_GROUP))
                        }
                    }
                }

                return result
            } catch (e: UnknownHostException) {
                // Retry for network unavailable, hope it's temporarily
                backOff *= 2
                sleep(backOff)
            } catch (e: SocketTimeoutException) {
                // Retry for network unavailable, hope it's temporarily
                backOff *= 2
                sleep(backOff)
            } catch (e: Exception) {
                e.printStackTrace()
                break
            }
        }

        return arrayListOf()
    }

    private fun createShares(albums: List<ShareByMe>) {
        for (album in albums) {
            for (recipient in album.with) {
                try {
                    webDav.ocsPost(
                        "$baseUrl$PUBLISH_ENDPOINT",
                        FormBody.Builder()
                            .add("path", "$lespasBase/${album.folderName}")
                            .add("shareWith", recipient.sharee.name)
                            .add("shareType", recipient.sharee.type.toString())
                            .add("permissions", recipient.permission.toString())
                            .build()
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun deleteShares(recipients: List<Recipient>) {
        for (recipient in recipients) {
            try {
                webDav.ocsDelete("$baseUrl$PUBLISH_ENDPOINT/${recipient.shareId}")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun publish(albums: List<ShareByMe>) {
        viewModelScope.launch(Dispatchers.IO) {
            createShares(albums)
            _shareByMe.value = refreshShareByMe()
        }
    }

    fun unPublish(albums: List<Album>) {
        viewModelScope.launch(Dispatchers.IO) {
            val recipients = mutableListOf<Recipient>()
            for (album in albums) {
                _shareByMe.value.find { it.fileId == album.id }?.apply { recipients.addAll(this.with) }
            }
            deleteShares(recipients)
            _shareByMe.value = refreshShareByMe()
        }
    }

    private fun createContentMeta(photoMeta: List<PhotoMeta>?, remotePhotos: List<RemotePhoto>?): String {
        var content = PHOTO_META_HEADER

        photoMeta?.forEach {
            //content += String.format(PHOTO_META_JSON, it.id, it.name, it.dateTaken.toEpochSecond(OffsetDateTime.now().offset), it.mimeType, it.width, it.height)
            content += String.format(PHOTO_META_JSON_V2, it.id, it.name, it.dateTaken.toEpochSecond(OffsetDateTime.now().offset), it.mimeType, it.width, it.height, it.orientation, it.caption, it.latitude, it.longitude, it.altitude, it.bearing)
        }

        remotePhotos?.forEach {
            //content += String.format(PHOTO_META_JSON, it.fileId, it.path.substringAfterLast('/'), it.timestamp, it.mimeType, it.width, it.height)
            with(it.photo) {
                content += String.format(PHOTO_META_JSON_V2, id, name, dateTaken.toEpochSecond(OffsetDateTime.now().offset), mimeType, width, height, orientation, caption, latitude, longitude, altitude, bearing)
            }
        }

        return content.dropLast(1) + "]}}"
    }

    fun createJointAlbumContentMetaFile(albumId: String, remotePhotos: List<RemotePhoto>?) {
        try {
            File("$localFileFolder/$albumId$CONTENT_META_FILE_SUFFIX").sink(false).buffer().use {
                it.write(createContentMeta(null, remotePhotos).encodeToByteArray())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun updatePublish(album: ShareByMe, removeRecipients: List<Recipient>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Remove sharees
                if (removeRecipients.isNotEmpty()) deleteShares(removeRecipients)

                // Add sharees
                if (album.with.isNotEmpty()) {
/*
                    if (!isShared(album.fileId)) {
                        // If sharing this album for the 1st time, create content.json on server
                        val content = createContentMeta(photoRepository.getPhotoMetaInAlbum(album.fileId), null)
                        webDav.upload(content, "${resourceRoot}${lespasBase}/${Uri.encode(album.folderName)}/${album.fileId}$CONTENT_META_FILE_SUFFIX", MIME_TYPE_JSON)
                    }
*/

                    createShares(listOf(album))
                }

                // Update _shareByMe hence update UI
                if (album.with.isNotEmpty() || removeRecipients.isNotEmpty()) _shareByMe.value = refreshShareByMe()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun renameShare(album: ShareByMe, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                webDav.move("$resourceRoot$lespasBase/${Uri.encode(album.folderName)}", "$resourceRoot$lespasBase/${Uri.encode(newName)}")
                deleteShares(album.with)
                album.folderName = newName
                createShares(listOf(album))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun isShared(albumId: String): Boolean = _shareByMe.value.indexOfFirst { it.fileId == albumId } != -1

    fun resetPublicationContentMeta() {
        _publicationContentMeta.value = mutableListOf()
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    suspend fun getRemotePhotoList(share: ShareWithMe, forceNetwork: Boolean) {
        var doRefresh = true

        withContext(Dispatchers.IO) {
            try {
                webDav.getStreamBool("${resourceRoot}${share.sharePath}/${share.albumId}$CONTENT_META_FILE_SUFFIX", true, if (forceNetwork) CacheControl.FORCE_NETWORK else null).apply {
                    if (forceNetwork || this.second) doRefresh = false
                    this.first.use { _publicationContentMeta.value = getContentMeta(it, share) }
                }

                if (doRefresh) webDav.getStream("${resourceRoot}${share.sharePath}/${share.albumId}$CONTENT_META_FILE_SUFFIX", true, CacheControl.FORCE_NETWORK).use { _publicationContentMeta.value = getContentMeta(it, share) }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun getContentMeta(inputStream: InputStream, share: ShareWithMe): List<RemotePhoto> {
        val result = mutableListOf<RemotePhoto>()

        val lespasJson = JSONObject(inputStream.bufferedReader().readText()).getJSONObject("lespas")
        val version = try {
            lespasJson.getInt("version")
        } catch (e: JSONException) {
            1
        }
        val photos = lespasJson.getJSONArray("photos")
        for (i in 0 until photos.length()) {
            photos.getJSONObject(i).apply {
                when {
                    // TODO make sure later version json file downward compatible
                    version >= 2 -> {
                        try {
                            getInt("orientation")
                            result.add(
                                RemotePhoto(
                                    Photo(
                                        id = getString("id"), name = getString("name"), mimeType = getString("mime"), width = getInt("width"), height = getInt("height"), lastModified = LocalDateTime.MIN, dateTaken = LocalDateTime.ofEpochSecond(getLong("stime"), 0, OffsetDateTime.now().offset),
                                        // Version 2 additions
                                        orientation = getInt("orientation"), caption = getString("caption"), latitude = getDouble("latitude"), longitude = getDouble("longitude"), altitude = getDouble("altitude"), bearing = getDouble("bearing"),
                                        // Should set eTag to value not as Photo.ETAG_NOT_YET_UPLOADED
                                        eTag = Photo.ETAG_FAKE
                                    ), share.sharePath
                                )
                            )
                        } catch (e: JSONException) {
                            result.add(
                                RemotePhoto(
                                    Photo(
                                        id = getString("id"), name = getString("name"), mimeType = getString("mime"), width = getInt("width"), height = getInt("height"), lastModified = LocalDateTime.MIN, dateTaken = LocalDateTime.ofEpochSecond(getLong("stime"), 0, OffsetDateTime.now().offset),
                                        // Should set eTag to value not as Photo.ETAG_NOT_YET_UPLOADED
                                        eTag = Photo.ETAG_FAKE
                                    ), share.sharePath
                                )
                            )
                        }
                    }
                    // Version 1 of content meta json
                    else -> {
                        result.add(
                            RemotePhoto(
                                Photo(
                                    id = getString("id"), name = getString("name"), mimeType = getString("mime"), width = getInt("width"), height = getInt("height"), lastModified = LocalDateTime.MIN, dateTaken = LocalDateTime.ofEpochSecond(getLong("stime"), 0, OffsetDateTime.now().offset),
                                    // Should set eTag to value not as Photo.ETAG_NOT_YET_UPLOADED
                                    eTag = Photo.ETAG_FAKE
                                ), share.sharePath
                            )
                        )
                    }
                }
            }
        }
        when (share.sortOrder) {
            Album.BY_NAME_ASC -> result.sortWith(compareBy { it.photo.name })
            Album.BY_NAME_DESC -> result.sortWith(compareByDescending { it.photo.name })
            Album.BY_DATE_TAKEN_ASC -> result.sortWith(compareBy { it.photo.dateTaken })
            Album.BY_DATE_TAKEN_DESC -> result.sortWith(compareByDescending { it.photo.dateTaken })
        }

        return result
    }

    fun acquireMediaFromShare(remotePhoto: RemotePhoto, toAlbum: Album) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val destFolder: String = when (toAlbum.id) {
                    PublicationDetailFragment.JOINT_ALBUM_ID -> "$resourceRoot${Uri.encode(toAlbum.name, "/")}"
                    else -> "$resourceRoot$lespasBase/${Uri.encode(toAlbum.name, "/")}".also { if (toAlbum.id.isEmpty()) webDav.createFolder(it) }
                }

                // TODO do we really need to update Joint Album's content meta file after modification?? the way doing this will generate this json file in current version, which will not be compatible with future one
                //  if we don't update, all changes to the Joint Album will be shown after the owner of it sync once, and conflict might happen during this period.
                //  This is really problematic since we don't have a proper server side app!!!
                // Copy media file on server. If file already exists in target folder, this will throw OkHttpWebDavException, it's OK since no more things need to do in this circumstance
                //webDav.copy("$resourceRoot${photo.path}", "${destFolder}/${Uri.encode(photo.path.substringAfterLast('/'))}")
                webDav.copy("$resourceRoot${remotePhoto.remotePath}/${remotePhoto.photo.name}", "${destFolder}/${remotePhoto.photo.name}")

                if (toAlbum.id == PublicationDetailFragment.JOINT_ALBUM_ID) {
                    // Update joint album's content meta. For user's own album, it's content meta will be updated during next server sync
                    // TODO: care for rollback if anything goes wrong?

                    // Target album's id is passed in property eTag
                    val targetShare = _shareWithMe.value.find { it.albumId == toAlbum.eTag }!!
                    var mediaList: MutableList<RemotePhoto>

                    webDav.getStream("${resourceRoot}${targetShare.sharePath}/${targetShare.albumId}$CONTENT_META_FILE_SUFFIX", true, CacheControl.FORCE_NETWORK).use { mediaList = getContentMeta(it, targetShare).toMutableList() }
                    if (!mediaList.isNullOrEmpty()) {
                        mediaList.add(remotePhoto)
                        when (targetShare.sortOrder) {
                            Album.BY_NAME_ASC -> mediaList.sortWith(compareBy { it.photo.name })
                            Album.BY_NAME_DESC -> mediaList.sortWith(compareByDescending { it.photo.name })
                            Album.BY_DATE_TAKEN_ASC -> mediaList.sortWith(compareBy { it.photo.dateTaken })
                            Album.BY_DATE_TAKEN_DESC -> mediaList.sortWith(compareByDescending { it.photo.dateTaken })
                        }
                        webDav.upload(createContentMeta(null, mediaList), "${resourceRoot}${targetShare.sharePath}/${targetShare.albumId}$CONTENT_META_FILE_SUFFIX", MIME_TYPE_JSON)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun getMediaExif(remotePhoto: RemotePhoto): Pair<ExifInterface, Long>? {
        var response: Response? = null
        var result: Pair<ExifInterface, Long>? = null

        try {
            response = webDav.getRawResponse("$resourceRoot${remotePhoto.remotePath}/${remotePhoto.photo.name}", true)
            result = Pair(ExifInterface(response.body!!.byteStream()), response.headersContentLength())
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            response?.close()
        }

        return result
    }

/*
    private fun getRemoteVideoThumbnail(remotePhoto: RemotePhoto): Bitmap? {
        var bitmap: Bitmap? = null

        val thumbnail = File(localCacheFolder, "${remotePhoto.photo.id}.thumbnail")

        // Load from local cache
        if (thumbnail.exists()) bitmap = BitmapFactory.decodeStream(thumbnail.inputStream())

        // Download from server
        bitmap ?: run {
            metadataRetriever.apply {
                setDataSource("$resourceRoot${Uri.encode(remotePhoto.remotePath, "/")}/${Uri.encode(remotePhoto.photo.name)}", HashMap<String, String>().apply { this["Authorization"] = "Basic $token" })
                bitmap = getFrameAtTime(0L) ?: videoThumbnail
            }

            // Cache thumbnail in local
            bitmap?.let {
                viewModelScope.launch(Dispatchers.IO) {
                    it.compress(Bitmap.CompressFormat.JPEG, 90, thumbnail.outputStream())
                }
            }
        }

        return bitmap
    }
*/

/*
    @SuppressLint("NewApi")
    @Suppress("BlockingMethodInNonBlockingContext")
    @JvmOverloads
    fun getPhoto(remotePhoto: RemotePhoto, view: ImageView, photoType: String, callBack: LoadCompleteListener? = null) {
        val photo = remotePhoto.photo
        val type = if (photo.mimeType.startsWith("video")) ImageLoaderViewModel.TYPE_VIDEO else photoType
        val jobKey = System.identityHashCode(view)

        //view.imageAlpha = 0
        var bitmap: Bitmap? = null
        var animatedDrawable: Drawable? = null
        val job = viewModelScope.launch(downloadDispatcher) {
            try {
                var key = "${photo.id}$type"
                if ((type == ImageLoaderViewModel.TYPE_COVER) || (type == ImageLoaderViewModel.TYPE_SMALL_COVER)) key = "$key-${remotePhoto.coverBaseLine}"

                imageCache.get(key)?.let { bitmap = it } ?: run {
                    // Get preview for TYPE_GRID. To speed up the process, should run Preview Generator app on Nextcloud server to pre-generate 1024x1024 size of preview files, if not, the 1st time of viewing this shared image would be slow
                    if (type == ImageLoaderViewModel.TYPE_GRID) try {
                        webDav.getStream("${baseUrl}${PREVIEW_ENDPOINT}${photo.id}", true, null).use { bitmap = BitmapFactory.decodeStream(it) }
                    } catch (e: Exception) {
                        // Catch all exception, give TYPE_GRID another chance below
                        e.printStackTrace()
                        bitmap = null
                    }

                    // If preview download fail (like no preview for video etc), or other types than TYPE_GRID, then we need to download the media file itself
                    bitmap ?: run {
                        val option = BitmapFactory.Options().apply {
                            // TODO the following setting make picture larger, care to find a new way?
                            //inPreferredConfig = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) Bitmap.Config.RGBA_F16 else Bitmap.Config.ARGB_8888
                        }

                        if (type == ImageLoaderViewModel.TYPE_VIDEO) bitmap = getRemoteVideoThumbnail(remotePhoto)
                        else webDav.getStream("$resourceRoot${remotePhoto.remotePath}/${photo.name}", true, null).use {
                            when (type) {
                                ImageLoaderViewModel.TYPE_COVER, ImageLoaderViewModel.TYPE_SMALL_COVER -> {
*/
/*
                                    // If album's cover size changed from other ends, like picture cropped on server, SyncAdapter will not handle the changes, the baseline could be invalid
                                    // TODO better way to handle this
                                    val top = if (photo.coverBaseLine > photo.height - 1) 0 else photo.coverBaseLine

                                    val bottom = min(top + (photo.width.toFloat() * 9 / 21).toInt(), photo.height - 1)
                                    val rect = Rect(0, top, photo.width - 1, bottom)
*//*

                                    val rect = when (photo.orientation) {
                                        0 -> Rect(0, remotePhoto.coverBaseLine, photo.width - 1, min(remotePhoto.coverBaseLine + (photo.width.toFloat() * 9 / 21).toInt(), photo.height - 1))
                                        90 -> Rect(remotePhoto.coverBaseLine, 0, min(remotePhoto.coverBaseLine + (photo.height.toFloat() * 9 / 21).toInt(), photo.width - 1), photo.height - 1)
                                        180 -> (photo.height - remotePhoto.coverBaseLine).let { Rect(0, Integer.max(it - (photo.width.toFloat() * 9 / 21).toInt(), 0), photo.width - 1, it) }
                                        else -> (photo.width - remotePhoto.coverBaseLine).let { Rect(Integer.max(it - (photo.height.toFloat() * 9 / 21).toInt(), 0), 0, it, photo.height - 1) }
                                    }

                                    var sampleSize = when (photo.width) {
                                        in (0..2000) -> 1
                                        in (2000..3000) -> 2
                                        else -> 4
                                    }
                                    if (type == ImageLoaderViewModel.TYPE_SMALL_COVER) sampleSize *= 2

                                    try {
                                        @Suppress("DEPRECATION")
                                        bitmap = (if (Build.VERSION.SDK_INT > Build.VERSION_CODES.R) BitmapRegionDecoder.newInstance(it) else BitmapRegionDecoder.newInstance(it, false))?.decodeRegion(rect, option.apply { inSampleSize = sampleSize })
                                        if (photo.orientation != 0) bitmap?.let { bitmap = Bitmap.createBitmap(bitmap!!, 0, 0, bitmap!!.width, bitmap!!.height, Matrix().apply { preRotate(photo.orientation.toFloat()) }, true) }

                                        bitmap
                                    } catch (e: IOException) {
                                        // No information on cover's mimetype
                                        // Video only album has video file as cover, BitmapRegionDecoder will throw IOException with "Image format not supported" stack trace message
                                        //e.printStackTrace()
                                        it.close()
                                        //webDav.getStream(photoPath, true,null).use { vResp-> bitmap = getRemoteVideoThumbnail(vResp, photo) }
                                        bitmap = getRemoteVideoThumbnail(remotePhoto)
                                    }
                                }
                                ImageLoaderViewModel.TYPE_GRID -> {
                                    // If preview is not available, we have to use the actual image file
                                    //if (photo.mimeType.startsWith("video")) bitmap = getRemoteVideoThumbnail(it, photo)
                                    //else bitmap = BitmapFactory.decodeStream(it, null, option.apply { inSampleSize = if (photo.width < 2000) 2 else 8 })
                                    bitmap = BitmapFactory.decodeStream(it, null, option.apply { inSampleSize = if (photo.width < 2000) 2 else 8 })
                                    if (photo.orientation != 0) bitmap?.let { bitmap = Bitmap.createBitmap(bitmap!!, 0, 0, bitmap!!.width, bitmap!!.height, Matrix().apply { preRotate((photo.orientation).toFloat()) }, true) }
                                    bitmap
                                }
                                ImageLoaderViewModel.TYPE_FULL, ImageLoaderViewModel.TYPE_QUATER -> {
                                    // Show cached low resolution bitmap first
                                    imageCache.get("${photo.id}${ImageLoaderViewModel.TYPE_GRID}")?.let {
                                        withContext(Dispatchers.Main) { view.setImageBitmap(it) }
                                        callBack?.onLoadComplete()
                                    }

                                    when {
                                        //photo.mimeType.startsWith("video")-> bitmap = getRemoteVideoThumbnail(it, photo)
                                        (photo.mimeType == "image/awebp" || photo.mimeType == "image/agif") -> {
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                                animatedDrawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(it.readBytes())))
                                            } else {
                                                bitmap = BitmapFactory.decodeStream(it, null, option.apply { inSampleSize = if (photo.width < 2000) 2 else 8 })
                                            }
                                        }
                                        else -> {
                                            // Large photo, allocationByteCount could exceed 100,000,000 bytes if fully decoded
                                            option.inSampleSize = if (photo.width * photo.height > 33333334) 2 else 1
                                            if (type == ImageLoaderViewModel.TYPE_QUATER) option.inSampleSize *= 2
                                            bitmap = BitmapFactory.decodeStream(it, null, option)
                                            // Rotate bitmap to upright position
                                            if (photo.orientation != 0) bitmap?.let { bitmap = Bitmap.createBitmap(bitmap!!, 0, 0, bitmap!!.width, bitmap!!.height, Matrix().apply { preRotate((photo.orientation).toFloat()) }, true) }
                                            bitmap
                                        }
                                    }
                                }
                                else -> null
                            }
                        }
                    }
                    if (bitmap != null && type != ImageLoaderViewModel.TYPE_FULL) imageCache.put(key, bitmap)
                }
            } catch (e: OkHttpWebDavException) {
                Log.e(">>>>>>>>>>", "${e.statusCode} ${e.stackTraceString}")
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                if (isActive) withContext(Dispatchers.Main) {
                    animatedDrawable?.let {
                        view.setImageDrawable(it.apply {
                            (this as AnimatedImageDrawable).apply {
                                if (sp.getBoolean(autoReplayKey, true)) this.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                                start()
                            }
                        })
                    } ?: run { view.setImageBitmap(bitmap ?: placeholderBitmap) }
                    //view.imageAlpha = 255
                }
                callBack?.onLoadComplete()
            }
        }

        // Replacing previous job
        replacePrevious(jobKey, job)
    }
*/

    @Suppress("BlockingMethodInNonBlockingContext")
    fun getAvatar(user: Sharee, view: View, callBack: LoadCompleteListener?) {
        val jobKey = System.identityHashCode(view)

        val job = viewModelScope.launch(downloadDispatcher) {
            var bitmap: Bitmap? = null
            var drawable: Drawable? = null
            try {
                if (user.type == SHARE_TYPE_GROUP) drawable = ContextCompat.getDrawable(view.context, R.drawable.ic_baseline_group_24)
                else {
                    // Only user has avatar
                    val key = "${user.name}-avatar"
                    imageCache.get(key)?.let { bitmap = it } ?: run {
                        // Set default avatar first
                        if (isActive) withContext(Dispatchers.Main) {
                            ContextCompat.getDrawable(view.context, R.drawable.ic_baseline_person_24)?.apply {
                                when (view) {
                                    is Chip -> view.chipIcon = this
                                    is TextView -> {
                                        (view.textSize * 1.2).roundToInt().let {
                                            val size = maxOf(48, it)
                                            this.setBounds(0, 0, size, size)
                                        }
                                        view.setCompoundDrawables(this, null, null, null)
                                    }
                                }
                            }
                        }

                        webDav.getStream("${baseUrl}${AVATAR_ENDPOINT}${Uri.encode(user.name)}/64", true, null).use { bitmap = BitmapFactory.decodeStream(it) }

                        bitmap?.let { imageCache.put(key, it) }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                if (isActive) withContext(Dispatchers.Main) {
                    if (drawable == null && bitmap != null) drawable = BitmapDrawable(view.resources, Tools.getRoundBitmap(view.context, bitmap!!))
                    drawable?.run {
                        when (view) {
                            is Chip -> view.chipIcon = this
                            is TextView -> {
                                (view.textSize * 1.2).roundToInt().let {
                                    val size = maxOf(48, it)
                                    this.setBounds(0, 0, size, size)
                                }
                                view.setCompoundDrawables(this, null, null, null)
                            }
                        }
                    }
                }

                callBack?.onLoadComplete()
            }
        }

        // Replacing previous job
        replacePrevious(jobKey, job)
    }

    fun getPreview(remotePhoto: RemotePhoto): Bitmap? {
        var bitmap: Bitmap?
        webDav.getStream("${baseUrl}${PREVIEW_ENDPOINT}${remotePhoto.photo.id}", true, null).use { bitmap = BitmapFactory.decodeStream(it) }
        bitmap ?: run {
            webDav.getStream("$resourceRoot${remotePhoto.remotePath}/${remotePhoto.photo.name}", true, null).use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 8 })
            }
        }

        return bitmap
    }

    fun downloadFile(media: String, dest: File, stripExif: Boolean): Boolean {
        return try {
            webDav.getStream("${resourceRoot}${media}", true, null).use { remote ->
                if (stripExif) BitmapFactory.decodeStream(remote)?.compress(Bitmap.CompressFormat.JPEG, 95, dest.outputStream())
                else dest.outputStream().use { local -> remote.copyTo(local, 8192) }
            }

            true
        } catch (e: Exception) {
            e.printStackTrace()

            false
        }
    }

    fun savePhoto(context: Context, remotePhoto: RemotePhoto) {
        if (remotePhoto.photo.mimeType.startsWith("image")) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val cr = context.contentResolver
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val mediaDetails = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, remotePhoto.photo.name)
                            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                            put(MediaStore.MediaColumns.MIME_TYPE, remotePhoto.photo.mimeType)
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        }
                        cr.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), mediaDetails)?.let { uri ->
                            cr.openOutputStream(uri)?.use { local ->
                                webDav.getStream("$resourceRoot${remotePhoto.remotePath}/${remotePhoto.photo.name}", true, null).use { remote ->
                                    remote.copyTo(local, 8192)

                                    mediaDetails.clear()
                                    mediaDetails.put(MediaStore.MediaColumns.IS_PENDING, 0)
                                    cr.update(uri, mediaDetails, null, null)
                                }
                            }
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        val fileName = "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)}/${remotePhoto.photo.name}"
                        File(fileName).outputStream().use { local ->
                            webDav.getStream("$resourceRoot${remotePhoto.remotePath}/${remotePhoto.photo.name}", true, null).use { remote ->
                                remote.copyTo(local, 8192)
                            }
                        }
                        MediaScannerConnection.scanFile(context, arrayOf(fileName), arrayOf(remotePhoto.photo.mimeType), null)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } else {
            // Video is now streaming, there is no local cache available, and might take some time to download, so we resort to Download Manager
            (context.getSystemService(Activity.DOWNLOAD_SERVICE) as DownloadManager).enqueue(
                DownloadManager.Request(Uri.parse("$resourceRoot${remotePhoto.remotePath}/${remotePhoto.photo.name}"))
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, remotePhoto.photo.name)
                    .setTitle(remotePhoto.photo.name)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .addRequestHeader("Authorization", "Basic $token")
            )
        }
    }

/*
    fun savePhoto(context: Context, photo: RemotePhoto) {
        // Clone a new HttpClient to avoid leaking webDav
        WorkManager.getInstance(context).enqueueUniqueWork("DOWNLOAD_${photo.fileId}", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<DownloadWorker>().setInputData(workDataOf(
                DownloadWorker.REMOTE_PHOTO_PATH_KEY to photo.path, DownloadWorker.REMOTE_PHOTO_MIMETYPE_KEY to photo.mimeType, DownloadWorker.RESOURCE_ROOT_KEY to resourceRoot)
            ).build()
        )
    }

    class DownloadWorker(private val context: Context, workerParams: WorkerParameters): CoroutineWorker(context, workerParams) {
        @Suppress("BlockingMethodInNonBlockingContext")
        override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
            val cr = context.contentResolver
            val photoPath = inputData.keyValueMap[REMOTE_PHOTO_PATH_KEY] as String
            val photoMimetype = inputData.keyValueMap[REMOTE_PHOTO_MIMETYPE_KEY] as String
            val resourceRoot = inputData.keyValueMap[RESOURCE_ROOT_KEY] as String

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val mediaDetails = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, photoPath.substringAfterLast('/'))
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        put(MediaStore.MediaColumns.MIME_TYPE, photoMimetype)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    cr.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), mediaDetails)?.let { uri ->
                        cr.openOutputStream(uri)?.use { local ->
                            httpClient.newCall(Request.Builder().url("$resourceRoot${photoPath}").build()).execute().body?.byteStream()?.use { remote->
                                remote.copyTo(local, 8192)

                                mediaDetails.clear()
                                mediaDetails.put(MediaStore.MediaColumns.IS_PENDING, 0)
                                cr.update(uri, mediaDetails, null, null)

                                Result.success()
                            }
                        }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), photoPath.substringAfterLast('/')).outputStream().use { local ->
                        httpClient.newCall(Request.Builder().url("$resourceRoot${photoPath}").build()).execute().body?.byteStream()?.use { remote->
                            remote.copyTo(local, 8192)

                            Result.success()
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            Result.failure()
        }

        companion object {
            const val REMOTE_PHOTO_PATH_KEY = "REMOTE_PHOTO_PATH_KEY"
            const val REMOTE_PHOTO_MIMETYPE_KEY = "REMOTE_PHOTO_MIMETYPE_KEY"
            const val WEBDAV_KEY = "WEBDAV_KEY"
            const val RESOURCE_ROOT_KEY = "RESOURCE_ROOT_KEY"
        }
    }

*/

    private val cr = application.contentResolver
    private val placeholderBitmap = ContextCompat.getDrawable(application, R.drawable.ic_baseline_placeholder_24)!!.toBitmap()
    private val loadingDrawable = ContextCompat.getDrawable(application, R.drawable.animated_placeholder) as AnimatedVectorDrawable
    private val downloadDispatcher = Executors.newFixedThreadPool(3).asCoroutineDispatcher()
    private val imageCache = ImageCache(((application.getSystemService(Context.ACTIVITY_SERVICE)) as ActivityManager).memoryClass / MEMORY_CACHE_SIZE * 1024 * 1024)
    private val decoderJobMap = HashMap<Int, Job>()

    @SuppressLint("NewApi")
    @Suppress("BlockingMethodInNonBlockingContext")
    fun setImagePhoto(imagePhoto: RemotePhoto, view: ImageView, viewType: String, callBack: LoadCompleteListener? = null) {
        val jobKey = System.identityHashCode(view)

        val job = viewModelScope.launch(downloadDispatcher) {
            var bitmap: Bitmap? = null
            var animatedDrawable: Drawable? = null
            val forceNetwork = imagePhoto.photo.shareId and Photo.NEED_REFRESH == Photo.NEED_REFRESH

            try {
                var type = if (imagePhoto.photo.mimeType.startsWith("video")) TYPE_VIDEO else viewType
                var key = "${imagePhoto.photo.id}$type"
                if ((type == TYPE_COVER) || (type == TYPE_SMALL_COVER)) key = "$key-${imagePhoto.coverBaseLine}"

                (if (forceNetwork) null else imageCache.get(key))?.let {
                    bitmap = it
                    //Log.e(">>>>>>>>>","got cache hit $key")
                } ?: run {
                    // Cache missed

                    bitmap = when (type) {
                        TYPE_VIDEO -> {
                            getVideoThumbnail(imagePhoto)
                        }
                        TYPE_GRID, TYPE_IN_MAP -> {
                            val thumbnailSize = if ((imagePhoto.photo.height < 1440) || (imagePhoto.photo.width < 1440)) 2 else 8
                            when {
                                imagePhoto.remotePath.isNotEmpty() && imagePhoto.photo.eTag != Photo.ETAG_NOT_YET_UPLOADED -> getRemoteThumbnail(imagePhoto, view, type, forceNetwork)
                                imagePhoto.photo.albumId == CameraRollFragment.FROM_CAMERA_ROLL -> {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                        ImageDecoder.decodeBitmap(ImageDecoder.createSource(cr, Uri.parse(imagePhoto.photo.id))) { decoder, _, _ -> decoder.setTargetSampleSize(thumbnailSize) }
                                        // TODO: For photo captured in Sony Xperia machine, loadThumbnail will load very small size bitmap
                                        //contentResolver.loadThumbnail(Uri.parse(photo.id), Size(photo.width/8, photo.height/8), null)
                                    } else {
                                        @Suppress("DEPRECATION")
                                        MediaStore.Images.Thumbnails.getThumbnail(cr, imagePhoto.photo.id.substringAfterLast('/').toLong(), MediaStore.Images.Thumbnails.MINI_KIND, null).run {
                                            if (imagePhoto.photo.orientation != 0) Bitmap.createBitmap(this, 0, 0, this.width, this.height, Matrix().also { it.preRotate(imagePhoto.photo.orientation.toFloat()) }, true)
                                            else this
                                        }
                                    }
                                }
                                else -> {
                                    // File is available locally, already rotated to it's upright position. Fall back to remote
                                    BitmapFactory.decodeFile("${localFileFolder}/${imagePhoto.photo.id}", BitmapFactory.Options().apply { inSampleSize = thumbnailSize }) ?: run { getRemoteThumbnail(imagePhoto, view, type) }
                                }
                            }
                        }
                        else -> {
                            if (imagePhoto.coverBaseLine == Album.SPECIAL_COVER_BASELINE) type = TYPE_FULL

                            when {
                                imagePhoto.remotePath.isNotEmpty() && imagePhoto.photo.eTag != Photo.ETAG_NOT_YET_UPLOADED -> {
                                    withContext(Dispatchers.Main) { view.background = loadingDrawable.apply { start() }}
                                    webDav.getStream("$resourceRoot${imagePhoto.remotePath}/${imagePhoto.photo.name}", true, null)
                                }
                                imagePhoto.photo.albumId == CameraRollFragment.FROM_CAMERA_ROLL -> cr.openInputStream(Uri.parse(imagePhoto.photo.id))
                                else -> try {
                                    File("${localFileFolder}/${imagePhoto.photo.id}").inputStream()
                                } catch (e: FileNotFoundException) {
                                    // Fall back to network fetching if loading local file failed
                                    withContext(Dispatchers.Main) { view.background = loadingDrawable.apply { start() }}
                                    webDav.getStream("$resourceRoot${imagePhoto.remotePath}/${imagePhoto.photo.name}", true, null)
                                }
                            }?.use { sourceStream ->
                                when (type) {
                                    TYPE_FULL, TYPE_QUATER -> {
                                        // Show cached low resolution bitmap first
                                        imageCache.get("${imagePhoto.photo.id}${TYPE_GRID}")?.let {
                                            //Log.e(">>>>>>>>>>>>", "show GRID version 1st")
                                            withContext(Dispatchers.Main) {
                                                view.setImageBitmap(it)
                                                view.background = null
                                            }
                                            callBack?.onLoadComplete()
                                        }

                                        when {
                                            (imagePhoto.photo.mimeType == "image/awebp" || imagePhoto.photo.mimeType == "image/agif") -> {
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                                    animatedDrawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(sourceStream.readBytes())))
                                                    null
                                                } else {
                                                    BitmapFactory.decodeStream(sourceStream, null, BitmapFactory.Options().apply { inSampleSize = if (imagePhoto.photo.width < 2000) 2 else 8 })
                                                }
                                            }
                                            else -> {
                                                // Large photo, allocationByteCount could exceed 100,000,000 bytes if fully decoded
                                                val option = BitmapFactory.Options().apply {
                                                    inSampleSize = if (imagePhoto.photo.width * imagePhoto.photo.height > 33333334) 2 else 1
                                                    if (type == TYPE_QUATER) inSampleSize *= 2
                                                }

                                                BitmapFactory.decodeStream(sourceStream, null, option)?.run {
                                                    if (
                                                        imagePhoto.photo.orientation != 0 &&
                                                        ((imagePhoto.remotePath.isNotEmpty() && imagePhoto.photo.eTag != Photo.ETAG_NOT_YET_UPLOADED) || imagePhoto.photo.albumId == CameraRollFragment.FROM_CAMERA_ROLL)
                                                    ) Bitmap.createBitmap(this, 0, 0, width, height, Matrix().apply { preRotate((imagePhoto.photo.orientation).toFloat()) }, true)
                                                    else this
                                                }
                                            }
                                        }
                                    }
                                    TYPE_COVER, TYPE_SMALL_COVER -> {
                                        //Log.e(">>>>>>>>>>>", "$key $imagePhoto")
                                        var width = imagePhoto.photo.width
                                        var height = imagePhoto.photo.height
                                        var orientation = imagePhoto.photo.orientation

                                        if (imagePhoto.photo.albumId == CameraRollFragment.FROM_CAMERA_ROLL) {
                                            if (orientation == 90 || orientation == 270) {
                                                width = imagePhoto.photo.height
                                                height = imagePhoto.photo.width
                                            }
                                        } else {
                                            if (!(imagePhoto.remotePath.isNotEmpty() && imagePhoto.photo.eTag != Photo.ETAG_NOT_YET_UPLOADED)) orientation = 0
                                        }
                                        val rect =
                                            when (orientation) {
                                                0 -> Rect(0, imagePhoto.coverBaseLine, width - 1, min(imagePhoto.coverBaseLine + (width.toFloat() * 9 / 21).toInt(), height - 1))
                                                90 -> Rect(imagePhoto.coverBaseLine, 0, min(imagePhoto.coverBaseLine + (width.toFloat() * 9 / 21).toInt(), height - 1), width - 1)
                                                180 -> (height - imagePhoto.coverBaseLine).let { Rect(0, Integer.max(it - (width.toFloat() * 9 / 21).toInt(), 0), width - 1, it) }
                                                else -> (height - imagePhoto.coverBaseLine).let { Rect(Integer.max(it - (width.toFloat() * 9 / 21).toInt(), 0), 0, it, width - 1) }
                                            }

                                        var sampleSize = when (width) {
                                            in (0..1439) -> 1
                                            in (1439..3000) -> 2
                                            else -> 4
                                        }
                                        if (type == TYPE_SMALL_COVER) sampleSize *= 2

                                        try {
                                            @Suppress("DEPRECATION")
                                            (if (Build.VERSION.SDK_INT > Build.VERSION_CODES.R) BitmapRegionDecoder.newInstance(sourceStream) else BitmapRegionDecoder.newInstance(sourceStream, false))?.decodeRegion(rect, null)?.let { bmp ->
                                                if (orientation != 0) Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { preRotate(orientation.toFloat()) }, true)
                                                else bmp
                                            }
                                        } catch (e: Exception) {
                                            // Fall back to video
                                            // TODO this is for v1 meta which do not contain cover's mimetype information, should be remove in future release
                                            getVideoThumbnail(imagePhoto)
                                        }
                                    }
                                    else -> {
                                        null
                                    }
                                }
                            }
                        }
                    }

                    if (bitmap != null && type != TYPE_FULL) imageCache.put(key, bitmap)
                }
            }
            catch (e: OkHttpWebDavException) {
                Log.e(">>>>>>>>>>", "${e.statusCode} ${e.stackTraceString}")
            }
            catch (e: Exception) {
                e.printStackTrace()
            }
            finally {
                if (isActive) withContext(Dispatchers.Main) {
                    animatedDrawable?.let {
                        view.setImageDrawable(it.apply {
                            (this as AnimatedImageDrawable).apply {
                                if (sp.getBoolean(autoReplayKey, true)) this.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                                start()
                            }
                        })
                    } ?: run { view.setImageBitmap(bitmap ?: placeholderBitmap) }
                    //view.imageAlpha = 255

                    // Stop loading indicator
                    view.background = null
                }
                callBack?.onLoadComplete()
            }
        }

        // Replacing previous job
        replacePrevious(jobKey, job)
    }

    private fun getVideoThumbnail(imagePhoto: RemotePhoto): Bitmap? {
        return try {
            if (imagePhoto.photo.albumId == CameraRollFragment.FROM_CAMERA_ROLL) {
                val photoId = imagePhoto.photo.id.substringAfterLast('/').toLong()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        cr.loadThumbnail(Uri.parse(imagePhoto.photo.id), Size(imagePhoto.photo.width, imagePhoto.photo.height), null)
                    } catch (e: ArithmeticException) {
                        // Some Android Q Rom, like AEX for EMUI 9, throw this exception
                        @Suppress("DEPRECATION")
                        MediaStore.Video.Thumbnails.getThumbnail(cr, photoId, MediaStore.Video.Thumbnails.MINI_KIND, null)
                    }
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Video.Thumbnails.getThumbnail(cr, photoId, MediaStore.Video.Thumbnails.MINI_KIND, null)
                }
            } else {
                var bitmap: Bitmap? = null
                val thumbnail = File(if (imagePhoto.remotePath.isEmpty()) localFileFolder else localCacheFolder, "${imagePhoto.photo.id}.thumbnail")

                // Load from local cache
                if (thumbnail.exists()) bitmap = BitmapFactory.decodeStream(thumbnail.inputStream())

                // Download from server
                bitmap ?: run {
                    MediaMetadataRetriever().apply {
                        if (imagePhoto.remotePath.isNotEmpty() && imagePhoto.photo.eTag != Photo.ETAG_NOT_YET_UPLOADED)
                            setDataSource("$resourceRoot${Uri.encode(imagePhoto.remotePath, "/")}/${Uri.encode(imagePhoto.photo.name)}", HashMap<String, String>().apply { this["Authorization"] = "Basic $token" })
                        else setDataSource("${localFileFolder}/${imagePhoto.photo.id}")
                        bitmap = getFrameAtTime(0L)
                        release()
                    }

                    // Cache thumbnail in local
                    bitmap?.compress(Bitmap.CompressFormat.JPEG, 90, thumbnail.outputStream())
                }

                bitmap
            }
        } catch (e: Exception) { null }
    }

    private suspend fun getRemoteThumbnail(imagePhoto: RemotePhoto, view: ImageView, type: String, forceNetwork: Boolean = false): Bitmap? {
        var bitmap: Bitmap?

        // Nextcloud will not provide preview for webp, heic/heif, if preview is available, then it's rotated by Nextcloud to upright position
        bitmap = try {
            withContext(Dispatchers.Main) { view.background = loadingDrawable.apply { start() }}
            webDav.getStream("${baseUrl}${PREVIEW_ENDPOINT}${imagePhoto.photo.id}", true, if (forceNetwork) CacheControl.FORCE_NETWORK else null).use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = if (type == TYPE_GRID) 2 else 1 })
            }
        } catch(e: Exception) { null }

        bitmap ?: run {
            // If preview is not available, we have to use the actual image file
            webDav.getStream("$resourceRoot${imagePhoto.remotePath}/${imagePhoto.photo.name}", true,null).use {
                bitmap = BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = if ((imagePhoto.photo.height < 1440) || (imagePhoto.photo.width < 1440)) 2 else 8 })
            }
            if (imagePhoto.photo.orientation != 0) bitmap?.let { bitmap = Bitmap.createBitmap(bitmap!!, 0, 0, bitmap!!.width, bitmap!!.height, Matrix().apply { preRotate((imagePhoto.photo.orientation).toFloat()) }, true) }
        }

        bitmap?.let { if (forceNetwork) photoRepository.resetNetworkRefresh(imagePhoto.photo.id) }

        return bitmap
    }

/*
    private suspend fun getImageThumbNail(imagePhoto: RemotePhoto, view: ImageView): Bitmap? {
        try {
            val thumbnailSize = if ((imagePhoto.photo.height < 1440) || (imagePhoto.photo.width < 1440)) 2 else 8
            val option = BitmapFactory.Options().apply { inSampleSize = thumbnailSize }
            return when {
                // File is not available locally
                // Nextcloud will not provide preview for webp, heic/heif, if preview is available, then it's rotated by Nextcloud to upright position
                imagePhoto.remotePath.isNotEmpty() && imagePhoto.photo.eTag != Photo.ETAG_NOT_YET_UPLOADED -> {
                    getRemoteThumbnail(imagePhoto, view, "")
                }
                // From camera roll
                imagePhoto.photo.albumId == CameraRollFragment.FROM_CAMERA_ROLL -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ImageDecoder.decodeBitmap(ImageDecoder.createSource(cr, Uri.parse(imagePhoto.photo.id))) { decoder, _, _ -> decoder.setTargetSampleSize(thumbnailSize)}
                        // TODO: For photo captured in Sony Xperia machine, loadThumbnail will load very small size bitmap
                        //contentResolver.loadThumbnail(Uri.parse(photo.id), Size(photo.width/8, photo.height/8), null)
                    } else {
                        @Suppress("DEPRECATION")
                        MediaStore.Images.Thumbnails.getThumbnail(cr, imagePhoto.photo.id.substringAfterLast('/').toLong(), MediaStore.Images.Thumbnails.MINI_KIND, null).run {
                            if (imagePhoto.photo.orientation != 0) Bitmap.createBitmap(this, 0, 0, this.width, this.height, Matrix().also { it.preRotate(imagePhoto.photo.orientation.toFloat()) }, true)
                            else this
                        }
                    }
                }
                else -> {
                    // File is available locally, already rotated to it's upright position
                    BitmapFactory.decodeFile("${localFileFolder}/${imagePhoto.photo.id}", option)

                }
            }
        } catch (e: Exception) { return null }
    }

*/
    fun cancelSetImagePhoto(view: View) {
        decoderJobMap[System.identityHashCode(view)]?.cancel()
    }

    fun invalidPhoto(photoId: String) {
        imageCache.snapshot().keys.forEach { key-> if (key.startsWith(photoId)) imageCache.remove(key) }
    }

    private fun replacePrevious(key: Int, newJob: Job) {
        decoderJobMap[key]?.cancel()
        decoderJobMap[key] = newJob
    }

    override fun onCleared() {
        //File(localCacheFolder, OkHttpWebDav.VIDEO_CACHE_FOLDER).deleteRecursively()
        decoderJobMap.forEach { if (it.value.isActive) it.value.cancel() }
        downloadDispatcher.close()
        super.onCleared()
    }

    class ImageCache(maxSize: Int) : LruCache<String, Bitmap>(maxSize) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    @Parcelize
    data class Sharee(
        var name: String,
        var label: String,
        var type: Int,
    ) : Parcelable

    @Parcelize
    data class Recipient(
        var shareId: String,
        var permission: Int,
        var sharedTime: Long,
        var sharee: Sharee,
    ) : Parcelable

    @Parcelize
    data class ShareByMe(
        var fileId: String,
        var folderName: String,
        var with: MutableList<Recipient>,
    ) : Parcelable

    @Parcelize
    data class ShareWithMe(
        var shareId: String,
        var sharePath: String,
        var albumId: String,
        var albumName: String,
        var shareBy: String,
        var shareByLabel: String,
        var permission: Int,
        var sharedTime: Long,
        var cover: Cover,
        var sortOrder: Int,
        var lastModified: Long,
    ) : Parcelable, Comparable<ShareWithMe> {
        override fun compareTo(other: ShareWithMe): Int = (other.lastModified - this.lastModified).toInt()
    }

    @Parcelize
    data class RemotePhoto(
        val photo: Photo,
        val remotePath: String = "",
        val coverBaseLine: Int = 0,
    ) : Parcelable

/*
    @Parcelize
    data class ImagePhoto(
        val photo: Photo,
        val type: String,
        val remotePath: String = "",    // Empty means photo
        val coverBaseLine: Int = 0,
    ) : Parcelable
*/

    companion object {
        const val TYPE_NULL = ""    // For startPostponedEnterTransition() immediately for video item
        const val TYPE_GRID = "_view"
        const val TYPE_FULL = "_full"
        const val TYPE_COVER = "_cover"
        const val TYPE_SMALL_COVER = "_smallcover"
        const val TYPE_QUATER = "_quater"
        const val TYPE_VIDEO = "_video"
        const val TYPE_IN_MAP = "_map"

        private const val MEMORY_CACHE_SIZE = 8     // one eighth of heap size

        private const val SHARED_BY_ME_ENDPOINT = "/ocs/v2.php/apps/files_sharing/api/v1/shares?path=lespas&subfiles=true&reshares=false&format=json"
        private const val SHARED_WITH_ME_ENDPOINT = "/ocs/v2.php/apps/files_sharing/api/v1/shares?shared_with_me=true&format=json"
        private const val SHAREE_LISTING_ENDPOINT = "/ocs/v1.php/apps/files_sharing/api/v1/sharees?itemType=file&format=json"
        private const val CAPABILITIES_ENDPOINT = "/ocs/v1.php/cloud/capabilities?format=json"
        private const val PUBLISH_ENDPOINT = "/ocs/v2.php/apps/files_sharing/api/v1/shares"
        private const val AVATAR_ENDPOINT = "/index.php/avatar/"
        private const val PREVIEW_ENDPOINT = "/index.php/core/preview?x=1024&y=1024&a=true&fileId="

        const val MIME_TYPE_JSON = "application/json"
        const val CONTENT_META_FILE_SUFFIX = "-content.json"
        const val PHOTO_META_HEADER = "{\"lespas\":{\"version\":2,\"photos\":["
        //const val PHOTO_META_JSON = "{\"id\":\"%s\",\"name\":\"%s\",\"stime\":%d,\"mime\":\"%s\",\"width\":%d,\"height\":%d},"
        const val PHOTO_META_JSON_V2 = "{\"id\":\"%s\",\"name\":\"%s\",\"stime\":%d,\"mime\":\"%s\",\"width\":%d,\"height\":%d,\"orientation\":%d,\"caption\":\"%s\",\"latitude\":%f,\"longitude\":%f,\"altitude\":%f,\"bearing\":%f},"

        const val SHARE_TYPE_USER = 0
        private const val SHARE_TYPE_USER_STRING = "user"
        const val SHARE_TYPE_GROUP = 1
        private const val SHARE_TYPE_GROUP_STRING = "group"

        const val PERMISSION_CAN_READ = 1
        private const val PERMISSION_CAN_UPDATE = 2
        private const val PERMISSION_CAN_CREATE = 4
        const val PERMISSION_JOINT = PERMISSION_CAN_CREATE + PERMISSION_CAN_UPDATE + PERMISSION_CAN_READ
        private const val PERMISSION_CAN_DELETE = 8
        private const val PERMISSION_CAN_SHARE = 16
        private const val PERMISSION_ALL = 31
    }
}