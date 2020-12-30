package site.leos.apps.lespas.helper

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent

class ShareChooserBroadcastReceiver : BroadcastReceiver() {
    private var dest = ""

    override fun onReceive(context: Context, intent: Intent) {
        dest = intent.getParcelableExtra<ComponentName>(Intent.EXTRA_CHOSEN_COMPONENT)?.packageName!!.substringAfterLast('.')
    }

    fun getDest(): String = dest
    fun clearFlag() { dest = "" }
}

