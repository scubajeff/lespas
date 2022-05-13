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
import org.xmlpull.v1.XmlPullParserException
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
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.*
import java.util.concurrent.TimeUnit

class OkHttpWebDav(private val userId: String, password: String, serverAddress: String, selfSigned: Boolean, cacheFolder: String?, userAgent: String?, cacheSize: Int) {
    private val chunkUploadBase = "${serverAddress}/remote.php/dav/uploads/${userId}"
    private val httpClient: OkHttpClient
    private var cachedHttpClient: OkHttpClient? = null

    init {
        val builder = OkHttpClient.Builder().apply {
            if (selfSigned) hostnameVerifier { _, _ -> true }
            addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("Authorization", Credentials.basic(userId, password, StandardCharsets.UTF_8)).build()) }
            addNetworkInterceptor { chain -> chain.proceed((chain.request().newBuilder().removeHeader("User-Agent").addHeader("User-Agent", userAgent ?: "OkHttpWebDav").build())) }
            readTimeout(20, TimeUnit.SECONDS)
            writeTimeout(20, TimeUnit.SECONDS)
        }
        httpClient = builder.build()
        cacheFolder?.let { cachedHttpClient = builder.cache(Cache(File(cacheFolder), cacheSize * 1024L * 1024L)).addNetworkInterceptor { chain -> chain.proceed(chain.request()).newBuilder().removeHeader("Pragma").header("Cache-Control", "public, max-age=${MAX_AGE}").build() }.build() }

        // Make cache folder for video download
        //File(cacheFolder, VIDEO_CACHE_FOLDER).mkdirs()
    }

    fun copy(source: String, dest: String): Pair<String, String> { return copyOrMove(true, source, dest) }

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
/*
    fun batchDelete(targets: List<String>, baseUrl: String, baseHREFFolder: String) {
        var elements = ""
        targets.forEach { elements += String.format(Locale.ROOT, BATCH_TARGET_ELEMENT, "${baseHREFFolder}${Uri.encode(it, "/")}") }
        httpClient.newCall(Request.Builder().url(baseUrl).method("BDELETE", (String.format(Locale.ROOT, BATCH_BODY, "delete", elements, "delete")).toRequestBody("text/xml".toMediaType())).build()).execute().use { response ->
            if (!response.isSuccessful) throw OkHttpWebDavException(response)
        }
    }
*/

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
        return (if (useCache) cachedHttpClient!!.newCall(reqBuilder.get().build()) else httpClient.newCall(reqBuilder.get().build())).execute()
    }

    fun getStream(source: String, useCache: Boolean, cacheControl: CacheControl?): InputStream = getStreamCall(source, useCache, cacheControl).first
    fun getStreamCall(source: String, useCache: Boolean, cacheControl: CacheControl?): Pair<InputStream, Call> {
        val reqBuilder = Request.Builder().url(source)
        (if (useCache) {
            cacheControl?.let { reqBuilder.cacheControl(cacheControl) }
            cachedHttpClient!!.newCall(reqBuilder.get().build())
        } else {
            httpClient.newCall(reqBuilder.get().build())
        }).run {
            execute().also { response ->
                if (response.isSuccessful) return Pair(response.body!!.byteStream(), this)
                else {
                    response.close()
                    throw OkHttpWebDavException(response)
                }
            }
        }
    }
    fun getStreamBool(source: String, useCache: Boolean, cacheControl: CacheControl?): Pair<InputStream, Boolean> {
        val reqBuilder = Request.Builder().url(source)
        (if (useCache) {
            cacheControl?.let { reqBuilder.cacheControl(cacheControl) }
            cachedHttpClient!!.newCall(reqBuilder.get().build())
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
                try {
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
                                    HREF_TAG -> res.name = URI(text).path.let { path ->
                                        if (path.endsWith('/')) {
                                            res.isFolder = true
                                            path.dropLast(1).substringAfterLast('/')
                                        } else {
                                            res.isFolder = false
                                            path.substringAfterLast('/')
                                        }
                                    }
                                    OC_UNIQUE_ID -> res.fileId = text
                                    DAV_GETETAG -> res.eTag = text
                                    DAV_GETCONTENTTYPE -> res.contentType = text
                                    DAV_GETLASTMODIFIED -> res.modified = try { Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(text)).atZone(ZoneId.systemDefault()).toLocalDateTime() } catch (e: Exception) { LocalDateTime.now() }
                                    DAV_SHARE_TYPE -> res.isShared = true
                                    DAV_GETCONTENTLENGTH -> res.size = try { text.toLong() } catch (e: NumberFormatException) { 0L }
                                    RESPONSE_TAG -> {
                                        text = ""
                                        result.add(res)
                                    }
                                }
                            }
                        }
                    }
                }
                // Catch all XML parsing exceptions here, note that returned result in these situations would be partial
                catch (e: XmlPullParserException) { e.printStackTrace() }
                catch (e: IllegalArgumentException) { e.printStackTrace() }
                catch (e: IOException) { e.printStackTrace() }
            } else { throw OkHttpWebDavException(response) }

            return result
        }
    }

    fun listWithExtraMeta(targetName: String, depth: String): List<DAVResource> {
        val result = mutableListOf<DAVResource>()

        httpClient.newCall(Request.Builder().url(targetName).cacheControl(CacheControl.FORCE_NETWORK).method("PROPFIND", PROPFIND_EXTRA_BODY.toRequestBody("text/xml".toMediaType())).header("Depth", depth).header("Brief", "t").build()).execute().use { response->
            var currentFolderId = ""
            val offset = OffsetDateTime.now().offset
            val prefix = targetName.substringAfter("//").substringAfter("/")

            if (response.isSuccessful) {
                try {
                    val parser = XmlPullParserFactory.newInstance().newPullParser()
                    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                    parser.setInput(response.body!!.byteStream().bufferedReader())

                    var res = DAVResource()
                    var text = ""
                    while (parser.next() != XmlPullParser.END_DOCUMENT) {
                        when (parser.eventType) {
                            XmlPullParser.START_TAG -> {
                                when (parser.name) {
                                    RESPONSE_TAG -> {
                                        res = DAVResource()
                                        res.dateTaken = LocalDateTime.MIN
                                    }
                                }
                            }
                            XmlPullParser.TEXT -> text = parser.text
                            XmlPullParser.END_TAG -> {
                                when (parser.name) {
                                    HREF_TAG -> {
                                        // Use URI.path to get escaped octets decoded
                                        res.name = URI(text).path.substringAfter(prefix).let { path ->
                                            if (path.endsWith('/')) {
                                                res.isFolder = true
                                                path.dropLast(1)
                                            } else {
                                                res.isFolder = false
                                                path
                                            }
                                        }
                                    }
                                    OC_UNIQUE_ID -> {
                                        res.fileId = text
                                        if (res.isFolder) currentFolderId = text
                                        else res.albumId = currentFolderId
                                    }
                                    DAV_GETETAG -> res.eTag = text
                                    DAV_GETCONTENTTYPE -> res.contentType = text
                                    DAV_GETLASTMODIFIED -> {
                                        res.modified = try { LocalDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME) } catch (e: DateTimeParseException) { LocalDateTime.now() }
                                    }
                                    DAV_SHARE_TYPE -> res.isShared = true
                                    DAV_GETCONTENTLENGTH -> res.size = try { text.toLong() } catch (e: NumberFormatException) { 0L }
                                    LESPAS_DATE_TAKEN -> res.dateTaken = try { LocalDateTime.ofEpochSecond(text.toLong() / 1000, 0, offset) } catch (e: Exception) { LocalDateTime.MIN }
                                    LESPAS_WIDTH -> res.width = try { text.toInt() } catch (e: NumberFormatException) { 0 }
                                    LESPAS_HEIGHT -> res.height = try { text.toInt() } catch (e: NumberFormatException) { 0 }
                                    LESPAS_ORIENTATION -> res.orientation = try { text.toInt() } catch (e: NumberFormatException) { 0 }
                                    RESPONSE_TAG -> {
                                        text = ""
                                        if (res.dateTaken == LocalDateTime.MIN) res.dateTaken = res.modified
                                        result.add(res)
                                    }
                                }
                            }
                        }
                    }
                }
                // Catch all XML parsing exceptions here, note that returned result in these situations would be partial
                catch (e: XmlPullParserException) { e.printStackTrace() }
                catch (e: IllegalArgumentException) { e.printStackTrace() }
                catch (e: IOException) { e.printStackTrace() }
            } else { throw OkHttpWebDavException(response) }

            return result
        }
    }

    fun move(source: String, dest: String): Pair<String, String> { return copyOrMove(false, source, dest) }

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

    fun patch(url: String, payload: String) {
        httpClient.newCall(Request.Builder().url(url).cacheControl(CacheControl.FORCE_NETWORK).method("PROPPATCH", String.format(Locale.ROOT, PROPPATCH_EXTRA_META_BODY, payload).toRequestBody("text/xml".toMediaType())).header("Brief", "t").build()).execute().use {}
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
            val uploadHttpClient = httpClient.newBuilder().readTimeout(4, TimeUnit.MINUTES).writeTimeout(4, TimeUnit.MINUTES).build()
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

    fun copyOrMove(copy: Boolean, source: String, dest: String): Pair<String, String> {
        val copyHttpClient = httpClient.newBuilder().readTimeout(4, TimeUnit.MINUTES).writeTimeout(4, TimeUnit.MINUTES).build()
        val hb = Headers.Builder().add("DESTINATION", dest).add("OVERWRITE", "T")
        copyHttpClient.newCall(Request.Builder().url(source).method(if (copy) "COPY" else "MOVE", null).headers(hb.build()).build()).execute().use { response->
            if (response.isSuccessful) return Pair(response.header("oc-fileid", "") ?: "", response.header("oc-etag", "") ?: "")
            else throw OkHttpWebDavException(response)
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
        var albumId: String = "",
        var dateTaken: LocalDateTime = LocalDateTime.now(),
        var width: Int = 0,
        var height: Int = 0,
        var orientation: Int = 0,
    ): Parcelable

    companion object {
        private const val MAX_AGE = "864000"                        // 10 days
        //const val VIDEO_CACHE_FOLDER = "videos"

        private const val CHUNK_SIZE = 50L * 1024L * 1024L          // Default chunk size is 50MB

        // PROPFIND depth
        const val JUST_FOLDER_DEPTH = "0"
        const val FOLDER_CONTENT_DEPTH = "1"
        const val RECURSIVE_DEPTH = "infinity"

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

        // LesPas properties
        const val LESPAS_DATE_TAKEN = "pictureDateTaken"
        const val LESPAS_WIDTH = "pictureWidth"
        const val LESPAS_HEIGHT = "pictureHeight"
        const val LESPAS_ORIENTATION = "pictureOrientation"

        private const val XML_HEADER = "<?xml version=\"1.0\"?>"
        private const val PROPFIND_BODY = "${XML_HEADER}<d:propfind xmlns:d=\"$DAV_NS\" xmlns:oc=\"$OC_NS\"><d:prop><oc:$OC_UNIQUE_ID/><d:$DAV_GETCONTENTTYPE/><d:$DAV_GETLASTMODIFIED/><d:$DAV_GETETAG/><oc:$OC_SHARETYPE/><d:$DAV_GETCONTENTLENGTH/></d:prop></d:propfind>"
        private const val PROPFIND_EXTRA_BODY = "${XML_HEADER}<d:propfind xmlns:d=\"$DAV_NS\" xmlns:oc=\"$OC_NS\"><d:prop><oc:$OC_UNIQUE_ID/><d:$DAV_GETCONTENTTYPE/><d:$DAV_GETLASTMODIFIED/><d:$DAV_GETETAG/><oc:$OC_SHARETYPE/><d:$DAV_GETCONTENTLENGTH/><oc:$LESPAS_DATE_TAKEN/><oc:$LESPAS_WIDTH/><oc:$LESPAS_HEIGHT/><oc:$LESPAS_ORIENTATION/></d:prop></d:propfind>"
        private const val PROPPATCH_EXTRA_META_BODY = "${XML_HEADER}<d:propertyupdate xmlns:d=\"$DAV_NS\" xmlns:oc=\"$OC_NS\"><d:set><d:prop>%s</d:prop></d:set></d:propertyupdate>"
/*
        private const val BATCH_BODY = "${XML_HEADER}<D:%s xmlns:D=\"$DAV_NS\"><D:target>%s</D:target></D:%s>"
        private const val BATCH_TARGET_ELEMENT = "<D:href>%s</D:href>"
*/

        private const val RESPONSE_TAG = "response"
        private const val HREF_TAG = "href"

        const val NEXTCLOUD_OCSAPI_HEADER = "OCS-APIRequest"
    }
}