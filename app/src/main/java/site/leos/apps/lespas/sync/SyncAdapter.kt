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
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.Album
import site.leos.apps.lespas.album.AlbumRepository
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.photo.PhotoRepository
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import javax.net.ssl.SSLHandshakeException
import javax.xml.namespace.QName

class SyncAdapter @JvmOverloads constructor(private val application: Application, autoInitialize: Boolean, allowParallelSyncs: Boolean = false
) : AbstractThreadedSyncAdapter(application.baseContext, autoInitialize, allowParallelSyncs){

    override fun onPerformSync(account: Account, extras: Bundle, authority: String, provider: ContentProviderClient, syncResult: SyncResult) {
        try {
            val order = extras.getInt(ACTION)   // Return 0 when no mapping of ACTION found

            val resourceRoot: String
            val sardine =  OkHttpSardine()

            // Initialize sardine library
            AccountManager.get(application).run {
                val userName = getUserData(account, context.getString(R.string.nc_userdata_username))
                val serverRoot = getUserData(account, context.getString(R.string.nc_userdata_server))
                sardine.setCredentials(userName, peekAuthToken(account, serverRoot), true)
                resourceRoot = serverRoot + context.getString(R.string.dav_files_endpoint) + userName + context.getString(R.string.lespas_base_folder_name)
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
                    // Check network type on every loop
                    if (PreferenceManager.getDefaultSharedPreferences(application)   // SyncService pass applicationContext to us
                            .getBoolean(application.getString(R.string.wifionly_pref_key), true)
                    ) {
                        if ((application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered) {
                            // Delay next sync for at least 30 minutes
                            syncResult.delayUntil = (System.currentTimeMillis() / 1000) + 30 * 60
                            return
                        }
                    }

                    when (action.action) {
                        Action.ACTION_DELETE_FILES_ON_SERVER -> {
                            sardine.delete("$resourceRoot/${Uri.encode(action.folderName)}/${Uri.encode(action.fileName.substringAfterLast('/'))}")
                            // TODO need to update album's etag to reduce network usage during next remote sync
                        }
                        Action.ACTION_DELETE_DIRECTORY_ON_SERVER -> {
                            sardine.delete("$resourceRoot/${Uri.encode(action.folderName)}")
                        }
                        Action.ACTION_ADD_FILES_ON_SERVER -> {
                            // Upload to server and verify
                            Log.e("++++++++", "uploading $resourceRoot/${action.folderName}/${action.fileName}")
                            sardine.put("$resourceRoot/${Uri.encode(action.folderName)}/${Uri.encode(action.fileName)}", File(application.filesDir, action.fileName), "image/*")
                            File(application.filesDir, action.fileName).delete()
                            // TODO shall we update local database here or leave it to next SYNC_REMOTE_CHANGES round?
                        }
                        Action.ACTION_ADD_DIRECTORY_ON_SERVER -> {
                            sardine.createDirectory("$resourceRoot/${Uri.encode(action.folderName)}")

                            // Verify it. TODO is this necessary??
                            sardine.exists("$resourceRoot/${Uri.encode(action.folderName)}")
                        }
                        Action.ACTION_MODIFY_ALBUM_ON_SERVER -> {
                            TODO()
                        }
                    }

                    // TODO: Error retry strategy, directory etag update, etc.
                    actionRepository.deleteSync(action)
                }
            //} else {
                Log.e("**********", "sync remote changes")
                // Compare remote and local album list
                val localAlbumsETags = albumRepository.getETagsMap()
                val localAlbumsNames = albumRepository.getNamesMap()
                val changedAlbums: MutableList<Album> = mutableListOf()
                sardine.list(resourceRoot, FOLDER_CONTENT_DEPTH, NC_PROFIND_PROP).drop(1).run {
                    val remoteAlbumIds = mutableListOf<String>()
                    var albumId: String
                    forEach { album ->
                        albumId = album.customProps[OC_UNIQUE_ID]!!
                        if (album.isDirectory) {
                            // Find changed/added items
                            remoteAlbumIds.add(albumId)
                            if (localAlbumsETags[albumId] != album.etag) {   // Also matched with new album id
                                Log.e("=======", "album changed: ${album.name} r_etag:${album.etag} l_etag:${localAlbumsETags[albumId]}")
                                changedAlbums.add(Album(albumId, album.name, null, null, "", 0, album.modified, Album.BY_DATE_TAKEN_ASC, album.etag, 0))
                            } else if (localAlbumsNames[albumId] != album.name) {
                                // Rename operation on server would not change item's own eTag, have to sync name changes here
                                albumRepository.changeName(albumId, album.name)
                            }
                        }
                    }

                    // Delete those albums not exist on server, happens when user delete album on the server
                    for (localAlbum in localAlbumsETags) {
                        if (!remoteAlbumIds.contains(localAlbum.key)) {
                            Log.e("=======", "deleting album: ${localAlbum.key}")
                            albumRepository.deleteByIdSync(localAlbum.key)
                        }
                    }
                }

                // Sync each changed album
                val changedPhotos = mutableListOf<Photo>()
                val remotePhotoIds = mutableListOf<String>()
                for (album in changedAlbums) {
                    // Update album first, so that it's photos can be insert without FOREIGN KEY constraint failed
                    albumRepository.upsertSync(album)

                    val localPhotoETags = photoRepository.getETagsMap(album.id)
                    val localPhotoNames = photoRepository.getNamesMap(album.id)
                    var photoId: String
                    sardine.list("$resourceRoot/${Uri.encode(album.name)}", FOLDER_CONTENT_DEPTH, NC_PROFIND_PROP).forEach { remotePhoto ->
                        if (remotePhoto.contentType.startsWith("image", true)) {
                            // Accumulate remote photos list
                            photoId = remotePhoto.customProps[OC_UNIQUE_ID]!!
                            remotePhotoIds.add(photoId)
                            if (localPhotoETags[photoId] != remotePhoto.etag) { // Also matches new photos
                                Log.e("=======", "updating photo: ${remotePhoto.name} r_etag:${remotePhoto.etag} l_etag:${localPhotoETags[photoId]}")
                                changedPhotos.add(
                                    Photo(photoId, album.id,
                                            "$resourceRoot/${album.name}/${remotePhoto.name}",      // Use full url for easy Glide load
                                            remotePhoto.etag, null, remotePhoto.modified, 0))
                            } else if (localPhotoNames[photoId]?.substringAfterLast('/') != remotePhoto.name) {
                                // Rename operation on server would not change item's own eTag, have to sync name changes here
                                photoRepository.changeName(photoId, "$resourceRoot/${album.name}/${remotePhoto.name}")
                            }
                        }
                    }
                    // Update photo
                    for (photo in changedPhotos) photoRepository.upsertSync(photo)

                    // Delete those photos not exist on server, happens when user delete photos on the server
                    for (localPhoto in localPhotoETags) {
                        if (!remotePhotoIds.contains(localPhoto.key)) {
                            Log.e("=======", "deleting photo: ${localPhoto.key}")
                            photoRepository.deleteByIdSync(localPhoto.key)
                        }
                    }

                    // Recycle the list
                    remotePhotoIds.clear()
                    changedPhotos.clear()
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
        }
        catch (e: AuthenticatorException) {
            syncResult.stats.numAuthExceptions++
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