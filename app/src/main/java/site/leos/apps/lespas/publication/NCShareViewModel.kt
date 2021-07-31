package site.leos.apps.lespas.publication

import android.accounts.AccountManager
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.graphics.*
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Parcelable
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.material.chip.Chip
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.parcelize.Parcelize
import okhttp3.CacheControl
import okhttp3.FormBody
import okio.IOException
import okio.buffer
import okio.sink
import okio.source
import org.json.JSONException
import org.json.JSONObject
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.Album
import site.leos.apps.lespas.album.Cover
import site.leos.apps.lespas.helper.ImageLoaderViewModel
import site.leos.apps.lespas.helper.OkHttpWebDav
import site.leos.apps.lespas.helper.OkHttpWebDavException
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.photo.PhotoMeta
import site.leos.apps.lespas.photo.PhotoRepository
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
    private val _sharees = MutableStateFlow<List<Sharee>>(arrayListOf())
    private val _publicationContentMeta = MutableStateFlow<List<RemotePhoto>>(arrayListOf())
    val shareByMe: StateFlow<List<ShareByMe>> = _shareByMe
    val shareWithMe: StateFlow<List<ShareWithMe>> = _shareWithMe
    val sharees: StateFlow<List<Sharee>> = _sharees
    val publicationContentMeta: StateFlow<List<RemotePhoto>> = _publicationContentMeta

    private var webDav: OkHttpWebDav

    private val baseUrl: String
    private val userName: String
    private val resourceRoot: String
    private val lespasBase = application.getString(R.string.lespas_base_folder_name)
    private val localCacheFolder = "${application.cacheDir}${lespasBase}"
    private val localFileFolder = Tools.getLocalRoot(application)

    private val placeholderBitmap = Tools.getBitmapFromVector(application, R.drawable.ic_baseline_placeholder_24)
    private val videoThumbnail = Tools.getBitmapFromVector(application, R.drawable.ic_baseline_play_circle_24)

    private val imageCache = ImageCache(((application.getSystemService(Context.ACTIVITY_SERVICE)) as ActivityManager).memoryClass / MEMORY_CACHE_SIZE * 1024 * 1024)
    private val decoderJobMap = HashMap<Int, Job>()
    private val downloadDispatcher = Executors.newFixedThreadPool(2).asCoroutineDispatcher()

    private val photoRepository = PhotoRepository(application)

    fun interface LoadCompleteListener{
        fun onLoadComplete()
    }

    init {
        AccountManager.get(application).run {
            val account = getAccountsByType(application.getString(R.string.account_type_nc))[0]
            userName = getUserData(account, application.getString(R.string.nc_userdata_username))
            baseUrl = getUserData(account, application.getString(R.string.nc_userdata_server))
            resourceRoot = "$baseUrl${application.getString(R.string.dav_files_endpoint)}$userName"
            webDav = OkHttpWebDav(userName, peekAuthToken(account, baseUrl), baseUrl, getUserData(account, application.getString(R.string.nc_userdata_selfsigned)).toBoolean(), "${application.cacheDir}/${application.getString(R.string.lespas_base_folder_name)}", "LesPas_${application.getString(R.string.lespas_version)}")
        }

        viewModelScope.launch(Dispatchers.IO) {
            _sharees.value = getSharees()
            getShareList()
        }
    }

    val themeColor: Flow<Int> = flow {
        var color = 0

        try {
            webDav.ocsGet("$baseUrl$CAPABILLITIES_ENDPOINT")?.apply {
                color = Integer.parseInt(getJSONObject("data").getJSONObject("capabilities").getJSONObject("theming").getString("color").substringAfter('#'), 16)
            }
            if (color != 0) emit(color)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }.flowOn(Dispatchers.IO)

    private fun getShareList() {
        val sharesBy = mutableListOf<ShareByMe>()
        val sharesWith = mutableListOf<ShareWithMe>()
        var sharee: Recipient
        var backOff = 2500L
        val lespasBaseLength = lespasBase.length
        val group = _sharees.value.filter { it.type == SHARE_TYPE_GROUP }

        while(true) {
            try {
                webDav.ocsGet("$baseUrl$SHARE_LISTING_ENDPOINT")?.apply {
                    var shareType: Int
                    var idString: String
                    var labelString: String
                    var pathString: String
                    var recipientString: String

                    val data = getJSONArray("data")
                    for (i in 0 until data.length()) {
                        data.getJSONObject(i).apply {
                            shareType = when (getString("type")) {
                                SHARE_TYPE_USER_STRING -> SHARE_TYPE_USER
                                SHARE_TYPE_GROUP_STRING -> SHARE_TYPE_GROUP
                                else -> -1
                            }
                            pathString = getString("path")
                            if (shareType >= 0 && getBoolean("is_directory") && pathString.startsWith(lespasBase) && pathString.length > lespasBaseLength) {
                                // Only interested in shares of subfolders under /lespas

                                recipientString = getString("recipient")
                                if (getString("owner") == userName) {
                                    // This is a share by me, get recipient's label
                                    idString = recipientString
                                    labelString = _sharees.value.find { it.name == idString }?.label ?: idString

                                    sharee = Recipient(getString("id"), getInt("permissions"), OffsetDateTime.parse(getString("time")).toInstant().epochSecond, Sharee(idString, labelString, shareType))

                                    @Suppress("SimpleRedundantLet")
                                    sharesBy.find { share -> share.fileId == getString("file_id") }?.let { item ->
                                        // If this folder existed in result, add new sharee only
                                        item.with.add(sharee)
                                    } ?: run {
                                        // Create new share by me item
                                        sharesBy.add(ShareByMe(getString("file_id"), getString("name"), mutableListOf(sharee)))
                                    }
                                } else if (sharesWith.indexOfFirst { it.albumId == getString("file_id") } == -1 && (recipientString == userName || group.indexOfFirst { it.name == recipientString } != -1)) {
                                    // This is a share with me, either direct to me or to my groups, get owner's label
                                    idString = getString("owner")
                                    labelString = _sharees.value.find { it.name == idString }?.label ?: idString

                                    sharesWith.add(ShareWithMe(getString("id"),"", getString("file_id"), getString("name"), idString, labelString, getInt("permissions"), OffsetDateTime.parse(getString("time")).toInstant().epochSecond, Cover("", 0, 0, 0),"", Album.BY_DATE_TAKEN_ASC, 0L))
                                }
                            }
                        }
                    }
                }

                _shareByMe.value = sharesBy

                if (sharesWith.isNotEmpty()) _shareWithMe.value = getAlbumMetaForShareWithMe(sharesWith).apply { sort() }

                break
            }
            catch (e: UnknownHostException) {
                // Retry for network unavailable, hope it's temporarily
                backOff *= 2
                sleep(backOff)
            }
            catch (e: SocketTimeoutException) {
                // Retry for network unavailable, hope it's temporarily
                backOff *= 2
                sleep(backOff)
            } catch (e: Exception) {
                e.printStackTrace()
                break
            }
        }
    }

    private fun updateShareByMe(): MutableList<ShareByMe> {
        val result = mutableListOf<ShareByMe>()
        var sharee: Recipient

        try {
            webDav.ocsGet("$baseUrl$SHARE_LISTING_ENDPOINT")?.apply {
                var shareType: Int
                var idString: String
                var labelString: String
                var pathString: String
                val lespasBaseLength = lespasBase.length

                val data = getJSONArray("data")
                for (i in 0 until data.length()) {
                    data.getJSONObject(i).apply {
                        shareType = when(getString("type")) {
                            SHARE_TYPE_USER_STRING-> SHARE_TYPE_USER
                            SHARE_TYPE_GROUP_STRING-> SHARE_TYPE_GROUP
                            else-> -1
                        }
                        pathString = getString("path")
                        if (shareType >= 0 && getString("owner") == userName && getBoolean("is_directory") && pathString.startsWith("/lespas") && pathString.length > lespasBaseLength) {
                            // Only interested in shares of subfolders under lespas/

                            idString = getString("recipient")
                            labelString = _sharees.value.find { it.name == idString }?.label ?: idString
                            sharee = Recipient(getString("id"), getInt("permissions"), OffsetDateTime.parse(getString("time")).toInstant().epochSecond, Sharee(idString, labelString, shareType))

                            @Suppress("SimpleRedundantLet")
                            result.find { share-> share.fileId == getString("file_id") }?.let { item->
                                // If this folder existed in result, add new sharee only
                                item.with.add(sharee)
                            } ?: run {
                                // Create new folder share item
                                result.add(ShareByMe(getString("file_id"), getString("name"), mutableListOf(sharee)))
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

    fun updateShareWithMe() {
        val result = mutableListOf<ShareWithMe>()
        val group = _sharees.value.filter { it.type == SHARE_TYPE_GROUP }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                webDav.ocsGet("$baseUrl$SHARE_LISTING_ENDPOINT")?.apply {
                    var shareType: Int
                    var idString: String
                    var labelString: String
                    var pathString: String
                    var recipientString: String
                    val lespasBaseLength = lespasBase.length

                    val data = getJSONArray("data")
                    for (i in 0 until data.length()) {
                        data.getJSONObject(i).apply {
                            shareType = when (getString("type")) {
                                SHARE_TYPE_USER_STRING -> SHARE_TYPE_USER
                                SHARE_TYPE_GROUP_STRING -> SHARE_TYPE_GROUP
                                else -> -1
                            }
                            pathString = getString("path")
                            if (getString("owner") != userName && shareType >= 0 && getBoolean("is_directory") && pathString.startsWith(lespasBase) && pathString.length > lespasBaseLength) {
                                // Only interested in shares of subfolders under lespas/

                                recipientString = getString("recipient")
                                if (result.indexOfFirst { it.albumId == getString("file_id") } == -1 && (recipientString == userName || group.indexOfFirst { it.name == recipientString } != -1)) {
                                    // This is a share with me, either direct to me or to my groups, get owner's label
                                    idString = getString("owner")
                                    labelString = _sharees.value.find { it.name == idString }?.label ?: idString

                                    result.add(ShareWithMe(getString("id"), "", getString("file_id"), getString("name"), idString, labelString, getInt("permissions"), OffsetDateTime.parse(getString("time")).toInstant().epochSecond, Cover("", 0, 0, 0), "", Album.BY_DATE_TAKEN_ASC, 0L))
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
    }

    private fun getAlbumMetaForShareWithMe(shares: List<ShareWithMe>): MutableList<ShareWithMe> {
        val result = shares.toMutableList()

        // Avoid flooding http calls to server, cache share path, since in most cases, user won't change share path often
        var sPath = getSharePath(result[0].shareId) ?: ""

        // Get shares' last modified timestamp by PROPFIND share path
        val lastModified = HashMap<String, Long>()
        val offset = OffsetDateTime.now().offset
        webDav.list("${resourceRoot}${sPath}", OkHttpWebDav.FOLDER_CONTENT_DEPTH).forEach { lastModified[it.fileId] = it.modified.toEpochSecond(offset) }

        for (share in result) {
            share.sharePath = "${sPath}/${share.albumName}"
            share.lastModified = lastModified[share.albumId] ?: 0L
            try {
                webDav.getStream("${resourceRoot}${share.sharePath}/${share.albumId}.json", true, CacheControl.FORCE_NETWORK).use {
                    JSONObject(it.bufferedReader().readText()).getJSONObject("lespas").let { meta ->
                        meta.getJSONObject("cover").apply {
                            share.cover = Cover(getString("id"), getInt("baseline"), getInt("width"), getInt("height"))
                            share.coverFileName = getString("filename")
                        }
                        share.sortOrder = meta.getInt("sort")
                    }

                }
            } catch (e: OkHttpWebDavException) {
                e.printStackTrace()
                if (e.statusCode == 404) {
                    // If we the meta file is not found on server, share path might be different, try again after updating the share path from server
                    sPath = getSharePath(share.shareId) ?: ""
                    webDav.list("${resourceRoot}${sPath}", OkHttpWebDav.FOLDER_CONTENT_DEPTH).forEach { lastModified[it.fileId] = it.modified.toEpochSecond(offset) }

                    share.sharePath = "${sPath}/${share.albumName}"
                    share.lastModified = lastModified[share.albumId] ?: 0L
                    webDav.getStream("${resourceRoot}${share.sharePath}/${share.albumId}.json", true, CacheControl.FORCE_NETWORK).use {
                        JSONObject(it.bufferedReader().readText()).getJSONObject("lespas").let { meta ->
                            meta.getJSONObject("cover").apply {
                                share.cover = Cover(getString("id"), getInt("baseline"), getInt("width"), getInt("height"))
                                share.coverFileName = getString("filename")
                            }
                            share.sortOrder = meta.getInt("sort")
                        }
                    }
                }
            }
        }

        return result
    }

    private fun getSharees(): MutableList<Sharee> {
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

    private fun getSharePath(shareId: String): String? {
        var path: String? = null

        try {
            webDav.ocsGet("$baseUrl$PUBLISH_ENDPOINT/${shareId}?format=json")?.apply {
                path = getJSONArray("data").getJSONObject(0).getString("path").substringBeforeLast('/')
            }
        }
        catch (e: java.io.IOException) { e.printStackTrace() }
        catch (e: IllegalStateException) { e.printStackTrace() }
        catch (e: JSONException) { e.printStackTrace() }

        return path
    }

    fun publish(albums: List<ShareByMe>) {
        viewModelScope.launch(Dispatchers.IO) {
            createShares(albums)
            _shareByMe.value = updateShareByMe()
        }
    }

    fun unPublish(recipients: List<Recipient>) {
        viewModelScope.launch(Dispatchers.IO) {
            deleteShares(recipients)
            _shareByMe.value = updateShareByMe()
        }
    }

    private fun createContentMeta(photoMeta: List<PhotoMeta>?, remotePhotos: List<RemotePhoto>?): String {
        var content = "{\"lespas\":{\"photos\":["

        photoMeta?.forEach {
            content += String.format(PHOTO_META_JSON, it.id, it.name, it.dateTaken.toEpochSecond(OffsetDateTime.now().offset), it.mimeType, it.width, it.height)
        }

        remotePhotos?.forEach {
            content += String.format(PHOTO_META_JSON, it.fileId, it.path.substringAfterLast('/'), it.timestamp, it.mimeType, it.width, it.height)
        }

        return content.dropLast(1) + "]}}"
    }

    fun createJointAlbumContentMetaFile(albumId: String, remotePhotos: List<RemotePhoto>?) {
        try {
            File("$localFileFolder/$albumId$CONTENT_META_FILE_SUFFIX").sink(false).buffer().use {
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
                        webDav.upload(content, "${resourceRoot}${lespasBase}/${Uri.encode(album.folderName)}/${album.fileId}$CONTENT_META_FILE_SUFFIX", MIME_TYPE_JSON)
                    }

                    createShares(listOf(album))
                }

                // Update _shareByMe hence update UI
                if (album.with.isNotEmpty() || removeRecipients.isNotEmpty()) _shareByMe.value = updateShareByMe()
            }
            catch (e: Exception) { e.printStackTrace() }
        }
    }

    fun renameShare(album: ShareByMe, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                webDav.move("$resourceRoot$lespasBase/${album.folderName}", "$resourceRoot$lespasBase/$newName")
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
                webDav.getStreamBool("${resourceRoot}${share.sharePath}/${share.albumId}$CONTENT_META_FILE_SUFFIX", true, if (forceNetwork) CacheControl.FORCE_NETWORK else null).apply {
                    if (forceNetwork || this.second) doRefresh = false
                    this.first.use { _publicationContentMeta.value = getContentMeta(it, share) }
                }

                if (doRefresh) webDav.getStream("${resourceRoot}${share.sharePath}/${share.albumId}$CONTENT_META_FILE_SUFFIX", true, CacheControl.FORCE_NETWORK).use { _publicationContentMeta.value = getContentMeta(it, share) }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun getContentMeta(inputStream: InputStream, share: ShareWithMe): List<RemotePhoto> {
        val result = mutableListOf<RemotePhoto>()

        val photos = JSONObject(inputStream.bufferedReader().readText()).getJSONObject("lespas").getJSONArray("photos")
        for (i in 0 until photos.length()) {
            photos.getJSONObject(i).apply {
                result.add(RemotePhoto(getString("id"), "${share.sharePath}/${getString("name")}", getString("mime"), getInt("width"), getInt("height"), 0, getLong("stime")))
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

    private fun getRemoteVideoThumbnail(inputStream: InputStream, photo: RemotePhoto): Bitmap? {
        var bitmap: Bitmap?
        // Download video file if necessary
        val fileName = "${OkHttpWebDav.VIDEO_CACHE_FOLDER}/${photo.path.substringAfterLast('/')}"
        val videoFile = File(localCacheFolder, fileName)
        if (!videoFile.exists()) {
            val sink = videoFile.sink(false).buffer()
            sink.writeAll(inputStream.source())
            sink.close()
        }

        // Get frame at 1s
        MediaMetadataRetriever().apply {
            setDataSource("$localCacheFolder/$fileName")
            bitmap = getFrameAtTime(1000000L) ?: videoThumbnail
            release()
        }

        return bitmap
    }

    fun getPhoto(photo: RemotePhoto, view: ImageView, type: String) { getPhoto(photo, view, type, null) }
    @SuppressLint("NewApi")
    @Suppress("BlockingMethodInNonBlockingContext")
    fun getPhoto(photo: RemotePhoto, view: ImageView, type: String, callBack: LoadCompleteListener?) {
        val jobKey = System.identityHashCode(view)

        //view.imageAlpha = 0
        var bitmap: Bitmap? = null
        var animatedDrawable: Drawable? = null
        val job = viewModelScope.launch(downloadDispatcher) {
            try {
                val key = "${photo.fileId}$type"
                imageCache.get(key)?.let { bitmap = it } ?: run {
                    // Get preview for TYPE_GRID. To speed up the process, should run Preview Generator app on Nextcloud server to pre-generate 1024x1024 size of preview files, if not, the 1st time of viewing this shared image would be slow
                    try {
                        if (type == ImageLoaderViewModel.TYPE_GRID) {
                            webDav.getStream("${baseUrl}/index.php/core/preview?x=1024&y=1024&a=true&fileId=${photo.fileId}", true, null).use { bitmap = BitmapFactory.decodeStream(it) }
                        }
                    } catch(e: Exception) {
                        // Catch all exception, give TYPE_GRID another chance below
                        e.printStackTrace()
                        bitmap = null
                    }

                    // If preview download fail (like no preview for video etc), or other types than TYPE_GRID, then we need to download the media file itself
                    bitmap ?: run {
                        // Show cached low resolution bitmap first
                        imageCache.get("${photo.fileId}${ImageLoaderViewModel.TYPE_GRID}")?.let {
                            withContext(Dispatchers.Main) { view.setImageBitmap(it) }
                            callBack?.onLoadComplete()
                        }

                        val option = BitmapFactory.Options().apply {
                            // TODO the following setting make picture larger, care to find a new way?
                            //inPreferredConfig = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) Bitmap.Config.RGBA_F16 else Bitmap.Config.ARGB_8888
                        }
                        webDav.getStream("$resourceRoot${photo.path}", true,null).use {
                            when (type) {
                                ImageLoaderViewModel.TYPE_COVER -> {
                                    val bottom = min(photo.coverBaseLine + (photo.width.toFloat() * 9 / 21).toInt(), photo.height)
                                    val rect = Rect(0, photo.coverBaseLine, photo.width, bottom)
                                    val sampleSize = when (photo.width) {
                                        in (0..2000) -> 1
                                        in (2000..3000) -> 2
                                        else -> 4
                                    }
                                    try  {
                                        bitmap = BitmapRegionDecoder.newInstance(it, false).decodeRegion(rect, option.apply { inSampleSize = sampleSize })
                                    } catch (e: IOException) {
                                        // Video only album has video file as cover, BitmapRegionDecoder will throw IOException with "Image format not supported" stack trace message
                                        e.printStackTrace()
                                        it.close()
                                        webDav.getStream("$resourceRoot${photo.path}", true,null).use { vResp->
                                            // TODO could take a long time to download the video file
                                            bitmap = getRemoteVideoThumbnail(vResp, photo)
                                        }
                                    }
                                }
                                ImageLoaderViewModel.TYPE_GRID -> {
                                    if (photo.mimeType.startsWith("video")) bitmap = getRemoteVideoThumbnail(it, photo)
                                    else bitmap = BitmapFactory.decodeStream(it, null, option.apply { inSampleSize = if (photo.width < 2000) 2 else 8 })
                                }
                                ImageLoaderViewModel.TYPE_FULL -> {
                                    // only image files would be requested as TYPE_FULL
                                    if (photo.mimeType == "image/awebp" || photo.mimeType == "image/agif") {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                            animatedDrawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(it.readBytes())))
                                        } else {
                                            bitmap = BitmapFactory.decodeStream(it, null, option.apply { inSampleSize = if (photo.width < 2000) 2 else 8 })
                                        }
                                    } else bitmap = BitmapFactory.decodeStream(it, null, option)
                                }
                                else-> {}
                            }
                        }

                        // If decoded bitmap is too large
                        bitmap?.let {
                            if (it.allocationByteCount > 100000000) {
                                bitmap = null
                                webDav.getStream("$resourceRoot${photo.path}", true, CacheControl.FORCE_CACHE).use { s-> bitmap = BitmapFactory.decodeStream(s, null, option.apply { inSampleSize = 2 })}
                            }
                        }
                    }
                    if (bitmap != null && type != ImageLoaderViewModel.TYPE_FULL) imageCache.put(key, bitmap)
                }
            }
            catch (e: Exception) { e.printStackTrace() }
            finally {
                if (isActive) withContext(Dispatchers.Main) {
                    animatedDrawable?.let { view.setImageDrawable(it.apply { (this as AnimatedImageDrawable).start() })} ?: run { view.setImageBitmap(bitmap ?: placeholderBitmap) }
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

    private fun replacePrevious(key: Int, newJob: Job) {
        decoderJobMap[key]?.cancel()
        decoderJobMap[key] = newJob
    }

    override fun onCleared() {
        File(localCacheFolder, OkHttpWebDav.VIDEO_CACHE_FOLDER).deleteRecursively()
        decoderJobMap.forEach { if (it.value.isActive) it.value.cancel() }
        downloadDispatcher.close()
        super.onCleared()
    }

    class ImageCache (maxSize: Int): LruCache<String, Bitmap>(maxSize) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }


    @Parcelize
    data class Sharee (
        var name: String,
        var label: String,
        var type: Int,
    ): Parcelable

    @Parcelize
    data class Recipient (
        var shareId: String,
        var permission: Int,
        var sharedTime: Long,
        var sharee: Sharee,
    ): Parcelable

    @Parcelize
    data class ShareByMe (
        var fileId: String,
        var folderName: String,
        var with: MutableList<Recipient>,
    ): Parcelable

    @Parcelize
    data class ShareWithMe (
        var shareId: String,
        var sharePath: String,
        var albumId: String,
        var albumName: String,
        var shareBy: String,
        var shareByLabel: String,
        var permission: Int,
        var sharedTime: Long,
        var cover: Cover,
        var coverFileName: String,
        var sortOrder: Int,
        var lastModified: Long,
    ): Parcelable, Comparable<ShareWithMe> {
        override fun compareTo(other: ShareWithMe): Int = (other.lastModified - this.lastModified).toInt()
    }

    @Parcelize
    data class RemotePhoto (
        val fileId: String,
        val path: String,
        val mimeType: String,
        val width: Int,
        val height: Int,
        val coverBaseLine: Int,
        val timestamp: Long,
    ): Parcelable

    companion object {
        private const val MEMORY_CACHE_SIZE = 8     // one eighth of heap size

        private const val SHARE_LISTING_ENDPOINT = "/ocs/v2.php/apps/sharelisting/api/v1/sharedSubfolders?format=json&path="
        private const val SHAREE_LISTING_ENDPOINT = "/ocs/v1.php/apps/files_sharing/api/v1/sharees?itemType=file&format=json"
        private const val CAPABILLITIES_ENDPOINT = "/ocs/v1.php/cloud/capabilities?format=json"
        private const val PUBLISH_ENDPOINT = "/ocs/v2.php/apps/files_sharing/api/v1/shares"
        private const val AVATAR_ENDPOINT = "/index.php/avatar/"

        const val MIME_TYPE_JSON = "application/json"
        const val CONTENT_META_FILE_SUFFIX = "-content.json"
        const val PHOTO_META_JSON = "{\"id\":\"%s\",\"name\":\"%s\",\"stime\":%d,\"mime\":\"%s\",\"width\":%d,\"height\":%d},"

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