package site.leos.apps.lespas

import android.accounts.AccountManager
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.preference.PreferenceManager
import site.leos.apps.lespas.sync.SyncAdapter

class SystemBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val accounts = AccountManager.get(context).getAccountsByType(context.getString(R.string.account_type_nc))
        if (accounts.isNotEmpty()) {
            when(intent.action) {
                // When phone owner changed, delete all user data, remove accounts
                DevicePolicyManager.ACTION_DEVICE_OWNER_CHANGED-> {
                    AccountManager.get(context).removeAccount(accounts[0], null, null, null)
                    (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).clearApplicationUserData()
                }

                // When our account has been removed, delete all user data
                AccountManager.ACTION_ACCOUNT_REMOVED -> {
                    if ((intent.extras?.getString(AccountManager.KEY_ACCOUNT_TYPE, "") == context.getString(R.string.account_type_nc)) &&
                        (intent.extras?.getString(AccountManager.KEY_ACCOUNT_NAME, "") == accounts[0].name))
                        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).clearApplicationUserData()
                }

                Intent.ACTION_BOOT_COMPLETED -> {
                    // Turn on periodic sync after bootup
                    if (PreferenceManager.getDefaultSharedPreferences(context).getBoolean(context.getString(R.string.sync_pref_key), false))
                        ContentResolver.addPeriodicSync(
                            AccountManager.get(context).accounts[0],
                            context.getString(R.string.sync_authority),
                            Bundle().apply { putInt(SyncAdapter.ACTION, SyncAdapter.SYNC_REMOTE_CHANGES) },
                            6 * 60 * 60
                        )
                }
            }
        }
    }
}