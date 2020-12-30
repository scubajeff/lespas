package site.leos.apps.lespas.settings

import android.accounts.AccountManager
import android.app.ActivityManager
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.ConfirmDialogFragment
import site.leos.apps.lespas.sync.SyncAdapter

class SettingsFragment : PreferenceFragmentCompat(), ConfirmDialogFragment.OnResultListener {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey)

        findPreference<ListPreference>(getString(R.string.auto_theme_perf_key))?.let {
            it.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
                AppCompatDelegate.setDefaultNightMode((newValue as String).toInt())
                true
            }
        }

        findPreference<SwitchPreferenceCompat>(getString(R.string.wifionly_pref_key))?.let {
            it.title = getString(if (it.isChecked) R.string.wifionly_title else R.string.wifionly_off_title)
            it.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, _ ->
                it.title = getString(if (it.isChecked) R.string.wifionly_off_title else R.string.wifionly_title)
                true
            }
        }

        findPreference<SwitchPreferenceCompat>(getString(R.string.snapseed_pref_key))?.let {
            it.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { pref, _ ->
                // Request WRITE_EXTERNAL_STORAGE permission if user want to integrate with Snapseed
                if (ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    if (shouldShowRequestPermissionRationale(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                        if (parentFragmentManager.findFragmentByTag(CONFIRM_DIALOG) == null) {
                            ConfirmDialogFragment.newInstance(getString(R.string.storage_access_permission_rationale), getString(R.string.proceed_request)).let {
                                it.setTargetFragment(this, PERMISSION_RATIONALE_REQUEST_CODE)
                                it.show(parentFragmentManager, CONFIRM_DIALOG)
                            }
                        }
                    } else requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), WRITE_STORAGE_PERMISSION_REQUEST)

                    // Set Snapseed integration to False if we don't have WRITE_EXTERNAL_STORAGE permission
                    (pref as SwitchPreferenceCompat).isChecked = false
                    false

                } else true
            }
        }

        findPreference<SwitchPreferenceCompat>(getString(R.string.snapseed_replace_pref_key))?.let {
            it.title = getString(if (it.isChecked) R.string.snapseed_replace_title else R.string.snapseed_add_title)
            it.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, _ ->
                it.title = getString(if (it.isChecked) R.string.snapseed_add_title else R.string.snapseed_replace_title)
                true
            }
        }
    }

    override fun onResume() {
        super.onResume()

        (activity as? AppCompatActivity)?.supportActionBar?.run {
            title = getString(R.string.settings_title)
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowTitleEnabled(true)
        }
    }

    override fun onPreferenceTreeClick(preference: Preference?): Boolean =
        when (preference?.key) {
            getString(R.string.sync_pref_key) -> {
                if (preferenceManager.sharedPreferences.getBoolean(preference.key, false))
                    ContentResolver.addPeriodicSync(
                        AccountManager.get(context).accounts[0],
                        getString(R.string.sync_authority),
                        Bundle().apply { putInt(SyncAdapter.ACTION, SyncAdapter.SYNC_REMOTE_CHANGES) },
                        6 * 60 * 60
                    )
                else ContentResolver.removePeriodicSync(AccountManager.get(context).accounts[0], getString(R.string.sync_authority), Bundle.EMPTY)

                true
            }
            getString(R.string.logout_pref_key) -> {
                if (parentFragmentManager.findFragmentByTag(CONFIRM_DIALOG) == null) {
                    ConfirmDialogFragment.newInstance(getString(R.string.logout_dialog_msg, AccountManager.get(context).accounts[0].name), getString(R.string.yes_logout)).let {
                        it.setTargetFragment(this, LOGOUT_CONFIRM_REQUEST_CODE)
                        it.show(parentFragmentManager, CONFIRM_DIALOG)
                    }
                }

                true
            }
            getString(R.string.auto_theme_perf_key) -> {
                preference.sharedPreferences.getString(getString(R.string.auto_theme_perf_key), getString(R.string.theme_auto_values))?.let {
                    AppCompatDelegate.setDefaultNightMode(it.toInt())
                }

                true
            }
            else -> super.onPreferenceTreeClick(preference)
        }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == WRITE_STORAGE_PERMISSION_REQUEST) {
            findPreference<SwitchPreferenceCompat>(getString(R.string.snapseed_pref_key))?.isChecked = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onResult(positive: Boolean, requestCode: Int) {
        if (positive) {
            when (requestCode) {
                LOGOUT_CONFIRM_REQUEST_CODE -> {
                    AccountManager.get(context).apply { removeAccountExplicitly(getAccountsByType(getString(R.string.account_type_nc))[0]) }
                    (context?.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).clearApplicationUserData()
                    activity?.finish()
                }
                PERMISSION_RATIONALE_REQUEST_CODE -> {
                    requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), WRITE_STORAGE_PERMISSION_REQUEST)
                }
            }
        } else {
            // If user choose not to proceed with permission granting, reset Snapseed integration to False
            if (requestCode == PERMISSION_RATIONALE_REQUEST_CODE) findPreference<SwitchPreferenceCompat>(getString(R.string.snapseed_pref_key))?.isChecked = false
        }
    }

    companion object {
        private const val CONFIRM_DIALOG = "CONFIRM_DIALOG"
        private const val LOGOUT_CONFIRM_REQUEST_CODE = 0
        private const val PERMISSION_RATIONALE_REQUEST_CODE = 1
        private const val WRITE_STORAGE_PERMISSION_REQUEST = 8989
    }

}