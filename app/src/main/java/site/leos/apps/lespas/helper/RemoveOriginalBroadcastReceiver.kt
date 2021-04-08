package site.leos.apps.lespas.helper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import site.leos.apps.lespas.sync.AcquiringDialogFragment

class RemoveOriginalBroadcastReceiver(private val removeOriginal:(Boolean) -> Unit): BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent) {
        removeOriginal(intent.getBooleanExtra(AcquiringDialogFragment.BROADCAST_REMOVE_ORIGINAL_EXTRA, false))
    }
}