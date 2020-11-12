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
import android.os.Bundle
import android.util.Log
import androidx.preference.PreferenceManager
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.Album
import site.leos.apps.lespas.album.AlbumRepository
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.photo.PhotoRepository
import java.io.IOException
import javax.xml.namespace.QName

class SyncAdapter @JvmOverloads constructor(private val application: Application, autoInitialize: Boolean, allowParallelSyncs: Boolean = false
) : AbstractThreadedSyncAdapter(application.baseContext, autoInitialize, allowParallelSyncs){

    override fun onPerformSync(account: Account, extras: Bundle, authority: String, provider: ContentProviderClient, syncResult: SyncResult) {
        try {
            val order = extras.getInt(ACTION)   // Return 0 when no mapping of ACTION found
            Log.e("*****", order.toString())

            val resourceRoot: String
            val sardine =  OkHttpSardine()

            // Initialize sardine library
            AccountManager.get(application).run {
                val userName = getUserData(account, context.getString(R.string.nc_userdata_username))
                val serverRoot = getUserData(account, context.getString(R.string.nc_userdata_server))
                sardine.setCredentials(userName, peekAuthToken(account, serverRoot), true)
                resourceRoot = serverRoot + context.getString(R.string.dav_files_endpoint) + userName + context.getString(R.string.lespas_base_folder_name)
            }

            // Make sure lespas base directory is there
            if (!sardine.exists(resourceRoot)) {
                sardine.createDirectory(resourceRoot)
                return
            }

            val albumRepository = AlbumRepository(application)
            val photoRepository = PhotoRepository(application)
            val actionRepository = ActionRepository(application)

            // Processing pending actions
            if (order == SYNC_LOCAL_CHANGES) {
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
                            Log.e("**********", "removing ${action.fileName}")
                            sardine.delete(action.fileName)
                            // TODO need to update album's etag
                        }
                        Action.ACTION_DELETE_DIRECTORY_ON_SERVER -> {
                            sardine.delete("$resourceRoot/${action.fileName}")      // ${action.filename} is the directory name set when action created
                        }
                        Action.ACTION_ADD_FILES_ON_SERVER -> {

                        }
                        Action.ACTION_ADD_DIRECTORY_ON_SERVER -> {
                            sardine.createDirectory("$resourceRoot/${action.fileName}")
                        }
                        Action.ACTION_MODIFY_ALBUM_ON_SERVER -> {

                        }
                    }

                    // TODO: Error retry strategy
                    actionRepository.deleteAllActions()
                }
            } else {
                Log.e("**********", "sync remote changes")
                // Compare remote and local album list
                val localAlbums = albumRepository.getSyncStatus()
                val pendingUpdate: MutableList<Album> = mutableListOf()
                sardine.list(resourceRoot, FOLDER_CONTENT_DEPTH, NC_PROFIND_PROP).drop(1).run {
                    val remoteAlbum = mutableListOf<String>()
                    forEach { album ->
                        remoteAlbum.add(album.customProps[OC_UNIQUE_ID]!!)
                        if (album.isDirectory) {
                            if (localAlbums[album.customProps[OC_UNIQUE_ID]!!] != album.etag) {
                                Log.e("=======", "album changed: ${album.name} r_etag:${album.etag} l_etag:${localAlbums[album.customProps[OC_UNIQUE_ID]!!]}")
                                pendingUpdate.add(Album(album.customProps[OC_UNIQUE_ID]!!, album.name, null, null, "", 0, album.modified, Album.BY_DATE_TAKEN_ASC, album.etag, 0))
                            }
                        }
                    }
                    Log.e("======", "changed albums:${pendingUpdate.size}")

                    // Delete those albums not exist on server
                    for (localAlbum in localAlbums) {
                        if (!remoteAlbum.contains(localAlbum.key)) {
                            Log.e("=======", "deleting album: ${localAlbum.key}")
                            albumRepository.deleteByIdSync(localAlbum.key)
                        }
                    }
                }

                // Sync each changed album
                val pendingDownload = mutableListOf<Photo>()
                val remotePhotos = mutableListOf<String>()
                for (album in pendingUpdate) {
                    // Update album first, so that it's photos can be insert without FOREIGN KEY constraint failed
                    albumRepository.upsertSync(album)

                    var count = 0
                    val localPhotos = photoRepository.getSyncStatus(album.id)
                    sardine.list("$resourceRoot/${album.name}", FOLDER_CONTENT_DEPTH, NC_PROFIND_PROP).run {
                        forEach { remotePhoto ->
                            if (remotePhoto.contentType.startsWith("image", true)) {
                                // Accumulate remote photos list
                                remotePhotos.add(remotePhoto.customProps[OC_UNIQUE_ID]!!)

                                count++
                                if (localPhotos[remotePhoto.customProps[OC_UNIQUE_ID]!!] != remotePhoto.etag) {
                                    Log.e("=======", "updating photo: ${remotePhoto.name} r_etag:${remotePhoto.etag} l_etag:${localPhotos[remotePhoto.customProps[OC_UNIQUE_ID]!!]}")
                                    pendingDownload.add(
                                        Photo(remotePhoto.customProps[OC_UNIQUE_ID]!!,
                                                album.id,
                                                "$resourceRoot/${album.name}/${remotePhoto.name}",      // Use full url for easy Glide load
                                                remotePhoto.etag, null, remotePhoto.modified, 0))
                                }
                            }
                        }
                        //Log.e("===========", "total:$count changed photos:${pendingDownload.size}")

                        // Update photo
                        for (photo in pendingDownload) photoRepository.upsertSync(photo)

                        // Delete those photos not exist on server
                        for (localPhoto in localPhotos) {
                            if (!remotePhotos.contains(localPhoto.key)) {
                                Log.e("=======", "deleting photo: ${localPhoto.key}")
                                photoRepository.deleteByIdSync(localPhoto.key)
                            }
                        }

                        // Recycle the list
                        remotePhotos.clear()
                        pendingDownload.clear()
                    }
                }
            }

            // Clear status counters
            syncResult.stats.clear()
        } catch (e: IOException) {
            syncResult.stats.numAuthExceptions++
            Log.e("************", e.message.toString())
        } catch (e: AuthenticatorException) {
            syncResult.stats.numAuthExceptions++
            e.printStackTrace()
        } catch (e:Exception) {
            e.printStackTrace()
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