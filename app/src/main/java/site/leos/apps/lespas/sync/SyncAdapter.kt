package site.leos.apps.lespas.sync

import android.accounts.Account
import android.accounts.AccountManager
import android.accounts.AuthenticatorException
import android.accounts.NetworkErrorException
import android.app.Application
import android.content.*
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.preference.PreferenceManager
import okhttp3.internal.closeQuietly
import okio.IOException
import okio.buffer
import okio.sink
import okio.source
import org.json.JSONException
import org.json.JSONObject
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.*
import site.leos.apps.lespas.helper.OkHttpWebDav
import site.leos.apps.lespas.helper.OkHttpWebDavException
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.photo.PhotoRepository
import site.leos.apps.lespas.publication.NCShareViewModel
import site.leos.apps.lespas.settings.SettingsFragment
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.*
import java.util.stream.Collectors
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

class SyncAdapter @JvmOverloads constructor(private val application: Application, autoInitialize: Boolean, allowParallelSyncs: Boolean = false
) : AbstractThreadedSyncAdapter(application.baseContext, autoInitialize, allowParallelSyncs){
    private lateinit var webDav: OkHttpWebDav
    private lateinit var resourceRoot: String
    private lateinit var dcimRoot: String
    private lateinit var localRootFolder: String
    private lateinit var token: String
    private val albumRepository = AlbumRepository(application)
    private val photoRepository = PhotoRepository(application)
    private val actionRepository = ActionRepository(application)
    private val sp = PreferenceManager.getDefaultSharedPreferences(application)
    private val wifionlyKey = application.getString(R.string.wifionly_pref_key)
    private val metaUpdatedNeeded = mutableSetOf<String>()
    private val contentMetaUpdatedNeeded = mutableSetOf<String>()
    private var workingAction: Action? = null

    override fun onPerformSync(account: Account, extras: Bundle, authority: String, provider: ContentProviderClient, syncResult: SyncResult) {

        try {
            //val order = extras.getInt(ACTION)   // Return 0 when no mapping of ACTION found

            prepare(account)
            while (true) {
                val actions = actionRepository.getAllPendingActions()
                if (actions.isEmpty()) break
                syncLocalChanges(actions)
            }
            syncRemoteChanges()
            updateMeta()
            backupCameraRoll()

            // Clear status counters
            syncResult.stats.clear()
        } catch (e: OkHttpWebDavException) {
            Log.e(">>>>OkHttpWebDavException: ", e.stackTraceString)
            when (e.statusCode) {
                400, 404, 405, 406, 410 -> {
                    // create file in non-existed folder, target not found, target readonly, target already existed, etc. should be skipped and move on to next action
                    //actionRepository.discardCurrentWorkingAction()
                    workingAction?.let { actionRepository.delete(it) }
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
        } finally {
            // Make sure meta get updated by adding them to action database
            metaUpdatedNeeded.forEach { actionRepository.addAction(Action(null, Action.ACTION_UPDATE_THIS_ALBUM_META, "", it, "", "", 0, 0)) }
            contentMetaUpdatedNeeded.forEach { actionRepository.addAction(Action(null, Action.ACTION_UPDATE_THIS_CONTENT_META, "", it, "", "", 0, 0)) }
        }
    }

    private fun prepare(account: Account) {
        // Check network type
        checkConnection()

        AccountManager.get(application).run {
            val userName = getUserData(account, context.getString(R.string.nc_userdata_username))
            val serverRoot = getUserData(account, context.getString(R.string.nc_userdata_server))
            token = getUserData(account, application.getString(R.string.nc_userdata_secret))

            resourceRoot = "$serverRoot${application.getString(R.string.dav_files_endpoint)}$userName${application.getString(R.string.lespas_base_folder_name)}"
            dcimRoot = "$serverRoot${application.getString(R.string.dav_files_endpoint)}${userName}/DCIM"
            localRootFolder = Tools.getLocalRoot(application)

            webDav = OkHttpWebDav(
                userName, peekAuthToken(account, serverRoot), serverRoot, getUserData(account, context.getString(R.string.nc_userdata_selfsigned)).toBoolean(),
                null,
                "LesPas_${application.getString(R.string.lespas_version)}",
                0,
            )
        }

        // Make sure lespas base directory is there, and it's really a nice moment to test server connectivity
        if (!webDav.isExisted(resourceRoot)) webDav.createFolder(resourceRoot)
    }

    private fun syncLocalChanges(pendingActions: List<Action>) {
        // Sync local changes, e.g., processing pending actions
        pendingActions.forEach { action ->
            // Save current action for deletion when some ignorable exceptions happen
            workingAction = action

            // Check network type on every loop, so that user is able to stop sync right in the middle
            checkConnection()

            // Do not do too many works here, as the local sync should be as simple as making several webdav calls, so that if any thing bad happen, we will be catched by
            // exceptions handling down below, and start again right here in later sync, e.g. atomic
            when (action.action) {
                Action.ACTION_DELETE_FILES_ON_SERVER -> {
                    webDav.delete("$resourceRoot/${Uri.encode(action.folderName)}/${Uri.encode(action.fileName)}")
                    contentMetaUpdatedNeeded.add(action.folderName)
                }

                Action.ACTION_DELETE_DIRECTORY_ON_SERVER -> {
                    webDav.delete("$resourceRoot/${Uri.encode(action.folderName)}")
                }

                Action.ACTION_ADD_FILES_ON_SERVER -> {
                    // folderId: file mimetype
                    // folderName: album name
                    // fileId: photo's id, if this is a new photo, same as photo name
                    // fileName: photo name
                    // date: created timestamp
                    // retry: album's flags
                    // local file saved as "filename" in lespas/ folder

                    val localFile = File(localRootFolder, action.fileName)
                    if (localFile.exists()) {
                        with (webDav.upload(localFile, "$resourceRoot/${Uri.encode(action.folderName)}/${Uri.encode(action.fileName)}", action.folderId, application)) {
                            // Nextcloud WebDAV PUT, MOVE, COPY return fileId and eTag
                            if (this.first.isNotEmpty() && this.second.isNotEmpty()) {
                                val newId = this.first.substring(0, 8).toInt().toString()   // remove leading 0s

                                if ((action.retry and Album.REMOTE_ALBUM) == Album.REMOTE_ALBUM) {
                                    // If this is a remote album, remove the image file and video thumbnail
                                    try { localFile.delete() } catch (e: Exception) { e.printStackTrace() }
                                    try { File(localRootFolder, "${action.fileName}.thumbnail").delete() } catch (e: Exception) { e.printStackTrace() }
                                } else {
                                    // If it's a local album, rename image file name to fileId
                                    try { localFile.renameTo(File(localRootFolder, newId)) } catch (e: Exception) { e.printStackTrace() }
                                    // Rename video thumbnail file too
                                    if (action.folderId.startsWith("video")) try { File(localRootFolder, "${action.fileName}.thumbnail").renameTo(File(localRootFolder, "${newId}.thumbnail")) } catch (e: Exception) { e.printStackTrace() }
                                }

                                // Update photo's id to the real fileId and latest eTag now. When called from Snapseed Replace, newEtag is what needs to be updated
                                photoRepository.fixPhotoIdEtag(action.fileId, newId, this.second)

                                // Fix album cover id if this photo is the cover
                                albumRepository.getAlbumByName(action.folderName).also { album ->
                                    if (album?.cover == action.fileId) {
                                        // Taking care the cover
                                        // TODO: Condition race here, e.g. user changes this album's cover right at this very moment
                                        albumRepository.fixCoverId(album.id, newId)

/*
                                        // cover's fileId is ready, create and sync album meta file. When called from Snapseed Replace, new file name passed in action.fileName is what needs to be updated
                                        with(album) { updateAlbumMeta(id, name, Cover(newId, coverBaseline, coverWidth, coverHeight), action.fileName, sortOrder) }
*/
                                        metaUpdatedNeeded.add(action.folderName)
                                    }
                                }

                                contentMetaUpdatedNeeded.add(action.folderName)
                            }
                        }
                    }
                }

                Action.ACTION_ADD_DIRECTORY_ON_SERVER -> {
                    webDav.createFolder("$resourceRoot/${Uri.encode(action.folderName)}").apply {
                        // Recreating the existing folder will return empty string
                        if (this.isNotEmpty()) this.substring(0, 8).toInt().toString().also { fileId ->
                            // fix album id for new album and photos create on local, put back the cover id in album row so that it will show up in album list
                            // mind that we purposely leave the eTag column empty
                            photoRepository.fixNewPhotosAlbumId(action.folderId, fileId)
                            albumRepository.fixNewLocalAlbumId(action.folderId, fileId, action.fileName)

                            // touch meta file
                            try { File("${localRootFolder}/${fileId}.json").createNewFile() } catch (e: Exception) { e.printStackTrace() }

                            // Mark meta update later
                            metaUpdatedNeeded.add(action.folderName)
                        }
                    }
                }

                //Action.ACTION_MODIFY_ALBUM_ON_SERVER -> {}

                Action.ACTION_RENAME_DIRECTORY -> {
                    // Action's folderName property is the old name, fileName property is the new name
                    webDav.move("$resourceRoot/${Uri.encode(action.folderName)}", "$resourceRoot/${Uri.encode(action.fileName)}")
                    //albumRepository.changeName(action.folderId, action.fileName)
                }

                Action.ACTION_RENAME_FILE -> {
                    // Action's fileId property is the old name, fileName property is the new name
                    webDav.move("$resourceRoot/${Uri.encode(action.folderName)}/${Uri.encode(action.fileId)}", "$resourceRoot/${Uri.encode(action.folderName)}/${Uri.encode(action.fileName)}")

                    // Always follow by a ACTION_ADD_FILES_ON_SERVER, no need to trigger meta update
                }

                Action.ACTION_UPDATE_ALBUM_META -> {
                    // Property folderId holds id of the album needed meta update
                    albumRepository.getThisAlbum(action.folderId).apply {
                        if (updateAlbumMeta(id, name, Cover(cover, coverBaseline, coverWidth, coverHeight, coverFileName, coverMimeType, coverOrientation), sortOrder)) {
                            // Touch file to avoid re-download
                            try { File(localRootFolder, "${id}.json").setLastModified(System.currentTimeMillis() + 10000) } catch (e: Exception) { e.printStackTrace() }
                        } else throw IOException()
                    }
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
                            val metaString = String.format(
/*
                                ",{\"id\":\"%s\",\"name\":\"%s\",\"stime\":%s,\"mime\":\"%s\",\"width\":%s,\"height\":%s}]}}",
                                this.first.substring(0, 8).toInt().toString(), action.fileName, metaFromAction[1], action.folderId, metaFromAction[2], metaFromAction[3]
*/
                                // Use PHOTO_META_JSON_V2 string while deleting it's trailing ',', add ',' at the beginning, add JSON closing ']}}' at the end
                                ",${NCShareViewModel.PHOTO_META_JSON_V2.dropLast(1)}]}}",
                                this.first.substring(0, 8).toInt().toString(), action.fileName, metaFromAction[1], action.folderId, metaFromAction[2], metaFromAction[3], metaFromAction[4], metaFromAction[5], metaFromAction[6], metaFromAction[7], metaFromAction[8], metaFromAction[9]
                            )
                            val contentMetaFile = File(localRootFolder, "${metaFromAction[0]}${NCShareViewModel.CONTENT_META_FILE_SUFFIX}")
                            if (!contentMetaFile.exists()) {
                                // Download content meta file if it's not ready, if user added photo from Publication Detail screen the file will exist, if user added photo by sharing from other apps, this file does not exist
                                webDav.download("${resourceRoot.substringBeforeLast('/')}${Uri.encode(action.folderName, "/")}/${metaFromAction[0]}${NCShareViewModel.CONTENT_META_FILE_SUFFIX}", contentMetaFile, null)
                            }
                            var newMetaString: String
                            contentMetaFile.source().buffer().use { newMetaString = it.readUtf8() }
                            newMetaString = newMetaString.dropLast(3) + metaString
                            contentMetaFile.sink(false).buffer().use { it.write(newMetaString.encodeToByteArray()) }

                            // No need to keep the media file, other user owns the album after all
                            localFile.delete()
                        }
                    }
                }

                Action.ACTION_UPDATE_JOINT_ALBUM_PHOTO_META-> {
                    // Property folderId holds joint album's id
                    // Property folderName holds joint album share path, start from Nextcloud server defined share path
                    // Actual album meta json file is created/modified by ACTION_ADD_FILES_TO_JOINT_ALBUM
                    val fileName = "${action.folderId}${NCShareViewModel.CONTENT_META_FILE_SUFFIX}"
                    File(localRootFolder, fileName).apply {
                        // TODO conflicting, some other users might change this publication's content
                        if (this.exists()) webDav.upload(this, "${resourceRoot.substringBeforeLast('/')}${Uri.encode(action.folderName, "/")}/$fileName", NCShareViewModel.MIME_TYPE_JSON, application)
                        this.delete()   // We don't actually need this at local after updating it
                    }
                }

                Action.ACTION_UPDATE_THIS_ALBUM_META-> {
                    // This action only fired if last sync process quit on exceptions
                    // Property folderName holds name of the album deemed meta update
                    metaUpdatedNeeded.add(action.folderName)
                }

                Action.ACTION_UPDATE_THIS_CONTENT_META-> {
                    // This action only fired if last sync process quit on exceptions
                    // Property folderName holds name of the album deemed meta update
                    contentMetaUpdatedNeeded.add(action.folderName)
                }
                Action.ACTION_REFRESH_ALBUM_LIST-> {
                    // Do nothing, this action is for launching remote sync
                }
                Action.ACTION_UPDATE_ALBUM_BGM-> {
                    val localFile = File(localRootFolder, action.fileName)
                    if (localFile.exists()) webDav.upload(localFile, "$resourceRoot/${Uri.encode(action.folderName)}/${BGM_FILENAME_ON_SERVER}", action.folderId, application)
                }
                Action.ACTION_DELETE_ALBUM_BGM-> {
                    webDav.delete("$resourceRoot/${Uri.encode(action.folderName)}/${BGM_FILENAME_ON_SERVER}")
                }
            }

            // TODO: Error retry strategy, directory etag update, etc.
            actionRepository.delete(action)
        }
    }

    private fun syncRemoteChanges() {
        //Log.e(">>>>>>>>**", "sync remote changes")
        val changedAlbums = mutableListOf<Album>()
        val remoteAlbumIds = arrayListOf<String>()

        // Merge changed and/or new album from server
        var localAlbum: List<Album>
        var hidden: Boolean

        // Create a changed album list, including all albums modified or created on server except newly created hidden ones
        webDav.list(resourceRoot, OkHttpWebDav.FOLDER_CONTENT_DEPTH).drop(1).forEach { remoteAlbum ->     // Drop the first one in the list, which is the parent folder itself
            if (remoteAlbum.isFolder) {
                // Collecting remote album ids, including hidden albums, for deletion syncing
                remoteAlbumIds.add(remoteAlbum.fileId)
                hidden = remoteAlbum.name.startsWith('.')

                localAlbum = albumRepository.getThisAlbumList(remoteAlbum.fileId)
                if (localAlbum.isNotEmpty()) {
                    // We have hit in local table, which means it's a existing album
                    // This list will have 1 item only
                    if (localAlbum[0].eTag != remoteAlbum.eTag) {
                        // eTag mismatched, this album changed on server, could be name changed (hidden state toggled) plus other changes

                        if (hidden) {
                            // Sync name change for hidden album and/or hide operation done on server
                            if (localAlbum[0].name != remoteAlbum.name) albumRepository.changeName(remoteAlbum.fileId, remoteAlbum.name)
                        }
                        else changedAlbums.add(
/*
                            Album(
                                id = remoteAlbum.fileId,             // Either local or remote version is fine
                                name = remoteAlbum.name,               // Use remote version, since it might be changed on server
                                startDate = localAlbum[0].startDate,        // Preserve local data
                                endDate = localAlbum[0].endDate,          // Preserve local data
                                cover = localAlbum[0].cover,            // Preserve local data
                                coverBaseline = localAlbum[0].coverBaseline,    // Preserve local data
                                coverWidth = localAlbum[0].coverWidth,       // Preserve local data
                                coverHeight = localAlbum[0].coverHeight,      // Preserve local data
                                remoteAlbum.modified,           // Use remote version
                                localAlbum[0].sortOrder,        // Preserve local data
                                remoteAlbum.eTag,               // Use remote eTag for unhidden albums
                                if (remoteAlbum.isShared) localAlbum[0].shareId or Album.SHARED_ALBUM else localAlbum[0].shareId and Album.SHARED_ALBUM.inv(),    // shareId's 1st bit denotes album shared status
                                1f,                  // Default to finished
                            )
*/
                            localAlbum[0].copy(
                                name = remoteAlbum.name,                // Use remote version, since it might be changed on server
                                lastModified = remoteAlbum.modified,
                                eTag = remoteAlbum.eTag,                // Use remote eTag for unhidden albums
                                shareId =                               // shareId's 1st bit denotes album shared status TODO should we enforce SHARED_ALBUM bit? it's actually determined by Share_With_Me now.
                                    if (remoteAlbum.isShared) localAlbum[0].shareId or Album.SHARED_ALBUM else localAlbum[0].shareId and Album.SHARED_ALBUM.inv(),
                                syncProgress = Album.SYNC_COMPLETED     // Make sure sync process set to finish for now
                            )
                        )
                    } else {
                        // Rename operation (including hidden state toggled) on server would not change item's own eTag, have to sync name change here
                        if (localAlbum[0].name != remoteAlbum.name) albumRepository.changeName(remoteAlbum.fileId, remoteAlbum.name)
                    }
                } else {
                    // Skip newly created hidden album on server, do not sync changes of it until it's un-hidden
                    if (hidden) return@forEach

                    // No hit on local, a new album from server, (make sure the 'cover' property is set to Album.NO_COVER, denotes a new album which will NOT be included in album list)
                    // Default album attribute set to "Remote" for any album not created by this device
                    changedAlbums.add(
/*
                        Album(
                            remoteAlbum.fileId,
                            remoteAlbum.name,
                            LocalDateTime.MAX, LocalDateTime.MIN,
                            Album.NO_COVER,
                            0, 0, 0,
                            remoteAlbum.modified,
                            sp.getString(application.getString(R.string.default_sort_order_pref_key), Album.BY_DATE_TAKEN_ASC.toString())?.toInt() ?: Album.BY_DATE_TAKEN_ASC,
                            remoteAlbum.eTag,
                            Album.DEFAULT_FLAGS or Album.EXCLUDED_ALBUM,
                            1f,
                        )
*/
                        Album(
                            id = remoteAlbum.fileId,
                            name = remoteAlbum.name,
                            eTag = remoteAlbum.eTag,
                            lastModified = remoteAlbum.modified,
                            // Default album attribute set to "Remote" for any album not created by this device, and "Excluded" in album list since cover is not available yet
                            shareId = Album.DEFAULT_FLAGS or Album.EXCLUDED_ALBUM,
                            sortOrder = sp.getString(application.getString(R.string.default_sort_order_pref_key), Album.BY_DATE_TAKEN_ASC.toString())?.toInt() ?: Album.BY_DATE_TAKEN_ASC)
                    )
                    //Log.e(">>>>>>>>", "no hit, creating changedAlbum ${remoteAlbum.name}")
                }
            }
        }

        // Delete those albums not exist on server, happens when user delete album on the server. Should skip local added new albums, e.g. those with eTag column empty
        // Include hidden albums
        for (local in albumRepository.getAllAlbumIdAndETag()) {
            if (!remoteAlbumIds.contains(local.id) && local.eTag.isNotEmpty()) {
                albumRepository.deleteById(local.id)
                val allPhotoIds = photoRepository.getAllPhotoIdsByAlbum(local.id)
                photoRepository.deletePhotosByAlbum(local.id)
                allPhotoIds.forEach {
                    try { File(localRootFolder, it.id).delete() } catch (e: Exception) { e.printStackTrace() }
                    try { File(localRootFolder, it.name).delete() } catch(e: Exception) { e.printStackTrace() }
                }
                try { File(localRootFolder, "${local.id}.json").delete() } catch (e: Exception) { e.printStackTrace() }
                //Log.e(">>>>", "Deleted album: ${local.id}")
            }
        }

        // Syncing changes for each album in changed albums list
        if (changedAlbums.isNotEmpty()) {
            // Sync each changed album
            val changedPhotos = mutableListOf<Photo>()
            val remotePhotoIds = mutableListOf<String>()
            val metadataRetriever = MediaMetadataRetriever()
            var exifInterface: androidx.exifinterface.media.ExifInterface? = null

            for (changedAlbum in changedAlbums) {
                // Check network type on every loop, so that user is able to stop sync right in the middle
                checkConnection()

                val localPhotoETags = photoRepository.getETagsMap(changedAlbum.id)
                val localPhotoNames = photoRepository.getNamesMap(changedAlbum.id)
                val localPhotoNamesReverse = localPhotoNames.entries.stream().collect(Collectors.toMap({ it.value }) { it.key })
                var remotePhotoId: String
                //val metaFileName = "${changedAlbum.id}.json"
                val bgmFileName = "${changedAlbum.id}${BGMDialogFragment.BGM_FILE_SUFFIX}"
                var contentModifiedTime = LocalDateTime.MIN

                // Create changePhotos list
                //Log.e(">>>>>>>>>>", "syncing remote album ${changedAlbum.name}")
                val remotePhotoList = webDav.list("${resourceRoot}/${Uri.encode(changedAlbum.name)}", OkHttpWebDav.FOLDER_CONTENT_DEPTH).drop(1)
                remotePhotoList.forEach { remotePhoto ->
                    when {
                        // Media files with supported format which are not hidden
                        (remotePhoto.contentType.substringAfter("image/", "") in Tools.SUPPORTED_PICTURE_FORMATS || remotePhoto.contentType.startsWith("video/", true)) && !remotePhoto.name.startsWith('.') -> {
                            remotePhotoId = remotePhoto.fileId

                            // Collect remote photos ids for detection of server deletion
                            remotePhotoIds.add(remotePhotoId)

                            if (localPhotoETags[remotePhotoId] != remotePhoto.eTag) {
                                // Since null is comparable, this also matches newly created photo id from server, e.g. there is no such remotePhotoId in local table

                                if (File(localRootFolder, remotePhoto.name).exists()) {
                                    // If there is local file with remote photo's name, that means it's a local added photo which is now coming back from server.
                                    //Log.e("<><><>", "coming back now ${remotePhoto.name}")

                                    // Remove old media file at local
                                    try { File(localRootFolder, remotePhotoId).delete() } catch (e: Exception) { Log.e(">>>>Exception: ", e.stackTraceToString()) }
                                    // Rename image file name to fileid
                                    try { File(localRootFolder, remotePhoto.name).renameTo(File(localRootFolder, remotePhotoId)) } catch (e: Exception) { Log.e(">>>>Exception: ", e.stackTraceToString()) }
                                    // Handle video thumbnail file too
                                    if (remotePhoto.contentType.startsWith("video")) {
                                        try { File(localRootFolder, "${remotePhotoId}.thumbnail").delete() } catch (e: Exception) { Log.e(">>>>Exception: ", e.stackTraceToString()) }
                                        try { File(localRootFolder, "${remotePhoto.name}.thumbnail").renameTo(File(localRootFolder, "${remotePhotoId}.thumbnail")) } catch (e: Exception) { Log.e(">>>>Exception: ", e.stackTraceToString()) }
                                    }

                                    localPhotoNamesReverse[remotePhoto.name]?.apply {
                                        // Update it's id to the real fileId and also eTag now
                                        photoRepository.fixPhoto(this, remotePhotoId, remotePhoto.name, remotePhoto.eTag, remotePhoto.modified)
                                        // Taking care the cover
                                        // TODO: Condition race here, e.g. user changes this album's cover right at this very moment
                                        if (changedAlbum.cover == this) {
                                            //Log.e("=======", "fixing cover from ${changedAlbum.cover} to $remotePhotoId")
                                            albumRepository.fixCoverId(changedAlbum.id, remotePhotoId)
                                            changedAlbum.cover = remotePhotoId

                                            metaUpdatedNeeded.add(changedAlbum.name)
                                        }
                                    }
                                } else {
                                    // A new photo created on server, or an existing photo updated on server, or album attribute changed back to local, or on first sync with server
                                    changedPhotos.add(Photo(id = remotePhotoId, albumId = changedAlbum.id, name = remotePhoto.name, eTag = remotePhoto.eTag, mimeType = remotePhoto.contentType, dateTaken = LocalDateTime.now(), lastModified = remotePhoto.modified))
                                    //changedPhotos.add(Photo(remotePhotoId, changedAlbum.id, remotePhoto.name, remotePhoto.eTag, LocalDateTime.now(), remotePhoto.modified, 0, 0, remotePhoto.contentType, 0))
                                    //Log.e(">>>>>>>>>>", "creating changePhoto ${remotePhoto.name}")
                                }
                            } else if (localPhotoNames[remotePhotoId] != remotePhoto.name) {
                                // Rename operation on server would not change item's own eTag, have to sync name changes here. The positive side is avoiding fetching the actual
                                // file again from server
                                photoRepository.changeName(remotePhotoId, remotePhoto.name)

                                // Published album's content meta needs update
                                contentMetaUpdatedNeeded.add(changedAlbum.name)
                                if (remotePhoto.name == photoRepository.getPhotoName(changedAlbum.cover)) metaUpdatedNeeded.add(changedAlbum.name)
                            }
                        }
                        // Content meta file
                        remotePhoto.contentType == NCShareViewModel.MIME_TYPE_JSON && remotePhoto.name.startsWith(changedAlbum.id) -> {
                            // If there is a file name as "{albumId}.json". mark down latest meta (both album meta and conent meta) update timestamp,
                            contentModifiedTime = maxOf(contentModifiedTime, remotePhoto.modified)
                        }
                        // BGM file
                        (remotePhoto.contentType.startsWith("audio/") || remotePhoto.contentType == "application/octet-stream") && remotePhoto.name == BGM_FILENAME_ON_SERVER -> {
                            // Download album BGM file if file size is different to local's, since we don't cache this file's id, eTag at local, size is the most reliable way.
                            if (File("${localRootFolder}/${bgmFileName}").length() != remotePhoto.size) {
                                webDav.download("${resourceRoot}/${Uri.encode(changedAlbum.name)}/${BGM_FILENAME_ON_SERVER}", "$localRootFolder/${bgmFileName}", null)
                                albumRepository.fixBGM(changedAlbum.id, remotePhoto.fileId, remotePhoto.eTag)
                            }
                        }
                    }
                }

                // Syncing meta, deal with album cover, sort order
                if (changedAlbum.cover == Album.NO_COVER) {
                    //Log.e(">>>>>>>>", "create cover for new album ${changedAlbum.name}")
                    // New album created on server, cover not yet available

                    // Safety check, if this new album is empty, process next album
                    if (changedPhotos.size <= 0) continue

                    // New album from server, try downloading album meta file. If this album was created directly on server rather than from another client, there wil be no cover at all
                    downloadAlbumMeta(changedAlbum)?.apply {
                        changedAlbum.cover = cover
                        changedAlbum.coverBaseline = coverBaseline
                        changedAlbum.coverWidth = coverWidth
                        changedAlbum.coverHeight = coverHeight
                        changedAlbum.coverFileName = coverFileName
                        changedAlbum.coverMimeType = coverMimeType
                        changedAlbum.coverOrientation = coverOrientation
                        changedAlbum.sortOrder = sortOrder

                        // Remove excluded flag since we have cover now, so that quick sync can happen
                        changedAlbum.shareId = changedAlbum.shareId and Album.EXCLUDED_ALBUM.inv()

                        // TODO This is needed when meta format changed from v1 to v2 on release 2.5.0 to restore existing cover, could be removed in future release
                        if (coverMimeType.isEmpty()) {
                            // A v1 meta file return which does not contain cover's mimetype information, try to get it from changePhotos list
                            changedPhotos.find { it.id == cover }?.let {
                                changedAlbum.coverMimeType = it.mimeType
                                changedAlbum.coverOrientation = it.orientation
                                metaUpdatedNeeded.add(changedAlbum.name)
                            }
                        }
                    } ?: run {
                        // If there has no meta, neither v1 nor v2, on server, create it at the end of syncing
                        //Log.e(">>>>>>>>>>>", "could not download meta file ${changedAlbum.id}.json of album  ${changedAlbum.name} from server")
                        metaUpdatedNeeded.add(changedAlbum.name)
                    }
                } else {
                    // Try to sync meta changes from other devices if this album exists on local device
                    val metaFileName = "${changedAlbum.id}.json"
                    remotePhotoList.find { it.name == metaFileName }?.let { remoteMeta->
                        //Log.e(">>>>>", "remote ${metaFileName} timestamp: ${remoteMeta.modified.toInstant(OffsetDateTime.now().offset).toEpochMilli()}")
                        //Log.e(">>>>>", "local ${metaFileName} timestamp: ${File("$localRootFolder/${metaFileName}").lastModified()}")
                        if (remoteMeta.modified.toInstant(OffsetDateTime.now().offset).toEpochMilli() - File("$localRootFolder/${metaFileName}").lastModified() > 180000) {
                            // If the delta of last modified timestamp of local and remote meta file is larger than 3 minutes, assume that it's a updated version from other devices, otherwise this is the same
                            // version of local. If more than one client update the cover during the same short period of less than 3 minutes, the last update will be the final, but all the other clients won't
                            // get updated cover setting, and if this album gets modified later, the cover setting will change!!
                            // TODO more proper way to handle conflict
                            downloadAlbumMeta(changedAlbum)?.apply {
                                //Log.e(">>>>>>>>>>>>>>>","downloaded ${changedAlbum.name}'s latest album meta json from server")
                                if (changedAlbum.coverMimeType.isNotEmpty()) {
                                    // Only sync with newer version of meta json
                                    changedAlbum.cover = cover
                                    changedAlbum.coverBaseline = coverBaseline
                                    changedAlbum.coverWidth = coverWidth
                                    changedAlbum.coverHeight = coverHeight
                                    changedAlbum.coverFileName = coverFileName
                                    changedAlbum.coverMimeType = coverMimeType
                                    changedAlbum.coverOrientation = coverOrientation
                                    changedAlbum.sortOrder = sortOrder
                                }
                            }
                        }
                    }
                }
                // If cover found in changed photo lists then move it to the top of the list so that we can download it and show album in album list asap in the following changedPhotos.forEachIndexed loop
                if (changedAlbum.cover != Album.NO_COVER) (changedPhotos.find { it.id == changedAlbum.cover })?.let { coverPhoto ->
                    changedPhotos.remove(coverPhoto)
                    changedPhotos.add(0, coverPhoto)
                }

                // Quick sync for "Remote" albums
                if (Tools.isRemoteAlbum(changedAlbum) && !Tools.isExcludedAlbum(changedAlbum) && changedPhotos.isNotEmpty()) {
                    //Log.e(">>>>>>>>>>", "album ${changedAlbum.name} is Remote and exists at local")
                    // If album is "Remote" and it's not a newly created album on server (denoted by cover equals to Album.NO_COVER), try syncing content meta instead of downloading, processing media file
                    if (changedAlbum.lastModified <= contentModifiedTime) {
                        //Log.e(">>>>>>>>>>", "content meta is latest, start quick syncing meta for album ${changedAlbum.name}")
                        // If content meta file modified time is not earlier than album folder modified time, there is no modification to this album done on server, safe to use content meta
                        val photoMeta = mutableListOf<Photo>()
                        var pId: String

                        webDav.getStream("$resourceRoot/${Uri.encode(changedAlbum.name)}/${changedAlbum.id}${NCShareViewModel.CONTENT_META_FILE_SUFFIX}", false, null).use { stream ->
                            val lespasJson = JSONObject(stream.bufferedReader().readText()).getJSONObject("lespas")
                            val version = try { lespasJson.getInt("version") } catch (e: JSONException) { 1 }
                            when {
                                // TODO Make sure later version of content meta file downward compatible
                                version >= 2 -> {
                                    val meta = lespasJson.getJSONArray("photos")
                                    for (i in 0 until meta.length()) {
                                        // Create photos by merging from content meta file and webDAV PROPFIND (eTag, lastModified are not available in content meta)
                                        // TODO: shall we update content meta to include eTag and lastModified?
                                        meta.getJSONObject(i).apply {
                                            pId = getString("id")
                                            changedPhotos.find { p -> p.id == pId }?.let {
                                                try {
                                                    getInt("orientation")
                                                } catch (e: JSONException) {
                                                    // Some client with version lower than 2.5.0 updated the content meta json file via function like adding photos to Joint Album
                                                    // We should quit quick sync, fall back to normal sync to that additoinal meta data can be retrieved
                                                    //Log.e(">>>>>>>>>>", "client lower than 2.5.0 updated content meta, quit quick sync")
                                                    contentMetaUpdatedNeeded.add(changedAlbum.name)
                                                    return@use
                                                }
                                                photoMeta.add(
                                                    Photo(
                                                        id = pId, albumId = changedAlbum.id, name = getString("name"), mimeType = getString("mime"),
                                                        eTag = it.eTag,
                                                        dateTaken = Instant.ofEpochSecond(getLong("stime")).atZone(ZoneId.systemDefault()).toLocalDateTime(), lastModified = it.lastModified,
                                                        width = getInt("width"), height = getInt("height"),
                                                        caption = getString("caption"),
                                                        orientation = getInt("orientation"),
                                                        latitude = getDouble("latitude"), longitude = getDouble("longitude"), altitude = getDouble("altitude"), bearing = getDouble("bearing"),
/*
                                                        id = pId, albumId = changedAlbum.id, name = getString("name"), mimeType = getString("mime"),
                                                        eTag = it.eTag,
                                                        dateTaken = Instant.ofEpochSecond(getLong("stime")).atZone(ZoneId.systemDefault()).toLocalDateTime(), lastModified = it.lastModified,
                                                        width = getInt("width"), height = getInt("height"),
*/
                                                    )
                                                )

                                                //Log.e(">>>>>>>>>>>>>>>>>>>>>>", "quick syncing new photo ${getString("name")} from server")

                                                // Maintain album start and end date
                                                with(photoMeta.last().dateTaken) {
                                                    if (this > changedAlbum.endDate) changedAlbum.endDate = this
                                                    if (this < changedAlbum.startDate) changedAlbum.startDate = this
                                                }

                                                changedPhotos.remove(it)
                                            }
                                        }
                                    }

                                    photoRepository.upsert(photoMeta)

                                    if (changedPhotos.isEmpty()) changedAlbum.shareId = changedAlbum.shareId and Album.EXCLUDED_ALBUM.inv()
                                }
                                else -> {
                                    // Version 1 content meta file, won't work for latest version quick sync, fall back to normal sync
                                    //Log.e(">>>>>>>>>>>>>>>>>>>>>>", "version 1 meta json found, quit quick sync")
                                }
                            }
                        }
                    } else {
                        //Log.e(">>>>>>>>>", "there are updates to album ${changedAlbum.name} done on server, quit quick sync")
                    }
                }

                // Fetch changed photo files, extract EXIF info, update Photo table
                changedPhotos.forEachIndexed { i, changedPhoto->
                    // Prepare the image file

/*
                    // changePhotos contain those file not exist at local, no need to do this now
                    // If image file with 'name' exists, replace the old file with this
                    localPhotoNames.getOrDefault(changedPhoto.id, changedPhoto.name).let { localImageFileName ->
                        if (File(localRootFolder, localImageFileName).exists()) {
                            try { File(localRootFolder, changedPhoto.id).delete() } catch (e: Exception) { Log.e(">>>>Exception: ", e.stackTraceToString()) }
                            try { File(localRootFolder, localImageFileName).renameTo(File(localRootFolder, changedPhoto.id)) } catch (e: Exception) { Log.e(">>>>Exception: ", e.stackTraceToString()) }
                            //Log.e(">>>>", "rename file $localImageFileName to ${changedPhoto.id}")
                        }
                    }

                    // Remove old video thumbnail if any, let ImageLoaderViewModel create a new one
                    if (changedPhoto.mimeType.startsWith("video")) try { File(localRootFolder, "${changedPhoto.id}.thumbnail").delete() } catch(e: Exception) { Log.e(">>>>Exception: ", e.stackTraceToString()) }
*/
                    // Check network type on every loop, so that user is able to stop sync right in the middle
                    checkConnection()

                    if (Tools.isRemoteAlbum(changedAlbum)) {
                        //Log.e(">>>>>>>>>>>>>>>>", "extracting meta remotely for photo ${changedPhoto.name}")
                        // If it's a Remote album, extract EXIF remotely, since EXIF locates before actual JPEG image stream, this might save some network bandwidth and time
                        if (changedPhoto.mimeType.startsWith("video", true)) {
                            try { metadataRetriever.setDataSource("${resourceRoot}/${Uri.encode(changedAlbum.name)}/${Uri.encode(changedPhoto.name)}", HashMap<String, String>().apply { this["Authorization"] = "Basic $token" })} catch (e: Exception) {}
                            exifInterface = null
                        } else {
                            webDav.getStream("$resourceRoot/${changedAlbum.name}/${changedPhoto.name}", false, null)?.use {
                                exifInterface = try { androidx.exifinterface.media.ExifInterface(it) } catch (e: Exception) { null }
                            }
                        }
                    } else {
                        // If it's a Local album, download image file from server and extract meta locally
                        webDav.download("$resourceRoot/${Uri.encode(changedAlbum.name)}/${Uri.encode(changedPhoto.name)}", "$localRootFolder/${changedPhoto.id}", null)
                        //Log.e(">>>>", "Downloaded ${changedPhoto.name}")

                        if (changedPhoto.mimeType.startsWith("video")) {
                            try { metadataRetriever.setDataSource("$localRootFolder/${changedPhoto.id}")} catch (e: Exception) {}
                            exifInterface = null
                        }
                        else exifInterface = try { androidx.exifinterface.media.ExifInterface("$localRootFolder/${changedPhoto.id}")} catch (e: Exception) { null }
                    }

                    with(Tools.getPhotoParams(metadataRetriever, exifInterface, if (Tools.isRemoteAlbum(changedAlbum)) "" else "$localRootFolder/${changedPhoto.id}", changedPhoto.mimeType, changedPhoto.name, keepOriginalOrientation = true)) {
                        // Preserve lastModified date from server if more accurate taken date can't be found (changePhoto.dateTaken is timestamped as when record created)
                        // In Tools.getPhotoParams(), if it can extract date from EXIF and filename, it will return the local media file creation date
                        changedPhoto.dateTaken = if (this.dateTaken >= changedPhoto.dateTaken) changedPhoto.lastModified else this.dateTaken
                        changedPhoto.width = this.width
                        changedPhoto.height = this.height
                        // If photo got rotated, mimetype will be changed to image/jpeg
                        changedPhoto.mimeType = this.mimeType
                        // Photo's original orientation is needed to display remote image in full format
                        changedPhoto.orientation = this.orientation
                        //changedPhoto.caption = this.caption
                        changedPhoto.latitude = this.latitude
                        changedPhoto.longitude = this.longitude
                        changedPhoto.altitude = this.altitude
                        changedPhoto.bearing = this.bearing
                    }

                    if (Tools.isRemoteAlbum(changedAlbum)) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && changedPhoto.mimeType.lowercase(Locale.getDefault()).run { this == "image/gif" || this == "image/webp" }) {
                            // Find out if it's animated GIF or WEBP
                            //Log.e(">>>>>>>>>>>>>", "need to download ${changedPhoto.name} to find out if it's animated")
                            webDav.getStream("$resourceRoot/${Uri.encode(changedAlbum.name)}/${Uri.encode(changedPhoto.name)}", false, null).use {
                                val d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(it.readBytes())))
                                changedPhoto.width = d.intrinsicWidth
                                changedPhoto.height = d.intrinsicHeight
                                if (d is AnimatedImageDrawable) changedPhoto.mimeType = "image/a${changedPhoto.mimeType.substringAfterLast('/')}"
                            }
                        } else {
                            if (changedPhoto.width == 0 && changedPhoto.mimeType.startsWith("image")) {
                                // If image resolution fetched from EXIF failed (for example, picture format don't support EXIF), we need to download the file from server
                                //Log.e(">>>>>>>>>>>>>", "need to download ${changedPhoto.name} to get resolution data")
                                webDav.getStream("$resourceRoot/${Uri.encode(changedAlbum.name)}/${Uri.encode(changedPhoto.name)}", false, null).use {
                                    BitmapFactory.Options().apply {
                                        inJustDecodeBounds = true
                                        BitmapFactory.decodeStream(it, null, this)
                                        changedPhoto.width = outWidth
                                        changedPhoto.height = outHeight
                                    }
                                }
                            }
                        }
                    }

                    // Update album's startDate, endDate fields
                    if (changedPhoto.dateTaken > changedAlbum.endDate) changedAlbum.endDate = changedPhoto.dateTaken
                    if (changedPhoto.dateTaken < changedAlbum.startDate) changedAlbum.startDate = changedPhoto.dateTaken

                    // update row when everything's fine. any thing that broke before this point will be captured by exception handler and will be worked on again in next round of sync
                    photoRepository.upsert(changedPhoto)

                    if (i == 0) {
                        // Time to show updated album in AlbumFragment
                        // If it's a new album without meta file, create default cover because width and height information are ready now
                        with(changedAlbum) {
                            if (cover == Album.NO_COVER) {
                                //Log.e(">>>>>>>>>>>>", "setting 1st photo in the list ${changedPhoto.name} to be the cover for new album ${changedAlbum.name}")
                                cover = changedPhoto.id
                                coverBaseline = (changedPhoto.height - (changedPhoto.width * 9 / 21)) / 2
                                coverWidth = changedPhoto.width
                                coverHeight = changedPhoto.height
                                coverFileName = changedPhoto.name
                                coverMimeType = changedPhoto.mimeType
                                coverOrientation = changedPhoto.orientation

                                metaUpdatedNeeded.add(this.name)
                            }
                        }

                        // Clear EXCLUDED bit so that album will show up in album list
                        changedAlbum.shareId = changedAlbum.shareId and Album.EXCLUDED_ALBUM.inv()

                        // eTag property should be Album.ETAG_NOT_YET_UPLOADED, means it's syncing, and setting sync progress to start value of 0f
                        albumRepository.upsert(changedAlbum.copy(eTag = Album.ETAG_NOT_YET_UPLOADED, syncProgress = 0f))
                    } else {
                        // Update sync status. AlbumFragment will show changes to user
                        albumRepository.updateAlbumSyncStatus(changedAlbum.id, (i + 1).toFloat() / changedPhotos.size, changedAlbum.startDate, changedAlbum.endDate)
                    }

                    // Finally, remove downloaded media file if this is a remote album (happens when adding photo to remote album on server or during app reinstall)
                    //if (Tools.isRemoteAlbum(changedAlbum)) try { File(localRootFolder, changedPhoto.id).delete() } catch (e: Exception) { Log.e(">>>>Exception: ", e.stackTraceToString()) }
                }

                // The above loop might take a long time to finish, during the process, user might already change cover or sort order by now, update it here
                if (changedPhotos.isNotEmpty()) {
                    with(albumRepository.getMeta(changedAlbum.id)) {
                        changedAlbum.sortOrder = this.sortOrder
                        changedAlbum.cover = this.cover
                        changedAlbum.coverBaseline = this.coverBaseline
                        changedAlbum.coverWidth = this.coverWidth
                        changedAlbum.coverHeight = this.coverHeight
                        changedAlbum.coverFileName = this.coverFileName
                        changedAlbum.coverMimeType = this.coverMimeType
                        changedAlbum.coverOrientation = coverOrientation
                    }

                    // Maintain album start and end date
                    with(photoRepository.getAlbumDuration(changedAlbum.id)) {
                        changedAlbum.startDate = first
                        changedAlbum.endDate = second
                    }

                    // There are photo changes, update meta
                    contentMetaUpdatedNeeded.add(changedAlbum.name)
                }

                // Every changed photos updated, we can commit changes to the Album table now. The most important column is "eTag", dictates the sync status
                //Log.e(">>>>>>>>>>>>>>>>>", "finish syncing album ${changedAlbum.name}")
                albumRepository.upsert(changedAlbum)

                // Delete those photos not exist on server (local photo id not in remote photo list and local photo's etag is not empty), happens when user delete photos on the server
                var deletion = false
                //localPhotoETags = photoRepository.getETagsMap(changedAlbum.id)
                for (localPhoto in localPhotoETags) {
                    if (localPhoto.value.isNotEmpty() && !remotePhotoIds.contains(localPhoto.key)) {
                        deletion = true
                        photoRepository.deleteById(localPhoto.key)
                        try { File(localRootFolder, localPhoto.key).delete() } catch (e: Exception) { e.printStackTrace() }
                    }
                }

                if (deletion) {
                    // Maintaining album cover and duration if deletion happened
                    val photosLeft = photoRepository.getAlbumPhotos(changedAlbum.id)
                    if (photosLeft.isNotEmpty()) {
                        albumRepository.getThisAlbum(changedAlbum.id).run {
                            startDate = photosLeft[0].dateTaken
                            endDate = photosLeft.last().dateTaken

                            photosLeft.find { it.id == this.cover } ?: run {
                                // If the last cover is deleted, use the first photo as default
                                cover = photosLeft[0].id
                                coverBaseline = (photosLeft[0].height - (photosLeft[0].width * 9 / 21)) / 2
                                coverWidth = photosLeft[0].width
                                coverHeight = photosLeft[0].height
                                coverFileName = photosLeft[0].name
                                coverMimeType = photosLeft[0].mimeType

                                metaUpdatedNeeded.add(changedAlbum.name)
                            }

                            albumRepository.update(this)
                        }

                        // Published album's content meta needs update
                        contentMetaUpdatedNeeded.add(changedAlbum.name)
                    } else {
                        // All photos under this album removed, delete album on both local and remote
                        albumRepository.deleteById(changedAlbum.id)
                        actionRepository.addAction(Action(null, Action.ACTION_DELETE_DIRECTORY_ON_SERVER, changedAlbum.id, changedAlbum.name, "", "", System.currentTimeMillis(), 1))
                        // Remove local meta file
                        try { File(localRootFolder, "${changedAlbum.id}.json").delete() } catch (e: Exception) { e.printStackTrace() }
                    }
                }

                // Recycle the list
                remotePhotoIds.clear()
                changedPhotos.clear()
            }

            metadataRetriever.release()
        }
    }

    private fun updateMeta() {
        mutableListOf<String>().apply { addAll(metaUpdatedNeeded) }.forEach { albumName->
            albumRepository.getAlbumByName(albumName)?.apply {
                //if (!cover.contains('.')) updateAlbumMeta(id, name, Cover(cover, coverBaseline, coverWidth, coverHeight), photoRepository.getPhotoName(cover), sortOrder)
                if (!cover.contains('.')) updateAlbumMeta(id, name, Cover(cover, coverBaseline, coverWidth, coverHeight, coverFileName, coverMimeType, coverOrientation), sortOrder)
            }

            // Maintain metaUpdatedNeeded set so that if any exception happened, those not updated yet can be saved into action database
            metaUpdatedNeeded.remove(albumName)
        }

        mutableListOf<String>().apply { addAll(contentMetaUpdatedNeeded) }.forEach { albumName->
            albumRepository.getAlbumByName(albumName)?.apply { updateContentMeta(id, name) }

            // Maintain metaUpdatedNeeded set so that if any exception happened, those not updated yet can be saved into action database
            contentMetaUpdatedNeeded.remove(albumName)
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
            @Suppress("DEPRECATION")
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
                            webDav.upload(
                                try {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.setRequireOriginal(ContentUris.withAppendedId(contentUri, cursor.getLong(idColumn))) else ContentUris.withAppendedId(contentUri, cursor.getLong(idColumn))
                                } catch (e: SecurityException) {
                                    ContentUris.withAppendedId(contentUri, cursor.getLong(idColumn))
                                } catch (e: UnsupportedOperationException) {
                                    ContentUris.withAppendedId(contentUri, cursor.getLong(idColumn))
                               },
                                "${dcimRoot}/${Uri.encode(relativePath, "/")}/${Uri.encode(fileName)}", mimeType, application.contentResolver, cursor.getLong(sizeColumn), application
                            )
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

    //private fun updateAlbumMeta(albumId: String, albumName: String, cover: Cover, coverFileName: String, sortOrder: Int): Boolean {
    private fun updateAlbumMeta(albumId: String, albumName: String, cover: Cover, sortOrder: Int): Boolean {
        try {
            val metaFileName = "${albumId}.json"
            val localFile = File(localRootFolder, metaFileName)

            // Need this file in phone
            //FileWriter("$localRootFolder/metaFileName").apply {
            localFile.writer().use {
                //it.write(String.format(ALBUM_META_JSON, cover.cover, coverFileName, cover.coverBaseline, cover.coverWidth, cover.coverHeight, sortOrder))
                it.write(String.format(ALBUM_META_JSON_V2, cover.cover, cover.coverFileName, cover.coverBaseline, cover.coverWidth, cover.coverHeight, cover.coverMimeType, cover.coverOrientation, sortOrder))
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
            webDav.getStream("$resourceRoot/${Uri.encode(album.name)}/${Uri.encode(metaFileName)}", false,null).reader().use { input->
                File(localRootFolder, metaFileName).writer().use { output ->
                    val content = input.readText()
                    output.write(content)

                    // Store meta info in meta data holder
                    val meta = JSONObject(content).getJSONObject("lespas")
                    val version = try { meta.getInt("version") } catch (e: JSONException) { 1 }
                    result = meta.getJSONObject("cover").run {
                        when {
                            // TODO Make sure later version of album meta file downward compatible
                            version >= 2 -> Meta(meta.getInt("sort"), getString("id"), getInt("baseline"), getInt("width"), getInt("height"), getString("filename"), getString("mimetype"), getInt("orientation"))
                            // Version 1 of album meta json
                            else -> Meta(meta.getInt("sort"), getString("id"), getInt("baseline"), getInt("width"), getInt("height"), getString("filename"), "", 0)
                        }
                    }
                    //Log.e(">>>>", "Downloaded meta file ${remoteAlbum.name}/${metaFileName}")
                }
            }
        }
        // TODO consolidate these exception handling codes
        catch (e: OkHttpWebDavException) { Log.e(">>>>OkHttpWebDavException: ${e.statusCode}", e.stackTraceString) }
        catch (e: FileNotFoundException) { Log.e(">>>>FileNotFoundException: meta file not exist", e.stackTraceToString())}
        catch (e: JSONException) { Log.e(">>>>JSONException: error parsing meta information", e.stackTraceToString())}
        catch (e: Exception) { e.printStackTrace() }

        return result
    }

    private fun updateContentMeta(albumId: String, albumName: String) {
        // TODO simply return if album by albumName doesn't exist
        //val albumId = id ?: (albumRepository.getAlbumByName(albumName)?.id ?: run { return })
        //var content = "{\"lespas\":{\"photos\":["
        var content = NCShareViewModel.PHOTO_META_HEADER
        photoRepository.getPhotoMetaInAlbum(albumId).forEach {
            //content += String.format(NCShareViewModel.PHOTO_META_JSON, it.id, it.name, it.dateTaken.toEpochSecond(OffsetDateTime.now().offset), it.mimeType, it.width, it.height)
            content += String.format(NCShareViewModel.PHOTO_META_JSON_V2, it.id, it.name, it.dateTaken.toEpochSecond(OffsetDateTime.now().offset), it.mimeType, it.width, it.height, it.orientation, it.caption, it.latitude, it.longitude, it.altitude, it.bearing)
        }
        content = content.dropLast(1) + "]}}"
        webDav.upload(content, "$resourceRoot/${Uri.encode(albumName)}/${albumId}${NCShareViewModel.CONTENT_META_FILE_SUFFIX}", NCShareViewModel.MIME_TYPE_JSON)
    }

    companion object {
        const val ACTION = "SYNC_ACTION"
        const val SYNC_LOCAL_CHANGES = 1
        const val SYNC_REMOTE_CHANGES = 2
        const val SYNC_BOTH_WAY = 3
        const val BACKUP_CAMERA_ROLL = 4
        const val SYNC_ALL = 7

        const val BGM_FILENAME_ON_SERVER = ".bgm"
        //const val ALBUM_META_JSON = "{\"lespas\":{\"cover\":{\"id\":\"%s\",\"filename\":\"%s\",\"baseline\":%d,\"width\":%d,\"height\":%d},\"sort\":%d}}"
        const val ALBUM_META_JSON_V2 = "{\"lespas\":{\"cover\":{\"id\":\"%s\",\"filename\":\"%s\",\"baseline\":%d,\"width\":%d,\"height\":%d,\"mimetype\":\"%s\",\"orientation\":%d},\"sort\":%d,\"version\":2}}"
    }
}