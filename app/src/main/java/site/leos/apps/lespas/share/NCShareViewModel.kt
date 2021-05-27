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
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit
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
    //private var sardine: Sardine? = null
    private val resourceRoot: String
    private val localRootFolder = "${application.cacheDir}${application.getString(R.string.lespas_base_folder_name)}"

    private val imageCache = ImageCache(((application.getSystemService(Context.ACTIVITY_SERVICE)) as ActivityManager).memoryClass / 6 * 1024 * 1024)
    private val placeholderBitmap = Tools.getBitmapFromVector(application, R.drawable.ic_baseline_placeholder_24)
    private val jobMap = HashMap<Int, Job>()

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
                val builder = OkHttpClient.Builder()
                if (getUserData(account, application.getString(R.string.nc_userdata_selfsigned)).toBoolean()) builder.hostnameVerifier { _, _ -> true }
                httpClient = builder.cache(Cache(File(localRootFolder), 500L * 1024L * 1024L))
                    .addNetworkInterceptor { chain -> chain.proceed(chain.request()).newBuilder().header("Cache-Control", CacheControl.Builder().maxAge(2, TimeUnit.DAYS).build().toString()).build() }
                    .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("Authorization", Credentials.basic(userName, peekAuthToken(account, baseUrl), StandardCharsets.UTF_8)).build()) }
                    .build()
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

    fun getPhoto(photo: RemotePhoto, view: ImageView, type: String) {
        val jobKey = System.identityHashCode(view)

        val job = viewModelScope.launch(Dispatchers.IO) {
            var bitmap = placeholderBitmap
            try {
                val key = "${photo.fileId}$type"
                imageCache.get(key)?.let {
                    bitmap = it
                } ?: run {
                    httpClient?.apply {
                        newCall(Request.Builder().url("$resourceRoot${photo.path}").get().build()).execute().use {
                            val option = BitmapFactory.Options().apply {
                                inPreferredConfig = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) Bitmap.Config.RGBA_F16 else Bitmap.Config.ARGB_8888
                            }
                            when (type) {
                                ImageLoaderViewModel.TYPE_COVER -> {
                                    val bottom = min(photo.coverBaseLine + (photo.width.toFloat() * 9 / 21).toInt(), photo.height)
                                    val rect = Rect(0, photo.coverBaseLine, photo.width, bottom)

                                    bitmap = BitmapRegionDecoder.newInstance(it.body?.byteStream(), false).decodeRegion(rect, option.apply { inSampleSize = 4 }) ?: placeholderBitmap
                                }
                                ImageLoaderViewModel.TYPE_GRID -> {
                                    if (photo.mimeType.startsWith("video")) {
                                        // TODO video thumbnail from network stream
                                    } else {
                                        bitmap = BitmapFactory.decodeStream(it.body?.byteStream(), null, option.apply { inSampleSize = 8 }) ?: placeholderBitmap
                                    }
                                }
                                ImageLoaderViewModel.TYPE_FULL -> {
                                    bitmap = BitmapFactory.decodeStream(it.body?.byteStream(), null, option) ?: placeholderBitmap
                                    if (bitmap.allocationByteCount > 100000000) bitmap = Bitmap.createScaledBitmap(bitmap, bitmap.width / 2, bitmap.height / 2, true)
                                }
                            }
                        }
                    }
                    if (type != ImageLoaderViewModel.TYPE_FULL) imageCache.put(key, bitmap)
                }
            }
            catch (e: Exception) { e.printStackTrace() }
            finally { withContext(Dispatchers.Main) { if (isActive) view.setImageBitmap(bitmap) }}
        }

        // Replacing previous job
        replacePrevious(jobKey, job)
    }

    private fun replacePrevious(key: Int, newJob: Job) {
        jobMap[key]?.cancel()
        jobMap[key] = newJob
    }

    override fun onCleared() {
        jobMap.forEach { if (it.value.isActive) it.value.cancel() }
        super.onCleared()
    }

    class ImageCache (maxSize: Int) : LruCache<String, Bitmap>(maxSize) {
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