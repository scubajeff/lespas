package site.leos.apps.lespas.sync

import android.accounts.Account
import android.accounts.AccountManager
import android.accounts.AuthenticatorException
import android.app.Application
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.Context
import android.content.SyncResult
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.preference.PreferenceManager
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import com.thegrizzlylabs.sardineandroid.impl.SardineException
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.Album
import site.leos.apps.lespas.album.AlbumRepository
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.photo.PhotoRepository
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.time.LocalDateTime
import java.time.ZoneId
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
                //Log.e("**********", "sync local changes")
                actionRepository.getAllPendingActions().forEach { action ->
                    // Check network type on every loop, so that user is able to stop sync right in the middle
                    if (PreferenceManager.getDefaultSharedPreferences(application).getBoolean(application.getString(R.string.wifionly_pref_key), true)) {
                        if ((application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered) {
                            syncResult.hasSoftError()
                            return
                        }
                    }

                    // Do not do too many works here, as the local sync should be as simple as making several webdav calls, so that if any thing bad happen, we will be catched by
                    // exceptions handling down below, and start again right here in later sync, e.g. atomic
                    try {
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
                                // MIME type is passed in fileId properties
                                sardine.put("$resourceRoot/${Uri.encode(action.folderName)}/${Uri.encode(action.fileName)}", File(localRootFolder, action.fileName), action.fileId)
                                //Log.e("****", "Uploaded ${action.fileName}")
                                // TODO shall we update local database here or leave it to next SYNC_REMOTE_CHANGES round?
                            }
                            Action.ACTION_ADD_DIRECTORY_ON_SERVER -> {
                                with("$resourceRoot/${Uri.encode(action.folderName)}") {
                                    try {
                                        sardine.createDirectory(this)
                                    } catch(e: SardineException) {
                                        when(e.statusCode) {
                                            // Should catch status code 405 here to ignore folder already exists on server
                                            405-> {}
                                            else-> {
                                                Log.e("****SardineException: ", e.stackTraceToString())
                                                syncResult.stats.numIoExceptions++
                                                return
                                            }
                                        }
                                    }
                                    // TODO if the following fail then the local newly added folder will still carry the fake id and will be removed at the next server sync, since it's id
                                    // does NOT exist on the server
                                    sardine.list(this, JUST_FOLDER_DEPTH, NC_PROPFIND_PROP)[0].customProps[OC_UNIQUE_ID]?.let {
                                        // fix album id for new album and photos create on local, put back the cover id in album row so that it will show up in album list
                                        // mind that we purposely leave the eTag column empty
                                        photoRepository.fixNewPhotosAlbumId(action.folderId, it)
                                        albumRepository.fixNewLocalAlbumId(action.folderId, it, action.fileName)
                                    }
                                }
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
                    } catch (e: SardineException) {
                        Log.e("****SardineException: ", e.stackTraceToString())
                        when(e.statusCode) {
                            400, 404, 405, 406, 410-> {
                                // create file in non-existed folder, target not found, target readonly, target already existed, etc. should be skipped and move onto next action
                            }
                            401, 403, 407-> {
                                syncResult.stats.numAuthExceptions++
                                return
                            }
                            409-> {
                                syncResult.stats.numConflictDetectedExceptions++
                                return
                            }
                            423-> {
                                // Interrupted upload will locked file on server, backoff 90 seconds so that lock gets cleared on server
                                syncResult.delayUntil = (System.currentTimeMillis() / 1000) + 90
                                return
                            }
                            else-> {
                                // Other unhandled error should be retried
                                syncResult.stats.numIoExceptions++
                                return
                            }
                        }
                    }

                    // TODO: Error retry strategy, directory etag update, etc.
                    actionRepository.deleteSync(action)
                }

            //} else {
                //Log.e("**********", "sync remote changes")
                val changedAlbums: MutableList<Album> = mutableListOf()
                val remoteAlbumIds = arrayListOf<String>()
                var remoteAlbumId: String
                // Merge changed and/or new album from server
                var localAlbum: List<Album>
                sardine.list(resourceRoot, FOLDER_CONTENT_DEPTH, NC_PROPFIND_PROP).drop(1).forEach { remoteAlbum ->     // Drop the first one in the list, which is the parent folder itself
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
                                        localAlbum[0].coverWidth,
                                        localAlbum[0].coverHeight,
                                        remoteAlbum.modified.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime(),  // Use remote version
                                        localAlbum[0].sortOrder,    // Preserve local data
                                        remoteAlbum.etag,   // Use remote version
                                        0,       // TODO share
                                        1f
                                    )
                                )
                            } else {
                                // Rename operation on server would not change item's own eTag, have to sync name changes here
                                if (localAlbum[0].name != remoteAlbum.name) albumRepository.changeName(remoteAlbumId, remoteAlbum.name)
                            }
                        } else {
                            // No hit at local, a new album created on server
                            changedAlbums.add(Album(remoteAlbumId, remoteAlbum.name, LocalDateTime.MAX, LocalDateTime.MIN, "", 0, 0, 0,
                                remoteAlbum.modified.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime(),
                                Album.BY_DATE_TAKEN_ASC, remoteAlbum.etag, 0, 1f)
                            )
                        }
                    }
                }

                // Delete those albums not exist on server, happens when user delete album on the server. Should skip local added new albums, e.g. those with cover column empty
                val localAlbumIdAndCover = albumRepository.getAllAlbumIds()
                for (local in localAlbumIdAndCover) {
                    if (!remoteAlbumIds.contains(local.id) && local.cover.isNotEmpty()) {
                        albumRepository.deleteByIdSync(local.id)
                        val allPhotoIds = photoRepository.getAllPhotoIdsByAlbum(local.id)
                        photoRepository.deletePhotosByAlbum(local.id)
                        allPhotoIds.forEach {
                            try {
                                File(localRootFolder, it.id).delete()
                            } catch (e: Exception) { e.printStackTrace() }
                            try {
                                File(localRootFolder, it.name).delete()
                            } catch(e: Exception) { e.printStackTrace() }
                        }
                        //Log.e("****", "Deleted album: ${local.id}")
                    }
                }

                if (changedAlbums.isNotEmpty()) {
                    // Sync each changed album
                    val changedPhotos = mutableListOf<Photo>()
                    val remotePhotoIds = mutableListOf<String>()
                    var tempAlbum: Album

                    for (changedAlbum in changedAlbums) {
                        tempAlbum = changedAlbum.copy(eTag = "")

                        var localPhotoETags = photoRepository.getETagsMap(changedAlbum.id)
                        val localPhotoNames = photoRepository.getNamesMap(changedAlbum.id)
                        var remotePhotoId: String
                        sardine.list("$resourceRoot/${Uri.encode(changedAlbum.name)}", FOLDER_CONTENT_DEPTH, NC_PROPFIND_PROP).drop(1).forEach { remotePhoto ->
                            if (remotePhoto.contentType.startsWith("image", true)) {
                                // Accumulate remote photos list
                                remotePhotoId = remotePhoto.customProps[OC_UNIQUE_ID]!!
                                remotePhotoIds.add(remotePhotoId)

                                if (localPhotoETags[remotePhotoId] != remotePhoto.etag) { // Also matches newly created photo id from server, e.g. no such remotePhotoId in local table
                                    //Log.e("=======", "updating photo: ${remotePhoto.name} r_etag:${remotePhoto.etag} l_etag:${localPhotoETags[remotePhotoId]}")

                                    if (localPhotoETags.containsKey(remotePhoto.name)) {
                                        // If there is a row in local Photo table with remote photo's name as it's id, that means it's a local added photo which is now coming back
                                        // from server. Update it's id to the real fileid and also etag now, rename image file name to fileid too.
                                        //Log.e("<><><>", "coming back now ${remotePhoto.name}")
                                        if (File(localRootFolder, remotePhoto.name).exists()) {
                                            try {
                                                File(localRootFolder, remotePhoto.name).renameTo(File(localRootFolder, remotePhotoId))
                                                //Log.e("****", "${remotePhoto.name} coming back as $remotePhotoId")
                                            } catch (e: Exception) { Log.e("****Exception: ", e.stackTraceToString()) }
                                        }
                                        photoRepository.fixPhotoId(remotePhoto.name, remotePhotoId, remotePhoto.etag,
                                            remotePhoto.modified.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime())
                                        // Taking care the cover
                                        // TODO: Condition race here, e.g. user changes this album's cover right at this very moment
                                        if (changedAlbum.cover == remotePhoto.name) {
                                            albumRepository.fixCoverId(changedAlbum.id, remotePhotoId)
                                            changedAlbum.cover = remotePhotoId
                                        }
                                    } else changedPhotos.add(Photo(remotePhotoId, changedAlbum.id, remotePhoto.name, remotePhoto.etag, LocalDateTime.now(),
                                            remotePhoto.modified.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime(), 0, 0, remotePhoto.contentType, 0
                                        )
                                    )  // TODO will share status change create new eTag?
                                } else if (localPhotoNames[remotePhotoId] != remotePhoto.name) {
                                    // Rename operation on server would not change item's own eTag, have to sync name changes here. The positive side is avoiding fetching the actual
                                    // file again from server
                                    photoRepository.changeName(remotePhotoId, remotePhoto.name)
                                }
                            }
                        }

                        // Fetch changed photo files, extract EXIF info, update Photo table
                        changedPhotos.forEachIndexed {i, changedPhoto->
                            // Prepare the image file
                            if (File(localRootFolder, changedPhoto.name).exists()) {
                                // If image file with 'name' exists, replace the old file with this
                                try {
                                    File(localRootFolder, changedPhoto.id).delete()
                                    File(localRootFolder, changedPhoto.name).renameTo(File(localRootFolder, changedPhoto.id))
                                    //Log.e("****", "rename file ${changedPhoto.name} to ${changedPhoto.id}")
                                } catch(e: Exception) { Log.e("****Exception: ", e.stackTraceToString())}
                            }
                            else {
                                // Download image file from server
                                sardine.get("$resourceRoot/${Uri.encode(changedAlbum.name)}/${Uri.encode(changedPhoto.name)}").use { input ->
                                    File("$localRootFolder/${changedPhoto.id}").outputStream().use { output ->
                                        input.copyTo(output, 8192)
                                        //Log.e("****", "Downloaded ${changedPhoto.name}")
                                    }
                                }
                            }

                            with(Tools.getPhotoParams("$localRootFolder/${changedPhoto.id}")) {
                                changedPhoto.dateTaken = dateTaken
                                changedPhoto.width = width
                                changedPhoto.height = height
                            }

                            // Update album's startDate, endDate fields
                            if (changedPhoto.dateTaken > changedAlbum.endDate) changedAlbum.endDate = changedPhoto.dateTaken
                            if (changedPhoto.dateTaken < changedAlbum.startDate) changedAlbum.startDate = changedPhoto.dateTaken

                            // update row when everything's fine. any thing that broke before this point will be captured by exception handler and will be worked on again in next round of sync
                            photoRepository.upsertSync(changedPhoto)

                            // Need to update and show the new album from server in local album list asap, have to do this in the loop
                            if (i == 0) {
                                if (changedAlbum.cover.isEmpty()) {
                                    // If this is a new album from server, then set it's cover to the first photo in the return list, set cover baseline
                                    // default to show middle part of the photo
                                    changedAlbum.cover = changedPhotos[0].id
                                    changedAlbum.coverBaseline = (changedPhotos[0].height - (changedPhotos[0].width * 9 / 21)) / 2
                                    changedAlbum.coverWidth = changedPhotos[0].width
                                    changedAlbum.coverHeight = changedPhotos[0].height

                                    // Get cover updated
                                    tempAlbum = changedAlbum.copy(eTag = "", syncProgress = 0f)
                                }
                                // Update UI only if more than one photo changed
                                if (changedPhotos.size > 1) albumRepository.upsertSync(tempAlbum)
                            } else {
                                // Even new album created on server is in local now, update album's sync progress only
                                albumRepository.updateAlbumSyncStatus(changedAlbum.id, (i + 1).toFloat() / changedPhotos.size, changedAlbum.startDate, changedAlbum.endDate)
                            }
                        }

                        // Every changed photos updated, we can commit changes to the Album table now. The most important column is "eTag", dictates the sync status
                        albumRepository.upsertSync(changedAlbum)

                        // Delete those photos not exist on server, happens when user delete photos on the server
                        var deletion = false
                        localPhotoETags = photoRepository.getETagsMap(changedAlbum.id)
                        for (localPhoto in localPhotoETags) {
                            if (!remotePhotoIds.contains(localPhoto.key)) {
                                deletion = true
                                photoRepository.deleteByIdSync(localPhoto.key)
                                try {
                                    File(localRootFolder, localPhoto.key).delete()
                                } catch (e: Exception) { e.printStackTrace() }
                                //Log.e("****", "Deleted photo: ${localPhoto.key}")
                            }
                        }

                        if (deletion) {
                            // Maintaining album cover and duration if deletion happened
                            val photosLeft = photoRepository.getAlbumPhotos(changedAlbum.id)
                            if (photosLeft.isNotEmpty()) {
                                val album = albumRepository.getThisAlbum(changedAlbum.id)
                                album[0].startDate = photosLeft[0].dateTaken
                                album[0].endDate = photosLeft.last().dateTaken
                                photosLeft.find { it.id == album[0].cover } ?: run {
                                    album[0].cover = photosLeft[0].id
                                    album[0].coverBaseline = (photosLeft[0].height - (photosLeft[0].width * 9 / 21)) / 2
                                    album[0].coverWidth = photosLeft[0].width
                                    album[0].coverHeight = photosLeft[0].height
                                }
                                albumRepository.updateSync(album[0])
                            } else {
                                // All photos under this album removed, delete album on both local and remote
                                albumRepository.deleteByIdSync(changedAlbum.id)
                                actionRepository.addAction(Action(null, Action.ACTION_DELETE_DIRECTORY_ON_SERVER, changedAlbum.id, changedAlbum.name, "", "", System.currentTimeMillis(), 1))
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
        } catch (e: SocketTimeoutException) {
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
        private const val DAV_NS = "DAV:"
        private const val OC_NS = "http://owncloud.org/ns"
        private const val NC_NS = "http://nextcloud.org/ns"

        // OC and NC defined localpart
        private const val OC_UNIQUE_ID = "fileid"
        private const val OC_SHARETYPE = "share-types"
        private const val OC_CHECKSUMS = "checksums"
        private const val NC_HASPREVIEW = "has-preview"
        private const val OC_SIZE = "size"
        private const val OC_DATA_FINGERPRINT = "data-fingerprint"

        // WebDAV defined localpart
        private const val DAV_GETETAG = "getetag"
        private const val DAV_GETLASTMODIFIED = "getlastmodified"
        private const val DAV_GETCONTENTTYPE = "getcontenttype"
        private const val DAV_RESOURCETYPE = "resourcetype"
        private const val DAV_GETCONTENTLENGTH = "getcontentlength"

        const val JUST_FOLDER_DEPTH = 0
        const val FOLDER_CONTENT_DEPTH = 1

        private val NC_PROPFIND_PROP = setOf(
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