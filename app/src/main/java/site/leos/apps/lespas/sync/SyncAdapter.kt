
package site.leos.apps.lespas.sync

import android.accounts.Account
import android.accounts.AccountManager
import android.accounts.AuthenticatorException
import android.app.Application
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.Context
import android.content.SyncResult
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import androidx.preference.PreferenceManager
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import com.thegrizzlylabs.sardineandroid.impl.SardineException
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.Album
import site.leos.apps.lespas.album.AlbumRepository
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.photo.PhotoRepository
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.net.ssl.SSLHandshakeException
import javax.xml.namespace.QName

class SyncAdapter @JvmOverloads constructor(private val application: Application, autoInitialize: Boolean, allowParallelSyncs: Boolean = false
) : AbstractThreadedSyncAdapter(application.baseContext, autoInitialize, allowParallelSyncs){

    override fun onPerformSync(account: Account, extras: Bundle, authority: String, provider: ContentProviderClient, syncResult: SyncResult) {
        try {
            val order = extras.getInt(ACTION)   // Return 0 when no mapping of ACTION found
            val resourceRoot: String
            val localRootFolder: String
            val sardine =  OkHttpSardine()

            // Initialize sardine library
            AccountManager.get(application).run {
                val userName = getUserData(account, context.getString(R.string.nc_userdata_username))
                val serverRoot = getUserData(account, context.getString(R.string.nc_userdata_server))
                sardine.setCredentials(userName, peekAuthToken(account, serverRoot), true)
                application.getString(R.string.lespas_base_folder_name).run {
                    resourceRoot = "$serverRoot${application.getString(R.string.dav_files_endpoint)}$userName$this"
                    localRootFolder = "${application.filesDir}$this"
                }
            }

            // Make sure lespas base directory is there, and it's really a nice moment to test server connectivity
            if (!sardine.exists(resourceRoot)) {
                sardine.createDirectory(resourceRoot)
                return
            }

            val albumRepository = AlbumRepository(application)
            val photoRepository = PhotoRepository(application)
            val actionRepository = ActionRepository(application)

            // Processing pending actions
            //if (order == SYNC_LOCAL_CHANGES) {
                Log.e("**********", "sync local changes")
                actionRepository.getAllPendingActions().forEach { action ->
                    // Check network type on every loop, so that user is able to stop sync right in the middle
                    if (PreferenceManager.getDefaultSharedPreferences(application).getBoolean(application.getString(R.string.wifionly_pref_key), true)) {
                        if ((application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered) {
                            syncResult.hasSoftError()
                            return
                        }
                    }

                    when (action.action) {
                        Action.ACTION_DELETE_FILES_ON_SERVER -> {
                            sardine.delete("$resourceRoot/${Uri.encode(action.folderName)}/${Uri.encode(action.fileName)}")
                            // TODO need to update album's etag to reduce network usage during next remote sync
                        }
                        Action.ACTION_DELETE_DIRECTORY_ON_SERVER -> {
                            sardine.delete("$resourceRoot/${Uri.encode(action.folderName)}")
                        }
                        Action.ACTION_ADD_FILES_ON_SERVER -> {
                            // Upload to server and verify
                            //Log.e("++++++++", "uploading $resourceRoot/${action.folderName}/${action.fileName}")
                            sardine.put("$resourceRoot/${Uri.encode(action.folderName)}/${Uri.encode(action.fileName)}", File(localRootFolder, action.fileName), "image/*")
                            // TODO shall we update local database here or leave it to next SYNC_REMOTE_CHANGES round?
                        }
                        Action.ACTION_ADD_DIRECTORY_ON_SERVER -> {
                            sardine.createDirectory("$resourceRoot/${Uri.encode(action.folderName)}")
                        }
                        Action.ACTION_MODIFY_ALBUM_ON_SERVER -> {
                            TODO()
                        }
                        Action.ACTION_RENAME_DIRECTORY -> {
                            // Action's filename field is the new directory name
                            sardine.move("$resourceRoot/${Uri.encode(action.folderName)}", "$resourceRoot/${Uri.encode(action.fileName)}")
                            //albumRepository.changeName(action.folderId, action.fileName)
                        }
                    }

                    // TODO: Error retry strategy, directory etag update, etc.
                    actionRepository.deleteSync(action)
                }

            //} else {
                Log.e("**********", "sync remote changes")
                val changedAlbums: MutableList<Album> = mutableListOf()
                val remoteAlbumIds = arrayListOf<String>()
                var remoteAlbumId: String
                // Merge changed and/or new album from server
                var localAlbum: List<Album>
                sardine.list(resourceRoot, FOLDER_CONTENT_DEPTH, NC_PROFIND_PROP).drop(1).forEach {remoteAlbum ->     // Drop the first one in the list, which is the parent folder itself
                    remoteAlbumId = remoteAlbum.customProps[OC_UNIQUE_ID]!!
                    if (remoteAlbum.isDirectory) {
                        remoteAlbumIds.add(remoteAlbumId)

                        localAlbum = albumRepository.getThisAlbum(remoteAlbumId)
                        if (localAlbum.isNotEmpty()) {
                            // We have hit in local table, which means it's a existing album
                            if (localAlbum[0].eTag != remoteAlbum.etag) {
                                // eTag mismatched, this album changed on server
                                changedAlbums.add(
                                    Album(
                                        remoteAlbumId,    // Either local or remote version is fine
                                        remoteAlbum.name,   // Use remote version, since it might be changed on server
                                        localAlbum[0].startDate,    // Preserve local data
                                        localAlbum[0].endDate,  // Preserve local data
                                        localAlbum[0].cover,    // Preserve local data
                                        localAlbum[0].coverBaseline,    // Preserve local data
                                        remoteAlbum.modified.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime(),  // Use remote version
                                        localAlbum[0].sortOrder,    // Preserve local data
                                        remoteAlbum.etag,   // Use remote version
                                        0       // TODO share
                                    )
                                )
                            } else {
                                // Rename operation on server would not change item's own eTag, have to sync name changes here
                                if (localAlbum[0].name != remoteAlbum.name) albumRepository.changeName(remoteAlbumId, remoteAlbum.name)
                            }
                        } else {
                            // No hit at local, a new album created on server
                            changedAlbums.add(Album(
                                remoteAlbumId, remoteAlbum.name, LocalDateTime.MAX, LocalDateTime.MIN, "", 0,
                                remoteAlbum.modified.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime(),
                                Album.BY_DATE_TAKEN_ASC, remoteAlbum.etag, 0
                            ))
                        }
                    }
                }

                // Delete those albums not exist on server, happens when user delete album on the server
                val localAlbumIds = albumRepository.getAllAlbumIds()
                for (localId in localAlbumIds) {
                    if (!remoteAlbumIds.contains(localId)) {
                        Log.e("=======", "deleting album: $localId")
                        albumRepository.deleteByIdSync(localId)
                        val allPhotoIds = photoRepository.getAllPhotoIdsByAlbum(localId)
                        photoRepository.deletePhotosByAlbum(localId)
                        allPhotoIds.forEach {
                            try {
                                File(localRootFolder, it.id).delete()
                            } catch (e: Exception) { e.printStackTrace() }
                            try {
                                File(localRootFolder, it.name).delete()
                            } catch(e: Exception) { e.printStackTrace() }
                        }
                    }
                }

                if (changedAlbums.isNotEmpty()) {
                    // Sync each changed album
                    var exif: ExifInterface
                    val changedPhotos = mutableListOf<Photo>()
                    val remotePhotoIds = mutableListOf<String>()
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    var timeString: String?

                    for (changedAlbum in changedAlbums) {
                        val localPhotoETags = photoRepository.getETagsMap(changedAlbum.id)
                        val localPhotoNames = photoRepository.getNamesMap(changedAlbum.id)
                        var remotePhotoId: String
                        sardine.list("$resourceRoot/${Uri.encode(changedAlbum.name)}", FOLDER_CONTENT_DEPTH, NC_PROFIND_PROP).drop(1).forEach { remotePhoto ->
                            if (remotePhoto.contentType.startsWith("image", true)) {
                                // Accumulate remote photos list
                                remotePhotoId = remotePhoto.customProps[OC_UNIQUE_ID]!!
                                remotePhotoIds.add(remotePhotoId)
                                if (localPhotoETags[remotePhotoId] != remotePhoto.etag) { // Also matches new photos
                                    Log.e("=======", "updating photo: ${remotePhoto.name} r_etag:${remotePhoto.etag} l_etag:${localPhotoETags[remotePhotoId]}")
                                    changedPhotos.add(
                                        Photo(
                                            remotePhotoId, changedAlbum.id, remotePhoto.name, remotePhoto.etag, LocalDateTime.now(),
                                            remotePhoto.modified.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime(), 0, 0, 0
                                        )
                                    )  // TODO will share status change create new eTag?
                                } else    // Rename operation on server would not change item's own eTag, have to sync name changes here. The positive thing is we don't have to fetch it from server
                                    if (localPhotoNames[remotePhotoId] != remotePhoto.name) {
                                        photoRepository.changeName(remotePhotoId, remotePhoto.name)
                                    }
                            }
                        }

                        // If this is a new album from server, then set it's cover to the first photo in the return list
                        if (changedAlbum.cover.isEmpty()) changedAlbum.cover = changedPhotos[0].id

                        // Fetch changed photo files, extract EXIF info, update Photo table
                        for (changedPhoto in changedPhotos) {
                            // Get the image file ready
                            if (File(localRootFolder, changedPhoto.name).exists()) {
                                // Newly added photo from local, so we have acquired the file and it still bears the original name
                                // Since we have the fileId now, rename it here
                                try {
                                    File(localRootFolder, changedPhoto.name).renameTo(File(localRootFolder, changedPhoto.id))
                                } catch (e: Exception) { e.printStackTrace() }
                            } else {
                                // No local copy, need to download from server
                                sardine.get("$resourceRoot/${Uri.encode(changedAlbum.name)}/${Uri.encode(changedPhoto.name)}").use { input ->
                                    File("$localRootFolder/${changedPhoto.id}").outputStream().use { output ->
                                        input.copyTo(output, 8192)
                                        Log.e("1111111", "finished downloading ${changedPhoto.name}")
                                    }
                                }
                            }

                            // Update dateTaken, width, height fields
                            exif = ExifInterface("$localRootFolder/${changedPhoto.id}")
                            timeString = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                            if (timeString == null) timeString = exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED)
                            if (timeString == null) timeString = exif.getAttribute(ExifInterface.TAG_DATETIME)
                            if (timeString == null) changedPhoto.dateTaken = changedPhoto.lastModified
                            else changedPhoto.dateTaken = LocalDateTime.parse(timeString, DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss"))
                            // Update album's startDate, endDate fields
                            if (changedPhoto.dateTaken > changedAlbum.endDate) changedAlbum.endDate = changedPhoto.dateTaken
                            if (changedPhoto.dateTaken < changedAlbum.startDate) changedAlbum.startDate = changedPhoto.dateTaken

                            BitmapFactory.decodeFile("$localRootFolder/${changedPhoto.id}", options)
                            changedPhoto.width = options.outWidth
                            changedPhoto.height = options.outHeight

                            // update row when everything's fine. any thing that broke before this point will be captured by exception handler and will be worked on again in next round of sync
                            photoRepository.upsertSync(changedPhoto)
                        }

                        // Every changed photos updated, we can commit changes to the Album table now. The most important column is "eTag", dictates the sync status
                        albumRepository.upsertSync(changedAlbum)

                        // Delete those photos not exist on server, happens when user delete photos on the server
                        for (localPhoto in localPhotoETags) {
                            if (!remotePhotoIds.contains(localPhoto.key)) {
                                Log.e("=======", "deleting photo: ${localPhoto.key}")
                                photoRepository.deleteByIdSync(localPhoto.key)
                                try {
                                    File(localRootFolder, localPhoto.key).delete()
                                } catch (e: Exception) { e.printStackTrace() }
                            }
                        }

                        // Recycle the list
                        remotePhotoIds.clear()
                        changedPhotos.clear()
                    }
                }
            //}

            // Clear status counters
            syncResult.stats.clear()
        } catch (e: IOException) {
            syncResult.stats.numIoExceptions++
            Log.e("****Exception: ", e.stackTraceToString())
        } catch (e: InterruptedIOException) {
            syncResult.stats.numIoExceptions++
            Log.e("****Exception: ", e.stackTraceToString())
        } catch (e: SSLHandshakeException) {
            syncResult.stats.numIoExceptions++
            Log.e("****Exception: ", e.stackTraceToString())
        } catch (e: AuthenticatorException) {
            syncResult.stats.numAuthExceptions++
            Log.e("****Exception: ", e.stackTraceToString())
        } catch (e: SardineException) {
            syncResult.stats.numIoExceptions++
            Log.e("****Exception: ", e.stackTraceToString())
        } catch (e:Exception) {
            Log.e("****Exception: ", e.stackTraceToString())
        }
    }

    companion object {
        const val ACTION = "ACTION"
        const val SYNC_LOCAL_CHANGES = 0
        const val SYNC_REMOTE_CHANGES = 1

        // PROPFIND properties namespace
        const val DAV_NS = "DAV:"
        const val OC_NS = "http://owncloud.org/ns"
        const val NC_NS = "http://nextcloud.org/ns"

        // OC and NC defined localpart
        const val OC_UNIQUE_ID = "fileid"
        const val OC_SHARETYPE = "share-types"
        const val OC_CHECKSUMS = "checksums"
        const val NC_HASPREVIEW = "has-preview"
        const val OC_SIZE = "size"
        const val OC_DATA_FINGERPRINT = "data-fingerprint"

        // WebDAV defined localpart
        const val DAV_GETETAG = "getetag"
        const val DAV_GETLASTMODIFIED = "getlastmodified"
        const val DAV_GETCONTENTTYPE = "getcontenttype"
        const val DAV_RESOURCETYPE = "resourcetype"
        const val DAV_GETCONTENTLENGTH = "getcontentlength"

        const val JUST_FOLDER_DEPTH = 0
        const val FOLDER_CONTENT_DEPTH = 1

        val NC_PROFIND_PROP = setOf(
            QName(DAV_NS, DAV_GETETAG, "D"),
            QName(DAV_NS, DAV_GETLASTMODIFIED, "D"),
            QName(DAV_NS, DAV_GETCONTENTTYPE, "D"),
            QName(DAV_NS, DAV_RESOURCETYPE, "D"),
            QName(OC_NS, OC_UNIQUE_ID, "oc"),
            QName(OC_NS, OC_SHARETYPE, "oc"),
            QName(NC_NS, NC_HASPREVIEW, "nc"),
            QName(OC_NS, OC_CHECKSUMS, "oc"),
            QName(OC_NS, OC_SIZE, "oc"),
            QName(OC_NS, OC_DATA_FINGERPRINT, "oc")
        )
    }
}