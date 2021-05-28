package site.leos.apps.lespas.share

import android.accounts.AccountManager
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Parcelable
import android.util.LruCache
import android.widget.ImageView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.thegrizzlylabs.sardineandroid.Sardine
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.parcelize.Parcelize
import okhttp3.*
import okio.IOException
import org.json.JSONException
import org.json.JSONObject
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.Album
import site.leos.apps.lespas.album.Cover
import site.leos.apps.lespas.helper.ImageLoaderViewModel
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.sync.SyncAdapter
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import java.util.concurrent.Executors
import kotlin.math.min

class NCShareViewModel(application: Application): AndroidViewModel(application) {
    private val _shareByMe = MutableStateFlow<List<ShareByMe>>(arrayListOf())
    private val _shareWithMe = MutableStateFlow<List<ShareWithMe>>(arrayListOf())
    private val _sharees = MutableStateFlow<List<Sharee>>(arrayListOf())
    val shareByMe: StateFlow<List<ShareByMe>> = _shareByMe
    val shareWithMe: StateFlow<List<ShareWithMe>> = _shareWithMe
    val sharees: StateFlow<List<Sharee>> = _sharees

    private val baseUrl: String
    private val userName: String
    private var httpClient: OkHttpClient? = null
    private var cachedHttpClient: OkHttpClient? = null
    private var sardine: Sardine? = null
    private val resourceRoot: String
    private val localRootFolder = "${application.cacheDir}${application.getString(R.string.lespas_base_folder_name)}"

    private val placeholderBitmap = Tools.getBitmapFromVector(application, R.drawable.ic_baseline_placeholder_24)

    private val imageCache = ImageCache(((application.getSystemService(Context.ACTIVITY_SERVICE)) as ActivityManager).memoryClass / 6 * 1024 * 1024)
    private val diskCache = Cache(File(localRootFolder), 500L * 1024L * 1024L)
    private val decoderJobMap = HashMap<Int, Job>()
    private val downloadDispatcher = Executors.newFixedThreadPool(2).asCoroutineDispatcher()

    fun interface LoadCompleteListener{
        fun onLoadComplete()
    }

    init {
        AccountManager.get(application).run {
            val account = accounts[0]
            userName = getUserData(account, application.getString(R.string.nc_userdata_username))
            baseUrl = getUserData(account, application.getString(R.string.nc_userdata_server))
            resourceRoot = "$baseUrl${application.getString(R.string.dav_files_endpoint)}$userName"
            try {
                val builder = OkHttpClient.Builder().apply {
                    if (getUserData(account, application.getString(R.string.nc_userdata_selfsigned)).toBoolean()) hostnameVerifier { _, _ -> true }
                    addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("Authorization", Credentials.basic(userName, peekAuthToken(account, baseUrl), StandardCharsets.UTF_8)).build()) }
                }
                httpClient = builder.build()
                cachedHttpClient = builder.cache(diskCache)
                    .addNetworkInterceptor { chain -> chain.proceed(chain.request()).newBuilder().removeHeader("Pragma").header("Cache-Control", "public, max-age=864000").build() }
                    .build()
                sardine = OkHttpSardine(httpClient)
            } catch (e: Exception) { e.printStackTrace() }
        }

        viewModelScope.launch(Dispatchers.IO) {
            getShareList()
            _sharees.value = getSharees()
        }
    }

    private fun ocsGet(url: String): JSONObject?  {
        var result: JSONObject? = null

        httpClient?.apply {
            newCall(Request.Builder().url(url).addHeader(NEXTCLOUD_OCSAPI_HEADER, "true").build()).execute().use {
                result = it.body?.string()?.let { response -> JSONObject(response).getJSONObject("ocs") }
            }
        }

        return result
    }

    private fun getShareList() {
        val sharesBy = mutableListOf<ShareByMe>()
        val sharesWith = mutableListOf<ShareWithMe>()
        var shareType: Int
        var sharee: Recipient

        try {
            ocsGet("$baseUrl$SHARE_LISTING_ENDPOINT")?.apply {
                //if (getJSONObject("meta").getInt("statuscode") != 200) return null  // TODO this safety check is not necessary
                val data = getJSONArray("data")
                for (i in 0 until data.length()) {
                    data.getJSONObject(i).apply {
                        shareType = when(getString("type")) {
                            SHARE_TYPE_USER_STRING-> SHARE_TYPE_USER
                            SHARE_TYPE_GROUP_STRING-> SHARE_TYPE_GROUP
                            else-> -1
                        }
                        if (shareType >= 0 && getBoolean("is_directory") && getString("path").startsWith("/lespas")) {
                            // Only interested in shares of subfolders under lespas/
                            if (getString("owner") == userName) {
                                sharee = Recipient(getString("id"), getInt("permissions"), OffsetDateTime.parse(getString("time")).toInstant().epochSecond, Sharee(getString("recipient"), getString("recipient"), shareType))

                                @Suppress("SimpleRedundantLet")
                                sharesBy.find { share -> share.fileId == getString("file_id") }?.let { item ->
                                    // If this folder existed in result, add new sharee only
                                    item.with.add(sharee)
                                } ?: run {
                                    // Create new share by me item
                                    sharesBy.add(ShareByMe(getString("file_id"), getString("name"), mutableListOf(sharee)))
                                }
                            } else if (getString("recipient") == userName) {
                                sharesWith.add(ShareWithMe(getString("id"), "", getString("file_id"), getString("name"), getString("owner"), getInt("permissions"), OffsetDateTime.parse(getString("time")).toInstant().epochSecond, Cover("", 0, 0, 0), "", Album.BY_DATE_TAKEN_ASC))
                            }
                        }
                    }
                }
            }

            for (share in sharesWith) {
                share.sharePath = getSharePath(share.shareId) ?: ""
                cachedHttpClient?.apply {
                    newCall(Request.Builder().url("${resourceRoot}${share.sharePath}/${share.albumId}.json").build()).execute().use {
                        JSONObject(it.body?.string() ?: "").getJSONObject("lespas").let { meta->
                            meta.getJSONObject("cover").apply {
                                share.cover = Cover(getString("id"), getInt("baseline"), getInt("width"), getInt("height"))
                                share.coverFileName = getString("filename")
                            }
                            share.sortOrder = meta.getInt("sort")
                        }
                    }
                }
            }

            _shareByMe.value = sharesBy
            _shareWithMe.value = sharesWith.apply { sort() }
        }
        catch (e: Exception) { e.printStackTrace() }
    }

    private fun getShareByMe(): MutableList<ShareByMe> {
        val result = mutableListOf<ShareByMe>()
        var shareType: Int
        var sharee: Recipient

        try {
            ocsGet("$baseUrl$SHARE_LISTING_ENDPOINT")?.apply {
                //if (getJSONObject("meta").getInt("statuscode") != 200) return null  // TODO this safety check is not necessary
                val data = getJSONArray("data")
                for (i in 0 until data.length()) {
                    data.getJSONObject(i).apply {
                        shareType = when(getString("type")) {
                            SHARE_TYPE_USER_STRING-> SHARE_TYPE_USER
                            SHARE_TYPE_GROUP_STRING-> SHARE_TYPE_GROUP
                            else-> -1
                        }
                        if (shareType >= 0 && getString("owner") == userName && getBoolean("is_directory") && getString("path").startsWith("/lespas")) {
                            // Only interested in shares of subfolders under lespas/

                            sharee = Recipient(getString("id"), getInt("permissions"), OffsetDateTime.parse(getString("time")).toInstant().epochSecond, Sharee(getString("recipient"), getString("recipient"), shareType))

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

    private fun getShareWithMe(): MutableList<ShareWithMe> {
        val result = mutableListOf<ShareWithMe>()
        var shareType: Int

        try {
            ocsGet("$baseUrl$SHARE_LISTING_ENDPOINT")?.apply {
                //if (getJSONObject("meta").getInt("statuscode") != 200) return null  // TODO this safety check is not necessary
                val data = getJSONArray("data")
                for (i in 0 until data.length()) {
                    data.getJSONObject(i).apply {
                        shareType = when(getString("type")) {
                            SHARE_TYPE_USER_STRING-> SHARE_TYPE_USER
                            SHARE_TYPE_GROUP_STRING-> SHARE_TYPE_GROUP
                            else-> -1
                        }
                        if (shareType >= 0 && getString("recipient") == userName && getBoolean("is_directory") && getString("path").startsWith("/lespas")) {
                            result.add(ShareWithMe(getString("id"), "", getString("file_id"), getString("name"), getString("owner"), getInt("permissions"), OffsetDateTime.parse(getString("time")).toInstant().epochSecond, Cover("", 0, 0, 0), "", Album.BY_DATE_TAKEN_ASC))
                        }
                    }
                }
            }

            for (share in result) {
                share.sharePath = getSharePath(share.shareId) ?: ""
                httpClient?.apply {
                    newCall(Request.Builder().url("${resourceRoot}${share.sharePath}/${share.albumId}.json").build()).execute().use {
                        JSONObject(it.body?.string() ?: "").getJSONObject("lespas").let { meta->
                            meta.getJSONObject("cover").apply {
                                share.cover = Cover(getString("id"), getInt("baseline"), getInt("width"), getInt("height"))
                                share.coverFileName = getString("filename")
                            }
                            share.sortOrder = meta.getInt("sort")
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

    private fun getSharees(): MutableList<Sharee> {
        val result = mutableListOf<Sharee>()

        try {
            ocsGet("$baseUrl$SHAREE_LISTING_ENDPOINT")?.apply {
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
        }
        catch (e: IOException) { e.printStackTrace() }
        catch (e: IllegalStateException) { e.printStackTrace() }
        catch (e: JSONException) { e.printStackTrace() }

        return arrayListOf()
    }

    private fun createShares(albums: List<ShareByMe>) {
        var response: Response? = null

        httpClient?.let { httpClient->
            for (album in albums) {
                for (recipient in album.with) {
                    try {
                        response = httpClient.newCall(Request.Builder().url("$baseUrl$PUBLISH_ENDPOINT").addHeader(NEXTCLOUD_OCSAPI_HEADER, "true")
                            .post(
                                FormBody.Builder()
                                    .add("path", "/lespas/${album.folderName}")
                                    .add("shareWith", recipient.sharee.name)
                                    .add("shareType", recipient.sharee.type.toString())
                                    .add("permissions", recipient.permission.toString())
                                    .build()
                            )
                            .build()
                        ).execute()
                    }
                    catch (e: java.io.IOException) { e.printStackTrace() }
                    catch (e: IllegalStateException) { e.printStackTrace() }
                    finally { response?.close() }
                }
            }
        }
    }

    private fun deleteShares(recipients: List<Recipient>) {
        var response: Response? = null

        httpClient?.let { httpClient->
            for (recipient in recipients) {
                try {
                    response = httpClient.newCall(Request.Builder().url("$baseUrl$PUBLISH_ENDPOINT/${recipient.shareId}").delete().addHeader(NEXTCLOUD_OCSAPI_HEADER, "true").build()).execute()
                }
                catch (e: java.io.IOException) { e.printStackTrace() }
                catch (e: IllegalStateException) { e.printStackTrace() }
                finally { response?.close() }
            }
        }
    }

    private fun getSharePath(shareId: String): String? {
        var path: String? = null

        try {
            ocsGet("$baseUrl$PUBLISH_ENDPOINT/${shareId}?format=json")?.apply {
                path = getJSONArray("data").getJSONObject(0).getString("path")
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
            _shareByMe.value = getShareByMe()
        }
    }

    fun unPublish(recipients: List<Recipient>) {
        viewModelScope.launch(Dispatchers.IO) {
            deleteShares(recipients)
            _shareByMe.value = getShareByMe()
        }
    }

    fun updatePublish(albums: ShareByMe, removeRecipients: List<Recipient>) {
        viewModelScope.launch(Dispatchers.IO) {
            if (albums.with.isNotEmpty()) createShares(listOf(albums))
            if (removeRecipients.isNotEmpty()) deleteShares(removeRecipients)
            if (albums.with.isNotEmpty() || removeRecipients.isNotEmpty()) _shareByMe.value = getShareByMe()
        }
    }

    suspend fun getRemotePhotoList(share: ShareWithMe): List<RemotePhoto> {
        val result = mutableListOf<RemotePhoto>()
        withContext(Dispatchers.IO) {
            sardine?.list("$resourceRoot${share.sharePath}", SyncAdapter.FOLDER_CONTENT_DEPTH, SyncAdapter.NC_PROPFIND_PROP)?.drop(1)!!.forEach { photo->
                // TODO show video file
                //if (photo.contentType.startsWith("image/") || photo.contentType.startsWith("video/"))
                if (photo.contentType.startsWith("image/"))
                    result.add(RemotePhoto(photo.customProps[SyncAdapter.OC_UNIQUE_ID]!!, "${share.sharePath}/${photo.name}", photo.contentType, 0, 0, 0, photo.modified.toInstant().epochSecond))
            }

            when(share.sortOrder) {
                Album.BY_NAME_ASC-> result.sortWith { o1, o2 -> o1.path.compareTo(o2.path) }
                Album.BY_NAME_DESC-> result.sortWith { o1, o2 -> o2.path.compareTo(o1.path) }
                Album.BY_DATE_TAKEN_ASC, Album.BY_DATE_MODIFIED_ASC-> result.sortWith { o1, o2 -> (o1.timestamp - o2.timestamp).toInt() }
                Album.BY_DATE_TAKEN_DESC, Album.BY_DATE_MODIFIED_DESC-> result.sortWith { o1, o2 -> (o2.timestamp - o1.timestamp).toInt() }
            }
        }

        return result
    }

/*
    fun getPhoto(share: ShareWithMe, callBack: LoadCompleteListener?) {
        viewModelScope.launch(Dispatchers.IO) {
            // TODO real disk cache
            val localCache = File("$localRootFolder/${share.cover.cover}")
            try {
                if (!localCache.exists()) {
                    httpClient?.apply {
                        newCall(Request.Builder().url("$resourceRoot${share.sharePath}/${share.coverFileName}").get().build()).execute().body?.byteStream()?.use { input ->
                            localCache.outputStream().use { output ->
                                input.copyTo(output, 8192)
                            }
                        }
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }

            callBack?.onLoadComplete()
        }
    }
*/

    fun getPhoto(photo: RemotePhoto, view: ImageView, type: String) { getPhoto(photo, view, type, null) }
    fun getPhoto(photo: RemotePhoto, view: ImageView, type: String, callBack: LoadCompleteListener?) {
        val jobKey = System.identityHashCode(view)

        view.imageAlpha = 0
        var bitmap: Bitmap? = null
        val job = viewModelScope.launch(downloadDispatcher) {
            try {
                val key = "${photo.fileId}$type"
                imageCache.get(key)?.let { bitmap = it } ?: run {
                    cachedHttpClient?.apply {
                        newCall(Request.Builder().url("$resourceRoot${photo.path}").get().build()).execute().use {
                            val option = BitmapFactory.Options().apply {
                                //inPreferredConfig = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) Bitmap.Config.RGBA_F16 else Bitmap.Config.ARGB_8888
                            }
                            when (type) {
                                ImageLoaderViewModel.TYPE_COVER -> {
                                    val bottom = min(photo.coverBaseLine + (photo.width.toFloat() * 9 / 21).toInt(), photo.height)
                                    val rect = Rect(0, photo.coverBaseLine, photo.width, bottom)

                                    bitmap = BitmapRegionDecoder.newInstance(it.body?.byteStream(), false).decodeRegion(rect, option.apply { inSampleSize = 4 })
                                }
                                ImageLoaderViewModel.TYPE_GRID -> {
                                    if (photo.mimeType.startsWith("video")) {
                                        // TODO video thumbnail from network stream
                                    } else {
                                        bitmap = BitmapFactory.decodeStream(it.body?.byteStream(), null, option.apply { inSampleSize = 8 })
                                    }
                                }
                                ImageLoaderViewModel.TYPE_FULL -> {
                                    bitmap = BitmapFactory.decodeStream(it.body?.byteStream(), null, option)?.also { bitmap->
                                        if (bitmap.allocationByteCount > 100000000) Bitmap.createScaledBitmap(bitmap, bitmap.width / 2, bitmap.height / 2, true)
                                    }
                                }
                            }
                        }
                    }
                    if (bitmap != null && type != ImageLoaderViewModel.TYPE_FULL) imageCache.put(key, bitmap)
                }
            }
            catch (e: Exception) { e.printStackTrace() }
            finally {
                if (isActive) withContext(Dispatchers.Main) {
                    view.setImageBitmap(bitmap ?: placeholderBitmap)
                    view.imageAlpha = 255
                }
                callBack?.onLoadComplete()
            }
        }

        // Replacing previous job
        replacePrevious(jobKey, job)
    }

    private fun replacePrevious(key: Int, newJob: Job) {
        decoderJobMap[key]?.cancel()
        decoderJobMap[key] = newJob
    }

    override fun onCleared() {
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
        var permission: Int,
        var sharedTime: Long,
        var cover: Cover,
        var coverFileName: String,
        var sortOrder: Int,
    ): Parcelable, Comparable<ShareWithMe> {
        override fun compareTo(other: ShareWithMe): Int = (other.sharedTime - this.sharedTime).toInt()
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
        private const val NEXTCLOUD_OCSAPI_HEADER = "OCS-APIRequest"
        private const val SHARE_LISTING_ENDPOINT = "/ocs/v2.php/apps/sharelisting/api/v1/sharedSubfolders?format=json&path="
        private const val SHAREE_LISTING_ENDPOINT = "/ocs/v1.php/apps/files_sharing/api/v1/sharees?itemType=file&format=json"
        private const val PUBLISH_ENDPOINT = "/ocs/v2.php/apps/files_sharing/api/v1/shares"

        const val SHARE_TYPE_USER = 0
        private const val SHARE_TYPE_USER_STRING = "user"
        const val SHARE_TYPE_GROUP = 1
        private const val SHARE_TYPE_GROUP_STRING = "group"

        const val PERMISSION_CAN_READ = 1
        private const val PERMISSION_CAN_UPDATE = 2
        private const val PERMISSION_CAN_CREATE = 4
        private const val PERMISSION_CAN_DELETE = 8
        private const val PERMISSION_CAN_SHARE = 16
        private const val PERMISSION_ALL = 31
    }
}