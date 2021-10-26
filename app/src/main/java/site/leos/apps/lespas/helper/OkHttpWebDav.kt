package site.leos.apps.lespas.helper

import android.accounts.NetworkErrorException
import android.content.ContentResolver
import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Parcelable
import androidx.preference.PreferenceManager
import kotlinx.parcelize.Parcelize
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.*
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import site.leos.apps.lespas.R
import java.io.File
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class OkHttpWebDav(private val userId: String, password: String, serverAddress: String, selfSigned: Boolean, cacheFolder: String, userAgent: String?) {
    private val chunkUploadBase = "${serverAddress}/remote.php/dav/uploads/${userId}"
    private val httpClient: OkHttpClient
    private val cachedHttpClient: OkHttpClient

    init {
        val builder = OkHttpClient.Builder().apply {
            if (selfSigned) hostnameVerifier { _, _ -> true }
            addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("Authorization", Credentials.basic(userId, password, StandardCharsets.UTF_8)).build()) }
            addNetworkInterceptor { chain -> chain.proceed((chain.request().newBuilder().removeHeader("User-Agent").addHeader("User-Agent", userAgent ?: "OkHttpWebDav").build())) }
            readTimeout(20, TimeUnit.SECONDS)
            writeTimeout(20, TimeUnit.SECONDS)
        }
        httpClient = builder.build()
        cachedHttpClient = builder.cache(Cache(File(cacheFolder), DISK_CACHE_SIZE)).addNetworkInterceptor { chain -> chain.proceed(chain.request()).newBuilder().removeHeader("Pragma").header("Cache-Control", "public, max-age=${MAX_AGE}").build() }.build()

        // Make cache folder for video download
        //File(cacheFolder, VIDEO_CACHE_FOLDER).mkdirs()
    }

    fun copy(source: String, dest: String) { copyOrMove(true, source, dest) }

    fun createFolder(folderName: String): String {
        httpClient.newCall(Request.Builder().url(folderName).method("MKCOL", null).build()).execute().use { response ->
            return when {
                response.isSuccessful -> response.header("oc-fileid", "") ?: ""
                // Ignore folder already existed error
                response.code == 405 -> ""
                else-> throw OkHttpWebDavException(response)
            }
        }
    }

    fun delete(targetName: String) {
        httpClient.newCall(Request.Builder().url(targetName).delete().build()).execute().use { response->
            if (!response.isSuccessful) throw OkHttpWebDavException(response)
        }
    }

    fun download(source: String, dest: String, cacheControl: CacheControl?) {
        val reqBuilder = Request.Builder().url(source)
        cacheControl?.let { reqBuilder.cacheControl(cacheControl) }
        httpClient.newCall(reqBuilder.get().build()).execute().use { response->
            if (response.isSuccessful) File(dest).sink(false).buffer().use { it.writeAll(response.body!!.source()) }
            else throw OkHttpWebDavException(response)
        }
    }

    fun download(source: String, dest: File, cacheControl: CacheControl?) {
        val reqBuilder = Request.Builder().url(source)
        cacheControl?.let { reqBuilder.cacheControl(cacheControl) }
        httpClient.newCall(reqBuilder.get().build()).execute().use { response->
            if (response.isSuccessful) dest.sink(false).buffer().use { it.writeAll(response.body!!.source()) }
            else throw OkHttpWebDavException(response)
        }
    }

    fun getCallFactory() = httpClient

    fun getRawResponse(source: String, useCache: Boolean): Response {
        val reqBuilder = Request.Builder().url(source)
        return (if (useCache) cachedHttpClient.newCall(reqBuilder.get().build()) else httpClient.newCall(reqBuilder.get().build())).execute()
    }

    fun getStream(source: String, useCache: Boolean, cacheControl: CacheControl?): InputStream = getStreamBool(source, useCache, cacheControl).first
    fun getStreamBool(source: String, useCache: Boolean, cacheControl: CacheControl?): Pair<InputStream, Boolean> {
        val reqBuilder = Request.Builder().url(source)
        (if (useCache) {
            cacheControl?.let { reqBuilder.cacheControl(cacheControl) }
            cachedHttpClient.newCall(reqBuilder.get().build())
        } else {
            httpClient.newCall(reqBuilder.get().build())
        }).execute().also { response ->
            if (response.isSuccessful) return Pair(response.body!!.byteStream(), response.networkResponse != null)
            else {
                response.close()
                throw OkHttpWebDavException(response)
            }
        }
    }

    fun isExisted(targetName: String): Boolean {
        var result: Boolean
        httpClient.newCall(Request.Builder().url(targetName).cacheControl(CacheControl.FORCE_NETWORK).method("PROPFIND", null).header("Depth", JUST_FOLDER_DEPTH).build()).execute().use { response ->
            result = when {
                response.isSuccessful -> true
                response.code == 404 -> false
                else -> throw OkHttpWebDavException(response)
            }
        }

        return result
    }

    fun list(targetName: String, depth: String): List<DAVResource> {
        val result = mutableListOf<DAVResource>()

        httpClient.newCall(Request.Builder().url(targetName).cacheControl(CacheControl.FORCE_NETWORK).method("PROPFIND", PROPFIND_BODY.toRequestBody("text/xml".toMediaType())).header("Depth", depth).build()).execute().use { response->
            if (response.isSuccessful) {
                val parser = XmlPullParserFactory.newInstance().newPullParser()
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                //parser.setInput(response.body!!.byteStream(), null)
                parser.setInput(response.body!!.byteStream().bufferedReader())

                var res = DAVResource()
                var text = ""
                while (parser.next() != XmlPullParser.END_DOCUMENT) {
                    when (parser.eventType) {
                        XmlPullParser.START_TAG -> {
                            when (parser.name) {
                                RESPONSE_TAG -> res = DAVResource()
                            }
                        }
                        XmlPullParser.TEXT -> text = parser.text
                        XmlPullParser.END_TAG -> {
                            when (parser.name) {
                                HREF_TAG -> res.name = URI(
                                    if (text.endsWith('/')) {
                                        res.isFolder = true
                                        text.dropLast(1).substringAfterLast('/')
                                    } else {
                                        res.isFolder = false
                                        text.substringAfterLast('/')
                                    }).path
                                OC_UNIQUE_ID -> res.fileId = text
                                DAV_GETETAG -> res.eTag = text
                                DAV_GETCONTENTTYPE -> res.contentType = text
                                DAV_GETLASTMODIFIED -> res.modified = Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(text)).atZone(ZoneId.systemDefault()).toLocalDateTime()
                                DAV_SHARE_TYPE -> res.isShared = true
                                RESPONSE_TAG -> result.add(res)
                                DAV_GETCONTENTLENGTH -> res.size = try { text.toLong() } catch (e: NumberFormatException) { 0L }
                            }
                        }
                    }
                }
            } else { throw OkHttpWebDavException(response) }

            return result
        }
    }

    fun move(source: String, dest: String) { copyOrMove(false, source, dest) }

    fun ocsDelete(url: String) {
        httpClient.newCall(Request.Builder().url(url).addHeader(NEXTCLOUD_OCSAPI_HEADER, "true").delete().build()).execute().use {}
    }

    fun ocsGet(url: String): JSONObject? =
        httpClient.newCall(Request.Builder().url(url).addHeader(NEXTCLOUD_OCSAPI_HEADER, "true").build()).execute().use { response ->
            if (response.isSuccessful) response.body?.string()?.let { json-> JSONObject(json).getJSONObject("ocs") }
            else null
        }

    fun ocsPost(url: String, body: RequestBody) {
        httpClient.newCall(Request.Builder().url(url).addHeader(NEXTCLOUD_OCSAPI_HEADER, "true").post(body).build()).execute().use {}
    }

    fun upload(source: String, dest: String, mimeType: String): Pair<String, String> {
        httpClient.newCall(Request.Builder().url(dest).put(source.toRequestBody(mimeType.toMediaTypeOrNull())).build()).execute().use { response->
            if (response.isSuccessful) return Pair(response.header("oc-fileid", "") ?: "", response.header("oc-etag", "") ?: "")
            else throw OkHttpWebDavException(response)
        }
    }

    fun upload(source: File, dest: String, mimeType: String, ctx: Context): Pair<String, String> {
        source.length().run {
            if (this > CHUNK_SIZE) return chunksUpload(source.inputStream(), dest.substringAfterLast('/'), dest, mimeType, this, ctx)
            else httpClient.newCall(Request.Builder().url(dest).put(source.asRequestBody(mimeType.toMediaTypeOrNull())).build()).execute().use { response->
                if (response.isSuccessful) return Pair(response.header("oc-fileid", "") ?: "", response.header("oc-etag", "") ?: "")
                else throw OkHttpWebDavException(response)
            }
        }
    }

    fun upload(source: Uri, dest: String, mimeType: String, contentResolver: ContentResolver, size: Long, ctx: Context): Pair<String, String> {
        contentResolver.openInputStream(source)?.use { input->
            if (size > CHUNK_SIZE) return chunksUpload(input, dest.substringAfterLast('/'), dest, mimeType, size, ctx)
            else httpClient.newCall(Request.Builder().url(dest).put(streamRequestBody(input, mimeType.toMediaTypeOrNull(), -1L)).build()).execute().use { response->
                if (response.isSuccessful) return Pair(response.header("oc-fileid", "") ?: "", response.header("oc-etag", "") ?: "")
                else throw OkHttpWebDavException(response)
            }
        } ?: throw IllegalStateException("InputStream provider crashed")
    }

    private fun chunksUpload(inputStream: InputStream, source: String, dest: String, mimeType: String, size: Long, ctx: Context): Pair<String, String> {
        val chunkFolder = "${chunkUploadBase}/${Uri.encode(source)}"
        var result = Pair("", "")
        val sp = PreferenceManager.getDefaultSharedPreferences(ctx)
        val wifionlyKey = ctx.getString(R.string.wifionly_pref_key)

        try {
            var chunkName: String
            var index = 0L
            var chunkSize = CHUNK_SIZE

            // Create upload folder on server
            httpClient.newCall(Request.Builder().url(chunkFolder).method("MKCOL", null).build()).execute().use { response->
                when {
                    response.isSuccessful-> {}
                    response.code == 405-> {
                        // Try to resume from the last position, assume that all uploaded chunks except the last 1 are intact
                        list(chunkFolder, FOLDER_CONTENT_DEPTH).drop(1).maxByOrNull { it.name }?.run {
                            try { (this.name.substringBefore('.')).toLong() } catch (e: NumberFormatException) { null }?.let {
                                // If last chunk uploaded is intact, start from the next chunk
                                index = it + if (this.size == CHUNK_SIZE) CHUNK_SIZE else 0

                                // Skip to resume position, if skip failed, start from the very beginning
                                inputStream.skip(index).let { skipped->
                                    //Log.e(">>>>>", "should skip $index, actually skip $skipped")
                                    if (skipped != index) index = 0
                                }
                            }
                        }
                    }
                    else-> throw OkHttpWebDavException(response)
                }
            }

            // Upload chunks
            // Longer timeout adapting to slow connection
            val uploadHttpClient = httpClient.newBuilder().readTimeout(2, TimeUnit.MINUTES).writeTimeout(2, TimeUnit.MINUTES).build()
            while(index < size) {
                if (sp.getBoolean(wifionlyKey, true)) {
                    if ((ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered) throw NetworkErrorException()
                }

                // Chunk file name is chunk's start position within inputstream
                chunkName = "${chunkFolder}/${String.format("%015d", index)}"
                with(size - index) { if (this < CHUNK_SIZE) chunkSize = this }
                //Log.e(">>>>>>", chunkName)

                uploadHttpClient.newCall(Request.Builder().url(chunkName).put(streamRequestBody(inputStream, mimeType.toMediaTypeOrNull(), chunkSize)).build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        // Upload interrupted, delete uploaded chunks
                        //try { httpClient.newCall(Request.Builder().url(chunkFolder).delete().build()).execute().use {} } catch (e: Exception) { e.printStackTrace() }
                        throw OkHttpWebDavException(response)
                    }
                }
                index += chunkSize
            }

            //Log.e(">>>>>>>", "start assemblying")
            try {
                // Tell server to assembly chunks, server might take sometime to finish stitching, so longer than usual timeout is needed
                httpClient.newBuilder().readTimeout(5, TimeUnit.MINUTES).writeTimeout(5, TimeUnit.MINUTES).callTimeout(7, TimeUnit.MINUTES).build()
                    .newCall(Request.Builder().url("${chunkFolder}/.file").method("MOVE", null).headers(Headers.Builder().add("DESTINATION", dest).add("OVERWRITE", "T").build()).build()).execute().use { response ->
                        if (response.isSuccessful) result = Pair(response.header("oc-fileid", "") ?: "", response.header("oc-etag", "") ?: "")
                        else {
                            // Upload interrupted, delete uploaded chunks
                            //try { httpClient.newCall(Request.Builder().url(chunkFolder).delete().build()).execute().use {} } catch (e: Exception) { e.printStackTrace() }
                            throw OkHttpWebDavException(response)
                        }
                    }
            }
            catch (e: InterruptedIOException) { e.printStackTrace() }
            catch (e: SocketTimeoutException) { e.printStackTrace() }
        } finally {
            try { inputStream.close() } catch (e: Exception) { e.printStackTrace() }
        }

        return result
    }

    private fun copyOrMove(copy: Boolean, source: String, dest: String) {
        val hb = Headers.Builder().add("DESTINATION", dest).add("OVERWRITE", "T")
        httpClient.newCall(Request.Builder().url(source).method(if (copy) "COPY" else "MOVE", null).headers(hb.build()).build()).execute().use { response->
            if (!response.isSuccessful) throw OkHttpWebDavException(response)
        }
    }

    private fun streamRequestBody(input: InputStream, mediaType: MediaType?, size: Long): RequestBody {
        return object : RequestBody() {
            override fun contentType(): MediaType? = mediaType
            override fun contentLength(): Long = if (size > 0) size else try { input.available().toLong() } catch (e: IOException) { -1 }
            override fun writeTo(sink: BufferedSink) { sink.write(input.source(), contentLength()) }
        }
    }

    @Parcelize
    data class DAVResource(
        var name: String = "",
        var fileId: String = "",
        var eTag: String = "",
        var modified: LocalDateTime = LocalDateTime.MIN,
        var contentType: String = "",
        var isFolder: Boolean = false,
        var isShared: Boolean = false,
        var size: Long = 0L,
    ): Parcelable

    companion object {
        private const val DISK_CACHE_SIZE = 300L * 1024L * 1024L    // 300MB
        private const val MAX_AGE = "864000"                        // 10 days
        //const val VIDEO_CACHE_FOLDER = "videos"

        private const val CHUNK_SIZE = 50L * 1024L * 1024L          // Default chunk size is 50MB

        // PROPFIND depth
        const val JUST_FOLDER_DEPTH = "0"
        const val FOLDER_CONTENT_DEPTH = "1"

        // PROPFIND properties namespace
        private const val DAV_NS = "DAV:"
        private const val OC_NS = "http://owncloud.org/ns"
        private const val NC_NS = "http://nextcloud.org/ns"

        // Standard properties
        private const val DAV_GETETAG = "getetag"
        private const val DAV_GETLASTMODIFIED = "getlastmodified"
        private const val DAV_GETCONTENTTYPE = "getcontenttype"
        private const val DAV_RESOURCETYPE = "resourcetype"
        private const val DAV_GETCONTENTLENGTH = "getcontentlength"
        private const val DAV_SHARE_TYPE = "share-type"

        // Nextcloud properties
        private const val OC_UNIQUE_ID = "fileid"
        private const val OC_SHARETYPE = "share-types"
        private const val OC_CHECKSUMS = "checksums"
        private const val NC_HASPREVIEW = "has-preview"
        private const val OC_SIZE = "size"
        private const val OC_DATA_FINGERPRINT = "data-fingerprint"

        private const val PROPFIND_BODY = "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"$DAV_NS\" xmlns:oc=\"$OC_NS\"><d:prop><oc:$OC_UNIQUE_ID/><d:$DAV_GETCONTENTTYPE/><d:$DAV_GETLASTMODIFIED/><d:$DAV_GETETAG/><oc:$OC_SHARETYPE/><d:$DAV_GETCONTENTLENGTH/></d:prop></d:propfind>"

        private const val RESPONSE_TAG = "response"
        private const val HREF_TAG = "href"

        const val NEXTCLOUD_OCSAPI_HEADER = "OCS-APIRequest"
    }
}