package site.leos.apps.lespas.settings

import android.accounts.AccountManager
import android.app.ActivityManager
import android.content.Context
import android.os.Bundle
import android.view.Menu
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceDialogFragmentCompat
import androidx.preference.PreferenceFragmentCompat
import site.leos.apps.lespas.R

class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey)

        setHasOptionsMenu(true)
    }

    override fun onResume() {
        super.onResume()

        (activity as? AppCompatActivity)?.supportActionBar?.run {
            title = getString(R.string.settings_title)
            setDisplayHomeAsUpEnabled(true)
        }
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)

        menu.run { findItem(R.id.action_settings).isVisible = false }
    }

    override fun onDisplayPreferenceDialog(preference: Preference?) {
        val logoutDialog = (preference as? LogoutDialogPreference)?.apply {
            dialogTitle = ""
            dialogMessage = getString(R.string.logout_dialog_msg, AccountManager.get(context).accounts[0].name)
        }

        if (logoutDialog != null) {
            LogoutDialogPrefCompat.newInstance(logoutDialog.key).let {
                it.setTargetFragment(this, 0)
                it.positiveResult = {
                    // Remove account and clear all use data
                    AccountManager.get(context).apply {
                        removeAccountExplicitly(getAccountsByType(getString(R.string.account_type_nc))[0])
                    }
                    (context?.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).clearApplicationUserData()
                    activity?.finish()
                }
                it.show(parentFragmentManager, null)
            }
        } else super.onDisplayPreferenceDialog(preference)
    }

    class LogoutDialogPrefCompat: PreferenceDialogFragmentCompat() {
        lateinit var positiveResult: ()->Unit

        override fun onDialogClosed(positiveResult: Boolean) {
            if (positiveResult) positiveResult()
        }

        companion object {
            // "key" is the value of PreferenceDialogFragmentCompat's protected member ARG_KEY
            fun newInstance(key: String) = LogoutDialogPrefCompat().apply { arguments = Bundle().apply { putString("key", key) } }
        }
    }
}