package site.leos.apps.lespas.sync

import android.accounts.Account
import android.accounts.AccountManager
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.Context
import android.content.SyncResult
import android.os.Bundle
import android.util.Log
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import site.leos.apps.lespas.R
import javax.xml.namespace.QName

class SyncAdapter @JvmOverloads constructor(context: Context, autoInitialize: Boolean, allowParallelSyncs: Boolean = false
) : AbstractThreadedSyncAdapter(context, autoInitialize, allowParallelSyncs){

    override fun onPerformSync(account: Account, extras: Bundle, authority: String, provider: ContentProviderClient, syncResult: SyncResult) {
        try {
            var resourceRoot: String
            val sardine =  OkHttpSardine()

            // Initialize sardine library
            AccountManager.get(context).run {
                val userName = getUserData(account, context.getString(R.string.nc_userdata_username))
                val serverRoot = getUserData(account, context.getString(R.string.nc_userdata_server))
                sardine.setCredentials(userName, peekAuthToken(account, serverRoot), true)
                resourceRoot = serverRoot + context.getString(R.string.dav_files_endpoint) + userName + context.getString(R.string.lespas_base_folder_name)
            }

            // Make sure lespas base directory is there
            if (!sardine.exists(resourceRoot)) sardine.createDirectory(resourceRoot)

            val ncProps = setOf(
                QName(DAV_NS, GETETAG_LOCALPART, "D"),
                QName(DAV_NS, GETLASTMODIFIED_LOCALPART, "D"),
                QName(DAV_NS, GETCONTENTTYPE_LOCALPART, "D"),
                QName(DAV_NS, GETRESOURCETYPE_LOCALPART, "D"),
                QName(OWNCLOUD_NS, UNIQUE_ID_LOCALPART, "oc"),
                QName(OWNCLOUD_NS, SHARETYPE_LOCALPART, "oc"),
                QName(NEXTCLOUD_NS, HASPREVIEW_LOCALPART, "nc"),
                QName(OWNCLOUD_NS, CHECKSUMS_LOCALPART, "oc"),
                QName(OWNCLOUD_NS, SIZE_LOCALPART, "oc"),
                QName(OWNCLOUD_NS, DATA_FINGERPRINT_LOCALPART, "oc")
            )
            sardine.list(resourceRoot, FOLDER_CONTENT_DEPTH, ncProps).forEach { album->
                Log.e("=======", "${album.name} ${album.etag} ${album.isDirectory} ${album.contentType} ${album.path} ${album.modified} ${album.customProps}")
            }


        } catch (e:Exception) {
            e.printStackTrace()
        }
    }

    companion object {
        // PROPFIND properties namespace
        const val DAV_NS = "DAV:"
        const val OWNCLOUD_NS = "http://owncloud.org/ns"
        const val NEXTCLOUD_NS = "http://nextcloud.org/ns"

        // OC and NC defined localpart
        const val UNIQUE_ID_LOCALPART = "fileid"
        const val SHARETYPE_LOCALPART = "share-types"
        const val CHECKSUMS_LOCALPART = "checksums"
        const val HASPREVIEW_LOCALPART = "has-preview"
        const val SIZE_LOCALPART = "size"
        const val DATA_FINGERPRINT_LOCALPART = "data-fingerprint"

        // WebDAV defined localpart
        const val GETETAG_LOCALPART = "getetag"
        const val GETLASTMODIFIED_LOCALPART = "getlastmodified"
        const val GETCONTENTTYPE_LOCALPART = "getcontenttype"
        const val GETRESOURCETYPE_LOCALPART = "resourcetype"
        const val GETCONTENTLENGTH_LOCALPART = "getcontentlength"

        const val JUST_FOLDER_DEPTH = 0
        const val FOLDER_CONTENT_DEPTH = 1
    }
}