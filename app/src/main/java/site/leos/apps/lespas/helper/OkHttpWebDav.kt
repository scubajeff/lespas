package site.leos.apps.lespas.helper

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.*
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets

class OkHttpWebDav(application: Application, private val userId: String, password: String, serverAddress: String, selfSigned: Boolean, appBase: String?, userAgent: String?) {
    private val serverBase = "${serverAddress}/remote.php/dav/files/${userId}${appBase.orEmpty()}"
    private val chunkUploadBase = "${serverAddress}/remote.php/dav/uploads/${userId}"
    private val httpClient: OkHttpClient = OkHttpClient.Builder().apply {
            if (selfSigned) hostnameVerifier { _, _ -> true }
            addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("Authorization", Credentials.basic(userId, password, StandardCharsets.UTF_8)).build()) }
            addNetworkInterceptor { chain -> chain.proceed((chain.request().newBuilder().removeHeader("User-Agent").addHeader("User-Agent", userAgent ?: "OkHttpWebDav").build())) }
            cache(Cache(File("${application.cacheDir}${appBase.orEmpty()}"), DISK_CACHE_SIZE))
            addNetworkInterceptor { chain -> chain.proceed(chain.request()).newBuilder().removeHeader("Pragma").header("Cache-Control", "public, max-age=${MAX_AGE}").build() }
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
            httpClient.newCall(Request.Builder().url(getResourceUrl(targetName)).method("PROPFIND", null).header("Depth", "0").build()).execute().use { response-> response.isSuccessful }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
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

    companion object {
        private const val UPLOAD_INTERRUPTED = "Trunk upload interrupted."
        private const val DISK_CACHE_SIZE = 300L * 1024L * 1024L    // 300MB
        private const val MAX_AGE = "864000"        // 10 days
        const val CHUNK_SIZE = 10L * 1024L * 1024L  // Default chunk upload size is 10MB
    }
}