package site.leos.apps.lespas.sync

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Application
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.Context
import android.content.SyncResult
import android.os.Bundle
import android.util.Log
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.Album
import site.leos.apps.lespas.album.AlbumRepository
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.photo.PhotoRepository
import javax.xml.namespace.QName

class SyncAdapter @JvmOverloads constructor(context: Context, autoInitialize: Boolean, allowParallelSyncs: Boolean = false
) : AbstractThreadedSyncAdapter(context, autoInitialize, allowParallelSyncs){

    override fun onPerformSync(account: Account, extras: Bundle, authority: String, provider: ContentProviderClient, syncResult: SyncResult) {
        val action: Int

        try {
            var resourceRoot: String
            val sardine =  OkHttpSardine()

            action = extras.getInt(ACTION) ?: ACTION_SYNC_WITH_SERVER

            // Initialize sardine library
            AccountManager.get(context).run {
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

            // TODO: could be const
            val ncProps = setOf(
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

            val albumRepository = AlbumRepository.getRepository(context as Application)     // SyncService pass applicationContext to us
            val photoRepository = PhotoRepository.getRepository(context as Application)     // SyncService pass applicationContext to us

            when(action) {
                ACTION_SYNC_WITH_SERVER-> {
                    // Compare remote and local album list
                    val localAlbums = albumRepository.getSyncStatus()
                    val pendingUpdate: MutableList<Album> = mutableListOf()
                    sardine.list(resourceRoot, FOLDER_CONTENT_DEPTH, ncProps).drop(1).run {
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
                        //Log.e("======", "changed albums:${pendingUpdate.size}")

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
                    for(album in pendingUpdate) {
                        // Update album first, so that it's photos can be insert without FOREIGN KEY constraint failed
                        albumRepository.upsertSync(album)

                        var count = 0
                        val localPhotos = photoRepository.getSyncStatus(album.id)
                        sardine.list("$resourceRoot/${album.name}", FOLDER_CONTENT_DEPTH, ncProps).run {
                            forEach { remotePhoto->
                                if (remotePhoto.contentType.startsWith("image", true)) {
                                    // Accumulate remote photos list
                                    remotePhotos.add(remotePhoto.customProps[OC_UNIQUE_ID]!!)

                                    count++
                                    if (localPhotos[remotePhoto.customProps[OC_UNIQUE_ID]!!] != remotePhoto.etag) {
                                        Log.e("=======", "updating photo: ${remotePhoto.name} r_etag:${remotePhoto.etag} l_etag:${localPhotos[remotePhoto.customProps[OC_UNIQUE_ID]!!]}")
                                        pendingDownload.add(Photo(remotePhoto.customProps[OC_UNIQUE_ID]!!, album.id, remotePhoto.name, remotePhoto.etag, null, remotePhoto.modified, 0))
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

                ACTION_DELETE_FILES_ON_SERVER-> {

                }
            }

        } catch (e:Exception) {
            Log.e("=======", e.toString())
            //e.printStackTrace()
        }
    }

    companion object {
        const val ACTION = "ACTION"
        const val ACTION_SYNC_WITH_SERVER = 0
        const val ACTION_DELETE_FILES_ON_SERVER = 1
        const val ACTION_DELETE_DIRECTORY_ON_SERVER = 2
        const val ACTION_ADD_FILES_ON_SERVER = 3
        const val ACTION_ADD_DIRECTORY_ON_SERVER = 4
        const val ACTION_MODIFY_ALBUM_ON_SERVER = 5

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
    }
}