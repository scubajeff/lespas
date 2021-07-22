package site.leos.apps.lespas.settings

import android.accounts.Account
import android.accounts.AccountManager
import android.app.ActivityManager
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.*
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import site.leos.apps.lespas.BuildConfig
import site.leos.apps.lespas.MainActivity
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.AlbumRepository
import site.leos.apps.lespas.helper.ConfirmDialogFragment
import site.leos.apps.lespas.helper.LesPasDialogFragment
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.helper.TransferStorageWorker
import site.leos.apps.lespas.photo.PhotoRepository
import site.leos.apps.lespas.sync.SyncAdapter


class SettingsFragment : PreferenceFragmentCompat(), ConfirmDialogFragment.OnResultListener {
    private var summaryString: String? = null
    private var totalSize = -1L
    private lateinit var volume: MutableList<StorageVolume>
    private lateinit var accounts: Array<Account>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        savedInstanceState?.let {
            summaryString = it.getString(STATISTIC_SUMMARY_STRING)
            totalSize = it.getLong(STATISTIC_TOTAL_SIZE)
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        volume = (requireContext().getSystemService(Context.STORAGE_SERVICE) as StorageManager).storageVolumes
        accounts = AccountManager.get(requireContext()).getAccountsByType(getString(R.string.account_type_nc))

        setPreferencesFromResource(R.xml.root_preferences, rootKey)

        findPreference<ListPreference>(getString(R.string.auto_theme_perf_key))?.let {
            it.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
                AppCompatDelegate.setDefaultNightMode((newValue as String).toInt())
                true
            }
        }

        findPreference<Preference>(getString(R.string.transfer_pref_key))?.let {
            it.isVisible = volume.size > 1
            if (it.isVisible) {
                val inInternal = it.sharedPreferences.getBoolean(KEY_STORAGE_LOCATION, true)
                it.title = getString(if (inInternal) R.string.transfer_to_external else R.string.transfer_to_internal)
//                savedInstanceState ?: run {
                    lifecycleScope.launch(Dispatchers.IO) {
                        totalSize = Tools.getStorageSize(requireContext())
                        withContext(Dispatchers.Main) {
                            if (inInternal && volume[1].state != Environment.MEDIA_MOUNTED) {
                                it.isEnabled = false
                                it.summary = getString(R.string.external_storage_not_writable)
                            } else {
                                it.isEnabled = isEnoughSpace(it.sharedPreferences)
                                if (!it.isEnabled) it.summary = getString(R.string.not_enough_space_message, Tools.humanReadableByteCountSI(totalSize), getString(if (inInternal) R.string.external_storage else R.string.internal_storage))
                            }
                        }
                    }
//                }
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
                                putLong(LAST_BACKUP, System.currentTimeMillis() / 1000)
                                apply()
                            }
                        }
                    }
                    toggleAutoSync(!(pref.isChecked))
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

    override fun onStart() {
        super.onStart()

        val tv = TypedValue()
        if (requireActivity().theme.resolveAttribute(android.R.attr.actionBarSize, tv, true)) {
            (requireView().parent as ViewGroup).setPadding(0, TypedValue.complexToDimensionPixelSize(tv.data, resources.displayMetrics), 0, 0)
        }
    }

    override fun onResume() {
        super.onResume()

        (requireActivity() as AppCompatActivity).supportActionBar?.run {
            title = getString(R.string.settings_title)
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowTitleEnabled(true)
        }

        // Set statistic summary if available
        summaryString?.let { findPreference<Preference>(getString(R.string.storage_statistic_pref_key))?.summary = it }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        summaryString?.let { outState.putString(STATISTIC_SUMMARY_STRING, it) }
        outState.putLong(STATISTIC_TOTAL_SIZE, totalSize)
    }

    override fun onStop() {
        (requireView().parent as ViewGroup).setPadding(0, 0, 0, 0)

        super.onStop()
    }

    override fun onPreferenceTreeClick(preference: Preference?): Boolean =
        when (preference?.key) {
            getString(R.string.sync_pref_key) -> {
                toggleAutoSync(preference.sharedPreferences.getBoolean(preference.key, false))
                true
            }
            getString(R.string.logout_pref_key) -> {
                if (parentFragmentManager.findFragmentByTag(CONFIRM_DIALOG) == null) ConfirmDialogFragment.newInstance(getString(R.string.logout_dialog_msg, accounts[0].name), getString(R.string.yes_logout)).let {
                    it.setTargetFragment(this, LOGOUT_CONFIRM_REQUEST_CODE)
                    it.show(parentFragmentManager, CONFIRM_DIALOG)
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
            getString(R.string.transfer_pref_key) -> {
                if (ContentResolver.isSyncActive(accounts[0], getString(R.string.sync_authority))) {
                    if (parentFragmentManager.findFragmentByTag(CONFIRM_DIALOG) == null) ConfirmDialogFragment.newInstance(getString(R.string.is_syncing_message), null, false).let {
                        it.setTargetFragment(this, IS_SYNCING_REQUEST_CODE)
                        it.show(parentFragmentManager, CONFIRM_DIALOG)
                    }
                }
                else
                    if (parentFragmentManager.findFragmentByTag(TRANSFER_FILES_DIALOG) == null) TransferStorageDialog.newInstance(getString(R.string.confirm_transferring_message, preference.title)).show(parentFragmentManager, TRANSFER_FILES_DIALOG)
                true
            }
            getString(R.string.storage_statistic_pref_key) -> {
                summaryString ?: run { showStatistic(preference) }
                true
            }
            else -> super.onPreferenceTreeClick(preference)
        }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        when(requestCode) {
            STORAGE_PERMISSION_REQUEST_FOR_SNAPSEED -> findPreference<SwitchPreferenceCompat>(getString(R.string.snapseed_pref_key))?.isChecked =
                grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            STORAGE_PERMISSION_REQUEST_FOR_BACKUP -> {
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
                                putLong(LAST_BACKUP, System.currentTimeMillis() / 1000)
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
                    (requireContext().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).clearApplicationUserData()
                    requireActivity().packageManager.setComponentEnabledSetting(ComponentName(BuildConfig.APPLICATION_ID, "${BuildConfig.APPLICATION_ID}.Gallery"), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
                    requireActivity().finish()
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
            ContentResolver.setSyncAutomatically(accounts[0], getString(R.string.sync_authority), true)
            ContentResolver.addPeriodicSync(accounts[0], getString(R.string.sync_authority), Bundle().apply { putInt(SyncAdapter.ACTION, SyncAdapter.SYNC_REMOTE_CHANGES) }, 6 * 3600L)
            /*
            ContentResolver.requestSync(SyncRequest.Builder()
                .setSyncAdapter(accounts[0], getString(R.string.sync_authority))
                .setExtras(Bundle().apply { putInt(SyncAdapter.ACTION, SyncAdapter.SYNC_REMOTE_CHANGES) })
                .syncPeriodic(6 * 3600L, 20 * 60L)
                .build())

             */
        }
        else ContentResolver.removePeriodicSync(accounts[0], getString(R.string.sync_authority), Bundle.EMPTY)
    }

    private fun showStatistic(preference: Preference) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            // Database statistic
            summaryString = getString(R.string.statistic_db_message, PhotoRepository(requireActivity().application).getPhotoTotal(), AlbumRepository(requireActivity().application).getAlbumTotal())
            withContext(Dispatchers.Main) { preference.summary = summaryString }

            // Storage space statistic
            summaryString = summaryString + "\n" +
                    getString(R.string.statistic_storage_message,
                        Tools.humanReadableByteCountSI(if (totalSize == -1L) Tools.getStorageSize(requireContext()) else totalSize),
                        getString(if (PreferenceManager.getDefaultSharedPreferences(requireContext()).getBoolean(KEY_STORAGE_LOCATION, true)) R.string.internal_storage else R.string.external_storage)
                    )
            withContext(Dispatchers.Main) { preference.summary = summaryString }
            summaryString = summaryString + "\n" + getString(R.string.statistic_free_space_message, Tools.humanReadableByteCountSI(requireContext().filesDir.freeSpace), getString(R.string.internal_storage))

            if (volume.size > 1 && volume[1].state == Environment.MEDIA_MOUNTED )
                summaryString = summaryString + "\n" + getString(R.string.statistic_free_space_message, Tools.humanReadableByteCountSI(requireContext().getExternalFilesDirs(null)[1].freeSpace), getString(R.string.external_storage))

            withContext(Dispatchers.Main) { preference.summary = summaryString }
        }
    }

    private fun isEnoughSpace(sp: SharedPreferences): Boolean =
        // Add 100MB redundant
        (if (sp.getBoolean(KEY_STORAGE_LOCATION, true)) requireContext().getExternalFilesDirs(null)[1] else requireContext().filesDir).freeSpace > totalSize + 100 * 1024 * 1024
        //(if (sp.getBoolean(KEY_STORAGE_LOCATION, true)) requireContext().getExternalFilesDirs(null)[1] else requireContext().filesDir).freeSpace > totalSize

    class TransferStorageDialog: LesPasDialogFragment(R.layout.fragment_transfer_storage_dialog) {
        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            view.findViewById<TextView>(R.id.message_textview).apply { text = arguments?.getString(MESSAGE) }
            view.findViewById<MaterialButton>(R.id.ok_button).setOnClickListener {
                WorkManager.getInstance(requireContext()).enqueueUniqueWork(TransferStorageWorker.WORKER_NAME, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<TransferStorageWorker>().build())
                (activity as MainActivity).observeTransferWorker()
                dismiss()
            }
            view.findViewById<MaterialButton>(R.id.cancel_button).setOnClickListener { dismiss() }
        }

        companion object {
            private const val MESSAGE = "MESSAGE"

            @JvmStatic
            fun newInstance(message: String) = TransferStorageDialog().apply { arguments = Bundle().apply { putString(MESSAGE, message) }}
        }
    }

    companion object {
        private const val CONFIRM_DIALOG = "CONFIRM_DIALOG"
        private const val TRANSFER_FILES_DIALOG = "CONFIRM_MOVING_DIALOG"
        private const val LOGOUT_CONFIRM_REQUEST_CODE = 100
        private const val PERMISSION_RATIONALE_REQUEST_CODE = 101
        private const val IS_SYNCING_REQUEST_CODE = 102
        private const val STORAGE_PERMISSION_REQUEST_FOR_SNAPSEED = 8989
        private const val STORAGE_PERMISSION_REQUEST_FOR_BACKUP = 9090

        private const val STATISTIC_SUMMARY_STRING = "STATISTIC_SUMMARY_STRING"
        private const val STATISTIC_TOTAL_SIZE = "STATISTIC_TOTAL_SIZE"

        const val LAST_BACKUP = "LAST_BACKUP_TIMESTAMP"
        const val KEY_STORAGE_LOCATION = "KEY_STORAGE_LOCATION"
    }

}