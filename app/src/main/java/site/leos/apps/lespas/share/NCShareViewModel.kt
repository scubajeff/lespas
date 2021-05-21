package site.leos.apps.lespas.share

import android.accounts.AccountManager
import android.app.Application
import android.os.Parcelable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import okhttp3.*
import okio.IOException
import org.json.JSONException
import org.json.JSONObject
import site.leos.apps.lespas.R
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime

class NCShareViewModel(application: Application): AndroidViewModel(application) {
    private val _shareByMe = MutableStateFlow<List<ShareByMe>?>(null)
    private val _shareWithMe = MutableStateFlow<List<ShareWithMe>?>(null)
    private val _sharees = MutableStateFlow<List<Sharee>?>(null)
    val shareByMe: StateFlow<List<ShareByMe>?> = _shareByMe
    val shareWithMe: StateFlow<List<ShareWithMe>?> = _shareWithMe
    val sharees: StateFlow<List<Sharee>?> = _sharees

    private val baseUrl: String
    private val userName: String
    private var httpClient: OkHttpClient? = null

    init {
        AccountManager.get(application).run {
            val account = accounts[0]
            userName = getUserData(account, application.getString(R.string.nc_userdata_username))
            baseUrl = getUserData(account, application.getString(R.string.nc_userdata_server))

            val interceptor = try {
                Interceptor { chain -> chain.proceed(chain.request().newBuilder().addHeader("Authorization", Credentials.basic(userName, peekAuthToken(account, baseUrl), StandardCharsets.UTF_8)).build()) }
            } catch (e: java.io.IOException) {
                e.printStackTrace()
                null
            }

            interceptor?.let {
                httpClient = OkHttpClient.Builder().addInterceptor(it).build()
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            flow<List<ShareByMe>?> { emit(getShareBy()) }.collect { _shareByMe.value = it }
            flow<List<ShareWithMe>?> { emit(getShareWith()) }.collect { _shareWithMe.value = it }
            flow<List<Sharee>?> { emit(getSharees()) }.collect { _sharees.value = it }
        }
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    private fun getShareBy(): MutableList<ShareByMe>? {
        val result = mutableListOf<ShareByMe>()
        var response: Response? = null

        httpClient?.let {
            try {
                // Get result from server
                response = it.newCall(Request.Builder().url("$baseUrl$SHARE_LISTING_URL").addHeader(NEXTCLOUD_OCSAPI_HEADER, "true").build()).execute()

                // Parsing result
                var shareType: Int
                var sharee: Recipient
                response?.body?.string()?.apply {
                    if (JSONObject(this).getJSONObject("ocs").getJSONObject("meta").getInt("statuscode") != 200) return null
                    val data = JSONObject(this).getJSONObject("ocs").getJSONArray("data")
                    for (i in 0 until data.length()) {
                        data.getJSONObject(i).apply {
                            shareType = when(getString("type")) {
                                SHARE_TYPE_USER_STRING-> SHARE_TYPE_USER
                                SHARE_TYPE_GROUP_STRING-> SHARE_TYPE_GROUP
                                else-> -1
                            }
                            if (shareType >= 0 && getString("owner") == userName && getBoolean("is_directory") && getString("path").startsWith("/lespas")) {
                                // Only interested in shares of subfolders under lespas/

                                //sharee = Recipient(getString("id"), shareType, getString("recipient"), getInt("permissions"), SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(getString("time").substring(0, 19)).toInstant().epochSecond)
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
            } catch (e: IOException) {
                e.printStackTrace()
            } catch (e: IllegalStateException) {
                e.printStackTrace()
            } catch (e: JSONException) {
                e.printStackTrace()
            } finally {
                response?.close()
            }
        }

        return result
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    private fun getShareWith(): MutableList<ShareWithMe>? {
        val result = mutableListOf<ShareWithMe>()
        var response: Response? = null

        httpClient?.let {
            try {
                // Get result from server
                response = it.newCall(Request.Builder().url("$baseUrl$SHARE_LISTING_URL").addHeader(NEXTCLOUD_OCSAPI_HEADER, "true").build()).execute()

                // Parsing result
                var shareType: Int
                response?.body?.string()?.apply {
                    if (JSONObject(this).getJSONObject("ocs").getJSONObject("meta").getInt("statuscode") != 200) return null

                    val data = JSONObject(this).getJSONObject("ocs").getJSONArray("data")
                    for (i in 0 until data.length()) {
                        data.getJSONObject(i).apply {
                            shareType = when(getString("type")) {
                                SHARE_TYPE_USER_STRING-> SHARE_TYPE_USER
                                SHARE_TYPE_GROUP_STRING-> SHARE_TYPE_GROUP
                                else-> -1
                            }
                            if (shareType >= 0 && getString("recipient") == userName && getBoolean("is_directory") && getString("path").startsWith("/lespas")) {
                                result.add(ShareWithMe(getString("file_id"), getString("name"), getString("owner"), getInt("permissions"), OffsetDateTime.parse(getString("time")).toInstant().epochSecond))
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                e.printStackTrace()
            } catch (e: IllegalStateException) {
                e.printStackTrace()
            } catch (e: JSONException) {
                e.printStackTrace()
            } finally {
                response?.close()
            }
        }

        return result
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    private fun getSharees(): MutableList<Sharee>? {
        val result = mutableListOf<Sharee>()
        var response: Response? = null

        httpClient?.let {
            try {
                // Get result from server
                response = it.newCall(Request.Builder().url("$baseUrl$SHAREE_LISTING_URL").addHeader(NEXTCLOUD_OCSAPI_HEADER, "true").build()).execute()

                // Parsing result
                response?.body?.string()?.apply {
                    if (JSONObject(this).getJSONObject("ocs").getJSONObject("meta").getInt("statuscode") != 100) return null

                    val data = JSONObject(this).getJSONObject("ocs").getJSONObject("data")
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
            } catch (e: IOException) {
                e.printStackTrace()
            } catch (e: IllegalStateException) {
                e.printStackTrace()
            } catch (e: JSONException) {
                e.printStackTrace()
            } finally {
                response?.close()
            }
        }

        return result
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
        var fileId: String,
        var albumName: String,
        var shareBy: String,
        var permission: Int,
        var sharedTime: Long,
    ): Parcelable

    companion object {
        private const val NEXTCLOUD_OCSAPI_HEADER = "OCS-APIRequest"
        //private const val SHARE_BASE_URL = "/ocs/v2.php/apps/files_sharing/api/v1"
        private const val SHARE_LISTING_URL = "/ocs/v2.php/apps/sharelisting/api/v1/sharedSubfolders?format=json&path="
        private const val SHAREE_LISTING_URL = "/ocs/v1.php/apps/files_sharing/api/v1/sharees?itemType=file&format=json"

        const val SHARE_TYPE_USER = 0
        private const val SHARE_TYPE_USER_STRING = "user"
        const val SHARE_TYPE_GROUP = 1
        private const val SHARE_TYPE_GROUP_STRING = "group"

        private const val PERMISSION_CAN_READ = 1
        private const val PERMISSION_CAN_UPDATE = 2
        private const val PERMISSION_CAN_CREATE = 4
        private const val PERMISSION_CAN_DELETE = 8
        private const val PERMISSION_CAN_SHARE = 16
        private const val PERMISSION_ALL = 31
    }
}