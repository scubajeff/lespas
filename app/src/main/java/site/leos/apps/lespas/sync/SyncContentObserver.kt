package site.leos.apps.lespas.sync

import android.accounts.Account
import android.content.ContentResolver
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle

class SyncContentObserver(private val account: Account): ContentObserver(null) {
    override fun onChange(selfChange: Boolean) {
        onChange(selfChange, null)
    }

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        ContentResolver.requestSync(account, SyncContentProvider.AUTHORITIES, Bundle().apply { putInt(SyncAdapter.ACTION, SyncAdapter.SYNC_LOCAL_CHANGES) })
    }
}
