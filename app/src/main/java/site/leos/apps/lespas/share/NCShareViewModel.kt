package site.leos.apps.lespas.share

import android.accounts.AccountManager
import android.app.Application
import android.net.Uri
import android.os.Parcelable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.thegrizzlylabs.sardineandroid.Sardine
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import okhttp3.*
import okio.IOException
import org.json.JSONException
import org.json.JSONObject
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.Album
import site.leos.apps.lespas.album.Cover
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime

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
    private var sardine: Sardine? = null
    private lateinit var resourceRoot: String
    private val localRootFolder = "${application.cacheDir}${application.getString(R.string.lespas_base_folder_name)}"

    fun interface LoadCompleteListener{
        fun onLoadComplete()
    }

    init {
        AccountManager.get(application).run {
            val account = accounts[0]
            userName = getUserData(account, application.getString(R.string.nc_userdata_username))
            baseUrl = getUserData(account, application.getString(R.string.nc_userdata_server))

            try {
                Interceptor { chain -> chain.proceed(chain.request().newBuilder().addHeader("Authorization", Credentials.basic(userName, peekAuthToken(account, baseUrl), StandardCharsets.UTF_8)).build()) }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }?.let {
                val builder = OkHttpClient.Builder()
                if (getUserData(account, application.getString(R.string.nc_userdata_selfsigned)).toBoolean()) builder.hostnameVerifier { _, _ -> true }
                httpClient = builder.cache(Cache(File(localRootFolder), 500L * 1024L * 1024L)).addInterceptor(it).build()
                sardine = OkHttpSardine(httpClient)
                resourceRoot = "$baseUrl${application.getString(R.string.dav_files_endpoint)}$userName"
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            _shareByMe.value = getShareByMe()
            _shareWithMe.value = getShareWithMe()
            _sharees.value = getSharees()
        }
    }

    private fun httpGet(url: String): JSONObject?  {
        var result: JSONObject? = null

        httpClient?.apply {
            newCall(Request.Builder().url(url).addHeader(NEXTCLOUD_OCSAPI_HEADER, "true").build()).execute().use {
                result = it.body?.string()?.let { response -> JSONObject(response).getJSONObject("ocs") }
            }
        }

        return result
    }

    private fun getShareByMe(): MutableList<ShareByMe> {
        val result = mutableListOf<ShareByMe>()
        var shareType: Int
        var sharee: Recipient

        try {
            httpGet("$baseUrl$SHARE_LISTING_URL")?.apply {
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
        var meta: JSONObject

        try {
            httpGet("$baseUrl$SHARE_LISTING_URL")?.apply {
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
                sardine?.get("$resourceRoot/${Uri.encode(share.sharePath, "@#&=*+-_.,:!?()/~'%")}/${share.albumId}.json")?.reader().use {
                    meta = JSONObject(it?.readText() ?: "").getJSONObject("lespas")
                    meta.getJSONObject("cover").apply {
                        share.cover = Cover(getString("id"), getInt("baseline"), getInt("width"), getInt("height"))
                        share.coverFileName = getString("filename")
                    }
                    share.sortOrder = meta.getInt("sort")
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
            httpGet("$baseUrl$SHAREE_LISTING_URL")?.apply {
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
                        response = httpClient.newCall(Request.Builder().url("$baseUrl$PUBLISH_URL").addHeader(NEXTCLOUD_OCSAPI_HEADER, "true")
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
                    response = httpClient.newCall(Request.Builder().url("$baseUrl$PUBLISH_URL/${recipient.shareId}").delete().addHeader(NEXTCLOUD_OCSAPI_HEADER, "true").build()).execute()
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
            httpGet("$baseUrl$PUBLISH_URL/${shareId}?format=json")?.apply {
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

    fun getPhoto(share: ShareWithMe, callBack: LoadCompleteListener?) {
        viewModelScope.launch(Dispatchers.IO) {
            // TODO real disk cache
            val localCache = File("$localRootFolder/${share.cover.cover}")
            if (!localCache.exists()) {
                sardine?.get("$resourceRoot${Uri.encode("${share.sharePath}/${share.coverFileName}", "@#&=*+-_.,:!?()/~'%")}")!!.use { input ->
                    localCache.outputStream().use { output ->
                        input.copyTo(output, 8192)
                    }
                }
            }

            callBack?.onLoadComplete()
        }
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
        var permission: Int,
        var sharedTime: Long,
        var cover: Cover,
        var coverFileName: String,
        var sortOrder: Int,
    ): Parcelable

    companion object {
        private const val NEXTCLOUD_OCSAPI_HEADER = "OCS-APIRequest"
        private const val SHARE_LISTING_URL = "/ocs/v2.php/apps/sharelisting/api/v1/sharedSubfolders?format=json&path="
        private const val SHAREE_LISTING_URL = "/ocs/v1.php/apps/files_sharing/api/v1/sharees?itemType=file&format=json"
        private const val PUBLISH_URL = "/ocs/v2.php/apps/files_sharing/api/v1/shares"

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