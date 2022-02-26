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
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Parcelable
import android.provider.MediaStore
import android.util.LruCache
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
import site.leos.apps.lespas.helper.ImageLoaderViewModel
import site.leos.apps.lespas.helper.OkHttpWebDav
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.photo.PhotoMeta
import site.leos.apps.lespas.photo.PhotoRepository
import site.leos.apps.lespas.settings.SettingsFragment
import java.io.File
import java.io.InputStream
import java.lang.Thread.sleep
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.ByteBuffer
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

    private val placeholderBitmap = ContextCompat.getDrawable(application, R.drawable.ic_baseline_placeholder_24)!!.toBitmap()
    private val videoThumbnail = ContextCompat.getDrawable(application, R.drawable.ic_baseline_movie_open_play_24)!!.toBitmap()

    private val imageCache = ImageCache(((application.getSystemService(Context.ACTIVITY_SERVICE)) as ActivityManager).memoryClass / MEMORY_CACHE_SIZE * 1024 * 1024)
    private val decoderJobMap = HashMap<Int, Job>()
    private val downloadDispatcher = Executors.newFixedThreadPool(2).asCoroutineDispatcher()

    private val photoRepository = PhotoRepository(application)

    private val sp = PreferenceManager.getDefaultSharedPreferences(application)
    private val autoReplayKey = application.getString(R.string.auto_replay_perf_key)

    private val metadataRetriever = MediaMetadataRetriever()

    fun interface LoadCompleteListener{
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
                userName, peekAuthToken(account, baseUrl), baseUrl, getUserData(account, application.getString(R.string.nc_userdata_selfsigned)).toBoolean(), localCacheFolder,
                "LesPas_${application.getString(R.string.lespas_version)}",
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
                            result.find { share-> share.fileId == getString("item_source") }?.let { item->
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
        }
        catch (e: IOException) { e.printStackTrace() }
        catch (e: IllegalStateException) { e.printStackTrace() }
        catch (e: JSONException) { e.printStackTrace() }

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
                            result.find { existed-> existed.albumId == folderId }?.let { existed->
                                // Existing sharedWithMe entry, we should keep the one with more permission bits set
                                if (existed.permission < permission) {
                                    existed.shareId = getString("id")
                                    existed.permission = permission
                                    existed.sharedTime = getLong("stime")
                                }
                            } ?: run {
                                // New sharedWithMe entry
                                result.add(ShareWithMe(
                                    getString("id"),
                                    getString("file_target"),
                                    folderId,
                                    getString("path").substringAfterLast('/'),
                                    getString("uid_owner"),
                                    getString("displayname_owner"),
                                    permission,
                                    getLong("stime"),
                                    Cover(Album.NO_COVER, 0, 0, 0, Album.NO_COVER, ""), Album.BY_DATE_TAKEN_ASC, 0L
                                ))
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
        shares.forEach { share->
            share.sharePath.substringBeforeLast('/').apply {
                if (this != sPath) {
                    sPath = this
                    webDav.list("${resourceRoot}${sPath}", OkHttpWebDav.FOLDER_CONTENT_DEPTH).drop(1).forEach { if (it.isFolder) lastModified[it.fileId] = it.modified.toEpochSecond(offset) }
                }
            }
        }

        // Retrieve share's meta data
        val total = shares.size
        shares.forEachIndexed { i, share->
            _shareWithMeProgress.value = ((i * 100.0) / total).toInt()
            share.lastModified = lastModified[share.albumId] ?: 0L
            try {
                webDav.getStream("${resourceRoot}${share.sharePath}/${share.albumId}_v2.json", true, CacheControl.FORCE_NETWORK).use {
                    JSONObject(it.bufferedReader().readText()).getJSONObject("lespas").let { meta ->
                        meta.getJSONObject("cover").apply {
                            share.cover = Cover(getString("id"), getInt("baseline"), getInt("width"), getInt("height"), getString("filename"), getString("mimetype"))
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

        while(true) {
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
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
    }

    private fun deleteShares(recipients: List<Recipient>) {
        for (recipient in recipients) {
            try {
                webDav.ocsDelete("$baseUrl$PUBLISH_ENDPOINT/${recipient.shareId}")
            } catch (e: Exception) { e.printStackTrace() }
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
            for (album in albums) { _shareByMe.value.find { it.fileId == album.id }?.apply { recipients.addAll(this.with) }}
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
            content += String.format(PHOTO_META_JSON_V2, it.fileId, it.path.substringAfterLast('/'), it.timestamp, it.mimeType, it.width, it.height, it.orientation, it.caption, it.latitude, it.longitude, it.altitude, it.bearing)
        }

        return content.dropLast(1) + "]}}"
    }

    fun createJointAlbumContentMetaFile(albumId: String, remotePhotos: List<RemotePhoto>?) {
        try {
            //File("$localFileFolder/$albumId$CONTENT_META_FILE_SUFFIX").sink(false).buffer().use {
            File("${localFileFolder}/${albumId}${CONTENT_META_FILE_SUFFIX_V2}").sink(false).buffer().use {
                it.write(createContentMeta(null, remotePhotos).encodeToByteArray())
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    fun updatePublish(album: ShareByMe, removeRecipients: List<Recipient>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Remove sharees
                if (removeRecipients.isNotEmpty()) deleteShares(removeRecipients)

                // Add sharees
                if (album.with.isNotEmpty()) {
                    if (!isShared(album.fileId)) {
                        // If sharing this album for the 1st time, create content.json on server
                        val content = createContentMeta(photoRepository.getPhotoMetaInAlbum(album.fileId), null)
                        //webDav.upload(content, "${resourceRoot}${lespasBase}/${Uri.encode(album.folderName)}/${album.fileId}$CONTENT_META_FILE_SUFFIX", MIME_TYPE_JSON)
                        webDav.upload(content, "${resourceRoot}${lespasBase}/${Uri.encode(album.folderName)}/${album.fileId}$CONTENT_META_FILE_SUFFIX_V2", MIME_TYPE_JSON)
                    }

                    createShares(listOf(album))
                }

                // Update _shareByMe hence update UI
                if (album.with.isNotEmpty() || removeRecipients.isNotEmpty()) _shareByMe.value = refreshShareByMe()
            }
            catch (e: Exception) { e.printStackTrace() }
        }
    }

    fun renameShare(album: ShareByMe, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                webDav.move("$resourceRoot$lespasBase/${Uri.encode(album.folderName)}", "$resourceRoot$lespasBase/${Uri.encode(newName)}")
                deleteShares(album.with)
                album.folderName = newName
                createShares(listOf(album))
            } catch (e: Exception) { e.printStackTrace() }
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
                //webDav.getStreamBool("${resourceRoot}${share.sharePath}/${share.albumId}$CONTENT_META_FILE_SUFFIX", true, if (forceNetwork) CacheControl.FORCE_NETWORK else null).apply {
                webDav.getStreamBool("${resourceRoot}${share.sharePath}/${share.albumId}$CONTENT_META_FILE_SUFFIX_V2", true, if (forceNetwork) CacheControl.FORCE_NETWORK else null).apply {
                    if (forceNetwork || this.second) doRefresh = false
                    this.first.use { _publicationContentMeta.value = getContentMeta(it, share) }
                }

                //if (doRefresh) webDav.getStream("${resourceRoot}${share.sharePath}/${share.albumId}$CONTENT_META_FILE_SUFFIX", true, CacheControl.FORCE_NETWORK).use { _publicationContentMeta.value = getContentMeta(it, share) }
                if (doRefresh) webDav.getStream("${resourceRoot}${share.sharePath}/${share.albumId}$CONTENT_META_FILE_SUFFIX_V2", true, CacheControl.FORCE_NETWORK).use { _publicationContentMeta.value = getContentMeta(it, share) }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun getContentMeta(inputStream: InputStream, share: ShareWithMe): List<RemotePhoto> {
        val result = mutableListOf<RemotePhoto>()

        val photos = JSONObject(inputStream.bufferedReader().readText()).getJSONObject("lespas").getJSONArray("photos")
        for (i in 0 until photos.length()) {
            photos.getJSONObject(i).apply {
                //result.add(RemotePhoto(getString("id"), "${share.sharePath}/${getString("name")}", getString("mime"), getInt("width"), getInt("height"), 0, getLong("stime")))
                result.add(RemotePhoto(
                    getString("id"), "${share.sharePath}/${getString("name")}", getString("mime"), getInt("width"), getInt("height"), 0, getLong("stime"),
                    getInt("orientation"), getString("caption"), getDouble("latitude"), getDouble("longitude"), getDouble("altitude"), getDouble("bearing")
                ))
            }
        }
        when (share.sortOrder) {
            Album.BY_NAME_ASC -> result.sortWith { o1, o2 -> o1.path.compareTo(o2.path) }
            Album.BY_NAME_DESC -> result.sortWith { o1, o2 -> o2.path.compareTo(o1.path) }
            Album.BY_DATE_TAKEN_ASC -> result.sortWith { o1, o2 -> (o1.timestamp - o2.timestamp).toInt() }
            Album.BY_DATE_TAKEN_DESC -> result.sortWith { o1, o2 -> (o2.timestamp - o1.timestamp).toInt() }
        }

        return result
    }

    fun acquireMediaFromShare(photo: RemotePhoto, toAlbum: Album) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val destFolder: String = when(toAlbum.id) {
                    PublicationDetailFragment.JOINT_ALBUM_ID-> "$resourceRoot${Uri.encode(toAlbum.name, "/")}"
                    else-> "$resourceRoot$lespasBase/${Uri.encode(toAlbum.name, "/")}".also { if (toAlbum.id.isEmpty()) webDav.createFolder(it) }
                }

                // Copy media file on server. If file already exists in target folder, this will throw OkHttpWebDavException, it's OK since no more things need to do in this circumstance
                webDav.copy("$resourceRoot${photo.path}", "${destFolder}/${Uri.encode(photo.path.substringAfterLast('/'))}")

                if (toAlbum.id == PublicationDetailFragment.JOINT_ALBUM_ID) {
                    // Update joint album's content meta. For user's own album, it's content meta will be updated during next server sync
                    // TODO: care for rollback if anything goes wrong?

                    // Target album's id is passed in property eTag
                    val targetShare = _shareWithMe.value.find { it.albumId == toAlbum.eTag }!!
                    var mediaList: MutableList<RemotePhoto>

                    //webDav.getStream("${resourceRoot}${targetShare.sharePath}/${targetShare.albumId}$CONTENT_META_FILE_SUFFIX", true, CacheControl.FORCE_NETWORK).use { mediaList = getContentMeta(it, targetShare).toMutableList() }
                    webDav.getStream("${resourceRoot}${targetShare.sharePath}/${targetShare.albumId}$CONTENT_META_FILE_SUFFIX_V2", true, CacheControl.FORCE_NETWORK).use { mediaList = getContentMeta(it, targetShare).toMutableList() }
                    if (!mediaList.isNullOrEmpty()) {
                        mediaList.add(photo)
                        when(targetShare.sortOrder) {
                            Album.BY_NAME_ASC -> mediaList.sortWith { o1, o2 -> o1.path.compareTo(o2.path) }
                            Album.BY_NAME_DESC -> mediaList.sortWith { o1, o2 -> o2.path.compareTo(o1.path) }
                            Album.BY_DATE_TAKEN_ASC -> mediaList.sortWith { o1, o2 -> (o1.timestamp - o2.timestamp).toInt() }
                            Album.BY_DATE_TAKEN_DESC -> mediaList.sortWith { o1, o2 -> (o2.timestamp - o1.timestamp).toInt() }
                        }
                        //webDav.upload(createContentMeta(null, mediaList), "${resourceRoot}${targetShare.sharePath}/${targetShare.albumId}$CONTENT_META_FILE_SUFFIX", MIME_TYPE_JSON)
                        webDav.upload(createContentMeta(null, mediaList), "${resourceRoot}${targetShare.sharePath}/${targetShare.albumId}$CONTENT_META_FILE_SUFFIX_V2", MIME_TYPE_JSON)
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    fun getMediaExif(photo: RemotePhoto): Pair<ExifInterface, Long>? {
        var response: Response? = null
        var result: Pair<ExifInterface, Long>? = null

        try {
            response = webDav.getRawResponse("$resourceRoot${photo.path}", true)
            result = Pair(ExifInterface(response.body!!.byteStream()), response.headersContentLength())
        } catch (e: Exception) { e.printStackTrace() }
        finally { response?.close() }

        return result
    }

    private fun getRemoteVideoThumbnail(photo: RemotePhoto): Bitmap? {
        var bitmap: Bitmap? = null

        val thumbnail = File(localCacheFolder, "${photo.fileId}.thumbnail")

        // Load from local cache
        if (thumbnail.exists()) bitmap = BitmapFactory.decodeStream(thumbnail.inputStream())

        // Download from server
        bitmap ?: run {
            metadataRetriever.apply {
                setDataSource("$resourceRoot${Uri.encode(photo.path, "/")}", HashMap<String, String>().apply { this["Authorization"] = "Basic $token" })
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

    @SuppressLint("NewApi")
    @Suppress("BlockingMethodInNonBlockingContext")
    @JvmOverloads
    fun getPhoto(photo: RemotePhoto, view: ImageView, photoType: String, callBack: LoadCompleteListener? = null) {
        val type = if (photo.mimeType.startsWith("video")) ImageLoaderViewModel.TYPE_VIDEO else photoType
        val jobKey = System.identityHashCode(view)

        //view.imageAlpha = 0
        var bitmap: Bitmap? = null
        var animatedDrawable: Drawable? = null
        val job = viewModelScope.launch(downloadDispatcher) {
            try {
                val photoPath = "$resourceRoot${photo.path}"
                var key = "${photo.fileId}$type"
                if ((type == ImageLoaderViewModel.TYPE_COVER) || (type == ImageLoaderViewModel.TYPE_SMALL_COVER)) key = "$key-${photo.coverBaseLine}"

                imageCache.get(key)?.let { bitmap = it } ?: run {
                    // Get preview for TYPE_GRID. To speed up the process, should run Preview Generator app on Nextcloud server to pre-generate 1024x1024 size of preview files, if not, the 1st time of viewing this shared image would be slow
                    if (type == ImageLoaderViewModel.TYPE_GRID) try {
                            webDav.getStream("${baseUrl}${PREVIEW_ENDPOINT}${photo.fileId}", true, null).use { bitmap = BitmapFactory.decodeStream(it) }
                    } catch(e: Exception) {
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

                        if (type == ImageLoaderViewModel.TYPE_VIDEO) bitmap = getRemoteVideoThumbnail(photo)
                        else webDav.getStream(photoPath, true,null).use {
                            when (type) {
                                ImageLoaderViewModel.TYPE_COVER, ImageLoaderViewModel.TYPE_SMALL_COVER -> {
/*
                                    // If album's cover size changed from other ends, like picture cropped on server, SyncAdapter will not handle the changes, the baseline could be invalid
                                    // TODO better way to handle this
                                    val top = if (photo.coverBaseLine > photo.height - 1) 0 else photo.coverBaseLine

                                    val bottom = min(top + (photo.width.toFloat() * 9 / 21).toInt(), photo.height - 1)
                                    val rect = Rect(0, top, photo.width - 1, bottom)
*/
                                    val rect = when(photo.orientation) {
                                        0 -> Rect(0, photo.coverBaseLine, photo.width - 1, min(photo.coverBaseLine + (photo.width.toFloat() * 9 / 21).toInt(), photo.height - 1))
                                        90 -> Rect(photo.coverBaseLine, 0, min(photo.coverBaseLine + (photo.height.toFloat() * 9 / 21).toInt(), photo.width - 1), photo.height - 1)
                                        180 -> (photo.height - photo.coverBaseLine).let { Rect(0, Integer.max(it - (photo.width.toFloat() * 9 / 21).toInt(), 0), photo.width - 1, it) }
                                        else-> (photo.width - photo.coverBaseLine).let { Rect(Integer.max(it - (photo.height.toFloat() * 9 / 21).toInt(), 0), 0, it, photo.height - 1) }
                                    }

                                    val sampleSize = when (photo.width) {
                                        in (0..2000) -> 1
                                        in (2000..3000) -> 2
                                        else -> 4
                                    }

                                    try  {
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
                                        bitmap = getRemoteVideoThumbnail(photo)
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
                                    imageCache.get("${photo.fileId}${ImageLoaderViewModel.TYPE_GRID}")?.let {
                                        withContext(Dispatchers.Main) { view.setImageBitmap(it) }
                                        callBack?.onLoadComplete()
                                    }

                                    when {
                                        //photo.mimeType.startsWith("video")-> bitmap = getRemoteVideoThumbnail(it, photo)
                                        (photo.mimeType == "image/awebp" || photo.mimeType == "image/agif")-> {
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                                animatedDrawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(it.readBytes())))
                                            } else {
                                                bitmap = BitmapFactory.decodeStream(it, null, option.apply { inSampleSize = if (photo.width < 2000) 2 else 8 })
                                            }
                                        }
                                        else-> {
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
            }
            catch (e: Exception) { e.printStackTrace() }
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
                }
                callBack?.onLoadComplete()
            }
        }

        // Replacing previous job
        replacePrevious(jobKey, job)
    }

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

                        webDav.getStream("${baseUrl}${AVATAR_ENDPOINT}${Uri.encode(user.name)}/64", true,null).use { bitmap = BitmapFactory.decodeStream(it) }

                        bitmap?.let { imageCache.put(key, it) }
                    }
                }
            }
            catch (e: Exception) { e.printStackTrace() }
            finally {
                if (isActive) withContext(Dispatchers.Main) {
                    if (drawable == null && bitmap != null) drawable = BitmapDrawable(view.resources, Tools.getRoundBitmap(view.context, bitmap!!))
                    drawable?.run {
                        when (view) {
                            is Chip -> view.chipIcon = this
                            is TextView-> {
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

    fun cancelGetPhoto(view: View) {
        decoderJobMap[System.identityHashCode(view)]?.cancel()
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

    fun savePhoto(context: Context, photo: RemotePhoto) {
        if (photo.mimeType.startsWith("image")) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val cr = context.contentResolver
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val mediaDetails = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, photo.path.substringAfterLast('/'))
                            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                            put(MediaStore.MediaColumns.MIME_TYPE, photo.mimeType)
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        }
                        cr.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), mediaDetails)?.let { uri ->
                            cr.openOutputStream(uri)?.use { local ->
                                webDav.getStream("$resourceRoot${photo.path}", true, null).use { remote ->
                                    remote.copyTo(local, 8192)

                                    mediaDetails.clear()
                                    mediaDetails.put(MediaStore.MediaColumns.IS_PENDING, 0)
                                    cr.update(uri, mediaDetails, null, null)
                                }
                            }
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        val fileName = "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)}/${photo.path.substringAfterLast('/')}"
                        File(fileName).outputStream().use { local ->
                            webDav.getStream("$resourceRoot${photo.path}", true, null).use { remote ->
                                remote.copyTo(local, 8192)
                            }
                        }
                        MediaScannerConnection.scanFile(context, arrayOf(fileName), arrayOf(photo.mimeType), null)
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
        } else {
            // Video is now streaming, there is no local cache available, and might take some time to download, so we resort to Download Manager
            (context.getSystemService(Activity.DOWNLOAD_SERVICE) as DownloadManager).enqueue(
                DownloadManager.Request(Uri.parse("$resourceRoot${photo.path}"))
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, photo.path.substringAfterLast('/'))
                    .setTitle(photo.path.substringAfterLast('/'))
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

    private fun replacePrevious(key: Int, newJob: Job) {
        decoderJobMap[key]?.cancel()
        decoderJobMap[key] = newJob
    }

    override fun onCleared() {
        //File(localCacheFolder, OkHttpWebDav.VIDEO_CACHE_FOLDER).deleteRecursively()
        decoderJobMap.forEach { if (it.value.isActive) it.value.cancel() }
        downloadDispatcher.close()
        metadataRetriever.release()
        super.onCleared()
    }

    class ImageCache (maxSize: Int): LruCache<String, Bitmap>(maxSize) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    @Parcelize
    data class Sharee(
        var name: String,
        var label: String,
        var type: Int,
    ): Parcelable

    @Parcelize
    data class Recipient(
        var shareId: String,
        var permission: Int,
        var sharedTime: Long,
        var sharee: Sharee,
    ): Parcelable

    @Parcelize
    data class ShareByMe(
        var fileId: String,
        var folderName: String,
        var with: MutableList<Recipient>,
    ): Parcelable

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
        //var coverFileName: String,
        var sortOrder: Int,
        var lastModified: Long,
    ): Parcelable, Comparable<ShareWithMe> {
        override fun compareTo(other: ShareWithMe): Int = (other.lastModified - this.lastModified).toInt()
    }

    @Parcelize
    data class RemotePhoto(
        val fileId: String,
        val path: String,
        val mimeType: String,
        val width: Int,
        val height: Int,
        val coverBaseLine: Int,
        val timestamp: Long,
        val orientation: Int = 0,
        val caption: String = "",
        val latitude: Double = Photo.NO_GPS_DATA,
        val longitude: Double = Photo.NO_GPS_DATA,
        val altitude: Double = Photo.NO_GPS_DATA,
        val bearing: Double = Photo.NO_GPS_DATA,
    ): Parcelable

    companion object {
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
        const val CONTENT_META_FILE_SUFFIX_V2 = "-content_v2.json"
        const val PHOTO_META_HEADER = "{\"lespas\":{\"version\":2,\"photos\":["
        const val PHOTO_META_JSON = "{\"id\":\"%s\",\"name\":\"%s\",\"stime\":%d,\"mime\":\"%s\",\"width\":%d,\"height\":%d},"
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