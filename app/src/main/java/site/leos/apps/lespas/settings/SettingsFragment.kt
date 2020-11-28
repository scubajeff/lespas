package site.leos.apps.lespas.settings

import android.accounts.AccountManager
import android.app.ActivityManager
import android.content.ContentResolver
import android.content.Context
import android.os.Bundle
import android.view.Menu
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.ConfirmDialogFragment
import site.leos.apps.lespas.sync.SyncAdapter

class SettingsFragment : PreferenceFragmentCompat(), ConfirmDialogFragment.OnPositiveConfirmedListener {

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

    override fun onPreferenceTreeClick(preference: Preference?): Boolean {
        when(preference?.key) {
            getString(R.string.sync_pref_key)-> {
                if (preferenceManager.sharedPreferences.getBoolean(preference.key, false))
                    ContentResolver.addPeriodicSync(
                        AccountManager.get(context).accounts[0],
                        getString(R.string.sync_authority),
                        Bundle().apply { putInt(SyncAdapter.ACTION, SyncAdapter.SYNC_LOCAL_CHANGES) },
                        6 * 60 * 60
                    )
                else ContentResolver.removePeriodicSync(AccountManager.get(context).accounts[0], getString(R.string.sync_authority), Bundle.EMPTY)
            }
            getString(R.string.logout_pref_key)-> {
                if (parentFragmentManager.findFragmentByTag(CONFIRM_LOGOUT_DIALOG) == null) {
                    ConfirmDialogFragment.newInstance(getString(R.string.logout_dialog_msg, AccountManager.get(context).accounts[0].name)).let {
                        it.setTargetFragment(this, 0)
                        it.show(parentFragmentManager, CONFIRM_LOGOUT_DIALOG)
                    }
                }
            }
        }
        return super.onPreferenceTreeClick(preference)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)

        menu.run { findItem(R.id.option_menu_settings).isVisible = false }
    }

    companion object {
        private const val CONFIRM_LOGOUT_DIALOG = "CONFIRM_LOGOUT_DIALOG"
    }

    override fun onPositiveConfirmed() {
        AccountManager.get(context).apply { removeAccountExplicitly(getAccountsByType(getString(R.string.account_type_nc))[0]) }
        (context?.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).clearApplicationUserData()
        activity?.finish()
    }
}