/*
 *   Copyright 2019 Jeffrey Liu (scubajeffrey@criptext.com)
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package site.leos.apps.lespas.sync

import android.accounts.Account
import android.accounts.AccountManager
import android.accounts.AuthenticatorException
import android.accounts.NetworkErrorException
import android.annotation.SuppressLint
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
import androidx.exifinterface.media.ExifInterface
import androidx.preference.PreferenceManager
import okhttp3.internal.http2.StreamResetException
import okio.IOException
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
import java.io.FileWriter
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.util.*
import java.util.stream.Collectors
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

class SyncAdapter @JvmOverloads constructor(private val application: Application, autoInitialize: Boolean, allowParallelSyncs: Boolean = false
) : AbstractThreadedSyncAdapter(application.baseContext, autoInitialize, allowParallelSyncs){
    private lateinit var webDav: OkHttpWebDav
    private lateinit var baseUrl: String
    private lateinit var hrefBase: String
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

    private val keySyncStatus = application.getString(R.string.sync_status_pref_key)
    private val keySyncStatusLocalAction = application.getString(R.string.sync_status_local_action_pref_key)
    private val keyBackupStatus = application.getString(R.string.cameraroll_backup_status_pref_key)

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

            reportStage(Action.SYNC_RESULT_FINISHED)
        } catch (e: OkHttpWebDavException) {
            Log.e(TAG, e.stackTraceString)
            when (e.statusCode) {
                400, 404, 405, 406, 410 -> {
                    // create file in non-existed folder, target not found, target readonly, target already existed, etc. should be skipped and move on to next action
                    //actionRepository.discardCurrentWorkingAction()
                    workingAction?.let { actionRepository.delete(it) } ?: run { syncResult.stats.numIoExceptions++ }
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
            Log.e(TAG, e.stackTraceToString())
        } catch (e: SocketTimeoutException) {
            syncResult.stats.numIoExceptions++
            Log.e(TAG, e.stackTraceToString())
        } catch (e: InterruptedIOException) {
            syncResult.stats.numIoExceptions++
            Log.e(TAG, e.stackTraceToString())
        } catch (e: UnknownHostException) {
            syncResult.stats.numIoExceptions++
            Log.e(TAG, e.stackTraceToString())
        } catch (e: ConnectException) {
            syncResult.stats.numIoExceptions++
            Log.e(TAG, e.stackTraceToString())
        } catch (e: SSLHandshakeException) {
            syncResult.stats.numIoExceptions++
            syncResult.delayUntil = (System.currentTimeMillis() / 1000) + 10 * 60       // retry 10 minutes later
            Log.e(TAG, e.stackTraceToString())
        } catch (e: SSLPeerUnverifiedException) {
            syncResult.stats.numIoExceptions++
            syncResult.delayUntil = (System.currentTimeMillis() / 1000) + 10 * 60       // retry 10 minutes later
            Log.e(TAG, e.stackTraceToString())
        } catch (e: AuthenticatorException) {
            syncResult.stats.numAuthExceptions++
            Log.e(TAG, e.stackTraceToString())
        } catch (e: IllegalArgumentException) {
            syncResult.hasSoftError()
            Log.e(TAG, e.stackTraceToString())
        } catch (e: IllegalStateException) {
            syncResult.hasSoftError()
            Log.e(TAG, e.stackTraceToString())
        } catch (e: NetworkErrorException) {
            syncResult.stats.numIoExceptions++
            Log.e(TAG, e.stackTraceToString())
        } catch (e: ConnectException) {
            syncResult.stats.numIoExceptions++
            Log.e(TAG, e.stackTraceToString())
        } catch (e:Exception) {
            Log.e(TAG, e.stackTraceToString())
        } finally {
            // Make sure meta get updated by adding them to action database
            metaUpdatedNeeded.forEach { actionRepository.addAction(Action(null, Action.ACTION_UPDATE_THIS_ALBUM_META, "", it, "", "", 0, 0)) }
            contentMetaUpdatedNeeded.forEach { actionRepository.addAction(Action(null, Action.ACTION_UPDATE_THIS_CONTENT_META, "", it, "", "", 0, 0)) }

            if (syncResult.stats.numIoExceptions > 0 || syncResult.stats.numAuthExceptions > 0) reportStage(Action.SYNC_RESULT_ERROR_GENERAL)
        }
    }

    private fun prepare(account: Account) {
        reportStage(Action.SYNC_STAGE_STARTED)

        // Check network type
        checkConnection()

        // If we don't have any album, clean up the local root folder, this is useful when upgrading to version 2.5.0 when local media files have to be deleted
        if (albumRepository.getAlbumTotal() == 0) {
            try { File(localRootFolder).deleteRecursively() } catch(_: Exception) {}
            try { File(localRootFolder).mkdir() } catch(_: Exception) {}
        }

        AccountManager.get(application).run {
            val userName = getUserData(account, context.getString(R.string.nc_userdata_username))
            baseUrl = getUserData(account, context.getString(R.string.nc_userdata_server))
            token = getUserData(account, application.getString(R.string.nc_userdata_secret))

            val davEndPoint = application.getString(R.string.dav_files_endpoint)
            hrefBase = "/${baseUrl.substringAfter("//").substringAfter("/")}${davEndPoint}${userName}"
            resourceRoot = "$baseUrl${davEndPoint}$userName${application.getString(R.string.lespas_base_folder_name)}"
            dcimRoot = "$baseUrl${application.getString(R.string.dav_files_endpoint)}${userName}/DCIM"
            localRootFolder = Tools.getLocalRoot(application)

            webDav = OkHttpWebDav(
                userName, token, baseUrl, getUserData(account, context.getString(R.string.nc_userdata_selfsigned)).toBoolean(),
                null,
                "LesPas_${application.getString(R.string.lespas_version)}",
                0,
            )
        }

        // Make sure lespas base directory is there, and it's really a nice moment to test server connectivity
        if (!webDav.isExisted(resourceRoot)) webDav.createFolder(resourceRoot)
    }

    private fun syncLocalChanges(pendingActions: List<Action>) {
        reportStage(Action.SYNC_STAGE_LOCAL)

        // Sync local changes, e.g., processing pending actions
        pendingActions.forEach { action ->
            // Save current action for deletion when some ignorable exceptions happen
            workingAction = action

            reportActionStatus(action.action, action.folderId, action.folderName, action.fileId, action.fileName, action.date)

            // Check network type on every loop, so that user is able to stop sync right in the middle
            checkConnection()

            // Don't try to do too many works here, as the local sync should be as simple as making several webdav calls, so that if any thing bad happen, we will be catched by
            // exceptions handling down below, and start again right here in later sync, e.g. atomic
            when (action.action) {
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
                        val normalMimeType = when(action.folderId){
                            "image/agif" -> "image/gif"
                            "image/awebp" -> "image/webp"
                            else -> action.folderId
                        }
                        with (webDav.upload(localFile, "$resourceRoot/${Uri.encode(action.folderName)}/${Uri.encode(action.fileName)}", normalMimeType, application)) {
                            // Nextcloud WebDAV PUT, MOVE, COPY return fileId and eTag
                            if (this.first.isNotEmpty() && this.second.isNotEmpty()) {
                                val newId = this.first.substring(0, 8).toInt().toString()   // remove leading 0s
                                var fixPreview = false

                                if ((action.retry and Album.REMOTE_ALBUM) == Album.REMOTE_ALBUM) {
                                    // If this is a remote album, remove the image file and video thumbnail
                                    try { localFile.delete() } catch (e: Exception) { e.printStackTrace() }
                                    try { File(localRootFolder, "${action.fileName}.thumbnail").delete() } catch (e: Exception) { e.printStackTrace() }

                                    // If it's modification rather than new creation (fileId is not the same as filename), we need to fetch new preview from server
                                    fixPreview = action.fileId != action.fileName
                                } else {
                                    // If it's a local album, rename image file name to fileId
                                    try { localFile.renameTo(File(localRootFolder, newId)) } catch (e: Exception) { e.printStackTrace() }
                                    // Rename video thumbnail file too
                                    if (action.folderId.startsWith("video")) try { File(localRootFolder, "${action.fileName}.thumbnail").renameTo(File(localRootFolder, "${newId}.thumbnail")) } catch (e: Exception) { e.printStackTrace() }
                                }

                                // Update photo's id to the real fileId and latest eTag now. When called from Snapseed Replace, newEtag is what needs to be updated
                                photoRepository.fixPhotoIdEtag(action.fileId, newId, this.second, fixPreview)

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

                Action.ACTION_DELETE_FILES_ON_SERVER -> {
                    webDav.delete("$resourceRoot/${Uri.encode(action.folderName)}/${Uri.encode(action.fileName)}")
                    contentMetaUpdatedNeeded.add(action.folderName)
                }

                Action.ACTION_DELETE_DIRECTORY_ON_SERVER -> {
                    webDav.delete("$resourceRoot/${Uri.encode(action.folderName)}")
                }

/*
                Action.ACTION_BATCH_DELETE_FILE_ON_SERVER -> {
                    // fileName: filenames separated by '|', all filenames are relative, folder start at 'lespas/', 'shared_with_me/' or 'DCIM/'
                    webDav.batchDelete(action.fileName.split('|'), baseUrl, hrefBase)
                }
*/

                Action.ACTION_DELETE_CAMERA_BACKUP_FILE -> {
                    webDav.delete("${resourceRoot.substringBeforeLast("/")}/${action.folderName}/${action.fileName}")
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

                    // Sync from server syncRemoteChanges() will detect the change and update content meta later
                }

                Action.ACTION_UPDATE_ALBUM_COVER -> {
                    // Property folderId holds id of the album needed meta update
                    // Property folderName has the name of the album, the name when this action fired, if there is a album renaming happen afterward, local database will only has the new name
                    albumRepository.getThisAlbum(action.folderId).apply {
                        updateAlbumMeta(id, action.folderName, Cover(cover, coverBaseline, coverWidth, coverHeight, coverFileName, coverMimeType, coverOrientation), sortOrder)

                        // Touch file to avoid re-download
                        try { File(localRootFolder, "${id}.json").setLastModified(System.currentTimeMillis() + 10000) } catch (e: Exception) { e.printStackTrace() }
                    }
                }

                Action.ACTION_ADD_FILES_TO_JOINT_ALBUM-> {
                    // Property folderId holds MIME type
                    // Property folderName holds joint album share path, start from Nextcloud server defined share path
                    // Property fileId holds string "joint album's id|dateTaken epoch milli second|mimetype|width|height|orientation|caption|latitude|longitude|altitude|bearing"
                    // Property fileName holds media file name
                    // Media file should locate in app's file folder
                    // Joint Album's content meta file will be downloaded in app's file folder, later Action.ACTION_UPDATE_JOINT_ALBUM_PHOTO_META will pick it up there and send it to server
                    val localFile = File(localRootFolder, action.fileName)
                    if (localFile.exists()) {
                        val normalMimeType = when(action.folderId){
                            "image/agif" -> "image/gif"
                            "image/awebp" -> "image/webp"
                            else -> action.folderId
                        }
                        try {
                            with(webDav.upload(localFile, "${resourceRoot.substringBeforeLast('/')}${Uri.encode(action.folderName, "/")}/${Uri.encode(action.fileName)}", normalMimeType, application)) {
                                logChangeToFile(action.fileId, this.first.substring(0, 8).toInt().toString(), action.fileName)

                                // No need to keep the media file, other user owns the album after all
                                localFile.delete()
                            }
                        } catch (e: OkHttpWebDavException) {
                            // WebDAV return 403 if file already existed in target folder
                            if (e.statusCode != 403) throw e
                        }
                    }
                }
                Action.ACTION_COPY_ON_SERVER, Action.ACTION_MOVE_ON_SERVER -> {
                    // folderId is source folder path, starts from 'lespas/' or 'shared_to_me_root/' or 'DCIM/
                    // folderName is target folder path, starts from 'lespas/' or 'shared_to_me_root/'
                    // fileId holds string "target album's id|dateTaken in milli second epoch|mimetype|width|height|orientation|caption|latitude|longitude|altitude|bearing"
                    // fileName is a string "file name|ture or false, whether it's joint album"

                    val fileName: String
                    val targetIsJointAlbum: Boolean
                    action.fileName.split('|').let {
                        fileName = it[0]
                        targetIsJointAlbum = it[1].toBoolean()
                    }
                    resourceRoot.substringBeforeLast('/').let { baseUrl ->
                        // webdav copy/move target file path will be sent in http call's header, need to be encoded here
                        try {
                            webDav.copyOrMove(action.action == Action.ACTION_COPY_ON_SERVER, "${baseUrl}/${action.folderId}/${fileName}", "${baseUrl}/${Uri.encode(action.folderName, "/")}/${Uri.encode(fileName.substringAfterLast("/"))}").run {
                                if (targetIsJointAlbum && action.fileId.isNotEmpty()) logChangeToFile(action.fileId, first.substring(0, 8).toInt().toString(), fileName)
                            }
                        } catch (e: OkHttpWebDavException) {
                            // WebDAV return 403 if file already existed in target folder
                            if (e.statusCode != 403) throw e
                        }
                    }
                }
                Action.ACTION_UPDATE_JOINT_ALBUM_PHOTO_META-> {
                    // Property folderId holds joint album's id
                    // Property folderName holds joint album share path, start from Nextcloud server defined share path

                    // TODO conflicting, some other users might change this publication's content, during this short period of time??
                    val updateLogFile = File(localRootFolder, "${action.folderId}${CHANGE_LOG_FILENAME_SUFFIX}")
                    val contentMetaUrl = "${resourceRoot.substringBeforeLast('/')}/${action.folderName}/${action.folderId}${CONTENT_META_FILE_SUFFIX}"

                    // Download Joint Album's latest content meta file, should skip http cache
                    val photos = mutableListOf<NCShareViewModel.RemotePhoto>().apply {
                        addAll(Tools.readContentMeta(webDav.getStream(contentMetaUrl, false, null), action.folderName))
                    }

                    try {
                        // Append change log
                        Tools.readContentMeta(updateLogFile.inputStream(), action.folderName).forEach { changeItem ->
                            // photo fileId should be unique
                            photos.firstOrNull { it.photo.id == changeItem.photo.id } ?: run { photos.add(changeItem) }
                        }

                        webDav.upload(Tools.remotePhotosToMetaJSONString(photos), contentMetaUrl, MIME_TYPE_JSON)

                        try { updateLogFile.delete() } catch (_: Exception) {}
                    }
                    catch(e: FileNotFoundException) {
                        // If somehow update log file is missing, like when all photos added already existed in Joint Album, abandon this action
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
                Action.ACTION_PATCH_PROPERTIES -> {
                    // Property folderName holds target folder name, relative to user's home folder
                    // Property fileName holds target file name
                    // Property fileId holds patch payload
                    //Log.e(TAG, "patching ${resourceRoot.substringBeforeLast('/')}${action.folderName}${action.fileName} ${action.fileId}")
                    webDav.patch("${resourceRoot.substringBeforeLast('/')}${action.folderName}${action.fileName}", action.fileId)
                }
            }

            // TODO: Error retry strategy, directory etag update, etc.
            actionRepository.delete(action)
        }

        workingAction = null

        // Clear action reporting preference
        reportActionStatus(Action.ACTION_FINISHED, " ", " ", " ", " ", System.currentTimeMillis())
    }

    private fun logChangeToFile(meta: String, newFileId: String, fileName: String,) {
        val metaFromAction = meta.split('|')
        val logFile = File(localRootFolder, "${metaFromAction[0]}${CHANGE_LOG_FILENAME_SUFFIX}")

        try {
            mutableListOf<NCShareViewModel.RemotePhoto>().apply {
                if (logFile.exists()) logFile.inputStream().use { addAll(Tools.readContentMeta(it, "")) }
                else logFile.createNewFile()

                val date = try { Tools.epochToLocalDateTime(metaFromAction[1].toLong()) } catch (e: Exception) { LocalDateTime.now() }
                add(
                    NCShareViewModel.RemotePhoto(
                        Photo(
                            id = newFileId, albumId = metaFromAction[0], name = fileName, eTag = "",
                            dateTaken = date, lastModified = date,
                            width = metaFromAction[3].toInt(), height = metaFromAction[4].toInt(),
                            mimeType = metaFromAction[2],
                            orientation = metaFromAction[5].toInt(), caption = metaFromAction[6],
                            latitude = metaFromAction[7].toDouble(), longitude = metaFromAction[8].toDouble(), altitude = metaFromAction[9].toDouble(), bearing = metaFromAction[10].toDouble(),
                        ), "", 0
                    )
                )

                FileWriter(logFile).let { file ->
                    file.write(Tools.remotePhotosToMetaJSONString(this))
                    file.close()
                }
            }
        } catch (e: Exception) {
            // Log replay is now base on best effort, don't halt the sync process
        }
    }

    private fun syncRemoteChanges() {
        //Log.e(TAG, "sync remote changes")
        reportStage(Action.SYNC_STAGE_REMOTE)

        val changedAlbums = mutableListOf<Album>()
        val remoteAlbumIds = arrayListOf<String>()

        // Merge changed and/or new album from server
        var localAlbum: List<Album>
        var hidden: Boolean

        // Create a changed album list, including all albums modified or created on server except newly created hidden ones
        reportActionStatus(Action.ACTION_COLLECT_REMOTE_CHANGES, " ", " ", " ", " ", System.currentTimeMillis())
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
                        // eTag mismatched, this album changed on server, could be name changed (hidden state toggled) plus others

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
                                name = remoteAlbum.name,                // Use remote version, since it might be changed on server or hidden state toggled
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
                    //if (hidden) return@forEach

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
                    //Log.e(TAG, "no hit, creating changedAlbum ${remoteAlbum.name}")
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
                //Log.e(TAG, "Deleted album: ${local.id}")
            }
        }

        // Syncing changes for each album in changed albums list
        if (changedAlbums.isNotEmpty()) {
            // Sync each changed album
            val changedPhotos = mutableListOf<Photo>()
            val remotePhotoIds = mutableListOf<String>()
            val metadataRetriever = MediaMetadataRetriever()
            var exifInterface: ExifInterface?

            for (changedAlbum in changedAlbums) {
                // Check network type on every loop, so that user is able to stop sync right in the middle
                checkConnection()

                val isRemoteAlbum = Tools.isRemoteAlbum(changedAlbum)
                val localPhotoETags = photoRepository.getETagsMap(changedAlbum.id)
                val localPhotoNames = photoRepository.getNamesMap(changedAlbum.id)
                val localPhotoNamesReverse = localPhotoNames.entries.stream().collect(Collectors.toMap({ it.value }) { it.key })
                var remotePhotoId: String
                //val metaFileName = "${changedAlbum.id}.json"
                val bgmFileName = "${changedAlbum.id}${BGMDialogFragment.BGM_FILE_SUFFIX}"
                var contentModifiedTime = LocalDateTime.MIN

                // Create changePhotos list
                //Log.e(TAG, "syncing remote album ${changedAlbum.name}")
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
                                    try { File(localRootFolder, remotePhotoId).delete() } catch (e: Exception) { Log.e(TAG, e.stackTraceToString()) }
                                    // Rename image file name to fileid
                                    try { File(localRootFolder, remotePhoto.name).renameTo(File(localRootFolder, remotePhotoId)) } catch (e: Exception) { Log.e(TAG, e.stackTraceToString()) }
                                    // Handle video thumbnail file too
                                    if (remotePhoto.contentType.startsWith("video")) {
                                        try { File(localRootFolder, "${remotePhotoId}.thumbnail").delete() } catch (e: Exception) { Log.e(TAG, e.stackTraceToString()) }
                                        try { File(localRootFolder, "${remotePhoto.name}.thumbnail").renameTo(File(localRootFolder, "${remotePhotoId}.thumbnail")) } catch (e: Exception) { Log.e(TAG, e.stackTraceToString()) }
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
                                    //Log.e(TAG, "creating changePhoto ${remotePhoto.name}")
                                }
                            } else if (localPhotoNames[remotePhotoId] != remotePhoto.name) {
                                // Rename operation on server would not change item's own eTag, have to sync name changes here. The positive side is avoiding fetching the actual file again from server
                                photoRepository.changeName(remotePhotoId, remotePhoto.name)
                                // Album content meta needs update
                                contentMetaUpdatedNeeded.add(changedAlbum.name)

                                // Parsing new filename for dataTaken string of yyyyMMddHHmmss or yyyyMMdd_HHmmss
                                Tools.parseDateFromFileName(remotePhoto.name)?.let { photoRepository.updateDateTaken(remotePhotoId, it) }

                                // If album's cover's filename changed on server
                                if (remotePhotoId == changedAlbum.cover) {
                                    albumRepository.changeCoverFileName(changedAlbum.id, remotePhoto.name)
                                    changedAlbum.coverFileName = remotePhoto.name
                                    metaUpdatedNeeded.add(changedAlbum.name)
                                }
                                //if (remotePhoto.name == photoRepository.getPhotoName(changedAlbum.cover)) metaUpdatedNeeded.add(changedAlbum.name)
                            }
                        }
                        // Content meta file
                        remotePhoto.contentType == MIME_TYPE_JSON && remotePhoto.name.startsWith(changedAlbum.id) -> {
                            // If there is a file name as "{albumId}.json" or "{albumId}-content.json". mark down latest meta (both album meta and conent meta) update timestamp,
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

                // Recreate metadata files on server if they are missing
                remotePhotoList.find { it.name == "${changedAlbum.id}.json" } ?: run { metaUpdatedNeeded.add(changedAlbum.name) }
                remotePhotoList.find { it.name == "${changedAlbum.id}${CONTENT_META_FILE_SUFFIX}" } ?: run { contentMetaUpdatedNeeded.add(changedAlbum.name) }

                // *****************************************************
                // Syncing album meta, deal with album cover, sort order
                // *****************************************************
                if (changedAlbum.cover == Album.NO_COVER) {
                    //Log.e(TAG, "create cover for new album ${changedAlbum.name}")
                    // New album created on server, cover not yet available
                    reportActionStatus(Action.ACTION_CREATE_ALBUM_FROM_SERVER, changedAlbum.name, " ", " ", " ", System.currentTimeMillis())

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
                        //Log.e(TAG, "could not download meta file ${changedAlbum.id}.json of album  ${changedAlbum.name} from server")
                        metaUpdatedNeeded.add(changedAlbum.name)
                    }
                } else {
                    reportActionStatus(Action.ACTION_UPDATE_ALBUM_FROM_SERVER, changedAlbum.name, " ", " ", " ", System.currentTimeMillis())

                    // Try to sync meta changes from other devices if this album exists on local device
                    val metaFileName = "${changedAlbum.id}.json"
                    remotePhotoList.find { it.name == metaFileName }?.let { remoteMeta->
                        //Log.e(TAG, "remote ${metaFileName} timestamp: ${remoteMeta.modified.toInstant(OffsetDateTime.now().offset).toEpochMilli()}")
                        //Log.e(TAG, "local ${metaFileName} timestamp: ${File("$localRootFolder/${metaFileName}").lastModified()}")
                        if (remoteMeta.modified.toInstant(OffsetDateTime.now().offset).toEpochMilli() - File("$localRootFolder/${metaFileName}").lastModified() > 180000) {
                            // If the delta of last modified timestamp of local and remote meta file is larger than 3 minutes, assume that it's a updated version from other devices, otherwise this is the same
                            // version of local. If more than one client update the cover during the same short period of less than 3 minutes, the last update will be the final, but all the other clients won't
                            // get updated cover setting, and if this album gets modified later, the cover setting will change!!
                            // TODO more proper way to handle conflict
                            downloadAlbumMeta(changedAlbum)?.apply {
                                //Log.e(TAG,"downloaded ${changedAlbum.name}'s latest album meta json from server")
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

                //*******************************
                // Quick sync for "Remote" albums
                //*******************************
                if (isRemoteAlbum && !Tools.isExcludedAlbum(changedAlbum) && changedPhotos.isNotEmpty()) {
                    //Log.e(TAG, "album ${changedAlbum.name} is Remote and exists at local")
                    // If album is "Remote" and it's not a newly created album on server (denoted by cover equals to Album.NO_COVER), try syncing content meta instead of downloading, processing media file
                    if (changedAlbum.lastModified <= contentModifiedTime) {
                        //Log.e(TAG, "content meta is latest, start quick syncing meta for album ${changedAlbum.name}")
                        // If content meta file modified time is not earlier than album folder modified time, there is no modification to this album done on server, safe to use content meta
                        val photoMeta = mutableListOf<Photo>()
                        var pId: String

                        try {
                            webDav.getStream("$resourceRoot/${Uri.encode(changedAlbum.name)}/${changedAlbum.id}${CONTENT_META_FILE_SUFFIX}", false, null).use { stream ->
                                val lespasJson = JSONObject(stream.bufferedReader().readText()).getJSONObject("lespas")
                                val version = try {
                                    lespasJson.getInt("version")
                                } catch (e: JSONException) {
                                    1
                                }
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
                                                        //Log.e(TAG, "client lower than 2.5.0 updated content meta, quit quick sync")
                                                        contentMetaUpdatedNeeded.add(changedAlbum.name)
                                                        return@use
                                                    }
                                                    photoMeta.add(
                                                        Photo(
                                                            id = pId, albumId = changedAlbum.id, name = getString("name"), mimeType = getString("mime"),
                                                            eTag = it.eTag,
                                                            dateTaken = try { Tools.epochToLocalDateTime(getLong("stime"))} catch (e: Exception) { LocalDateTime.now() }, lastModified = it.lastModified,
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

                                                    //Log.e(TAG, "quick syncing new photo ${getString("name")} from server")

                                                    // Maintain album start and end date
                                                    with(photoMeta.last().dateTaken) {
                                                        if (this > changedAlbum.endDate) changedAlbum.endDate = this
                                                        if (this < changedAlbum.startDate) changedAlbum.startDate = this
                                                    }

                                                    // Meta data is available, no need to download it
                                                    changedPhotos.remove(it)
                                                }
                                            }
                                        }

                                        photoRepository.upsert(photoMeta)

                                        // If all newly added photos' meta data are available in content meta file (this is the case when photos were added by another client), we can reveal the album in list now.
                                        if (changedPhotos.isEmpty()) changedAlbum.shareId = changedAlbum.shareId and Album.EXCLUDED_ALBUM.inv()
                                    }
                                    else -> {
                                        // Version 1 content meta file, won't work for latest version quick sync, fall back to normal sync
                                        // Should mark content meta update here, since older client might change json file even without modified any content, like when publish an album
                                        contentMetaUpdatedNeeded.add(changedAlbum.name)
                                    }
                                }
                            }
                        } catch (e: OkHttpWebDavException) {
                            // If content meta file is not available, quit quick sync
                            if (e.statusCode == 404) contentMetaUpdatedNeeded.add(changedAlbum.name)
                            else throw e
                        } catch (e: JSONException) {
                            // JSON parsing error, quit quick sync
                            contentMetaUpdatedNeeded.add(changedAlbum.name)
                        }
                    } else {
                        // There are updates done on server, quit quick sync
                        contentMetaUpdatedNeeded.add(changedAlbum.name)
                    }
                }

                //*****************************************************************
                // Fetch changed photo files, extract EXIF info, update Photo table
                //*****************************************************************
                changedPhotos.forEachIndexed { i, changedPhoto->
                    // Check network type on every loop, so that user is able to stop sync right in the middle
                    checkConnection()

                    if (isRemoteAlbum) {
                        //Log.e(TAG, "extracting meta remotely for photo ${changedPhoto.name}")
                        // If it's a Remote album, extract EXIF remotely, since EXIF locates before actual JPEG image stream, this might save some network bandwidth and time
                        if (changedPhoto.mimeType.startsWith("video", true)) {
                            try { metadataRetriever.setDataSource("${resourceRoot}/${Uri.encode(changedAlbum.name)}/${Uri.encode(changedPhoto.name)}", HashMap<String, String>().apply { this["Authorization"] = "Basic $token" })} catch (_: Exception) {}
                            exifInterface = null
                        } else {
                            webDav.getStream("$resourceRoot/${changedAlbum.name}/${changedPhoto.name}", false, null).use {
                                exifInterface = try { ExifInterface(it) } catch (e: Exception) { null }
                            }
                        }
                    } else {
                        // If it's a Local album, download image file from server and extract meta locally
                        webDav.download("$resourceRoot/${Uri.encode(changedAlbum.name)}/${Uri.encode(changedPhoto.name)}", "$localRootFolder/${changedPhoto.id}", null)
                        //Log.e(TAG, "Downloaded ${changedPhoto.name}")

                        if (changedPhoto.mimeType.startsWith("video")) {
                            try { metadataRetriever.setDataSource("$localRootFolder/${changedPhoto.id}")} catch (_: Exception) {}
                            exifInterface = null
                        }
                        else exifInterface = try { ExifInterface("$localRootFolder/${changedPhoto.id}")
                        } catch (e: Exception) { null }
                    }

                    with(Tools.getPhotoParams(metadataRetriever, exifInterface, if (isRemoteAlbum) "" else "$localRootFolder/${changedPhoto.id}", changedPhoto.mimeType, changedPhoto.name, keepOriginalOrientation = isRemoteAlbum)) {
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

                    if (isRemoteAlbum) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && changedPhoto.mimeType.lowercase(Locale.getDefault()).run { this == "image/gif" || this == "image/webp" }) {
                            // Find out if it's animated GIF or WEBP
                            //Log.e(TAG, "need to download ${changedPhoto.name} to find out if it's animated")
                            webDav.getStream("$resourceRoot/${Uri.encode(changedAlbum.name)}/${Uri.encode(changedPhoto.name)}", false, null).use {
                                val d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(it.readBytes())))
                                changedPhoto.width = d.intrinsicWidth
                                changedPhoto.height = d.intrinsicHeight
                                if (d is AnimatedImageDrawable) changedPhoto.mimeType = "image/a${changedPhoto.mimeType.substringAfterLast('/')}"
                            }
                        } else {
                            if (changedPhoto.width == 0 && changedPhoto.mimeType.startsWith("image")) {
                                // If image resolution fetched from EXIF failed (for example, picture format don't support EXIF), we need to download the file from server
                                //Log.e(TAG, "need to download ${changedPhoto.name} to get resolution data")
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
                                //Log.e(TAG, "setting 1st photo in the list ${changedPhoto.name} to be the cover for new album ${changedAlbum.name}")
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

/*
                    // Finally, remove downloaded media file if this is a remote album (happens when adding photo to remote album on server or during app reinstall)
                    if (isRemoteAlbum) {
                        try { File(localRootFolder, changedPhoto.id).delete() } catch (e: Exception) {}
                        if (changedPhoto.mimeType.startsWith("video")) try { File(localRootFolder, "${changedPhoto.id}.thumbnail").delete() } catch (e: Exception) {}
                    }
*/
                }

                if (changedPhotos.isNotEmpty()) {
                    // New meta scanned at local, update content meta file
                    contentMetaUpdatedNeeded.add(changedAlbum.name)

                    // The above loop might take a long time to finish, during the process, user might already change cover or sort order by now, update it here
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
                        if (first < changedAlbum.startDate) changedAlbum.startDate = first
                        if (second > changedAlbum.endDate) changedAlbum.endDate = second
                    }
                }

                // Every changed photos updated, we can commit changes to the Album table now. The most important column is "eTag", dictates the sync status
                //Log.e(TAG, "finish syncing album ${changedAlbum.name}")
                albumRepository.upsert(changedAlbum)

                //*********************************************************************************************************************************************************************
                // Delete those photos not exist on server (local photo id not in remote photo list and local photo's etag is not empty), happens when user delete photos on the server
                //*********************************************************************************************************************************************************************
                var deletion = false
                //localPhotoETags = photoRepository.getETagsMap(changedAlbum.id)
                for (localPhoto in localPhotoETags) {
                    if (localPhoto.value.isNotEmpty() && !remotePhotoIds.contains(localPhoto.key)) {
                        deletion = true
                        photoRepository.deleteById(localPhoto.key)
                        try { File(localRootFolder, localPhoto.key).delete() } catch (_: Exception) {}
                        try { File(localRootFolder, "${localPhoto.key}.thumbnail").delete() } catch (_: Exception) {}
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

    @SuppressLint("ApplySharedPref")
    private fun backupCameraRoll() {
        // Backup camera roll if setting turn on
        if (sp.getBoolean(application.getString(R.string.cameraroll_backup_pref_key), false)) {
            reportStage(Action.SYNC_STAGE_BACKUP)

            // Make sure DCIM base directory is there
            if (!webDav.isExisted(dcimRoot)) webDav.createFolder(dcimRoot)

            // Make sure device subfolder is under DCIM/
            dcimRoot += "/${Tools.getDeviceModel()}"
            if (!webDav.isExisted(dcimRoot)) webDav.createFolder(dcimRoot)

            val contentUri = MediaStore.Files.getContentUri("external")
            val mediaMetadataRetriever = MediaMetadataRetriever()
            val cr = application.contentResolver

            val dateTakenColumnName = "datetaken"     // MediaStore.MediaColumns.DATE_TAKEN, hardcoded here since it's only available in Android Q or above
            val orientationColumnName = "orientation"     // MediaStore.MediaColumns.ORIENTATION, hardcoded here since it's only available in Android Q or above
            @Suppress("DEPRECATION")
            val pathSelection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Files.FileColumns.RELATIVE_PATH else MediaStore.Files.FileColumns.DATA
            val projection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                pathSelection,
                dateTakenColumnName,
                orientationColumnName,
                MediaStore.Files.FileColumns.DATE_ADDED,
                MediaStore.Files.FileColumns.MEDIA_TYPE,
                MediaStore.Files.FileColumns.MIME_TYPE,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns.WIDTH,
                MediaStore.Files.FileColumns.HEIGHT,
            )
            val selection = "(${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})" + " AND " +
                    "($pathSelection LIKE '%DCIM%')" + " AND " + "(${MediaStore.Files.FileColumns.DATE_ADDED} > ${sp.getLong(SettingsFragment.LAST_BACKUP, System.currentTimeMillis() / 1000)})"

            cr.query(contentUri, projection, selection, null, "${MediaStore.Files.FileColumns.DATE_ADDED} ASC"
            )?.use { cursor->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val pathColumn = cursor.getColumnIndexOrThrow(pathSelection)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
                val typeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.WIDTH)
                val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.HEIGHT)
                val dateTakenColumn = cursor.getColumnIndexOrThrow(dateTakenColumnName)
                val orientationColumn = cursor.getColumnIndexOrThrow(orientationColumnName)

                var relativePath: String
                var fileName: String
                var size: Long
                var mimeType: String
                var id: Long
                var photoUri: Uri
                var latitude: Double
                var longitude: Double
                var altitude: Double
                var bearing: Double
                var mDate: Long

                while(cursor.moveToNext()) {
                    //Log.e(TAG, "${cursor.getString(nameColumn)} ${cursor.getString(dateColumn)}  ${cursor.getString(pathColumn)} needs uploading")
                    // Check network type on every loop, so that user is able to stop sync right in the middle
                    checkConnection()

                    // Get uri, in Android Q or above, try getting original uri for meta data extracting
                    id = cursor.getLong(idColumn)
                    photoUri = ContentUris.withAppendedId(contentUri, id)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) try { photoUri = MediaStore.setRequireOriginal(ContentUris.withAppendedId(contentUri, id)) } catch (_: Exception) {}

                    fileName = cursor.getString(nameColumn)
                    size = cursor.getLong(sizeColumn)

                    reportBackupStatus(fileName, size, cursor.position, cursor.count)

                    relativePath = cursor.getString(pathColumn).substringAfter("DCIM/").substringBeforeLast('/')
                    mimeType = cursor.getString(typeColumn)
                    //Log.e(TAG, "relative path is $relativePath  server file will be ${dcimRoot}/${relativePath}/${fileName}")

                    // Indefinite while loop is for handling 404 error when folders needed to be created on server before hand
                    while(true) {
                        try {
                            webDav.upload(photoUri, "${dcimRoot}/${Uri.encode(relativePath, "/")}/${Uri.encode(fileName)}", mimeType, cr, size, application)
                            break
                        } catch (e: OkHttpWebDavException) {
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
                        } catch (e: StreamResetException) {
                            var subFolder = dcimRoot
                            relativePath.split("/").forEach {
                                subFolder += "/$it"
                                if (!webDav.isExisted(subFolder)) webDav.createFolder(subFolder)
                            }
                        }
                    }

                    // Try to get GPS data
                    latitude = Photo.GPS_DATA_UNKNOWN
                    longitude = Photo.GPS_DATA_UNKNOWN
                    altitude = Photo.GPS_DATA_UNKNOWN
                    bearing = Photo.GPS_DATA_UNKNOWN
                    if (Tools.hasExif(mimeType)) {
                        try {
                            cr.openInputStream(photoUri)?.use { stream ->
                                ExifInterface(stream).let { exif ->
                                    exif.latLong?.let { latLong ->
                                        latitude = latLong[0]
                                        longitude = latLong[1]
                                        altitude = exif.getAltitude(Photo.NO_GPS_DATA)
                                        bearing = Tools.getBearing(exif)
                                    } ?: run {
                                        // No GPS data
                                        latitude = Photo.NO_GPS_DATA
                                        longitude = Photo.NO_GPS_DATA
                                        altitude = Photo.NO_GPS_DATA
                                        bearing = Photo.NO_GPS_DATA
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                    } else if (mimeType.startsWith("video/")) {
                        try {
                            mediaMetadataRetriever.setDataSource(application, photoUri)
                            Tools.getVideoLocation(mediaMetadataRetriever).let {
                                latitude = it[0]
                                longitude = it[1]
                                altitude = Photo.NO_GPS_DATA
                                bearing = Photo.NO_GPS_DATA
                            }
                        } catch (_: SecurityException) {}
                    }

                    // Patch photo's DAV properties to accelerate future operations on camera roll archive
                    mDate = cursor.getLong(dateTakenColumn)
                    if (mDate == 0L) mDate = cursor.getLong(dateColumn) * 1000
                    try {
                        webDav.patch(
                            "${dcimRoot}/${relativePath}/${fileName}",
                            "<oc:${OkHttpWebDav.LESPAS_DATE_TAKEN}>" + mDate + "</oc:${OkHttpWebDav.LESPAS_DATE_TAKEN}>" +      // timestamp from Android MediaStore is in UTC timezone
                                    "<oc:${OkHttpWebDav.LESPAS_ORIENTATION}>" + cursor.getInt(orientationColumn) + "</oc:${OkHttpWebDav.LESPAS_ORIENTATION}>" +
                                    "<oc:${OkHttpWebDav.LESPAS_WIDTH}>" + cursor.getInt(widthColumn) + "</oc:${OkHttpWebDav.LESPAS_WIDTH}>" +
                                    "<oc:${OkHttpWebDav.LESPAS_HEIGHT}>" + cursor.getInt(heightColumn) + "</oc:${OkHttpWebDav.LESPAS_HEIGHT}>" +
                                    if (latitude == Photo.GPS_DATA_UNKNOWN) ""
                                    else
                                    "<oc:${OkHttpWebDav.LESPAS_LATITUDE}>" + latitude + "</oc:${OkHttpWebDav.LESPAS_LATITUDE}>" +
                                    "<oc:${OkHttpWebDav.LESPAS_LONGITUDE}>" + longitude + "</oc:${OkHttpWebDav.LESPAS_LONGITUDE}>" +
                                    "<oc:${OkHttpWebDav.LESPAS_ALTITUDE}>" + altitude + "</oc:${OkHttpWebDav.LESPAS_ALTITUDE}>" +
                                    "<oc:${OkHttpWebDav.LESPAS_BEARING}>" + bearing + "</oc:${OkHttpWebDav.LESPAS_BEARING}>"
                        )
                    } catch (_: Exception) {}

                    // Save latest timestamp after successful upload
                    sp.edit().apply {
                        putLong(SettingsFragment.LAST_BACKUP, cursor.getLong(dateColumn) + 1)
                        commit()
                    }
                }

                mediaMetadataRetriever.release()

                // Report finished status
                reportBackupStatus(" ", 0L, 0, 0)
            }
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

    //private fun updateAlbumMeta(albumId: String, albumName: String, cover: Cover, coverFileName: String, sortOrder: Int): Boolean {
    private fun updateAlbumMeta(albumId: String, albumName: String, cover: Cover, sortOrder: Int) {
        val metaFileName = "${albumId}.json"
        val localFile = File(localRootFolder, metaFileName)

        // Need this file in phone
        //FileWriter("$localRootFolder/metaFileName").apply {
        localFile.writer().use {
            //it.write(String.format(ALBUM_META_JSON, cover.cover, coverFileName, cover.coverBaseline, cover.coverWidth, cover.coverHeight, sortOrder))
            it.write(String.format(Locale.ROOT, ALBUM_META_JSON_V2, cover.cover, cover.coverFileName, cover.coverBaseline, cover.coverWidth, cover.coverHeight, cover.coverMimeType, cover.coverOrientation, sortOrder))
        }

        // If local meta json file created successfully
        webDav.upload(localFile, "$resourceRoot/${Uri.encode(albumName)}/${metaFileName}", MIME_TYPE_JSON, application)
    }

    private fun updateContentMeta(albumId: String, albumName: String) {
        webDav.upload(Tools.metasToJSONString(photoRepository.getPhotoMetaInAlbum(albumId)), "$resourceRoot/${Uri.encode(albumName)}/${albumId}${CONTENT_META_FILE_SUFFIX}", MIME_TYPE_JSON)
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
                    //Log.e(TAG, "Downloaded meta file ${remoteAlbum.name}/${metaFileName}")
                }
            }
        }
        // Catch exception here so that sync can go on if anything bad happen in this function, album meta file will be recreated if the current one is broken or missing
        catch (e: OkHttpWebDavException) { Log.e("$TAG OkHttpWebDavException: ${e.statusCode}", e.stackTraceString) }
        catch (e: FileNotFoundException) { Log.e("$TAG FileNotFoundException: meta file not exist", e.stackTraceToString())}
        catch (e: JSONException) { Log.e("$TAG JSONException: error parsing meta information", e.stackTraceToString())}
        catch (e: Exception) { e.printStackTrace() }

        return result
    }

    private fun checkConnection() {
        if (sp.getBoolean(wifionlyKey, true) && (application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered) {
            reportStage(Action.SYNC_RESULT_NO_WIFI)
            throw NetworkErrorException()
        }
    }

    private fun reportStage(stageId: Int) {
        sp.edit().run {
            putString(keySyncStatus, String.format(Locale.ROOT, SYNC_STATUS_MESSAGE_FORMAT, stageId, System.currentTimeMillis()))
            commit()
        }
    }

    private fun reportActionStatus(id: Int, s1: String, s2: String, s3: String, s4: String, timestamp: Long) {
        sp.edit().run {
            putString(keySyncStatusLocalAction, String.format(Locale.ROOT, SYNC_STATUS_LOCAL_ACTION_MESSAGE_FORMAT, id, s1, s2, s3, s4, timestamp))
            commit()
        }
    }

    private fun reportBackupStatus(name: String, size: Long, position: Int, total: Int) {
        sp.edit().run {
            System.currentTimeMillis().let { timestamp ->
                putString(keyBackupStatus, String.format(Locale.ROOT, SYNC_STATUS_BACKUP_MESSAGE_FORMAT, name, Tools.humanReadableByteCountSI(size), position + 1, total, timestamp))
                putString(keySyncStatusLocalAction, String.format(Locale.ROOT, SYNC_STATUS_LOCAL_ACTION_MESSAGE_FORMAT, Action.ACTION_BACKUP_FILE, name, " ", " ", " ", timestamp))
            }
            commit()
        }
    }

    companion object {
        const val ACTION = "SYNC_ACTION"
        const val SYNC_LOCAL_CHANGES = 1
        const val SYNC_REMOTE_CHANGES = 2
        const val SYNC_BOTH_WAY = 3
        const val BACKUP_CAMERA_ROLL = 4
        const val SYNC_ALL = 7

        const val BGM_FILENAME_ON_SERVER = ".bgm"
        const val CONTENT_META_FILE_SUFFIX = "-content.json"
        const val MIME_TYPE_JSON = "application/json"
        //const val ALBUM_META_JSON = "{\"lespas\":{\"cover\":{\"id\":\"%s\",\"filename\":\"%s\",\"baseline\":%d,\"width\":%d,\"height\":%d},\"sort\":%d}}"
        const val ALBUM_META_JSON_V2 = "{\"lespas\":{\"cover\":{\"id\":\"%s\",\"filename\":\"%s\",\"baseline\":%d,\"width\":%d,\"height\":%d,\"mimetype\":\"%s\",\"orientation\":%d},\"sort\":%d,\"version\":2}}"
        //const val PHOTO_META_JSON = "{\"id\":\"%s\",\"name\":\"%s\",\"stime\":%d,\"mime\":\"%s\",\"width\":%d,\"height\":%d},"
        const val PHOTO_META_JSON_V2 = "{\"id\":\"%s\",\"name\":\"%s\",\"stime\":%d,\"mime\":\"%s\",\"width\":%d,\"height\":%d,\"orientation\":%d,\"caption\":\"%s\",\"latitude\":%.5f,\"longitude\":%.5f,\"altitude\":%.5f,\"bearing\":%.5f},"
        // Future update of additional fields to content meta file should be added to header, leave photo list at the very last, so that individual photo meta can be added at the end
        const val PHOTO_META_HEADER = "{\"lespas\":{\"version\":2,\"photos\":["

        private const val CHANGE_LOG_FILENAME_SUFFIX = "-changelog"

        private const val SYNC_STATUS_MESSAGE_FORMAT = "%d|%d"
        private const val SYNC_STATUS_LOCAL_ACTION_MESSAGE_FORMAT = "%d``%s``%s``%s``%s``%d"
        private const val SYNC_STATUS_BACKUP_MESSAGE_FORMAT = "%s|%s|%d|%d|%d"

        private const val TAG = "SyncAdapter: "
    }
}