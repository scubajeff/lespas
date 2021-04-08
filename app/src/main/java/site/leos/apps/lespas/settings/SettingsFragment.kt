package site.leos.apps.lespas.settings

import android.accounts.AccountManager
import android.app.ActivityManager
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.preference.*
import site.leos.apps.lespas.BuildConfig
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.ConfirmDialogFragment
import site.leos.apps.lespas.helper.Tools
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
            val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) android.Manifest.permission.READ_EXTERNAL_STORAGE else android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(requireContext(), permission) != PackageManager.PERMISSION_GRANTED) it.isChecked = false

            it.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { pref, _ ->
                // Request WRITE_EXTERNAL_STORAGE permission if user want to integrate with Snapseed
                if (ContextCompat.checkSelfPermission(requireContext(), permission) != PackageManager.PERMISSION_GRANTED) {
                    if (shouldShowRequestPermissionRationale(permission)) {
                        if (parentFragmentManager.findFragmentByTag(CONFIRM_DIALOG) == null) {
                            ConfirmDialogFragment.newInstance(getString(R.string.storage_access_permission_rationale), getString(R.string.proceed_request)).let { fragment->
                                fragment.setTargetFragment(this, PERMISSION_RATIONALE_REQUEST_CODE)
                                fragment.show(parentFragmentManager, CONFIRM_DIALOG)
                            }
                        }
                    } else requestPermissions(arrayOf(permission), STORAGE_PERMISSION_REQUEST_FOR_SNAPSEED)

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

        findPreference<SwitchPreferenceCompat>(getString(R.string.cameraroll_backup_pref_key))?.apply {
            val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) android.Manifest.permission.READ_EXTERNAL_STORAGE else android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(requireContext(), permission) != PackageManager.PERMISSION_GRANTED) {
                isChecked = false
                toggleAutoSync(false)
            }

            onPreferenceChangeListener = Preference.OnPreferenceChangeListener { pref, _ ->
                if (ContextCompat.checkSelfPermission(requireContext(), permission) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(permission), STORAGE_PERMISSION_REQUEST_FOR_BACKUP)

                    (pref as SwitchPreferenceCompat).isChecked = false
                    toggleAutoSync(false)
                    false
                }
                else {
                    if ((pref as SwitchPreferenceCompat).isChecked) {
                        findPreference<SwitchPreferenceCompat>(getString(R.string.sync_pref_key))?.let {
                            it.isChecked = false
                            it.isEnabled = true
                        }
                    }
                    else {
                        // Check and disable periodic sync setting if user enable camera roll backup
                        findPreference<SwitchPreferenceCompat>(getString(R.string.sync_pref_key))?.let {
                            it.isChecked = true
                            it.isEnabled = false
                        }
                        // Note down the current timestamp, photos taken later on will be backup
                        with(PreferenceManager.getDefaultSharedPreferences(requireContext().applicationContext)) {
                            if (this.getLong(LAST_BACKUP, 0L) == 0L) this.edit().apply {
                                putLong(LAST_BACKUP, System.currentTimeMillis()/1000)
                                apply()
                            }
                        }
                    }
                    toggleAutoSync(!((pref as SwitchPreferenceCompat).isChecked))
                    true
                }
            }
            summaryOn = getString(R.string.cameraroll_backup_summary, Tools.getDeviceModel())
            // Make sure SYNC preference acts accordingly
            if (isChecked) findPreference<SwitchPreferenceCompat>(getString(R.string.sync_pref_key))?.let {
                it.isChecked = true
                it.isEnabled = false
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
                toggleAutoSync(preference.sharedPreferences.getBoolean(preference.key, false))
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
            getString(R.string.gallery_launcher_pref_key) -> {
                requireActivity().packageManager.apply {
                    setComponentEnabledSetting(
                        ComponentName(BuildConfig.APPLICATION_ID, "${BuildConfig.APPLICATION_ID}.Gallery"),
                        if (preferenceManager.sharedPreferences.getBoolean(preference.key, false)) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                    )
                    setComponentEnabledSetting(
                        ComponentName(BuildConfig.APPLICATION_ID, "${BuildConfig.APPLICATION_ID}.LesPas"),
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP
                    )
                }
                true
            }
            else -> super.onPreferenceTreeClick(preference)
        }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        when(requestCode) {
            STORAGE_PERMISSION_REQUEST_FOR_SNAPSEED-> findPreference<SwitchPreferenceCompat>(getString(R.string.snapseed_pref_key))?.isChecked = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            STORAGE_PERMISSION_REQUEST_FOR_BACKUP-> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    findPreference<SwitchPreferenceCompat>(getString(R.string.cameraroll_backup_pref_key))?.apply {
                        isChecked = true
                        // Check and disable periodic sync setting if user enable camera roll backup
                        findPreference<SwitchPreferenceCompat>(getString(R.string.sync_pref_key))?.let {
                            it.isChecked = true
                            it.isEnabled = false
                        }
                        // Note down the current timestamp, photos taken later on will be backup
                        with(PreferenceManager.getDefaultSharedPreferences(requireContext().applicationContext)) {
                            if (this.getLong(LAST_BACKUP, 0L) == 0L) this.edit().apply {
                                putLong(LAST_BACKUP, System.currentTimeMillis()/1000)
                                apply()
                            }
                        }
                        toggleAutoSync(true)
                    }
                }
            }
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
                    requestPermissions(arrayOf(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) android.Manifest.permission.READ_EXTERNAL_STORAGE else android.Manifest.permission.WRITE_EXTERNAL_STORAGE), STORAGE_PERMISSION_REQUEST_FOR_SNAPSEED)
                }
            }
        } else {
            // If user choose not to proceed with permission granting, reset Snapseed integration to False
            if (requestCode == PERMISSION_RATIONALE_REQUEST_CODE) findPreference<SwitchPreferenceCompat>(getString(R.string.snapseed_pref_key))?.isChecked = false
        }
    }

    private fun toggleAutoSync(on: Boolean) {
        if (on) {
            ContentResolver.setSyncAutomatically(AccountManager.get(context).accounts[0], getString(R.string.sync_authority), true)
            ContentResolver.addPeriodicSync(AccountManager.get(context).accounts[0], getString(R.string.sync_authority), Bundle().apply { putInt(SyncAdapter.ACTION, SyncAdapter.SYNC_REMOTE_CHANGES) },6 * 3600L)
            /*
            ContentResolver.requestSync(SyncRequest.Builder()
                .setSyncAdapter(AccountManager.get(context).accounts[0], getString(R.string.sync_authority))
                .setExtras(Bundle().apply { putInt(SyncAdapter.ACTION, SyncAdapter.SYNC_REMOTE_CHANGES) })
                .syncPeriodic(6 * 3600L, 20 * 60L)
                .build())

             */
        }
        else ContentResolver.removePeriodicSync(AccountManager.get(context).accounts[0], getString(R.string.sync_authority), Bundle.EMPTY)
    }

    companion object {
        private const val CONFIRM_DIALOG = "CONFIRM_DIALOG"
        private const val LOGOUT_CONFIRM_REQUEST_CODE = 0
        private const val PERMISSION_RATIONALE_REQUEST_CODE = 1
        private const val STORAGE_PERMISSION_REQUEST_FOR_SNAPSEED = 8989
        private const val STORAGE_PERMISSION_REQUEST_FOR_BACKUP = 9090

        const val LAST_BACKUP = "LAST_BACKUP_TIMESTAMP"
    }

}