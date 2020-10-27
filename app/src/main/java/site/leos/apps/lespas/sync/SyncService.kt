package site.leos.apps.lespas.sync

import android.app.Service
import android.content.Intent
import android.os.IBinder

class SyncService : Service() {
    override fun onCreate() {
        synchronized(sSyncAdapterLock) {
            sSyncAdapter = sSyncAdapter ?: SyncAdapter(applicationContext, true)
        }
    }
    override fun onBind(intent: Intent?): IBinder? {
        return sSyncAdapter?.syncAdapterBinder ?: throw IllegalStateException()
    }

    companion object {
        private var sSyncAdapter: SyncAdapter? = null
        private val sSyncAdapterLock = Any()
    }
}