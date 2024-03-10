/*
 *   Copyright 2019 Jeffrey Liu (scubajeffrey@protonmail.ch)
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
import android.app.Application
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.ContentUris
import android.content.Context
import android.content.SyncResult
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.drawable.AnimatedImageDrawable
import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock.sleep
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import androidx.preference.PreferenceManager
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.internal.http2.StreamResetException
import okio.IOException
import org.json.JSONException
import org.json.JSONObject
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.Album
import site.leos.apps.lespas.album.AlbumRepository
import site.leos.apps.lespas.album.BGMDialogFragment
import site.leos.apps.lespas.album.Cover
import site.leos.apps.lespas.album.Meta
import site.leos.apps.lespas.album.MetaRescanDialogFragment
import site.leos.apps.lespas.gallery.GalleryFragment
import site.leos.apps.lespas.helper.OkHttpWebDav
import site.leos.apps.lespas.helper.OkHttpWebDavException
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.photo.PhotoRepository
import site.leos.apps.lespas.publication.NCShareViewModel
import site.leos.apps.lespas.settings.SettingsFragment
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileWriter
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.ObjectInputStream
import java.lang.Integer.max
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.Period
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import java.util.stream.Collectors
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.math.min

class SyncAdapter @JvmOverloads constructor(private val application: Application, autoInitialize: Boolean, allowParallelSyncs: Boolean = false
) : AbstractThreadedSyncAdapter(application.baseContext, autoInitialize, allowParallelSyncs){
    private lateinit var webDav: OkHttpWebDav
    private lateinit var baseUrl: String
    private lateinit var userBase: String
    private lateinit var lespasBase: String
    private lateinit var localBaseFolder: String
    private lateinit var token: String
    private var blogSiteName = ""
    private var userName = ""
    private val albumRepository = AlbumRepository(application)
    private val photoRepository = PhotoRepository(application)
    private val backupSettingRepository = BackupSettingRepository(application)
    private val actionRepository = ActionRepository(application)
    private val sp = PreferenceManager.getDefaultSharedPreferences(application)
    private val wifionlyKey = application.getString(R.string.wifionly_pref_key)
    private val metaUpdatedNeeded = mutableSetOf<String>()
    private val contentMetaUpdatedNeeded = mutableSetOf<String>()
    private val blogUpdateNeeded = mutableSetOf<String>()
    private var prefBackupNeeded = false
    private var archiveETagNeeded = false
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
/*
            if (sp.getBoolean(application.getString(R.string.cameraroll_backup_pref_key), false)) backup(dcimBase)
            if (sp.getBoolean(application.getString(R.string.pictures_backup_pref_key), false)) backup(picturesBase)
*/
            backupGallery()
            if (archiveETagNeeded) fetchArchiveETag()
            if (prefBackupNeeded) backupPreference()

            // Clear status counters
            syncResult.stats.clear()

            reportStage(Action.SYNC_RESULT_FINISHED)
        } catch (e: OkHttpWebDavException) {
            Log.e(TAG, e.stackTraceString)
            when (e.statusCode) {
                400, 404, 405, 406, 409, 410 -> {
                    // create file in non-existed folder, target not found, target readonly, target already existed, etc. should be skipped and move on to next action
                    // Caddy web server prefer 409 than 404 for 'target not found'
                    workingAction?.let { actionRepository.delete(it) } ?: run { syncResult.stats.numIoExceptions++ }
                }
                401, 403, 407 -> {
                    syncResult.stats.numAuthExceptions++
                }
/*
                409 -> {
                    syncResult.stats.numConflictDetectedExceptions++
                }
*/
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
            if (prefBackupNeeded) actionRepository.addAction(Action(null, Action.ACTION_BACKUP_PREFERENCE, "", "", "", "", 0, 0))

            if (syncResult.stats.numIoExceptions > 0 || syncResult.stats.numAuthExceptions > 0) reportStage(Action.SYNC_RESULT_ERROR_GENERAL)
        }
    }

    private fun prepare(account: Account) {
        reportStage(Action.SYNC_STAGE_STARTED)

        // Check network type
        checkConnection()

/*
        // If we don't have any album, clean up the local root folder, this is useful when upgrading to version 2.5.0 when local media files have to be deleted
        if (albumRepository.getAlbumTotal() == 0) {
            try { File(localBaseFolder).deleteRecursively() } catch(_: Exception) {}
            try { File(localBaseFolder).mkdir() } catch(_: Exception) {}
        }
*/

        AccountManager.get(application).run {
            userName = getUserData(account, application.getString(R.string.nc_userdata_username))
            baseUrl = getUserData(account, application.getString(R.string.nc_userdata_server))

            token = getUserData(account, application.getString(R.string.nc_userdata_secret))
            userBase = "${baseUrl}${application.getString(R.string.dav_files_endpoint)}${userName}"
            lespasBase = "${userBase}${Tools.getRemoteHome(application)}"
            localBaseFolder = Tools.getLocalRoot(application)
            blogSiteName = Tools.getBlogSiteName(getUserData(account, application.getString(R.string.nc_userdata_loginname)) ?: userName)

            webDav = OkHttpWebDav(userName, token, baseUrl, getUserData(account, application.getString(R.string.nc_userdata_selfsigned)).toBoolean(), getUserData(account, application.getString(R.string.nc_userdata_certificate)), "${localBaseFolder}/cache","LesPas_${application.getString(R.string.lespas_version)}",PreferenceManager.getDefaultSharedPreferences(application).getInt(SettingsFragment.CACHE_SIZE, 800),)
        }

        // Make sure lespas base directory is there, and it's really a nice moment to test server connectivity
        makeSureFolderExisted(lespasBase)
    }

    private fun makeSureFolderExisted(folder: String) {
        if (!webDav.isExisted(folder)) webDav.createFolder(folder)
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

                    val localFile = File(localBaseFolder, action.fileName)
                    if (localFile.exists()) {
                        val normalMimeType = when(action.folderId){
                            "image/agif" -> "image/gif"
                            "image/awebp" -> "image/webp"
                            else -> action.folderId
                        }
                        with (webDav.upload(localFile, "$lespasBase/${action.folderName}/${action.fileName}", normalMimeType, application)) {
                            // Nextcloud WebDAV PUT, MOVE, COPY return fileId and eTag
                            if (this.first.isNotEmpty() && this.second.isNotEmpty()) {
                                val newId = this.first.substring(0, 8).toInt().toString()   // remove leading 0s
                                var fixPreview = false

                                if ((action.retry and Album.REMOTE_ALBUM) == Album.REMOTE_ALBUM) {
                                    // If this is a remote album, remove the image file and video thumbnail
                                    try { localFile.delete() } catch (e: Exception) { e.printStackTrace() }
                                    try { File(localBaseFolder, "${action.fileName}.thumbnail").delete() } catch (e: Exception) { e.printStackTrace() }

                                    // If it's modification rather than new creation (fileId is not the same as filename), we need to fetch new preview from server
                                    fixPreview = action.fileId != action.fileName
                                } else {
                                    // If it's a local album, rename image file name to fileId
                                    try { localFile.renameTo(File(localBaseFolder, newId)) } catch (e: Exception) { e.printStackTrace() }
                                    // Rename video thumbnail file too
                                    if (action.folderId.startsWith("video")) try { File(localBaseFolder, "${action.fileName}.thumbnail").renameTo(File(localBaseFolder, "${newId}.thumbnail")) } catch (e: Exception) { e.printStackTrace() }
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
                    webDav.delete("$lespasBase/${action.folderName}/${action.fileName}")
                    contentMetaUpdatedNeeded.add(action.folderName)
                }

                Action.ACTION_DELETE_DIRECTORY_ON_SERVER -> {
                    webDav.delete("$lespasBase/${action.folderName}")
                }

/*
                Action.ACTION_BATCH_DELETE_FILE_ON_SERVER -> {
                    // fileName: filenames separated by '|', all filenames are relative, folder start at 'lespas/', 'shared_with_me/' or 'DCIM/'
                    webDav.batchDelete(action.fileName.split('|'), baseUrl, hrefBase)
                }
*/

                Action.ACTION_DELETE_FILE_IN_ARCHIVE -> {
                    // Property fileName holds the camera archive file's path, relative to archiveBase
                    webDav.delete("${userBase}/${action.fileName}")
                }
                Action.ACTION_FETCH_ARCHIVE_FOLDER_ETAG -> { archiveETagNeeded = true }

                Action.ACTION_ADD_DIRECTORY_ON_SERVER -> {
                    // Property folderId holds the fake album id
                    // Property folderName holds the album name
                    // Property fileName holds the cover photo id, it's not the correct fileId at this moment, will be fixed later
                    webDav.createFolder("$lespasBase/${action.folderName}").apply {
                        // Recreating the existing folder will return empty string
                        if (this.isNotEmpty()) this.substring(0, 8).toInt().toString().also { fileId ->
                            // fix album id for new album and photos create on local, put back the cover id in album row so that it will show up in album list
                            // mind that we purposely leave the eTag column empty
                            photoRepository.fixNewPhotosAlbumId(action.folderId, fileId)
                            albumRepository.fixNewLocalAlbumId(action.folderId, fileId, action.fileName)

                            // touch meta file
                            try { File("${localBaseFolder}/${fileId}.json").createNewFile() } catch (e: Exception) { e.printStackTrace() }

                            // Mark meta update later
                            metaUpdatedNeeded.add(action.folderName)
                        }
                    }
                }

                //Action.ACTION_MODIFY_ALBUM_ON_SERVER -> {}

                Action.ACTION_RENAME_DIRECTORY -> {
                    // Action's folderName property is the old name, fileName property is the new name
                    webDav.move("${lespasBase}/${action.folderName}", "${lespasBase}/${action.fileName}")
                    //albumRepository.changeName(action.folderId, action.fileName)
                }

                Action.ACTION_RENAME_FILE -> {
                    // Action's fileId property is the old name, fileName property is the new name
                    webDav.move("${lespasBase}/${action.folderName}/${action.fileId}", "${lespasBase}/${action.folderName}/${action.fileName}")

                    // Sync from server syncRemoteChanges() will detect the change and update content meta later
                }

                Action.ACTION_UPDATE_ALBUM_META -> {
                    // Property folderId holds id of the album needed meta update
                    // Property folderName has the name of the album, the name when this action fired, if there is a album renaming happen afterward, local database will only has the new name
                    albumRepository.getThisAlbum(action.folderId).apply {
                        updateAlbumMeta(id, action.folderName, Cover(cover, coverBaseline, coverWidth, coverHeight, coverFileName, coverMimeType, coverOrientation), sortOrder)

                        // Touch file to avoid re-download
                        try { File(localBaseFolder, "${id}.json").setLastModified(System.currentTimeMillis() + 10000) } catch (e: Exception) { e.printStackTrace() }
                    }
                }

                Action.ACTION_ADD_FILES_TO_JOINT_ALBUM-> {
                    // Property folderId holds MIME type
                    // Property folderName holds joint album share path, start from Nextcloud server defined share path
                    // Property fileId holds string "joint album's id|dateTaken epoch milli second|mimetype|width|height|orientation|caption|latitude|longitude|altitude|bearing"
                    // Property fileName holds media file name
                    // Media file should locate in app's file folder
                    // Joint Album's content meta file will be downloaded in app's file folder, later Action.ACTION_UPDATE_JOINT_ALBUM_PHOTO_META will pick it up there and send it to server

                    val localFile = File(localBaseFolder, action.fileName)
                    if (localFile.exists()) {
                        val normalMimeType = when(action.folderId){
                            "image/agif" -> "image/gif"
                            "image/awebp" -> "image/webp"
                            else -> action.folderId
                        }
                        try {
                            with(webDav.upload(localFile, "${userBase}/${action.folderName}/${action.fileName}", normalMimeType, application)) {
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
                    // folderId is source folder path, starts from lespasBase, dcimBase or share_to_me base
                    // folderName is target folder path, starts from lespasBase or share_to_me base
                    // fileId holds string "target album's id(only valid for Joint Album)|dateTaken in milli second epoch|mimetype|width|height|orientation|caption|latitude|longitude|altitude|bearing"
                    // fileName is a string "file name|ture or false, whether it's joint album|ture or false, whether it's remote album. fileName might contain subfolder name when the source is camera roll archive"

                    //Log.e(">>>>>>>>", "syncLocalChanges: ${action.fileName} ${action.folderId} ${action.folderName}")
                    val fileName: String
                    val targetIsJointAlbum: Boolean
                    val targetIsRemoteAlbum: Boolean
                    action.fileName.split('|').let {
                        fileName = it[0]
                        targetIsJointAlbum = it[1].toBoolean()
                        targetIsRemoteAlbum = it[2].toBoolean()
                    }
                    try {
                        webDav.copyOrMove(action.action == Action.ACTION_COPY_ON_SERVER, "${userBase}/${action.folderId}/${fileName}", "${userBase}/${action.folderName}/${fileName}").run {
                            if (action.fileId.isNotEmpty()) {
                                // If meta sent
                                val newId = this.first.substring(0, 8).toInt().toString()   // remove leading 0s

                                if (targetIsJointAlbum) {
                                    // If target is in joint album, try best effort group patching
                                    logChangeToFile(action.fileId, newId, fileName)
                                }
                                else {
                                    // Prepare media file
                                    var mediaFileDownloaded = true

                                    try {
                                        val metas = action.fileId.split('|')
                                        // If local image file exist, rename it
                                        File(localBaseFolder, action.fileName).let {
                                            if (it.exists()) {
                                                if (targetIsRemoteAlbum) {
                                                    // Delete local media file in target album is remote album
                                                    it.delete()
                                                    if (metas[2].startsWith("video")) File(localBaseFolder, "${fileName}.thumbnail").let { thumbnail -> if (thumbnail.exists()) thumbnail.delete() }
                                                }
                                                else {
                                                    // For local album, rename media file from filename to fileId
                                                    it.renameTo(File(localBaseFolder, newId))
                                                    if (metas[2].startsWith("video")) File(localBaseFolder, "${fileName}.thumbnail").let { thumbnail -> if (thumbnail.exists()) thumbnail.renameTo(File(localBaseFolder, "${newId}.thumbnail")) }
                                                }
                                            } else {
                                                // When source is remote album or other's publication, media file is not available locally
                                                // If target album is local, we should download it from server now. Except for images with orientation not in it's up-right position, they will be downloaded in syncRemoteChanges() so that it can be properly rotated
                                                if (!targetIsRemoteAlbum && (metas[2].startsWith("video") || try { metas[5].toInt() } catch (_: NumberFormatException) { 0 } == 0)) webDav.download("${userBase}/${action.folderName}/${fileName}", "$localBaseFolder/${newId}", null)
                                                else mediaFileDownloaded = false
                                            }
                                        }
                                    } catch (e: Exception) { e.printStackTrace() }

                                    // Update photo's id to the real fileId and latest eTag now
                                    // Some version of Nextcloud won't return 'oc-etag' header after webdav COPY or MOVE call, denote with ETAG_MISSING so that syncRemoteChange() will fill in the correct eTag later
                                    photoRepository.fixPhotoIdEtag(fileName, newId, if (mediaFileDownloaded) this.second.ifEmpty { ETAG_MISSING } else Photo.ETAG_FAKE, targetIsRemoteAlbum)
                                }
                            }
                        }
                    } catch (e: OkHttpWebDavException) {
                        if (e.statusCode != 403) {
                            // WebDAV return 403 if file already existed in target folder
                            throw e
                        }
                    }
                }
                Action.ACTION_UPDATE_JOINT_ALBUM_PHOTO_META-> {
                    // Property folderId holds joint album's id
                    // Property folderName holds joint album share path, start from Nextcloud server defined share path

                    // TODO conflicting, some other users might change this publication's content, during this short period of time??
                    val updateLogFile = File(localBaseFolder, "${action.folderId}${CHANGE_LOG_FILENAME_SUFFIX}")
                    val contentMetaUrl = "${userBase}/${action.folderName}/${action.folderId}${CONTENT_META_FILE_SUFFIX}"

                    // Download Joint Album's latest content meta file, should skip http cache
                    val photos = mutableListOf<NCShareViewModel.RemotePhoto>().apply {
                        addAll(Tools.readContentMeta(webDav.getStream(contentMetaUrl, false, null), action.folderName, useUTC = true))
                    }

                    try {
                        // Append change log
                        Tools.readContentMeta(updateLogFile.inputStream(), action.folderName, useUTC = true).forEach { changeItem ->
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
                    // Property folderName holds album's folder name
                    val localFile = File(localBaseFolder, action.fileName)
                    if (localFile.exists()) webDav.upload(localFile, "$lespasBase/${action.folderName}/${BGM_FILENAME_ON_SERVER}", action.folderId, application)
                }
                Action.ACTION_DELETE_ALBUM_BGM-> {
                    // Property folderName holds album's folder name
                    webDav.delete("$lespasBase/${action.folderName}/${BGM_FILENAME_ON_SERVER}")
                }
                Action.ACTION_PATCH_PROPERTIES -> {
                    // Property folderName holds target folder name, relative to user's home folder
                    // Property fileName holds target file name
                    // Property fileId holds patch payload

                    //Log.e(TAG, "patching ${resourceRoot.substringBeforeLast('/')}${action.folderName}${action.fileName} ${action.fileId}")
                    webDav.patch("${userBase}/${action.folderName}/${action.fileName}", action.fileId)
                }
                Action.ACTION_CREATE_BLOG_POST -> {
                    // Property folderId holds target folder Id
                    // Property folderName holds target folder name, for sync status reporting purpose
                    // Property fileId holds theme id
                    // Property fileName holds option flags, 1st bit 'includeSocial', 2nd bit 'includeCopyright'
                    if (createBlogSite()) {
                        updateBlogIndex()
                        createBlogPost(albumRepository.getThisAlbum(action.folderId), photoRepository.getPhotosForBlog(action.folderId), action.fileId)
                    } else {
                        // TODO
                    }
                }
                Action.ACTION_DELETE_BLOG_POST -> {
                    // Property folderId holds target folder Id
                    webDav.delete("${lespasBase}/${BLOG_CONTENT_FOLDER}/${action.folderId}.md")
                    webDav.delete("${lespasBase}/${BLOG_ASSETS_FOLDER}/${action.folderId}")
                }
                Action.ACTION_UPDATE_BLOG_SITE_TITLE -> { updateBlogIndex() }
                Action.ACTION_BACKUP_PREFERENCE -> { prefBackupNeeded = true }
                Action.ACTION_META_RESCAN -> {
                    // Property folderId holds target folder Id
                    // Property folderName holds target folder name
                    // Remove content meta file on server to prevent quick sync from running, hence meta rescan happens later
                    webDav.delete("${lespasBase}/${action.folderName}/${action.folderId}${CONTENT_META_FILE_SUFFIX}")
                }
            }

            actionRepository.delete(action)
        }

        workingAction = null

        // Clear action reporting preference
        reportActionStatus(Action.ACTION_FINISHED, " ", " ", " ", " ", System.currentTimeMillis())
    }

    private fun createBlogSite(): Boolean {
        var siteCreated = false

        // Find out if blog site already created
        var token = webDav.getCSRFToken("${baseUrl}${NCShareViewModel.CSRF_TOKEN_ENDPOINT}")
        if (token.first.isEmpty()) token = webDav.getCSRFToken("${baseUrl}${NCShareViewModel.CSRF_TOKEN_ENDPOINT_VARIANT}")
        webDav.getCallFactory().newCall(
            Request.Builder().url("${baseUrl}${NCShareViewModel.PICO_WEBSITES_ENDPOINT}").addHeader("requesttoken", token.first).addHeader("cookie", token.second).addHeader(OkHttpWebDav.NEXTCLOUD_OCSAPI_HEADER, "true").get().build()
        ).execute().use { response ->
            if (response.isSuccessful && Tools.collectBlogResult(response.body?.string())?.isNotEmpty() == true) siteCreated = true
        }

        //Log.e(">>>>>>>>", "createBlogSite: existing: $blogs")
        if (!siteCreated) {
            // Create blog site folder in lespas/
            webDav.createFolder("${lespasBase}/${BLOG_FOLDER}")

            // Create pico site
            token = webDav.getCSRFToken("${baseUrl}${NCShareViewModel.CSRF_TOKEN_ENDPOINT}")
            if (token.first.isEmpty()) webDav.getCSRFToken("${baseUrl}${NCShareViewModel.CSRF_TOKEN_ENDPOINT_VARIANT}")
            webDav.getCallFactory().newCall(Request.Builder()
                .url("${baseUrl}${NCShareViewModel.PICO_WEBSITES_ENDPOINT}")
                .addHeader("requesttoken", token.first).addHeader("cookie", token.second).addHeader(OkHttpWebDav.NEXTCLOUD_OCSAPI_HEADER, "true")
                .post(
                    FormBody.Builder()
                        .addEncoded("data[name]", "Les Pas")    // Site name asserted in Pico's lib/Model/Website.php, must be longer than 2 characters and not more than 255
                        .addEncoded("data[path]", "${Tools.getRemoteHome(application)}/${BLOG_FOLDER}")     // Site path is "__picoblog__" under user's lespas home
                        .addEncoded("data[site]", blogSiteName)     // user's login name is cached and used here
                        .addEncoded("data[theme]", "pico_lespas")   // The theme name must match what is installed in pico_cms administration screen, since pico_cms won't provide the way to manage this for non-administration account, it might break
                        .addEncoded("data[template]", "empty")      // We don't provide template
                        .build()
                ).build()
            ).execute().use { response ->
                siteCreated = response.isSuccessful

                if (siteCreated) {
                    // After site created, Pico return the full list of all sites created by the user, collectBlogResult will try to filter webistes not created by Les Pas
                    Tools.collectBlogResult(response.body?.string())?.forEach { blog ->
                        //Log.e(">>>>>>>>", "createBlogSite: blog id is ${blog.id}")
                        webDav.createFolder("${lespasBase}/${BLOG_CONTENT_FOLDER}")//.apply { Log.e(">>>>>>>>", "createBlogSite: created content folder: $this") }
                        webDav.createFolder("${lespasBase}/${BLOG_ASSETS_FOLDER}")//.apply { Log.e(">>>>>>>>", "createBlogSite: created assets folder: $this") }
                        PreferenceManager.getDefaultSharedPreferences(application).edit().run {
                            // Save blog site id for later use, like removing it
                            putString(SettingsFragment.PICO_BLOG_ID, blog.id)
                            putString(SettingsFragment.PICO_BLOG_FOLDER, blog.path)
                            apply()
                        }
                    }
                }
            }
        }

        return siteCreated
    }

    private fun createBlogPost(album: Album, blogPhotos: List<Photo>, themeId: String) {
        // Create subfolder in assets folder
        webDav.createFolder("${lespasBase}/${BLOG_ASSETS_FOLDER}/${album.id}")

        // If user choose Timeline theme but album is not sort by taken time, force sort order to Album.BY_DATE_TAKEN_ASC
        if (themeId == THEME_TIMELINE && album.sortOrder % 100 > Album.BY_DATE_TAKEN_DESC) album.sortOrder = Album.BY_DATE_TAKEN_ASC
        // Sort photos
        val photos = Tools.sortPhotos(blogPhotos, album.sortOrder)

        // If album's cover is video item, select the first image item as cover
        val cover: Photo
        var baseline: Int
        if (album.coverMimeType.startsWith("video/")) {
            cover = photos.find { it.mimeType.startsWith("image/") }!!.also { baseline = (if (it.orientation == 90 || it.orientation == 270) it.width else it.height) / 2 }
        } else {
            cover = photos.find { it.id == album.cover } ?: photoRepository.getThisPhoto(album.cover)
            baseline = album.coverBaseline
        }
        // If cover file is not animated, it will be cropped to 21:9 ratio, remove suffix in cover asset file name, avoid conflict to cover's own item's asset file
        val coverAsset = "${album.id}/${if (Tools.isMediaPlayable(cover.mimeType)) cover.name else cover.name.substringBeforeLast('.')}"

        // Web page construction
        // YAML header of blog post
        var content = String.format(YAML_HEADER_BLOG.trimIndent(), album.name, album.endDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)), coverAsset, coverAsset, themeId) + "\n"

        // Blog post content
        when (themeId) {
            THEME_CASCADE -> {
                var leftColumn = ""
                var rightColumn = ""
                var leftBottom = 0
                var rightBottom = 0
                var filename: String
                var caption: String
                var item: String
                var itemHeight: Int

                photos.forEach { photo ->
                    filename = "${ASSETS_URL}/${album.id}/${photo.name}"
                    caption = photo.caption.replace("\r\n", "<br>")
                    caption = photo.caption.replace("\n", "<br>")

                    item = String.format(
                        ITEM_CASCADE.trimIndent(),
                        if (photo.mimeType.startsWith("image")) String.format(ITEM_GENERAL_PHOTO.trimIndent(), filename) else String.format(ITEM_GENERAL_VIDEO.trimIndent(), filename, photo.mimeType),
                        if (photo.caption.isEmpty()) "" else String.format(ITEM_CASCADE_CAPTION.trimIndent(), caption)
                    )
                    itemHeight = if (photo.orientation == 90 || photo.orientation == 270) photo.width else photo.height

                    if (leftBottom <= rightBottom) {
                        leftColumn += item
                        leftBottom += itemHeight
                    } else {
                        rightColumn += item
                        rightBottom += itemHeight
                    }
                }

                // Constructing final content, delete the last line break of both columns
                content += String.format(CONTENT_CASCADE.trimIndent(), leftColumn.dropLast(1), rightColumn.dropLast(1))
            }

            THEME_MAGAZINE -> {
                var index = 0
                var filename: String
                var caption: String
                val grid = mutableListOf<Photo>()

                do {
                    photos[index].let { photo ->
                        filename = "${ASSETS_URL}/${album.id}/${photo.name}"

                        if (photo.caption.isNotEmpty()) {
                            // Append pending grid
                            if (grid.isNotEmpty()) {
                                content += addMagazineGrid(grid, album.id)
                                grid.clear()
                            }
                            caption = photo.caption.replace("\r\n", "<br>")
                            caption = photo.caption.replace("\n", "<br>")

                            content += String.format(
                                (if (index % 2 == 0) ITEM_MAGAZINE_LEFT else ITEM_MAGAZINE_RIGHT).trimIndent(),
                                if (photo.mimeType.startsWith("image")) String.format(ITEM_MAGAZINE_PHOTO.trimIndent(), filename) else String.format(ITEM_MAGAZINE_VIDEO.trimIndent(), filename, photo.mimeType), caption
                            )
                        } else {
                            grid.add(photo)

                            // Append full grid
                            if (grid.size == 3) {
                                content += addMagazineGrid(grid, album.id)
                                grid.clear()
                            }
                        }
                    }
                    index++
                } while ( index < photos.size)

                // Append pending grid
                if (grid.isNotEmpty()) {
                    content += addMagazineGrid(grid, album.id)
                    grid.clear()
                }
            }

            THEME_TIMELINE -> {
                var index = 0
                var filename: String
                var caption: String
                var items = ""
                val dayTitle = application.getString(R.string.timeline_theme_day_title)

                val period = Period.between(photos.first().dateTaken.toLocalDate(), photos.last().dateTaken.toLocalDate())
                val timeSpan = when {
                    period.years != 0 -> IN_YEARS
                    period.months != 0 -> IN_MONTHS
                    period.days !=0 -> IN_DAYS
                    else -> {
                        content += ITEM_TIMELINE_SESSION_DIV_HEADER.trimIndent()
                        IN_ONE_DAY
                    }
                }
                var current = 0
                val inReverse = album.sortOrder % 100 == Album.BY_DATE_TAKEN_DESC
                var dayCount = 0
                if (inReverse) {
                    // Found out how many days span in this period, so that days can be counted down in the title area. Note that Period.day can not be used here, it counts calendar day difference
                    var i = LocalDate.MIN
                    photos.forEach { photo ->
                        photo.dateTaken.toLocalDate().let { date ->
                            if (i != date) {
                                i = date
                                dayCount++
                            }
                        }
                    }
                    dayCount++
                }

                do {
                    photos[index].let { photo ->
                        // Section header
                        when(timeSpan) {
                            IN_YEARS -> {
                                photo.dateTaken.year.let { year ->
                                    if (year != current) {
                                        if (current != 0) {
                                            // Ending for each year section
                                            content += String.format(ITEM_TIMELINE_SESSION_END.trimIndent(), items)
                                            items = ""
                                        }

                                        // New year section started
                                        current = year
                                        content += String.format(ITEM_TIMELINE_SESSION_START.trimIndent(), current.toString())

                                        // Move first item in year's section to the right if the last item in last section is on the left
                                        if (index % 2 != 0) items += ITEM_TIMELINE_BLOCK_VOID.trimIndent()
                                    }
                                }
                            }
                            IN_MONTHS -> {
                                photo.dateTaken.monthValue.let { month ->
                                    if (month != current) {
                                        if (current != 0) {
                                            // Ending for each year section
                                            content += String.format(ITEM_TIMELINE_SESSION_END.trimIndent(), items)
                                            items = ""
                                        }

                                        // New year section started
                                        current = month
                                        content += String.format(ITEM_TIMELINE_SESSION_START.trimIndent(), photo.dateTaken.month.getDisplayName(TextStyle.FULL, Locale.getDefault()))

                                        // Move first item in year's section to the right if the last item in last section is on the left
                                        if (index % 2 != 0) items += ITEM_TIMELINE_BLOCK_VOID.trimIndent()
                                    }
                                }
                            }
                            IN_DAYS -> {
                                photo.dateTaken.dayOfMonth.let { day ->
                                    if (day != current) {
                                        if (current != 0) {
                                            // Ending for each year section
                                            content += String.format(ITEM_TIMELINE_SESSION_END.trimIndent(), items)
                                            items = ""
                                        }

                                        // New year section started
                                        current = day
                                        if (inReverse) dayCount-- else dayCount++
                                        content += String.format(ITEM_TIMELINE_SESSION_START.trimIndent(), String.format(dayTitle, dayCount))

                                        // Move first item in year's section to the right if the last item in last section is on the left
                                        if (index % 2 != 0) items += ITEM_TIMELINE_BLOCK_VOID.trimIndent()
                                    }
                                }
                            }
                            IN_ONE_DAY -> {}
                        }

                        // Items
                        filename = "${ASSETS_URL}/${album.id}/${photo.name}"
                        caption = photo.caption.replace("\r\n", "<br>")
                        caption = photo.caption.replace("\n", "<br>")

                        items += String.format(
                            ITEM_TIMELINE_CONTAINER.trimIndent(),
                            if (photo.mimeType.startsWith("image")) String.format(ITEM_GENERAL_PHOTO.trimIndent(), filename) else String.format(ITEM_GENERAL_VIDEO.trimIndent(), filename, photo.mimeType),
                            caption,
                            // Stupid time offset back and forth!
                            LocalDateTime.ofInstant(photo.dateTaken.toInstant(OffsetDateTime.now().offset), ZoneId.of("Z")).format(when(timeSpan) {
                                IN_ONE_DAY -> DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
                                IN_DAYS -> DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
                                else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                            })
                        )
                    }
                    index++
                } while ( index < photos.size)

                // Add final section ending
                content += String.format(ITEM_TIMELINE_SESSION_END.trimIndent(), items)
                //content += String.format(CONTENT_TIMELINE.trimIndent(), timeline)
            }
        }

        // Create all assets including cover
        updateAssets(album, photos, cover, baseline)

        // Create {albumId.md} content file
        webDav.upload(content, "${lespasBase}/${BLOG_CONTENT_FOLDER}/${album.id}.md", MIME_TYPE_MARKDOWN) //.apply { Log.e(">>>>>>>>", "createBlogPost: blog post created: $first $second") }
    }

    private fun addMagazineGrid(grid: List<Photo>, albumId: String): String {
        var result = """<div class="row">""" + "\n"
        var fileName: String
        for (item in grid) {
            fileName = "${ASSETS_URL}/${albumId}/${item.name}"
            result += String.format(ITEM_MAGAZINE_GRID.trimIndent(), if (item.mimeType.startsWith("image")) String.format(ITEM_MAGAZINE_PHOTO.trimIndent(), fileName) else String.format(ITEM_MAGAZINE_VIDEO.trimIndent(), fileName, item.mimeType))
        }

        return "$result</div>\n\n"
    }

    private fun updateBlogIndex() {
        // Get user display name
        var userDisplayName = ""
        try { webDav.ocsGet("$baseUrl${String.format(NCShareViewModel.USER_METADATA_ENDPOINT, userName)}")?.apply { userDisplayName = getJSONObject("data").getString("displayname") } } catch (_: Exception) {}

        // No way to check if display name is changed, so index file is updated every time when a blog post is being updated
        val indexFile = "${lespasBase}/${BLOG_CONTENT_FOLDER}/${INDEX_FILE}"
        webDav.upload(
            String.format(
                YAML_HEADER_INDEX.trimIndent(), PreferenceManager.getDefaultSharedPreferences(application).getString(application.getString(R.string.blog_name_pref_key), application.getString(R.string.blog_name_default)),
                userDisplayName,
                baseUrl.substringBefore("//") + "//" + baseUrl.substringAfter("//").substringBefore('/')
            ),
            indexFile, MIME_TYPE_MARKDOWN
        )   //.apply { Log.e(">>>>>>>>", "updateBlogIndex: index.md created: $first $second") }
    }

    private fun updateAssets(album: Album, photos: List<Photo>, cover: Photo, baseline: Int) {
        val addition = mutableListOf<Photo>()
        val deletion = mutableListOf<OkHttpWebDav.DAVResource>()
        val isRemote = Tools.isRemoteAlbum(album)

        // If cover file is not animated, it will be cropped to 21:9 ratio, remove suffix in cover asset file name, avoid conflict to cover's own item's asset file
        val coverName = if (Tools.isMediaPlayable(cover.mimeType)) cover.name else cover.name.substringBeforeLast('.')

        // Get current asset list for this album, ignore any exceptions, worst case is re-transferring all the assets again
        val assetFolder = "${lespasBase}/${BLOG_ASSETS_FOLDER}/${album.id}"
        val remoteAssets = try { webDav.list(assetFolder, OkHttpWebDav.FOLDER_CONTENT_DEPTH).drop(1) } catch (_: Exception) { mutableListOf() }

        // Prepare deletion list
        remoteAssets.forEach { remote -> photos.find { remote.name == it.name || remote.name == coverName } ?: run { deletion.add(remote) } }
        // Prepare addition list
        photos.forEach { local -> remoteAssets.find { local.name == it.name } ?: run { addition.add(local) } }

        //Log.e(">>>>>>>>", "updateAssets: additions: $addition")
        //Log.e(">>>>>>>>", "updateAssets: deletions: $deletion")

        // Update new cover
        remoteAssets.find { it.name == coverName } ?: run { updateAsset(cover, album.id, album.name, isRemote, isCover = true, coverBaseline = baseline) }
        // Update new photos, TODO will copy new animated cover for 1 more time
        addition.forEach { updateAsset(it, album.id, album.name, isRemote) }

        // Remove obsolete photos and cover
        deletion.forEach { webDav.delete("${assetFolder}/${it.name}") }
    }

    private fun updateAsset(photo: Photo, albumId: String, albumName: String, isRemote: Boolean, isCover: Boolean = false, coverBaseline: Int = -1, override: Boolean = false): Boolean {
        var result = false
        // Shrink picture size to around 1000 pixel long
        val longEdge = max(photo.width, photo.height)
        var size = 1
        if (!isCover) while (longEdge / size > 1600) { size *= 2 }
        val shrinkOption = BitmapFactory.Options().apply { this.inSampleSize = size }

        var sourceStream: InputStream? = null

        try {
            // No need to override
            //if (!override && webDav.isExisted(targetFile)) return true

            // For animated GIF and WebP, directly copy this file to blog assets folder
            if (Tools.isMediaPlayable(photo.mimeType)) {
                webDav.copy("${lespasBase}/${albumName}/${photo.name}", "${lespasBase}/${BLOG_ASSETS_FOLDER}/${albumId}/${photo.name}")
                return true
            }

            if (isRemote) {
                // Get preview from server if it's not for cover, Nextcloud will not provide preview for webp, heic/heif, if preview is available, then it's rotated by Nextcloud to upright position
                if (!isCover) sourceStream = try {
                    webDav.getStream("${baseUrl}${NCShareViewModel.PREVIEW_ENDPOINT}${photo.id}", true, null)
                } catch (e: OkHttpWebDavException) {
                    if (e.statusCode == 404 || e.statusCode == 409) null else throw e
                }

                // Preview not available, get original instead
                sourceStream?.let { shrinkOption.inSampleSize = 1 } ?: run { sourceStream = webDav.getStream("${lespasBase}/${albumName}/${photo.name}", true, null) }
            } else {
                sourceStream = File("${localBaseFolder}/${photo.id}").inputStream()
            }

            sourceStream?.use { source ->
                //Log.e(">>>>>>>>", "    updateAsset: image source stream ready ${photo.name}")
                val tempFile = File(application.cacheDir, photo.name)

                @Suppress("DEPRECATION")
                var bitmap = if (isCover) {
                    shrinkOption.inSampleSize = 2

                    val width = photo.width
                    val height = photo.height
                    val rect = when (photo.orientation) {
                        0 -> Rect(0, coverBaseline, width - 1, min(coverBaseline + (width.toFloat() * 9 / 21).toInt(), height - 1))
                        90 -> Rect(coverBaseline, 0, min(coverBaseline + (width.toFloat() * 9 / 21).toInt(), height - 1), width - 1)
                        180 -> (height - coverBaseline).let { Rect(0, max(it - (width.toFloat() * 9 / 21).toInt(), 0), width - 1, it) }
                        else -> (height - coverBaseline).let { Rect(max(it - (width.toFloat() * 9 / 21).toInt(), 0), 0, it, width - 1) }
                    }
                    (if (Build.VERSION.SDK_INT > Build.VERSION_CODES.R) BitmapRegionDecoder.newInstance(source) else BitmapRegionDecoder.newInstance(source, false))?.decodeRegion(rect, shrinkOption)
                } else BitmapFactory.decodeStream(source, null, shrinkOption)

                bitmap?.let { bmp ->
                    //Log.e(">>>>>>>>", "    updateAsset: image bitmap ready ${photo.name}")
                    if (photo.orientation != 0) bitmap = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { preRotate((photo.orientation).toFloat()) }, true)
                    bitmap?.compress(Bitmap.CompressFormat.JPEG, 90, tempFile.outputStream())

                    val targetFile = "${lespasBase}/${BLOG_ASSETS_FOLDER}/${albumId}/${if (isCover) photo.name.substringBeforeLast('.') else photo.name}"
                    webDav.upload(tempFile, targetFile, Photo.DEFAULT_MIMETYPE, application)    //.apply { Log.e(">>>>>>>>", "    prepareAsset: ${photo.name} asset created.") }
                }

                tempFile.delete()
            }

            result = true
        } catch (e: OkHttpWebDavException) {
            Log.e(">>>>>>>>", "updateAsset: ${e.stackTraceString}")
        }
        catch (e: Exception) {
            // TODO better exception handling
            e.printStackTrace()
        }
        finally {
            sourceStream?.close()
        }

        return result
    }

    private fun logChangeToFile(meta: String, newFileId: String, fileName: String,) {
        val metaFromAction = meta.split('|')
        val logFile = File(localBaseFolder, "${metaFromAction[0]}${CHANGE_LOG_FILENAME_SUFFIX}")

        try {
            mutableListOf<NCShareViewModel.RemotePhoto>().apply {
                if (logFile.exists()) logFile.inputStream().use { addAll(Tools.readContentMeta(it, "", useUTC = true)) }
                else logFile.createNewFile()

                val date = try { Tools.epochToLocalDateTime(metaFromAction[1].toLong(), true) } catch (e: Exception) { LocalDateTime.now() }
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
        webDav.list(lespasBase, OkHttpWebDav.FOLDER_CONTENT_DEPTH).drop(1).forEach { remoteAlbum ->     // Drop the first one in the list, which is the parent folder itself
            if (remoteAlbum.isFolder) {
                // Skip blog folder
                if (remoteAlbum.name == BLOG_FOLDER || remoteAlbum.name == BLOG_FOLDER.drop(1)) return@forEach

                // Skip archive folder
                if (remoteAlbum.name == ARCHIVE_BASE.drop(1)) return@forEach

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
                            localAlbum[0].copy(
                                // Use remote version, since it might be changed on server or hidden state toggled
                                name = remoteAlbum.name,
                                lastModified = remoteAlbum.modified,
                                // Use remote eTag for unhidden albums
                                eTag = remoteAlbum.eTag,
                                // shareId's 1st bit denotes album shared status TODO should we enforce SHARED_ALBUM bit? it's actually determined by Share_With_Me now
                                shareId = if (remoteAlbum.isShared) localAlbum[0].shareId or Album.SHARED_ALBUM else localAlbum[0].shareId and Album.SHARED_ALBUM.inv(),
                                // Make sure sync process set to finish for now
                                syncProgress = Album.SYNC_COMPLETED,
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
                    try { File(localBaseFolder, it.id).delete() } catch (e: Exception) { e.printStackTrace() }
                    try { File(localBaseFolder, it.name).delete() } catch(e: Exception) { e.printStackTrace() }
                }
                try { File(localBaseFolder, "${local.id}.json").delete() } catch (e: Exception) { e.printStackTrace() }
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
                val remotePhotoList = webDav.list("${lespasBase}/${changedAlbum.name}", OkHttpWebDav.FOLDER_CONTENT_DEPTH).drop(1)
                remotePhotoList.forEach { remotePhoto ->
                    when {
                        // Media files with supported format which are not hidden
                        (remotePhoto.contentType.substringAfter("image/", "") in Tools.SUPPORTED_PICTURE_FORMATS || remotePhoto.contentType.startsWith("video/", true)) && !remotePhoto.name.startsWith('.') -> {
                            remotePhotoId = remotePhoto.fileId

                            // Collect remote photos ids for detection of server deletion
                            remotePhotoIds.add(remotePhotoId)

                            if (localPhotoETags[remotePhotoId] != remotePhoto.eTag) {
                                // Since null is comparable, this also matches newly created photo id from server, e.g. there is no such remotePhotoId in local table

                                if (localPhotoETags[remotePhotoId] == ETAG_MISSING ) {
                                    // Local photo with eTag "ETAG_MISSING" is created in ACTION_COPY_ON_SERVER or ACTION_MOVE_ON_SERVER where some version of Nextcloud server won't return correct oc-etag header after webdav COPY or MOVE call
                                    photoRepository.updateETag(remotePhotoId, remotePhoto.eTag)
                                    return@forEach
                                }

                                if (File(localBaseFolder, remotePhoto.name).exists()) {
                                    // If there is local file with remote photo's name, that means it's a local added photo which is now coming back from server.
                                    //Log.e("<><><>", "coming back now ${remotePhoto.name}")

                                    // Remove old media file at local
                                    try { File(localBaseFolder, remotePhotoId).delete() } catch (e: Exception) { Log.e(TAG, e.stackTraceToString()) }
                                    // Rename image file name to fileid
                                    try { File(localBaseFolder, remotePhoto.name).renameTo(File(localBaseFolder, remotePhotoId)) } catch (e: Exception) { Log.e(TAG, e.stackTraceToString()) }
                                    // Handle video thumbnail file too
                                    if (remotePhoto.contentType.startsWith("video")) {
                                        try { File(localBaseFolder, "${remotePhotoId}.thumbnail").delete() } catch (e: Exception) { Log.e(TAG, e.stackTraceToString()) }
                                        try { File(localBaseFolder, "${remotePhoto.name}.thumbnail").renameTo(File(localBaseFolder, "${remotePhotoId}.thumbnail")) } catch (e: Exception) { Log.e(TAG, e.stackTraceToString()) }
                                    }

                                    localPhotoNamesReverse[remotePhoto.name]?.let { oldId ->
                                        // Update it's id to the real fileId and also eTag now
                                        photoRepository.fixPhoto(oldId, remotePhotoId, remotePhoto.name, remotePhoto.eTag, remotePhoto.modified)
                                        // Taking care the cover
                                        // TODO: Condition race here, e.g. user changes this album's cover right at this very moment
                                        if (changedAlbum.cover == oldId) {
                                            //Log.e("=======", "fixing cover from ${changedAlbum.cover} to $remotePhotoId")
                                            albumRepository.fixCoverId(changedAlbum.id, remotePhotoId)
                                            changedAlbum.cover = remotePhotoId

                                            metaUpdatedNeeded.add(changedAlbum.name)
                                        }
                                    }
                                    // content meta update already set by Action.ACTION_ADD_FILES_ON_SERVER
                                } else {
                                    // A new photo created on server, or an existing photo updated on server, or album attribute changed back to local, or on first sync with server
                                    changedPhotos.add(Photo(id = remotePhotoId, albumId = changedAlbum.id, name = remotePhoto.name, eTag = remotePhoto.eTag, mimeType = remotePhoto.contentType, dateTaken = LocalDateTime.now(), lastModified = remotePhoto.modified, caption = remotePhoto.caption))
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
                            if (File("${localBaseFolder}/${bgmFileName}").length() != remotePhoto.size) {
                                webDav.download("${lespasBase}/${changedAlbum.name}/${BGM_FILENAME_ON_SERVER}", "$localBaseFolder/${bgmFileName}", null)
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
                        if (remoteMeta.modified.toInstant(OffsetDateTime.now().offset).toEpochMilli() - File("$localBaseFolder/${metaFileName}").lastModified() > 180000) {
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
                            webDav.getStream("$lespasBase/${changedAlbum.name}/${changedAlbum.id}${CONTENT_META_FILE_SUFFIX}", false, null).use { stream ->
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
                                                            dateTaken = try { Tools.epochToLocalDateTime(getLong("stime"), true)} catch (e: Exception) { LocalDateTime.now() }, lastModified = it.lastModified,
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
                            if (e.statusCode == 404 || e.statusCode == 409) contentMetaUpdatedNeeded.add(changedAlbum.name)
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
                // Get current meta saved in DB, for preserving caption when local photo modified like being edited by Snapseed
                val photoExtras = photoRepository.getPhotoExtras(changedAlbum.id)

                changedPhotos.forEachIndexed { i, changedPhoto->
                    // Check network type on every loop, so that user is able to stop sync right in the middle
                    checkConnection()

                    if (isRemoteAlbum) {
                        //Log.e(TAG, "extracting meta remotely for photo ${changedPhoto.name}")
                        // If it's a Remote album, extract EXIF remotely, since EXIF locates before actual JPEG image stream, this might save some network bandwidth and time
                        if (changedPhoto.mimeType.startsWith("video", true)) {
                            try { metadataRetriever.setDataSource("${lespasBase}/${changedAlbum.name}/${changedPhoto.name}", HashMap<String, String>().apply { this["Authorization"] = "Basic $token" })} catch (_: Exception) {}
                            exifInterface = null
                        } else {
                            webDav.getStream("$lespasBase/${changedAlbum.name}/${changedPhoto.name}", false, null).use {
                                exifInterface = try { ExifInterface(it) } catch (e: Exception) { null } catch (_: OutOfMemoryError) { null }
                            }
                        }
                    } else {
                        // If it's a Local album, download image file from server and extract meta locally
                        webDav.download("$lespasBase/${changedAlbum.name}/${changedPhoto.name}", "$localBaseFolder/${changedPhoto.id}", null)
                        //Log.e(TAG, "Downloaded ${changedPhoto.name}")

                        if (changedPhoto.mimeType.startsWith("video")) {
                            try { metadataRetriever.setDataSource("$localBaseFolder/${changedPhoto.id}")} catch (_: Exception) {}
                            exifInterface = null
                        }
                        else exifInterface = try { ExifInterface("$localBaseFolder/${changedPhoto.id}") } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }
                    }

                    with(Tools.getPhotoParams(metadataRetriever, exifInterface, if (isRemoteAlbum) "" else "$localBaseFolder/${changedPhoto.id}", changedPhoto.mimeType, changedPhoto.name, keepOriginalOrientation = isRemoteAlbum)) {
                        // Preserve lastModified date from server if more accurate taken date can't be found (changePhoto.dateTaken is timestamped as when it's initialized)
                        // In Tools.getPhotoParams(), if it can't extract date from EXIF and filename, it will return the local media file creation date which is always more closer to the current moment
                        changedPhoto.dateTaken = if (this.dateTaken >= changedPhoto.dateTaken) changedPhoto.lastModified else this.dateTaken
                        changedPhoto.width = this.width
                        changedPhoto.height = this.height
                        // If photo got rotated, mimetype will be changed to image/jpeg
                        changedPhoto.mimeType = this.mimeType
                        // Photo's original orientation is needed to display remote image in full format
                        changedPhoto.orientation = this.orientation
                        // Preserve original caption, if it's a new photo copied/moved from another album, the old caption was downloaded from webdav extra property. If it's a local change, caption is in photoExtras. Otherwise, will be taken from EXIF
                        // TODO other extras like address and classification id??
                        if (changedPhoto.caption.isEmpty()) changedPhoto.caption = photoExtras.find { it.id == changedPhoto.id }?.caption ?: this.caption
                        changedPhoto.latitude = this.latitude
                        changedPhoto.longitude = this.longitude
                        changedPhoto.altitude = this.altitude
                        changedPhoto.bearing = this.bearing

                        if (!isRemoteAlbum && changedPhoto.id == changedAlbum.cover) {
                            // Update local album's cover info if cover image original orientation is not 0 degree and it was rotated to up-right position in above Tools.getPhotoParams.
                            // This will happen when user created a new album with portrait format archived item being automatically selected as cover
                            changedAlbum.coverWidth = changedPhoto.width
                            changedAlbum.coverHeight = changedPhoto.height
                            changedAlbum.coverOrientation = 0
                        }
                    }

                    if (isRemoteAlbum) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && changedPhoto.mimeType.lowercase(Locale.getDefault()).run { this == "image/gif" || this == "image/webp" }) {
                            // Find out if it's animated GIF or WEBP
                            //Log.e(TAG, "need to download ${changedPhoto.name} to find out if it's animated")
                            webDav.getStream("$lespasBase/${changedAlbum.name}/${changedPhoto.name}", false, null).use {
                                val d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(it.readBytes())))
                                changedPhoto.width = d.intrinsicWidth
                                changedPhoto.height = d.intrinsicHeight
                                if (d is AnimatedImageDrawable) changedPhoto.mimeType = "image/a${changedPhoto.mimeType.substringAfterLast('/')}"
                            }
                        } else {
                            if (changedPhoto.width == 0 && changedPhoto.mimeType.startsWith("image")) {
                                // If image resolution fetched from EXIF failed (for example, picture format don't support EXIF), we need to download the file from server
                                //Log.e(TAG, "need to download ${changedPhoto.name} to get resolution data")
                                webDav.getStream("$lespasBase/${changedAlbum.name}/${changedPhoto.name}", false, null).use {
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
                }

                // Restore blog exclusion setting and/or captions, location, date taken from local sidecar backup during meta re-scan
                try {
                    File(localBaseFolder, "${changedAlbum.id}${SIDECAR_FILENAME_SUFFIX}").let { sidecarFile ->
                        if (sidecarFile.exists()) {
                            (ObjectInputStream(FileInputStream(sidecarFile)).readObject() as MetaRescanDialogFragment.Sidecar).let { sidecar ->
                                val photos = photoRepository.getAlbumPhotos(changedAlbum.id)

                                photos.forEach { photo ->
                                    sidecar.photoSidecarData.find { it.id == photo.id }?.let {
                                        if (sidecar.restoreCaption) photo.caption = it.caption
                                        if (sidecar.restoreLocation) {
                                            photo.latitude = it.latitude
                                            photo.longitude = it.longitude
                                            photo.altitude = it.altitude
                                            photo.bearing = it.bearing
                                            photo.locality = it.locality
                                            photo.country = it.country
                                            photo.countryCode = it.countryCode
                                        }
                                        if (sidecar.restoreTakenDate) photo.dateTaken = it.dateTaken

                                        // Restore blog exclusion setting
                                        photo.shareId = Photo.DEFAULT_PHOTO_FLAG or (it.shareId and Photo.EXCLUDE_FROM_BLOG)
                                    }
                                }

                                photoRepository.upsert(photos)
                            }
                        }

                        // Remove sidecar
                        sidecarFile.delete()
                    }
                } catch (e: Exception) { e.printStackTrace() }

                // Force update album start and end date when album content changed whether there are photo files got added/deleted or simply just name changed
                with(photoRepository.getAlbumDuration(changedAlbum.id)) {
                    if (first < changedAlbum.startDate) changedAlbum.startDate = first
                    if (second > changedAlbum.endDate) changedAlbum.endDate = second
                }

                // Every changed photos updated, we can commit changes to the Album table now. The most important column is "eTag", dictates the sync status
                //Log.e(">>>>>>>>", "finish syncing album ${changedAlbum.name}")
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
                        try { File(localBaseFolder, localPhoto.key).delete() } catch (_: Exception) {}
                        try { File(localBaseFolder, "${localPhoto.key}.thumbnail").delete() } catch (_: Exception) {}
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
                        try { File(localBaseFolder, "${changedAlbum.id}.json").delete() } catch (e: Exception) { e.printStackTrace() }
                    }
                }

                // Recycle the list
                remotePhotoIds.clear()
                changedPhotos.clear()
            }

            metadataRetriever.release()
        }
    }

    private fun backupGallery() {
        val deviceModel = Tools.getDeviceModel()

        reportStage(Action.SYNC_STAGE_BACKUP_PICTURES)

        // Make sure archive folders hierarchy exsited on server
        var backupFolder = "${lespasBase}${ARCHIVE_BASE}"
        makeSureFolderExisted(backupFolder)
        backupFolder += "/${deviceModel}"
        makeSureFolderExisted(backupFolder)

        val mediaMetadataRetriever = MediaMetadataRetriever()
        val cr = application.contentResolver
        val contentUri = MediaStore.Files.getContentUri("external")
        var selection: String
        var projection: Array<String>
        val pathSelection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Files.FileColumns.RELATIVE_PATH else MediaStore.Files.FileColumns.DATA
        val dateTakenColumnName = "datetaken"     // MediaStore.MediaColumns.DATE_TAKEN, hardcoded here since it's only available in Android Q or above
        val orientationColumnName = "orientation"     // MediaStore.MediaColumns.ORIENTATION, hardcoded here since it's only available in Android Q or above

        var contentId: Long
        var photoUri: Uri
        var relativePath: String
        var size: Long
        var mDate: Long
        val photo = Photo(albumId = "", dateTaken = LocalDateTime.now(), lastModified = LocalDateTime.now())
        val addition = mutableListOf<GalleryFragment.LocalMedia>()

        backupSettingRepository.getEnabled().forEach {
            if (it.lastBackup == BackupSetting.NOT_YET) return@forEach

            val subFolder = "${backupFolder}/${it.folder}"
            makeSureFolderExisted(subFolder)

            projection = arrayOf(
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
            selection = "(${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})" + " AND " +
                    "($pathSelection LIKE '${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) it.folder else "${GalleryFragment.STORAGE_EMULATED}_/${it.folder}"}%')" + " AND " +    // path start with specific folder
                    "(${MediaStore.Files.FileColumns.DATE_ADDED} > ${it.lastBackup})"   // DATE_ADDED is in second

            cr.query(contentUri, projection, selection, null, "${MediaStore.Files.FileColumns.DATE_ADDED} ASC")?.use { cursor->
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

                while(cursor.moveToNext()) {
                    //Log.e(TAG, "${cursor.getString(nameColumn)} ${cursor.getString(dateColumn)}  ${cursor.getString(pathColumn)} needs uploading")
                    // Check network type on every loop, so that user is able to stop sync right in the middle
                    checkConnection()

                    //Log.e(">>>>>>>>", "backupGallery: ${it.folder} ${cursor.getLong(idColumn)} ${cursor.getString(nameColumn)} ${cursor.getLong(dateColumn)} ${cursor.getString(pathColumn)} ${cursor.getInt(widthColumn)} ${cursor.getInt(orientationColumn)}")
                    //Log.e(TAG, "relative path is $relativePath  server file will be ${dcimRoot}/${relativePath}/${fileName}")
                    relativePath = cursor.getString(pathColumn).substringAfter("${it.folder}/").substringBeforeLast('/', "")
                    // Exclude sub folder in user defined exclusion set
                    if (relativePath.isNotEmpty() && it.exclude.contains(relativePath.substringBefore('/'))) continue
                    
                    // Get uri, in Android Q or above, try getting original uri for meta data extracting
                    contentId = cursor.getLong(idColumn)
                    photoUri = ContentUris.withAppendedId(contentUri, contentId)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) try { photoUri = MediaStore.setRequireOriginal(ContentUris.withAppendedId(contentUri, contentId)) } catch (_: Exception) {}

                    // For Android 9 and below, MediaStore.Files.FileColumns.DISPLAY_NAME won't return file name for files in Download folder
                    photo.name = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && it.folder == "Download") cursor.getString(pathColumn).substringAfterLast('/') else cursor.getString(nameColumn)
                    size = try { cursor.getLong(sizeColumn) } catch(_: Exception) { 0L }
                    photo.caption = size.toString()
                    photo.mimeType = cursor.getString(typeColumn)

                    reportBackupStatus(photo.name, size, cursor.position, cursor.count)

                    // Indefinite while loop is for handling 404 and 409 (Caddy seems to prefer) error when folders needed to be created on server before hand
                    while(true) {
                        try {
                            webDav.upload(photoUri, "${subFolder}/${relativePath}/${photo.name}", photo.mimeType, cr, size, application).apply {
                                photo.id = first.substring(0, 8).toInt().toString()
                                photo.eTag = second
                            }
                            break
                        } catch (e: OkHttpWebDavException) {
                            when (e.statusCode) {
                                // Caddy seems preferring 409 response code when PUT file to a non-existing folder
                                404, 409 -> {
                                    // create file in non-existed folder, should create subfolder first
                                    createSubFoldersRecursively(subFolder, relativePath)
                                }
                                400 -> sleep(2000)
                                else -> throw e
                            }
                        } catch (e: StreamResetException) {
                            createSubFoldersRecursively(subFolder, relativePath)
                        } catch (e: EOFException) {
                            // Under some unknown situation StreamResetException might be suppressed by EOFException
                            e.suppressed.let { suppressed ->
                                if (suppressed.isNotEmpty()) {
                                    if (suppressed[0] is StreamResetException) createSubFoldersRecursively(subFolder, relativePath)
                                }
                            }
                        }
                    }

                    photo.width = cursor.getInt(widthColumn)
                    photo.height = cursor.getInt(heightColumn)
                    photo.orientation = cursor.getInt(orientationColumn)

                    // Try to get GPS data
                    photo.latitude = Photo.GPS_DATA_UNKNOWN
                    photo.longitude = Photo.GPS_DATA_UNKNOWN
                    photo.altitude = Photo.GPS_DATA_UNKNOWN
                    photo.bearing = Photo.GPS_DATA_UNKNOWN
                    if (Tools.hasExif(photo.mimeType)) {
                        try {
                            cr.openInputStream(photoUri)?.use { stream ->
                                ExifInterface(stream).let { exif ->
                                    exif.latLong?.let { latLong ->
                                        photo.latitude = latLong[0]
                                        photo.longitude = latLong[1]
                                        photo.altitude = exif.getAltitude(Photo.NO_GPS_DATA)
                                        photo.bearing = Tools.getBearing(exif)
                                    } ?: run {
                                        // No GPS data
                                        photo.latitude = Photo.NO_GPS_DATA
                                        photo.longitude = Photo.NO_GPS_DATA
                                        photo.altitude = Photo.NO_GPS_DATA
                                        photo.bearing = Photo.NO_GPS_DATA
                                    }
                                }
                            }
                        } catch (_: Exception) {} catch (_: OutOfMemoryError) {}
                    } else if (photo.mimeType.startsWith("video/")) {
                        try {
                            mediaMetadataRetriever.setDataSource(application, photoUri)
                            Tools.getVideoLocation(mediaMetadataRetriever).let { coordinate ->
                                photo.latitude = coordinate[0]
                                photo.longitude = coordinate[1]
                                photo.altitude = Photo.NO_GPS_DATA
                                photo.bearing = Photo.NO_GPS_DATA
                            }
                        } catch (_: SecurityException) {}
                    }

                    // Patch photo's DAV properties to accelerate future operations on archive
                    mDate = cursor.getLong(dateTakenColumn)
                    if (mDate == 0L) mDate = cursor.getLong(dateColumn) * 1000
                    try {
                        webDav.patch(
                            "${subFolder}/${relativePath}/${photo.name}",
                            "<oc:${OkHttpWebDav.LESPAS_DATE_TAKEN}>" + mDate + "</oc:${OkHttpWebDav.LESPAS_DATE_TAKEN}>" +      // timestamp from Android MediaStore is in UTC timezone
                                    "<oc:${OkHttpWebDav.LESPAS_ORIENTATION}>" + photo.orientation + "</oc:${OkHttpWebDav.LESPAS_ORIENTATION}>" +
                                    "<oc:${OkHttpWebDav.LESPAS_WIDTH}>" + photo.width + "</oc:${OkHttpWebDav.LESPAS_WIDTH}>" +
                                    "<oc:${OkHttpWebDav.LESPAS_HEIGHT}>" + photo.height + "</oc:${OkHttpWebDav.LESPAS_HEIGHT}>" +
                                    if (photo.latitude == Photo.GPS_DATA_UNKNOWN) ""
                                    else
                                        "<oc:${OkHttpWebDav.LESPAS_LATITUDE}>" + photo.latitude + "</oc:${OkHttpWebDav.LESPAS_LATITUDE}>" +
                                                "<oc:${OkHttpWebDav.LESPAS_LONGITUDE}>" + photo.longitude + "</oc:${OkHttpWebDav.LESPAS_LONGITUDE}>" +
                                                "<oc:${OkHttpWebDav.LESPAS_ALTITUDE}>" + photo.altitude + "</oc:${OkHttpWebDav.LESPAS_ALTITUDE}>" +
                                                "<oc:${OkHttpWebDav.LESPAS_BEARING}>" + photo.bearing + "</oc:${OkHttpWebDav.LESPAS_BEARING}>"
                        )
                    } catch (_: Exception) {}

                    // Save latest timestamp after successful upload
                    backupSettingRepository.updateLastBackupTimestamp(it.folder, cursor.getLong(dateColumn))

                    // Update archive snapshot file addition list
                    // Use system default zone for time display, sorting and grouping by date in Gallery list
                    photo.dateTaken = LocalDateTime.ofInstant(Instant.ofEpochMilli(mDate), ZoneId.systemDefault())
                    photo.lastModified = LocalDateTime.ofInstant(Instant.ofEpochMilli(cursor.getLong(dateColumn) * 1000), ZoneId.systemDefault())
                    addition.add(0,
                        GalleryFragment.LocalMedia(
                            location = GalleryFragment.LocalMedia.IS_REMOTE,
                            folder = it.folder,
                            NCShareViewModel.RemotePhoto(photo = photo.copy(), remotePath = "${Tools.getRemoteHome(application)}/${ARCHIVE_BASE}/${deviceModel}/${it.folder}/${relativePath}"),
                            volume = deviceModel,
                            fullPath = "${it.folder}/${relativePath}/",
                            appName = relativePath.substringAfterLast('/'),
                            remoteFileId = "",
                        )
                    )
                }
            }

            // Stealth removing can only happen in older version of Android
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && it.autoRemove > 0) {
                projection  = arrayOf(
                    MediaStore.Files.FileColumns._ID,
                    pathSelection,
                    MediaStore.Files.FileColumns.DATE_ADDED,
                    MediaStore.Files.FileColumns.MEDIA_TYPE,
                )
                selection = "(${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})" + " AND " +
                        "($pathSelection LIKE '${"${if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) it.folder else GalleryFragment.STORAGE_EMULATED}_/${it.folder}"}%')" + " AND " +
                        "(${MediaStore.Files.FileColumns.DATE_ADDED} < ${min(System.currentTimeMillis() / 1000 - it.autoRemove * 86400L, it.lastBackup)})"  // DATE_ADDED is in second

                val deletion = arrayListOf<Long>()
                cr.query(contentUri, projection, selection, null, null)?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                    val pathColumn = cursor.getColumnIndexOrThrow(pathSelection)

                    while(cursor.moveToNext()) {
                        if (cursor.getString(pathColumn).substringAfter("${it.folder}/").substringBefore('/') !in it.exclude) deletion.add(cursor.getLong(idColumn))
                    }
                }

                if (deletion.isNotEmpty()) cr.delete(contentUri, "${MediaStore.Files.FileColumns._ID} IN (${deletion.joinToString()})", null)
            }
        }

        mediaMetadataRetriever.release()

        // Report finished status
        reportBackupStatus(" ", 0L, 0, 0)

        // Save new backups to local archive snapshot file, avoid fetching server archive
        if (addition.isNotEmpty()) {
            archiveETagNeeded = true
            File(localBaseFolder, NCShareViewModel.ARCHIVE_SNAPSHOT_FILE).let { file ->
                if (file.exists()) {
                    var jsonString: String
                    file.reader().use { jsonString = it.readText() }

                    Tools.jsonToArchiveList(jsonString).let { oldList -> file.writer().use { it.write(Tools.archiveToJSONString(addition.plus(oldList))) }}
                }
            }
        }
    }

    private fun fetchArchiveETag() {
        try {
            webDav.list("${lespasBase}${ARCHIVE_BASE}", OkHttpWebDav.JUST_FOLDER_DEPTH, forceNetwork = true).let { davs ->
                if (davs.isNotEmpty()) {
                    sp.edit().run {
                        putString(LATEST_ARCHIVE_FOLDER_ETAG, davs[0].eTag)
                        apply()
                    }
                }
            }

            archiveETagNeeded = false
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun backupPreference() {
        webDav.upload(
            StringBuffer().apply {
                PreferenceManager.getDefaultSharedPreferences(application).all.forEach {
                    append("${it.key}=${it.value}${PREFERENCE_BACKUP_SEPARATOR}")
                }
            }.toString().dropLast(1),
            "${lespasBase}/${PREFERENCE_BACKUP_ON_SERVER}", "text/plain")
        prefBackupNeeded = false
    }

    private fun createSubFoldersRecursively(base: String, path: String) {
        var subFolder = base
        path.split("/").forEach {
            subFolder += "/$it"
            makeSureFolderExisted(subFolder)
        }
    }

    private fun updateMeta() {
        mutableListOf<String>().apply { addAll(metaUpdatedNeeded) }.forEach { albumName->
            albumRepository.getAlbumByName(albumName)?.apply {
                var coverId = cover
                //if (!cover.contains('.')) updateAlbumMeta(id, name, Cover(cover, coverBaseline, coverWidth, coverHeight, coverFileName, coverMimeType, coverOrientation), sortOrder)
                // TODO what if the cover file name do not contain suffix, or even worst is all digits
                if (cover.contains('.')) {
                    // Cover is not fileId base, fix it here
                    coverId = photoRepository.getPhotoIdByNameInAlbum(id, cover)
                    albumRepository.fixCoverId(id, coverId)
                }
                updateAlbumMeta(id, name, Cover(coverId, coverBaseline, coverWidth, coverHeight, coverFileName, coverMimeType, coverOrientation), sortOrder)
            }

            // Maintain metaUpdatedNeeded set so that if any exception happened, those not updated yet can be saved into action database
            metaUpdatedNeeded.remove(albumName)
        }

        mutableListOf<String>().apply { addAll(contentMetaUpdatedNeeded) }.forEach { albumName->
            albumRepository.getAlbumByName(albumName)?.apply { updateContentMeta(id, name) }

            // Maintain metaUpdatedNeeded set so that if any exception happened, those not updated yet can be saved into action database
            contentMetaUpdatedNeeded.remove(albumName)
        }

        var themeId = ""
        mutableListOf<String>().apply { addAll(blogUpdateNeeded) }.forEach { albumId ->
            try {
                webDav.getStream("${lespasBase}/${BLOG_CONTENT_FOLDER}/${albumId}.md", false, null).bufferedReader().use { reader ->
                    var line: String? = ""
                    while(line != null) {
                        line = reader.readLine()
                        line?.let {
                            if (line.substringBefore(':') == "Theme") {
                                themeId = line.substringAfter(':').trim()
                                return@use
                            }
                        }
                    }
                }

                createBlogPost(albumRepository.getThisAlbum(albumId), photoRepository.getPhotosForBlog(albumId), themeId)
            } catch (_: Exception) {}
        }
    }

    //private fun updateAlbumMeta(albumId: String, albumName: String, cover: Cover, coverFileName: String, sortOrder: Int): Boolean {
    private fun updateAlbumMeta(albumId: String, albumName: String, cover: Cover, sortOrder: Int) {
        val metaFileName = "${albumId}.json"
        val localFile = File(localBaseFolder, metaFileName)

        // Need this file in phone
        //FileWriter("$localRootFolder/metaFileName").apply {
        localFile.writer().use {
            //it.write(String.format(ALBUM_META_JSON, cover.cover, coverFileName, cover.coverBaseline, cover.coverWidth, cover.coverHeight, sortOrder))
            it.write(String.format(Locale.ROOT, ALBUM_META_JSON_V2, cover.cover, cover.coverFileName, cover.coverBaseline, cover.coverWidth, cover.coverHeight, cover.coverMimeType, cover.coverOrientation, sortOrder))
        }

        // If local meta json file created successfully
        webDav.upload(localFile, "$lespasBase/${albumName}/${metaFileName}", MIME_TYPE_JSON, application)

        blogUpdateNeeded.add(albumId)
    }

    private fun updateContentMeta(albumId: String, albumName: String) {
        webDav.upload(Tools.metasToJSONString(photoRepository.getPhotoMetaInAlbum(albumId)), "$lespasBase/${albumName}/${albumId}${CONTENT_META_FILE_SUFFIX}", MIME_TYPE_JSON)

        blogUpdateNeeded.add(albumId)
    }

    private fun downloadAlbumMeta(album: Album): Meta? {
        var result: Meta? = null

        try {
            val metaFileName = "${album.id}.json"

            // Download the updated meta file
            webDav.getStream("$lespasBase/${album.name}/${metaFileName}", false,null).reader().use { input->
                File(localBaseFolder, metaFileName).writer().use { output ->
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

    // Avoid repeated getString() in loop
    private val keySyncStatus = application.getString(R.string.sync_status_pref_key)
    private val keySyncStatusLocalAction = application.getString(R.string.sync_status_local_action_pref_key)
    private val keyBackupStatus = application.getString(R.string.backup_status_pref_key)

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

        const val ARCHIVE_BASE = "/Backup"
        const val LATEST_ARCHIVE_FOLDER_ETAG = "LATEST_ARCHIVE_FOLDER_ETAG"

        const val PREFERENCE_BACKUP_ON_SERVER = ".mobile_preference"
        const val PREFERENCE_BACKUP_SEPARATOR = "\u0000"
        const val SIDECAR_FILENAME_SUFFIX = "-sidecar"
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
        private const val ETAG_MISSING = "ETAG_MISSING"

        const val BLOG_FOLDER = ".__picoblog__"
        const val BLOG_CONTENT_FOLDER = "${BLOG_FOLDER}/content"
        private const val BLOG_ASSETS_FOLDER = "${BLOG_FOLDER}/assets"
        const val THEME_CASCADE = "cascade"
        const val THEME_MAGAZINE = "magazine"
        const val THEME_TIMELINE = "timeline"
        private const val INDEX_FILE = "index.md"
        const val MIME_TYPE_MARKDOWN = "text/markdown"
        private const val ASSETS_URL = "%assets_url%"
        private const val YAML_HEADER_INDEX =
            """
                ---
                Title: %s
                Author: %s
                Host: %s
                Template: index
                Robots: noindex, nofollow, noimageindex
                Purpose: pico_categories_page
                numPerPage: 12
                ---
            """
        private const val YAML_HEADER_BLOG =
            """
                ---
                Title: %s
                Template: single
                Date: %s
                Thumbnail: %s
                Featured: %s
                Theme: %s
                Robots: noindex, nofollow, noimageindex
                Purpose: pico_categories_page
                ---
            """

        // Cascade theme
        private const val CONTENT_CASCADE =
            """
                <div class="fh5co-grid">
                <div class="fh5co-col-1">
                %s
                </div>
                
                <div class="fh5co-col-2">
                %s
                </div>
                </div>
            """
        private const val ITEM_CASCADE =
            """
                <div class="fh5co-item animate-box">
                <div class="polaroid">
                %s
                %s
                </div>
                </div>
                
            """
        private const val ITEM_CASCADE_CAPTION =
            """
                <div class="polaroid-caption">%s</div>
            """
        /*
                    usual responsive image
                    """
                        <figure><img src="%s" class="img-responsive"><figcaption-epic>%s</figcaption-epic></figure>
                    """
                    responsive image with zoom
                    """
                        <figure><a href="%s" class="image-popup"><img src="%s"><div class="fh5co-item-text-wrap"><div class="fh5co-item-text"><h2><i class="icon-zoom-in"></i></h2></div></div></a><figcaption-epic>%s</figcaption-epic></figure>
                    """
        */

        // Magazine theme
        private const val ITEM_MAGAZINE_LEFT =
            """
                <div class="row rp-b">
                <div class="col-lg-6 col-md-12 animate-box">
                %s
                </div>
                <div class="col-lg-6 col-md-12 cp-l animate-box">
                %s
                </div>
                </div>
                
                
            """
        private const val ITEM_MAGAZINE_RIGHT =
            """
                <div class="row rp-b">
                <div class="col-lg-6 col-lg-push-6 col-md-12 col-md-push-0 animate-box">
                %s
                </div>
                <div class="col-lg-6 col-lg-pull-6 col-md-12 col-md-pull-0 cp-r animate-box">
                %s
                </div>
                </div>
                
                
            """
        private const val ITEM_MAGAZINE_GRID =
            """
                <div class="col-md-4 animate-box">
                %s
                </div>
                
            """
        private const val ITEM_MAGAZINE_PHOTO =
            """
                <figure><img src="%s" class="img-responsive"></figure>
            """
        private const val ITEM_MAGAZINE_VIDEO =
            """
                <figure><video controls class="img-responsive"><source src="%s" type="%s"></video></figure>
            """

        // Timeline theme
        private const val IN_YEARS = 1
        private const val IN_MONTHS = 2
        private const val IN_DAYS = 3
        private const val IN_ONE_DAY = 4

        private const val ITEM_TIMELINE_SESSION_DIV_HEADER =
            """
                <div class="cd-timeline">
                
            """
        private const val ITEM_TIMELINE_SESSION_START =
            """
                <div class="cd-timeline-block"><div class="cd-year">%s</div></div>
                <div class="cd-timeline">
                
            """
        private const val ITEM_TIMELINE_SESSION_END =
            """
                %s
                </div>
                
            """
        private const val ITEM_TIMELINE_CONTAINER =
            """
                <div class="cd-timeline-block">
                <div class="cd-timeline-img"></div>
                <div class="cd-timeline-content">
                <div>%s</div>
                <p>%s</p>
                <span class="cd-date">%s</span>
                </div>
                </div>
                
            """
        private const val ITEM_GENERAL_PHOTO =
            """
                <img class="img-responsive" src="%s" />
            """
        private const val ITEM_GENERAL_VIDEO =
            """
                <video controls class="img-responsive"><source src="%s" type="%s"></video>
            """
        private const val ITEM_TIMELINE_BLOCK_VOID =
            """
                <div class="cd-timeline-block" />
                
            """
/*
        private const val SOCIAL_LINKS =
            """
                <ul class="fh5co-social">
                %s
                </ul>
            """

        private const val TWITTER_SOCIAL_LINK =
            """
                <li><a href="https://twitter.com/intent/tweet?url={{ pages["index"].meta.host }}{{ current_page.url }}&text={{ meta.title }}" target="_blank"><i class="icon-twitter"></i></a></li>
            """

        private const val FACEBOOK_SOCIAL_LINK =
            """
                <li><a href="https://www.facebook.com/sharer/sharer.php?u={{ pages["index"].meta.host }}{{ current_page.url }}&t={{ meta.title }}" target="_blank"><i class="icon-facebook2"></i></a></li>
            """

        private const val SINA_WEIBO_SOCIAL_LINK =
            """
                <li><a href="https://service.weibo.com/share/share.php?url={{ pages["index"].meta.host }}{{ current_page.url }}&title={{ meta.title }}&pic={{ meta.featured|link }}&content=utf-8" target="_blank"><i class="icon-sina-weibo"></i></a></li>
            """

        private const val QZONE_SOCIAL_LINK =
            """
                <li><a href="https://sns.qzone.qq.com/cgi-bin/qzshare/cgi_qzshare_onekey?url={{ pages["index"].meta.host }}{{ current_page.url }}&title={{ meta.title }}&pics={{ meta.featured|link }}&summary={{ meta.description|striptags }}" target="_blank"><i class="icon-qzone"></i></a></li>
            """

        private const val DOUBAN_SOCIAL_LINK =
            """
                <li><a href="https://www.douban.com/recommend/?url={{ pages["index"].meta.host }}{{ current_page.url }}&title={{ meta.title }}" target="_blank"><i class="icon-douban"></i></a></li>
            """
*/
    }
}