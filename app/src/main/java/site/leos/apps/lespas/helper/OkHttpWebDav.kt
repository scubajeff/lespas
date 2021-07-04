package site.leos.apps.lespas.helper

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Parcelable
import android.util.Xml
import kotlinx.parcelize.Parcelize
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.*
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class OkHttpWebDav(context: Context, private val userId: String, password: String, serverAddress: String, selfSigned: Boolean, appBase: String?, userAgent: String?) {
    private val serverBase = "${serverAddress}/remote.php/dav/files/${userId}${appBase.orEmpty()}"
    private val chunkUploadBase = "${serverAddress}/remote.php/dav/uploads/${userId}"
    private val httpClient: OkHttpClient = OkHttpClient.Builder().apply {
        if (selfSigned) hostnameVerifier { _, _ -> true }
        addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("Authorization", Credentials.basic(userId, password, StandardCharsets.UTF_8)).build()) }
        addNetworkInterceptor { chain -> chain.proceed((chain.request().newBuilder().removeHeader("User-Agent").addHeader("User-Agent", userAgent ?: "OkHttpWebDav").build())) }
        cache(Cache(File("${context.cacheDir}${appBase.orEmpty()}"), DISK_CACHE_SIZE))
        addNetworkInterceptor { chain -> chain.proceed(chain.request()).newBuilder().removeHeader("Pragma").header("Cache-Control", "public, max-age=${MAX_AGE}").build() }
        retryOnConnectionFailure(true)
    }.build()

    fun copy(source: String, dest: String): Boolean = copyOrMove(true, source, dest)

    fun createFolder(folderName: String): Pair<Boolean, String> {
        var isSuccessful = false
        var fileId = ""

        try {
            httpClient.newCall(Request.Builder().url(getResourceUrl(folderName)).method("MKCOL", null).build()).execute().use { response ->
                when {
                    response.isSuccessful -> fileId = response.headers["oc-fileid"]?.also { isSuccessful = true } ?: ""
                    response.code == 405 -> { isSuccessful = true }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }

        return Pair(isSuccessful, fileId)
    }

    fun delete(targetName: String): Boolean {
        return try {
            httpClient.newCall(Request.Builder().url(getResourceUrl(targetName)).delete().build()).execute().use { response-> response.isSuccessful }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun download(source: String, dest: String, cacheControl: CacheControl?): Boolean {
        var isSuccessful = false

        try {
            val reqBuilder = Request.Builder().url(getResourceUrl(source))
            cacheControl?.let { reqBuilder.cacheControl(cacheControl) }
            httpClient.newCall(reqBuilder.get().build()).execute().body?.source()?.use { input-> File(dest).sink(false).buffer().writeAll(input.buffer) }
/*
            httpClient.newCall(reqBuilder.get().build()).execute().body?.byteStream()?.use { input->
                File(dest).outputStream().use { output->
                    input.copyTo(output, 8192)
                }
            }
*/
            isSuccessful = true
        } catch (e: Exception) { e.printStackTrace() }

        return isSuccessful
    }

    fun getStream(source: String, cacheControl: CacheControl?): InputStream? =
        try {
            val reqBuilder = Request.Builder().url(getResourceUrl(source))
            cacheControl?.let { reqBuilder.cacheControl(cacheControl) }
            httpClient.newCall(reqBuilder.get().build()).execute().body?.byteStream()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }

    fun isExisted(targetName: String): Boolean {
        return try {
            httpClient.newCall(Request.Builder().url(getResourceUrl(targetName)).cacheControl(CacheControl.FORCE_NETWORK).method("PROPFIND", null).header("Depth", JUST_FOLDER_DEPTH).build()).execute().use { response-> response.isSuccessful }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun list(targetName: String, depth: String): List<DAVResource> {
        val result = mutableListOf<DAVResource>()

        try {
            httpClient.newCall(Request.Builder().url(getResourceUrl(targetName)).cacheControl(CacheControl.FORCE_NETWORK).method("PROPFIND", PROPFIND_BODY.toRequestBody("text/xml".toMediaType())).header("Depth", depth).build()).execute().use { response->
                if (response.isSuccessful) {
                    val parser = Xml.newPullParser()
                    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                    //parser.setInput(response.byteStream(), null)
                    parser.setInput(response.body!!.byteStream().bufferedReader())

                    var res = DAVResource()
                    var text = ""
                    var tag = ""
                    var event = parser.eventType
                    while (event != XmlPullParser.END_DOCUMENT) {
                        tag = parser.name
                        when (event) {
                            XmlPullParser.START_TAG -> {
                                when (tag) {
                                    RESPONSE_TAG -> res = DAVResource()
                                }
                            }
                            XmlPullParser.TEXT -> text = parser.text
                            XmlPullParser.END_TAG -> {
                                when (tag) {
                                    HREF_TAG -> res.name =
                                        if (text.endsWith('/')) {
                                            res.isFolder = true
                                            text.dropLast(1).substringAfterLast('/')
                                        } else {
                                            res.isFolder = false
                                            text.substringAfterLast('/')
                                        }
                                    OC_UNIQUE_ID -> res.fileId = text
                                    DAV_GETETAG -> res.eTag = text
                                    DAV_GETCONTENTTYPE -> res.contentType = text
                                    DAV_GETLASTMODIFIED -> res.modified = LocalDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME)
                                    RESPONSE_TAG -> result.add(res)
                                }
                            }
                        }
                        event = parser.next()
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }

        return result
    }

    fun move(source: String, dest: String): Boolean = copyOrMove(false, source, dest)

    fun upload(source: String, dest: String, mimeType: String): Boolean {
        return try {
            httpClient.newCall(Request.Builder().url(getResourceUrl(dest)).put(source.toRequestBody(mimeType.toMediaTypeOrNull())).build()).execute().use { response-> response.isSuccessful }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun upload(source: File, dest: String, mimeType: String): Boolean {
        return try {
            httpClient.newCall(Request.Builder().url(getResourceUrl(dest)).put(source.asRequestBody(mimeType.toMediaTypeOrNull())).build()).execute().use { response-> response.isSuccessful }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun upload(source: Uri, dest: String, mimeType: String, contentResolver: ContentResolver): Boolean {
        return try {
            contentResolver.openInputStream(source)?.use { input->
                httpClient.newCall(Request.Builder().url(getResourceUrl(dest)).put(streamRequestBody(input, mimeType.toMediaTypeOrNull(), -1L)).build()).execute().use { response-> response.isSuccessful }
            } ?: false
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun chunksUpload(source: String, dest: String, mimeType: String): Boolean {
        val file = File(source)
        return chunksUpload(file.inputStream(), source, dest, mimeType, file.length())
    }

    fun chunksUpload(inputStream: InputStream, source: String, dest: String, mimeType: String, size: Long): Boolean {
        var isSuccessful = false
        val uploadFolder = "${chunkUploadBase}/${Uri.encode(source.substringAfterLast('/'))}"

        try {
            // Create upload folder on server
            httpClient.newCall(Request.Builder().url(uploadFolder).method("MKCOL", null).build()).execute().use { response-> if (!response.isSuccessful) throw IOException(UPLOAD_INTERRUPTED) }

            // Upload chunks
            var chunkName: String
            val octetMimeType = "application/octet-stream".toMediaTypeOrNull()
            var index = 0L
            var chunkSize = CHUNK_SIZE
            while(index < size) {
                chunkName = "${uploadFolder}/${String.format("%015d", index)}"
                with(size - index) { if (this < CHUNK_SIZE) chunkSize = this}
                httpClient.newCall(Request.Builder().url(chunkName).put(streamRequestBody(inputStream, octetMimeType, chunkSize)).build()).execute().use { response-> if (!response.isSuccessful) throw IOException(UPLOAD_INTERRUPTED) }
                index += chunkSize
            }

            // Tell server to assembly chunks
            httpClient.newCall(Request.Builder().url("${uploadFolder}/.file").method("MOVE", null).headers(Headers.Builder().add("DESTINATION", getResourceUrl(dest)).add("OVERWRITE", "T").build()).build()).execute().use { response-> if (!response.isSuccessful) throw IOException(UPLOAD_INTERRUPTED) }

            isSuccessful = true
        } catch (e: Exception) { e.printStackTrace() }

        // Upload interrupted, delete uploaded chunks
        if (!isSuccessful) try { httpClient.newCall(Request.Builder().url(uploadFolder).delete().build()).execute().use {} } catch (e: Exception) { e.printStackTrace() }

        return isSuccessful
    }

    private fun getResourceUrl(targetName: String): String = "${serverBase}/${Uri.encode(targetName)}"

    private fun copyOrMove(copy: Boolean, source: String, dest: String): Boolean {
        var isSuccessful = false

        try {
            val hb = Headers.Builder().add("DESTINATION", getResourceUrl(dest)).add("OVERWRITE", "T")
            httpClient.newCall(Request.Builder().url(getResourceUrl(source)).method(if (copy) "COPY" else "MOVE", null).headers(hb.build()).build()).execute().use { response->
                isSuccessful = response.isSuccessful
            }
        } catch (e:Exception) { e.printStackTrace() }

        return isSuccessful
    }

    private fun streamRequestBody(input: InputStream, mediaType: MediaType?, size: Long): RequestBody {
        return object : RequestBody() {
            override fun contentType(): MediaType? = mediaType
            override fun contentLength(): Long = if (size > 0) size else try { input.available().toLong() } catch (e: IOException) { -1 }
            override fun writeTo(sink: BufferedSink) { sink.write(input.source(), size) }
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
    ): Parcelable

    companion object {
        private const val DISK_CACHE_SIZE = 300L * 1024L * 1024L    // 300MB
        private const val MAX_AGE = "864000"        // 10 days

        private const val UPLOAD_INTERRUPTED = "Trunk upload interrupted."
        const val CHUNK_SIZE = 10L * 1024L * 1024L  // Default chunk upload size is 10MB

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

        // Nextcloud properties
        private const val OC_UNIQUE_ID = "fileid"
        private const val OC_SHARETYPE = "share-types"
        private const val OC_CHECKSUMS = "checksums"
        private const val NC_HASPREVIEW = "has-preview"
        private const val OC_SIZE = "size"
        private const val OC_DATA_FINGERPRINT = "data-fingerprint"

        private const val PROPFIND_BODY = "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"$DAV_NS\" xmlns:oc=\"$OC_NS\"><d:prop><oc:$OC_UNIQUE_ID/><d:$DAV_GETCONTENTTYPE/><d:$DAV_GETLASTMODIFIED/><d:$DAV_GETETAG/></d:prop></d:propfind>"

        private const val RESPONSE_TAG = "response"
        private const val HREF_TAG = "href"
    }
}