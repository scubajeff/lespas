package site.leos.apps.lespas.sync

import android.accounts.Account
import android.accounts.AccountManager
import android.accounts.AuthenticatorException
import android.accounts.NetworkErrorException
import android.app.Application
import android.content.*
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.preference.PreferenceManager
import okio.IOException
import okio.buffer
import okio.sink
import okio.source
import org.json.JSONException
import org.json.JSONObject
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.Album
import site.leos.apps.lespas.album.AlbumRepository
import site.leos.apps.lespas.album.Cover
import site.leos.apps.lespas.helper.OkHttpWebDav
import site.leos.apps.lespas.helper.OkHttpWebDavException
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.photo.PhotoRepository
import site.leos.apps.lespas.publication.NCShareViewModel
import site.leos.apps.lespas.settings.SettingsFragment
import java.io.File
import java.io.FileNotFoundException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.stream.Collectors
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

class SyncAdapter @JvmOverloads constructor(private val application: Application, autoInitialize: Boolean, allowParallelSyncs: Boolean = false
) : AbstractThreadedSyncAdapter(application.baseContext, autoInitialize, allowParallelSyncs){
    private lateinit var webDav: OkHttpWebDav
    private lateinit var resourceRoot: String
    private lateinit var dcimRoot: String
    private lateinit var localRootFolder: String
    private val albumRepository = AlbumRepository(application)
    private val photoRepository = PhotoRepository(application)
    private val actionRepository = ActionRepository(application)
    private val sp = PreferenceManager.getDefaultSharedPreferences(application)
    private val wifionlyKey = application.getString(R.string.wifionly_pref_key)

    override fun onPerformSync(account: Account, extras: Bundle, authority: String, provider: ContentProviderClient, syncResult: SyncResult) {
        try {
            //val order = extras.getInt(ACTION)   // Return 0 when no mapping of ACTION found

            prepare(account)
            syncLocalChanges()
            syncRemoteChanges()
            backupCameraRoll()

            // Clear status counters
            syncResult.stats.clear()
        } catch (e: OkHttpWebDavException) {
            Log.e(">>>>OkHttpWebDavException: ", e.stackTraceString)
            when (e.statusCode) {
                400, 404, 405, 406, 410 -> {
                    // create file in non-existed folder, target not found, target readonly, target already existed, etc. should be skipped and move onto next action
                    // TODO remote the top action item
                }
                401, 403, 407 -> {
                    syncResult.stats.numAuthExceptions++
                }
                409 -> {
                    syncResult.stats.numConflictDetectedExceptions++
                }
                423 -> {
                    // Interrupted upload will locked file on server, backoff 90 seconds so that lock gets cleared on server
                    syncResult.stats.numIoExceptions++
                    syncResult.delayUntil = (System.currentTimeMillis() / 1000) + 90
                }
                in 500..600 -> {
                    // Server error, backoff 5 minutes
                    syncResult.stats.numIoExceptions++
                    syncResult.delayUntil = (System.currentTimeMillis() / 1000) + 300
                }
                else -> {
                    // Other unhandled error should be retried
                    syncResult.stats.numIoExceptions++
                }
            }
        } catch (e: IOException) {
            syncResult.stats.numIoExceptions++
            Log.e(">>>>IOException: ", e.stackTraceToString())
        } catch (e: SocketTimeoutException) {
            syncResult.stats.numIoExceptions++
            Log.e(">>>>SocketTimeoutException: ", e.stackTraceToString())
        } catch (e: InterruptedIOException) {
            syncResult.stats.numIoExceptions++
            Log.e(">>>>InterruptedIOException: ", e.stackTraceToString())
        } catch (e: SSLHandshakeException) {
            syncResult.stats.numIoExceptions++
            syncResult.delayUntil = (System.currentTimeMillis() / 1000) + 10 * 60       // retry 10 minutes later
            Log.e(">>>>SSLHandshakeException: ", e.stackTraceToString())
        } catch (e: SSLPeerUnverifiedException) {
            syncResult.stats.numIoExceptions++
            syncResult.delayUntil = (System.currentTimeMillis() / 1000) + 10 * 60       // retry 10 minutes later
            Log.e(">>>>SSLPeerUnverifiedException: ", e.stackTraceToString())
        } catch (e: AuthenticatorException) {
            syncResult.stats.numAuthExceptions++
            Log.e(">>>>AuthenticatorException: ", e.stackTraceToString())
        } catch (e: IllegalArgumentException) {
            syncResult.hasSoftError()
            Log.e(">>>>IllegalArgumentException: ", e.stackTraceToString())
        } catch (e: IllegalStateException) {
            syncResult.hasSoftError()
            Log.e(">>>>IllegalStateException: ", e.stackTraceToString())
        } catch (e: NetworkErrorException) {
            syncResult.hasSoftError()
            Log.e(">>>>NetworkErrorException: ", e.stackTraceToString())
        } catch (e:Exception) {
            Log.e(">>>>Exception: ", e.stackTraceToString())
        }
    }

    private fun prepare(account: Account) {
        // Check network type
        checkConnection()

        AccountManager.get(application).run {
            val userName = getUserData(account, context.getString(R.string.nc_userdata_username))
            val serverRoot = getUserData(account, context.getString(R.string.nc_userdata_server))

            resourceRoot = "$serverRoot${application.getString(R.string.dav_files_endpoint)}$userName${application.getString(R.string.lespas_base_folder_name)}"
            dcimRoot = "$serverRoot${application.getString(R.string.dav_files_endpoint)}${userName}/DCIM"
            localRootFolder = Tools.getLocalRoot(application)

            webDav = OkHttpWebDav(userName, peekAuthToken(account, serverRoot), serverRoot, getUserData(account, context.getString(R.string.nc_userdata_selfsigned)).toBoolean(), "${application.cacheDir}/${application.getString(R.string.lespas_base_folder_name)}", "LesPas_${application.getString(R.string.lespas_version)}")
        }

        // Make sure lespas base directory is there, and it's really a nice moment to test server connectivity
        if (!webDav.isExisted(resourceRoot)) {
            webDav.createFolder(resourceRoot)
            // TODO is return really necessary?
            return
        }
    }

    private fun syncLocalChanges() {
        // Sync local changes, e.g., processing pending actions
        actionRepository.getAllPendingActions().forEach { action ->
            // Check network type on every loop, so that user is able to stop sync right in the middle
            checkConnection()

            // Do not do too many works here, as the local sync should be as simple as making several webdav calls, so that if any thing bad happen, we will be catched by
            // exceptions handling down below, and start again right here in later sync, e.g. atomic
            when (action.action) {
                Action.ACTION_DELETE_FILES_ON_SERVER -> {
                    webDav.delete("$resourceRoot/${Uri.encode(action.folderName)}/${Uri.encode(action.fileName)}")
                    // TODO need to update album's etag to reduce network usage during next remote sync
                }
                Action.ACTION_DELETE_DIRECTORY_ON_SERVER -> {
                    webDav.delete("$resourceRoot/${Uri.encode(action.folderName)}")
                }
                Action.ACTION_ADD_FILES_ON_SERVER -> {
                    // MIME type passed in folderId property
                    val localFile = File(localRootFolder, action.fileName)
                    if (localFile.exists()) {
                        with (webDav.upload(localFile, "$resourceRoot/${Uri.encode(action.folderName)}/${Uri.encode(action.fileName)}", action.folderId, application)) {
                            // Nextcloud WebDAV PUT, MOVE, COPY return fileId and eTag
                            if (this.first.isNotEmpty() && this.second.isNotEmpty()) {
                                val newId = this.first.substring(0, 8).toInt().toString()
                                // Rename image file name to fileId
                                try { localFile.renameTo(File(localRootFolder, newId)) } catch (e: Exception) { e.printStackTrace() }

                                // Update photo's id to the real fileId and latest eTag now. When called from Snapseed Replace, newEtag is what needs to be updated
                                photoRepository.fixPhotoIdEtag(action.fileId, newId, this.second)

                                // Fix album cover id if this photo is the cover
                                albumRepository.getAlbumByName(action.folderName).also { album ->
                                    if (album.cover == action.fileId) {
                                        // Taking care the cover
                                        // TODO: Condition race here, e.g. user changes this album's cover right at this very moment
                                        albumRepository.fixCoverId(album.id, newId)

                                        // cover's fileId is ready, create and sync album meta file. When called from Snapseed Replace, new file name passed in action.fileName is what needs to be updated
                                        with(album) { updateAlbumMeta(id, name, Cover(newId, coverBaseline, coverWidth, coverHeight), action.fileName, sortOrder) }
                                    }

                                }
                            }
                        }
                    }
                }
/*
                Action.ACTION_UPDATE_FILE -> {
                    // MIME type is passed in folderId property
                    val localFile = File(localRootFolder, action.fileName)
                    if (localFile.exists()) webDav.upload(localFile, "$resourceRoot/${Uri.encode(action.folderName)}/${Uri.encode(action.fileName)}", action.folderId)
                }
*/
                Action.ACTION_ADD_DIRECTORY_ON_SERVER -> {
                    webDav.createFolder("$resourceRoot/${Uri.encode(action.folderName)}").apply {
                        if (this.isNotEmpty()) this.substring(0, 8).toInt().toString().also { fileId ->
                            // fix album id for new album and photos create on local, put back the cover id in album row so that it will show up in album list
                            // mind that we purposely leave the eTag column empty
                            photoRepository.fixNewPhotosAlbumId(action.folderId, fileId)
                            albumRepository.fixNewLocalAlbumId(action.folderId, fileId, action.fileName)
                            // touch meta file
                            try {
                                File("${localRootFolder}/${fileId}.json").createNewFile()
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        } else throw IOException()
                    }
                }
                Action.ACTION_MODIFY_ALBUM_ON_SERVER -> {}
                Action.ACTION_RENAME_DIRECTORY -> {
                    // Action's folderName property is the old name, fileName property is the new name
                    webDav.move("$resourceRoot/${Uri.encode(action.folderName)}", "$resourceRoot/${Uri.encode(action.fileName)}")
                    //albumRepository.changeName(action.folderId, action.fileName)
                }
                Action.ACTION_RENAME_FILE -> {
                    // Action's fileId property is the old name, fileName property is the new name
                    webDav.move("$resourceRoot/${Uri.encode(action.folderName)}/${Uri.encode(action.fileId)}", "$resourceRoot/${Uri.encode(action.folderName)}/${Uri.encode(action.fileName)}")
                }
                Action.ACTION_UPDATE_ALBUM_META -> {
                    albumRepository.getThisAlbum(action.folderId)[0].apply {
                        if (updateAlbumMeta(id, name, Cover(cover, coverBaseline, coverWidth, coverHeight), action.fileName, sortOrder)) {
                            // Touch file to avoid re-download
                            try { File(localRootFolder, "${id}.json").setLastModified(System.currentTimeMillis() + 10000) } catch (e: Exception) { e.printStackTrace() }
                        } else throw IOException()
                    }
                }
                Action.ACTION_UPDATE_PHOTO_META -> {
                    updateContentMeta(null, action.folderName)
                }
                Action.ACTION_ADD_FILES_TO_JOINT_ALBUM-> {
                    // Property folderId holds MIME type
                    // Property folderName holds joint album share path, start from Nextcloud server defined share path
                    // Property fileId holds string "joint album's id|dateTaken|width|height"
                    // Property fileName holds media file name
                    // Media file is located in app's cache folder
                    // Joint Album's content meta file is located in app's file folder
                    val localFile = File(application.cacheDir, action.fileName)
                    if (localFile.exists()) {
                        with (webDav.upload(localFile, "${resourceRoot.substringBeforeLast('/')}${Uri.encode(action.folderName, "/")}/${Uri.encode(action.fileName)}", action.folderId, application)) {
                            // After upload, update joint album's content meta json file, this file will be uploaded to server after all added media files in this batch has been uploaded
                            val metaFromAction = action.fileId.split('|')
                            val metaString = String.format(PHOTO_META_JSON, this.first.substring(0, 8).toInt().toString(), action.fileName, metaFromAction[1], action.folderId, metaFromAction[2], metaFromAction[3])
                            //val metaString = String.format(PHOTO_META_JSON, "fake", action.fileName, metaFromAction[1], action.folderId, metaFromAction[2], metaFromAction[3])
                            val contentMetaFile = File(localRootFolder, "${metaFromAction[0]}${NCShareViewModel.CONTENT_META_FILE_SUFFIX}")
                            if (!contentMetaFile.exists()) {
                                // Download content meta file if it's not ready
                                webDav.download("${resourceRoot.substringBeforeLast('/')}${Uri.encode(action.folderName, "/")}/${metaFromAction[0]}${NCShareViewModel.CONTENT_META_FILE_SUFFIX}", contentMetaFile, null)
                            }
                            var newMetaString: String
                            contentMetaFile.source().buffer().use { newMetaString = it.readUtf8() }
                            newMetaString = newMetaString.dropLast(3) + metaString
                            contentMetaFile.sink(false).buffer().use { it.write(newMetaString.encodeToByteArray()) }

                            // Don't keep the media file, other user owns the album after all
                            localFile.delete()
                        }

                    }
                }
                Action.ACTION_UPDATE_JOINT_ALBUM_PHOTO_META-> {
                    // Property folderId holds joint album's id
                    // Property folderName holds joint album share path, start from Nextcloud server defined share path
                    val fileName = "${action.folderId}${NCShareViewModel.CONTENT_META_FILE_SUFFIX}"
                    File(localRootFolder, fileName).apply {
                        // TODO conflicting, some other users might change this publication's content
                        if (this.exists()) webDav.upload(this, "${resourceRoot.substringBeforeLast('/')}${Uri.encode(action.folderName, "/")}/$fileName", NCShareViewModel.MIME_TYPE_JSON, application)
                        this.delete()
                    }
                }
            }

            // TODO: Error retry strategy, directory etag update, etc.
            actionRepository.deleteSync(action)
        }
    }

    private fun syncRemoteChanges() {
        //Log.e(">>>>>>>>**", "sync remote changes")
        val changedAlbums: MutableList<Album> = mutableListOf()
        val remoteAlbumIds = arrayListOf<String>()

        // Merge changed and/or new album from server
        var localAlbum: List<Album>
        webDav.list(resourceRoot, OkHttpWebDav.FOLDER_CONTENT_DEPTH).drop(1).forEach { remoteAlbum ->     // Drop the first one in the list, which is the parent folder itself
            if (remoteAlbum.isFolder) {
                remoteAlbumIds.add(remoteAlbum.fileId)

                localAlbum = albumRepository.getThisAlbum(remoteAlbum.fileId)
                if (localAlbum.isNotEmpty()) {
                    // We have hit in local table, which means it's a existing album
                    if (localAlbum[0].eTag != remoteAlbum.eTag) {
                        // eTag mismatched, this album changed on server
                        changedAlbums.add(
                            Album(
                                remoteAlbum.fileId,             // Either local or remote version is fine
                                remoteAlbum.name,               // Use remote version, since it might be changed on server
                                localAlbum[0].startDate,        // Preserve local data
                                localAlbum[0].endDate,          // Preserve local data
                                localAlbum[0].cover,            // Preserve local data
                                localAlbum[0].coverBaseline,    // Preserve local data
                                localAlbum[0].coverWidth,       // Preserve local data
                                localAlbum[0].coverHeight,      // Preserve local data
                                remoteAlbum.modified,           // Use remote version
                                localAlbum[0].sortOrder,        // Preserve local data
                                remoteAlbum.eTag,               // Use remote version
                                if (remoteAlbum.isShared) 1 else 0,
                                1f,
                            )
                        )
                    } else {
                        // Rename operation on server would not change item's own eTag, have to sync name changes here
                        if (localAlbum[0].name != remoteAlbum.name) albumRepository.changeName(remoteAlbum.fileId, remoteAlbum.name)
                    }
                } else {
                    // No hit on local, a new album from server, make sure the cover property is set to "", a sign shows it's a new album
                    changedAlbums.add(Album(remoteAlbum.fileId, remoteAlbum.name, LocalDateTime.MAX, LocalDateTime.MIN, "", 0, 0, 0, remoteAlbum.modified, Album.BY_DATE_TAKEN_ASC, remoteAlbum.eTag, 0, 1f))
                }
            }
        }

        // Delete those albums not exist on server, happens when user delete album on the server. Should skip local added new albums, e.g. those with cover column empty
        val localAlbumIdAndCover = albumRepository.getAllAlbumIdAndCover()
        for (local in localAlbumIdAndCover) {
            if (!remoteAlbumIds.contains(local.id) && local.cover.isNotEmpty()) {
                albumRepository.deleteByIdSync(local.id)
                val allPhotoIds = photoRepository.getAllPhotoIdsByAlbum(local.id)
                photoRepository.deletePhotosByAlbum(local.id)
                allPhotoIds.forEach {
                    try { File(localRootFolder, it.id).delete() } catch (e: Exception) { e.printStackTrace() }
                    try { File(localRootFolder, it.name).delete() } catch(e: Exception) { e.printStackTrace() }
                }
                //Log.e(">>>>", "Deleted album: ${local.id}")
            }
        }

        if (changedAlbums.isNotEmpty()) {
            // Sync each changed album
            val changedPhotos = mutableListOf<Photo>()
            val remotePhotoIds = mutableListOf<String>()
            //var tempAlbum: Album

            for (changedAlbum in changedAlbums) {
                // Check network type on every loop, so that user is able to stop sync right in the middle
                checkConnection()

                val localPhotoETags = photoRepository.getETagsMap(changedAlbum.id)
                val localPhotoNames = photoRepository.getNamesMap(changedAlbum.id)
                val localPhotoNamesReverse = localPhotoNames.entries.stream().collect(Collectors.toMap({ it.value }) { it.key })
                var remotePhotoId: String
                var localImageFileName: String
                val metaFileName = "${changedAlbum.id}.json"
                var needUpdateContentMeta = false

                // Create changePhotos list
                val remotePhotoList = webDav.list("${resourceRoot}/${Uri.encode(changedAlbum.name)}", OkHttpWebDav.FOLDER_CONTENT_DEPTH).drop(1)
                remotePhotoList.forEach { remotePhoto ->
                    if (remotePhoto.contentType.startsWith("image/", true) || remotePhoto.contentType.startsWith("video/", true)) {
                        remotePhotoId = remotePhoto.fileId
                        // Accumulate remote photos list
                        remotePhotoIds.add(remotePhotoId)

                        if (localPhotoETags[remotePhotoId] != remotePhoto.eTag) { // Also matches newly created photo id from server, e.g. no such remotePhotoId in local table
                            //Log.e("=======", "updating photo: ${remotePhoto.name} r_etag:${remotePhoto.etag} l_etag:${localPhotoETags[remotePhotoId]}")

                            //if (localPhotoETags.containsKey(remotePhoto.name)) {
                            if (File(localRootFolder, remotePhoto.name).exists()) {
                                // If there is local file with remote photo's name, that means it's a local added photo which is now coming back from server.
                                //Log.e("<><><>", "coming back now ${remotePhoto.name}")
                                // Rename image file name to fileid
                                try {
                                    // If this file has already being uploaded,
                                    File(localRootFolder, remotePhotoId).delete()
                                } catch (e: Exception) { Log.e(">>>>Exception: ", e.stackTraceToString()) }
                                try {
                                    File(localRootFolder, remotePhoto.name).renameTo(File(localRootFolder, remotePhotoId))
                                    //Log.e(">>>>", "rename ${remotePhoto.name} to $remotePhotoId")
                                } catch (e: Exception) { Log.e(">>>>Exception: ", e.stackTraceToString()) }

                                localPhotoNamesReverse[remotePhoto.name]?.apply {
                                    // Update it's id to the real fileId and also eTag now
                                    photoRepository.fixPhoto(this, remotePhotoId, remotePhoto.name, remotePhoto.eTag, remotePhoto.modified)
                                    // Taking care the cover
                                    // TODO: Condition race here, e.g. user changes this album's cover right at this very moment
                                    if (changedAlbum.cover == this) {
                                        //Log.e("=======", "fixing cover from ${changedAlbum.cover} to $remotePhotoId")
                                        albumRepository.fixCoverId(changedAlbum.id, remotePhotoId)
                                        changedAlbum.cover = remotePhotoId

                                        // cover's fileId is ready, create and sync album meta file
                                        changedAlbum.apply { updateAlbumMeta(id, name, Cover(changedAlbum.cover, coverBaseline, coverWidth, coverHeight), remotePhoto.name, sortOrder) }
                                    }
                                }
                            } else {
                                // Either a new photo created on server or an existing photo updated on server
                                changedPhotos.add(Photo(remotePhotoId, changedAlbum.id, remotePhoto.name, remotePhoto.eTag, LocalDateTime.now(), remotePhoto.modified, 0, 0, remotePhoto.contentType, 0))

                                // Published album's content meta needs update
                                if (changedAlbum.shareId == 1) needUpdateContentMeta = true
                            }
                        } else if (localPhotoNames[remotePhotoId] != remotePhoto.name) {
                            // Rename operation on server would not change item's own eTag, have to sync name changes here. The positive side is avoiding fetching the actual
                            // file again from server
                            photoRepository.changeName(remotePhotoId, remotePhoto.name)

                            // Published album's content meta needs update
                            if (changedAlbum.shareId == 1) needUpdateContentMeta = true
                        }
                    }
                }

                // Syncing meta
                if (changedAlbum.cover.isEmpty()) {
                    // New album from server, try downloading album meta file. If this album was created on server, might not have cover set up yet
                    downloadAlbumMeta(changedAlbum)?.apply {
                        changedAlbum.cover = cover
                        changedAlbum.coverBaseline = baseline
                        changedAlbum.coverWidth = width
                        changedAlbum.coverHeight = height
                        changedAlbum.sortOrder = sortOrder
                    }

                    // Find the cover in photo lists
                    val coverPhoto = if (changedAlbum.cover.isNotEmpty()) {
                        // meta file is ready
                        changedPhotos.find { it.id == changedAlbum.cover }!!
                    } else {
                        // no meta file, should be a album created on server
                        // Get first JPEG or PNG file, only these two format can be set as coverart because they are supported by BitmapRegionDecoder API
                        // If we just can't find one single photo of these two formats in this new album, fall back to the first one in the list, cover will be shown as placeholder drawable though
                        changedPhotos.find { it.mimeType == "image/jpeg" || it.mimeType == "image/png" } ?: changedPhotos[0]
                    }

                    // Move cover photo to the first position of changedPhotos list so that we can download it and show album in album list asap in the following changedPhotos.forEachIndexed loop
                    changedPhotos.remove(coverPhoto)
                    changedPhotos.add(0, coverPhoto)
                } else {
                    // Try to sync meta changes from other devices if this album existed on local device
                    remotePhotoList.find { it.name == metaFileName }?.let { remoteMeta->
                        //Log.e(">>>>>", "remote ${metaFileName} timestamp: ${remoteMeta.modified.toInstant(OffsetDateTime.now().offset).toEpochMilli()}")
                        //Log.e(">>>>>", "local ${metaFileName} timestamp: ${File("$localRootFolder/${metaFileName}").lastModified()}")
                        if (remoteMeta.modified.toInstant(OffsetDateTime.now().offset).toEpochMilli() - File("$localRootFolder/${metaFileName}").lastModified() > 180000) {
                            // If the delta of last modified timestamp of local and remote meta file is larger than 3 minutes, assume that it's a updated version from other devices
                            // TODO more proper way to do this
                            downloadAlbumMeta(changedAlbum)?.apply {
                                changedAlbum.cover = cover
                                changedAlbum.coverBaseline = baseline
                                changedAlbum.coverWidth = width
                                changedAlbum.coverHeight = height
                                changedAlbum.sortOrder = sortOrder
                            }

                            // If the new cover is new or updated somewhere else, move it to the top of download list
                            changedPhotos.find { it.id == changedAlbum.cover }?.let { newCover->
                                changedPhotos.remove(newCover)
                                changedPhotos.add(0, newCover)
                            }
                        }
                    }
                }

                // Fetch changed photo files, extract EXIF info, update Photo table
                changedPhotos.forEachIndexed { i, changedPhoto->
                    // Prepare the image file
                    localImageFileName = localPhotoNames.getOrDefault(changedPhoto.id, changedPhoto.name)
                    if (File(localRootFolder, localImageFileName).exists()) {
                        // If image file with 'name' exists, replace the old file with this
                        try {
                            File(localRootFolder, changedPhoto.id).delete()
                        } catch (e: Exception) {
                            Log.e(">>>>Exception: ", e.stackTraceToString())
                        }
                        try {
                            File(localRootFolder, localImageFileName).renameTo(File(localRootFolder, changedPhoto.id))
                            Log.e(">>>>", "rename file $localImageFileName to ${changedPhoto.id}")
                        } catch (e: Exception) {
                            Log.e(">>>>Exception: ", e.stackTraceToString())
                        }
                    } else {
                        // Check network type on every loop, so that user is able to stop sync right in the middle
                        checkConnection()

                        // Download image file from server
                        webDav.download("$resourceRoot/${Uri.encode(changedAlbum.name)}/${Uri.encode(changedPhoto.name)}", "$localRootFolder/${changedPhoto.id}", null)
                        //Log.e(">>>>", "Downloaded ${changedPhoto.name}")
                    }

                    with(Tools.getPhotoParams("$localRootFolder/${changedPhoto.id}", changedPhoto.mimeType, changedPhoto.name)) {
                        changedPhoto.dateTaken = dateTaken
                        changedPhoto.width = width
                        changedPhoto.height = height
                        changedPhoto.mimeType = mimeType
                    }

                    // Update album's startDate, endDate fields
                    if (changedPhoto.dateTaken > changedAlbum.endDate) changedAlbum.endDate = changedPhoto.dateTaken
                    if (changedPhoto.dateTaken < changedAlbum.startDate) changedAlbum.startDate = changedPhoto.dateTaken

                    // update row when everything's fine. any thing that broke before this point will be captured by exception handler and will be worked on again in next round of sync
                    photoRepository.upsertSync(changedPhoto)

                    // Time to show updated album in AlbumFragment
                    // If it's a new album without meta file, create default cover because width and height information are ready now
                    with(changedAlbum) {
                        if (cover.isEmpty()) {
                            this.cover = changedPhoto.id
                            coverBaseline = (changedPhoto.height - (changedPhoto.width * 9 / 21)) / 2
                            coverWidth = changedPhoto.width
                            coverHeight = changedPhoto.height
                        }
                    }

                    if (i == 0) {
                        // eTag property should be "", means it's syncing
                        albumRepository.upsertSync(changedAlbum.copy(eTag = "", syncProgress = 0f))
                    } else {
                        // Update sync status. AlbumFragment will show changes to user
                        albumRepository.updateAlbumSyncStatus(changedAlbum.id, (i + 1).toFloat() / changedPhotos.size, changedAlbum.startDate, changedAlbum.endDate)
                    }
                }

                // TODO: The above loop might take a long time to finish, during the process, user might already change cover or sort order by now, update it here
                if (changedPhotos.isNotEmpty()) {
                    with(albumRepository.getMeta(changedAlbum.id)) {
                        changedAlbum.sortOrder = this.sortOrder
                        changedAlbum.cover = this.cover
                        changedAlbum.coverBaseline = this.coverBaseline
                        changedAlbum.coverWidth = this.coverWidth
                        changedAlbum.coverHeight = this.coverHeight
                    }
                }

                // Every changed photos updated, we can commit changes to the Album table now. The most important column is "eTag", dictates the sync status
                albumRepository.upsertSync(changedAlbum)

                // Delete those photos not exist on server, happens when user delete photos on the server
                var deletion = false
                //localPhotoETags = photoRepository.getETagsMap(changedAlbum.id)
                for (localPhoto in localPhotoETags) {
                    if (!remotePhotoIds.contains(localPhoto.key)) {
                        deletion = true
                        photoRepository.deleteByIdSync(localPhoto.key)
                        try {
                            File(localRootFolder, localPhoto.key).delete()
                            //Log.e(">>>>", "Deleted photo: ${localPhoto.key}")
                        } catch (e: Exception) { e.printStackTrace() }
                    }
                }

                if (deletion) {
                    // Maintaining album cover and duration if deletion happened
                    val photosLeft = photoRepository.getAlbumPhotos(changedAlbum.id)
                    if (photosLeft.isNotEmpty()) {
                        albumRepository.getThisAlbum(changedAlbum.id)[0].run {
                            startDate = photosLeft[0].dateTaken
                            endDate = photosLeft.last().dateTaken
                            photosLeft.find { it.id == this.cover } ?: run {
                                // If the last cover is deleted, use the first photo as default
                                this.cover = photosLeft[0].id
                                coverBaseline = (photosLeft[0].height - (photosLeft[0].width * 9 / 21)) / 2
                                coverWidth = photosLeft[0].width
                                coverHeight = photosLeft[0].height

                                // Update cover meta
                                updateAlbumMeta(id, name, Cover(this.cover, coverBaseline, coverWidth, coverHeight), photosLeft[0].name, sortOrder)
                            }
                            albumRepository.updateSync(this)
                        }

                        // Published album's content meta needs update
                        if (changedAlbum.shareId == 1) needUpdateContentMeta = true
                    } else {
                        // All photos under this album removed, delete album on both local and remote
                        albumRepository.deleteByIdSync(changedAlbum.id)
                        actionRepository.addAction(Action(null, Action.ACTION_DELETE_DIRECTORY_ON_SERVER, changedAlbum.id, changedAlbum.name, "", "", System.currentTimeMillis(), 1))
                    }
                }

                // Recycle the list
                remotePhotoIds.clear()
                changedPhotos.clear()

                // If published album's content changed from somewhere else, update it's content meta file now
                if (needUpdateContentMeta) updateContentMeta(changedAlbum.id, changedAlbum.name)
            }
        }
    }

    private fun backupCameraRoll() {
        // Backup camera roll if setting turn on
        if (sp.getBoolean(application.getString(R.string.cameraroll_backup_pref_key), false)) {
            // Make sure DCIM base directory is there
            if (!webDav.isExisted(dcimRoot)) webDav.createFolder(dcimRoot)

            // Make sure device subfolder is under DCIM/
            dcimRoot += "/${Tools.getDeviceModel()}"
            if (!webDav.isExisted(dcimRoot)) webDav.createFolder(dcimRoot)

            var lastTime = sp.getLong(SettingsFragment.LAST_BACKUP, 0L)
            val contentUri = MediaStore.Files.getContentUri("external")
            val pathSelection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Files.FileColumns.RELATIVE_PATH else MediaStore.Files.FileColumns.DATA
            val projection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                pathSelection,
                MediaStore.Files.FileColumns.DATE_ADDED,
                MediaStore.Files.FileColumns.MEDIA_TYPE,
                MediaStore.Files.FileColumns.MIME_TYPE,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.SIZE,
            )
            val selection = "(${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})" + " AND " +
                    "($pathSelection LIKE '%DCIM%')" + " AND " + "(${MediaStore.Files.FileColumns.DATE_ADDED} > ${lastTime})"
            application.contentResolver.query(contentUri, projection, selection, null, "${MediaStore.Files.FileColumns.DATE_ADDED} ASC"
            )?.use { cursor->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val pathColumn = cursor.getColumnIndexOrThrow(pathSelection)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
                val typeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)

                var relativePath: String
                var fileName: String
                var mimeType: String

                while(cursor.moveToNext()) {
                    // Check network type on every loop, so that user is able to stop sync right in the middle
                    checkConnection()

                    //Log.e(">>>>>>>>", "${cursor.getString(nameColumn)} ${cursor.getString(dateColumn)}  ${cursor.getString(pathColumn)} needs uploading")
                    fileName = cursor.getString(nameColumn)
                    relativePath = cursor.getString(pathColumn).substringAfter("DCIM/").substringBeforeLast('/')
                    mimeType = cursor.getString(typeColumn)
                    //Log.e(">>>>>", "relative path is $relativePath  server file will be ${dcimRoot}/${relativePath}/${fileName}")

                    // Indefinite while loop is for handling 404 error when folders needed to be created on server before hand
                    while(true) {
                        try {
                            webDav.upload(ContentUris.withAppendedId(contentUri, cursor.getLong(idColumn)), "${dcimRoot}/${Uri.encode(relativePath, "/")}/${Uri.encode(fileName)}", mimeType, application.contentResolver, cursor.getLong(sizeColumn), application)
                            break
                        } catch (e: OkHttpWebDavException) {
                            Log.e(">>>>>OkHttpWebDavException: ", e.stackTraceString)
                            when (e.statusCode) {
                                404 -> {
                                    // create file in non-existed folder, should create subfolder first
                                    var subFolder = dcimRoot
                                    relativePath.split("/").forEach {
                                        subFolder += "/$it"
                                        if (!webDav.isExisted(subFolder)) webDav.createFolder(subFolder)
                                    }
                                }
                                else -> throw e
                            }
                        }
                    }

                    // New timestamp when success
                    lastTime = cursor.getLong(dateColumn) + 1
                }
            }

            // Save latest timestamp
            sp.edit().apply {
                putLong(SettingsFragment.LAST_BACKUP, lastTime)
                apply()
            }
        }
    }

    private fun checkConnection() {
        if (sp.getBoolean(wifionlyKey, true)) {
            if ((application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered) throw NetworkErrorException()
        }
    }

    private fun updateAlbumMeta(albumId: String, albumName: String, cover: Cover, coverFileName: String, sortOrder: Int): Boolean {
        try {
            val metaFileName = "${albumId}.json"
            val localFile = File(localRootFolder, metaFileName)

            // Need this file in phone
            //FileWriter("$localRootFolder/metaFileName").apply {
            localFile.writer().use {
                it.write(String.format("{\"lespas\":{\"cover\":{\"id\":\"%s\",\"filename\":\"%s\",\"baseline\":%d,\"width\":%d,\"height\":%d},\"sort\":%d}}", cover.cover, coverFileName, cover.coverBaseline, cover.coverWidth, cover.coverHeight, sortOrder))
            }

            // If local meta json file created successfully
            webDav.upload(localFile, "$resourceRoot/${Uri.encode(albumName)}/${Uri.encode(metaFileName)}", NCShareViewModel.MIME_TYPE_JSON, application)

        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }

        return true
    }

    private fun downloadAlbumMeta(album: Album): Meta? {
        var result: Meta? = null

        try {
            val metaFileName = "${album.id}.json"

            // Download the updated meta file
            webDav.getStream("$resourceRoot/${Uri.encode(album.name)}/${Uri.encode(metaFileName)}", null).reader().use { input->
                File(localRootFolder, metaFileName).writer().use { output ->
                    val content = input.readText()
                    output.write(content)

                    // Store meta info in meta data holder
                    val meta = JSONObject(content).getJSONObject("lespas")
                    meta.getJSONObject("cover").apply { result = Meta(getString("id"), getInt("baseline"), getInt("width"), getInt("height"), meta.getInt("sort")) }
                    //Log.e(">>>>", "Downloaded meta file ${remoteAlbum.name}/${metaFileName}")
                }
            }
        }
        // TODO consolidate these exception handling codes
        catch (e: OkHttpWebDavException) { Log.e(">>>>OkHttpWebDavException: ", e.stackTraceToString()) }
        catch (e: FileNotFoundException) { Log.e(">>>>FileNotFoundException: meta file not exist", e.stackTraceToString())}
        catch (e: JSONException) { Log.e(">>>>JSONException: error parsing meta information", e.stackTraceToString())}
        catch (e: Exception) { e.printStackTrace() }

        return result
    }

    private fun updateContentMeta(id: String?, albumName: String) {
        val albumId = id ?: albumRepository.getAlbumByName(albumName).id
        var content = "{\"lespas\":{\"photos\":["
        photoRepository.getPhotoMetaInAlbum(albumId).forEach {
            content += String.format(NCShareViewModel.PHOTO_META_JSON, it.id, it.name, it.dateTaken.toEpochSecond(OffsetDateTime.now().offset), it.mimeType, it.width, it.height)
        }
        content = content.dropLast(1) + "]}}"
        webDav.upload(content, "$resourceRoot/${Uri.encode(albumName)}/${albumId}-content.json", NCShareViewModel.MIME_TYPE_JSON)
    }

/*
    private fun getMediaMetaFromUri(uri: String): NCShareViewModel.RemotePhoto? = getMediaMetaFromUri(Uri.parse(uri))
    private fun getMediaMetaFromUri(uri: Uri): NCShareViewModel.RemotePhoto? {
        val result = NCShareViewModel.RemotePhoto()
        val cr = application.contentResolver

        try {
            // Get file name, last modified timestamp and MIME type from content resolver query
            cr.query(uri, null, null, null, null, null)?.use { cursor ->
                cursor.moveToFirst()
                result.path = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
                result.timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)) / 1000
                result.mimeType = cursor.getString(cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)).lowercase()
            }

            when {
                result.mimeType.startsWith("video/", true) -> {
                    with(MediaMetadataRetriever()) {
                        setDataSource(application, uri)
                        Tools.getVideoFileDate(this, result.path).apply { if (this != LocalDateTime.MIN) result.timestamp = this.toEpochSecond(OffsetDateTime.now().offset) }

                        // Get video file dimension
                        extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.let {
                            if (it == "90" || it == "270") {
                                // Swap width and height if rotate 90 or 270 degree
                                result.height = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 0
                                result.width = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 0
                            } else {
                                result.width = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 0
                                result.height = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 0
                            }
                        }
                    }
                }
                result.mimeType.startsWith("image/", true) -> {
                    val exif: ExifInterface
                    cr.openInputStream(uri)?.use {
                        exif = ExifInterface(it)
                        Tools.getImageFileDate(exif, result.path)?.let { date -> SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.getDefault()).apply{ timeZone = TimeZone.getDefault() }.parse(date)?.run { result.timestamp = this.time / 1000 }}

                        result.coverBaseLine = exif.rotationDegrees
                    }

                    // Detect animated GIF/WebP
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        when (result.mimeType) {
                            "image/gif" -> if (ImageDecoder.decodeDrawable(ImageDecoder.createSource(cr, uri)) is AnimatedImageDrawable) result.mimeType = "image/agif"
                            "image/webp" -> if (ImageDecoder.decodeDrawable(ImageDecoder.createSource(cr, uri)) is AnimatedImageDrawable) result.mimeType = "image/awebp"
                        }
                    }

                    BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, this) }
                        result.width = if (outWidth != -1) outWidth else return null
                        result.height = if (outHeight != -1) outHeight else return null
                    }
                }
                else -> return null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }

        return result
    }
*/

    data class Meta (
        val cover: String,
        val baseline: Int,
        val width: Int,
        val height: Int,
        val sortOrder: Int,
    )

    companion object {
        const val ACTION = "SYNC_ACTION"
        const val SYNC_LOCAL_CHANGES = 1
        const val SYNC_REMOTE_CHANGES = 2
        const val SYNC_BOTH_WAY = 3
        const val BACKUP_CAMERA_ROLL = 4
        const val SYNC_ALL = 7

        private const val PHOTO_META_JSON = ",{\"id\":\"%s\",\"name\":\"%s\",\"stime\":%s,\"mime\":\"%s\",\"width\":%s,\"height\":%s}]}}"

    }
}