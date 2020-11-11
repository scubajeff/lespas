package site.leos.apps.lespas.sync

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.net.Uri
import site.leos.apps.lespas.BuildConfig
import site.leos.apps.lespas.LespasDatabase

class SyncContentProvider: ContentProvider() {
    private val sUriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
        addURI(AUTHORITIES, Action.TABLE_NAME, ACTION_ALL)
        addURI(AUTHORITIES, "${Action.TABLE_NAME}/#", ACTION_WITH_ID)
    }

    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = when(sUriMatcher.match(uri)) {
        ACTION_ALL, ACTION_WITH_ID-> {
            val cursor: Cursor
            LespasDatabase.getDatabase(context!!.applicationContext).actionDao().run {
                cursor = if (sUriMatcher.match(uri) == ACTION_ALL) getAllCursor() else getByIdCursor(ContentUris.parseId(uri))
            }
            cursor
        }
        else -> throw UnsupportedOperationException("Unknown URI: $uri")
    }

    override fun getType(uri: Uri): String? = when(sUriMatcher.match(uri)) {
        ACTION_ALL-> "vnd.android.cursor.dir/vnd.$AUTHORITIES.${Action.TABLE_NAME}"
        ACTION_WITH_ID-> "vnd.android.cursor.item/vnd.$AUTHORITIES.${Action.TABLE_NAME}"
        else-> throw IllegalArgumentException("Unsupported URI: $uri")
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = when(sUriMatcher.match(uri)) {
        ACTION_ALL-> {
            val resultUri = ContentUris.withAppendedId(uri, LespasDatabase.getDatabase(context!!.applicationContext).actionDao().insertSync(Action.fromContentValues(values!!)))
            context!!.contentResolver.notifyChange(resultUri, null)
            resultUri
        }
        ACTION_WITH_ID -> throw IllegalArgumentException("Invalid URI, cannot insert with ID: $uri")
        else -> throw IllegalArgumentException("Unknown URI: $uri")
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = when(sUriMatcher.match(uri)) {
        ACTION_ALL-> throw IllegalArgumentException("Invalid URI, cannot delete without ID $uri")
        ACTION_WITH_ID-> {
            val count = LespasDatabase.getDatabase(context!!.applicationContext).actionDao().deleteById(ContentUris.parseId(uri))
            context!!.contentResolver.notifyChange(uri, null)
            count
        }
        else -> throw UnsupportedOperationException("Unknown URI: $uri")
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = when(sUriMatcher.match(uri)) {
        ACTION_ALL-> throw IllegalArgumentException("Invalid URI, cannot update without ID $uri")
        ACTION_WITH_ID-> {
            val count = LespasDatabase.getDatabase(context!!.applicationContext).actionDao().updateSync(Action.fromContentValues(values!!))
            context!!.contentResolver.notifyChange(uri, null)
            count
        }
        else -> throw UnsupportedOperationException("Unknown URI: $uri")
    }

    companion object {
        const val AUTHORITIES = "${BuildConfig.APPLICATION_ID}.syncprovider"    // Matched with setting in module's build.gradle
        const val URI = "content://$AUTHORITIES/${Action.TABLE_NAME}"

        const val ACTION_ALL = 1
        const val ACTION_WITH_ID = 2
    }
}