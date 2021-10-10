package site.leos.apps.lespas.settings

import android.accounts.Account
import android.accounts.AccountManager
import android.app.ActivityManager
import android.content.*
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
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
import site.leos.apps.lespas.auth.NCLoginFragment
import site.leos.apps.lespas.helper.ConfirmDialogFragment
import site.leos.apps.lespas.helper.LesPasDialogFragment
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.helper.TransferStorageWorker
import site.leos.apps.lespas.photo.PhotoRepository
import site.leos.apps.lespas.sync.SyncAdapter

class SettingsFragment : PreferenceFragmentCompat() {
    private var summaryString: String? = null
    private var totalSize = -1L
    private lateinit var volume: MutableList<StorageVolume>
    private lateinit var accounts: Array<Account>
    private var isSnapseedNotInstalled = true

    // For Android 11 and above, use MediaStore trash request pending intent to prompt for user's deletion confirmation, so we don't need WRITE_EXTERNAL_STORAGE
    private val storagePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) android.Manifest.permission.READ_EXTERNAL_STORAGE else android.Manifest.permission.WRITE_EXTERNAL_STORAGE
    private lateinit var snapseedPermissionRequestLauncher: ActivityResultLauncher<String>
    private lateinit var showCameraRollPermissionRequestLauncher: ActivityResultLauncher<String>
    private lateinit var backupCameraRollPermissionRequestLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        savedInstanceState?.let {
            summaryString = it.getString(STATISTIC_SUMMARY_STRING)
            totalSize = it.getLong(STATISTIC_TOTAL_SIZE)
        }

        snapseedPermissionRequestLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted->
            requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

            findPreference<SwitchPreferenceCompat>(getString(R.string.snapseed_pref_key))?.isChecked = isGranted
        }
        showCameraRollPermissionRequestLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted->
            requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

            findPreference<SwitchPreferenceCompat>(getString(R.string.cameraroll_as_album_perf_key))?.isChecked = isGranted
        }
        backupCameraRollPermissionRequestLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted->
            requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

            if (isGranted) {
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Confirm dialog result handler
        parentFragmentManager.setFragmentResultListener(ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY, viewLifecycleOwner) { key, bundle ->
            if (key == ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY) {
                if (bundle.getBoolean(ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY, false)) {
                    when(bundle.getString(ConfirmDialogFragment.INDIVIDUAL_REQUEST_KEY, "")) {
                        LOGOUT_CONFIRM_DIALOG -> {
                            AccountManager.get(context).apply { removeAccountExplicitly(getAccountsByType(getString(R.string.account_type_nc))[0]) }
                            (requireContext().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).clearApplicationUserData()
                            requireActivity().packageManager.setComponentEnabledSetting(ComponentName(BuildConfig.APPLICATION_ID, "${BuildConfig.APPLICATION_ID}.Gallery"), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
                            requireActivity().finish()
                        }
                        PERMISSION_RATIONALE_REQUEST_DIALOG-> {
                            requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_NOSENSOR

                            snapseedPermissionRequestLauncher.launch(storagePermission)
                        }
                        INSTALL_SNAPSEED_DIALOG->
                            try {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${SNAPSEED_PACKAGE_NAME}")))
                            } catch (e: ActivityNotFoundException) {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${SNAPSEED_PACKAGE_NAME}")))
                            }
                    }
                } else {
                    when(bundle.getString(ConfirmDialogFragment.INDIVIDUAL_REQUEST_KEY, "")) {
                        INSTALL_SNAPSEED_DIALOG, PERMISSION_RATIONALE_REQUEST_DIALOG-> findPreference<SwitchPreferenceCompat>(getString(R.string.snapseed_pref_key))?.isChecked = false
                    }
                }
            }
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

        findPreference<SwitchPreferenceCompat>(getString(R.string.snapseed_pref_key))?.let {
            if (ContextCompat.checkSelfPermission(requireContext(), storagePermission) != PackageManager.PERMISSION_GRANTED) it.isChecked = false

            it.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { pref, _ ->
                // Request WRITE_EXTERNAL_STORAGE permission if user want to integrate with Snapseed
                if (ContextCompat.checkSelfPermission(requireContext(), storagePermission) != PackageManager.PERMISSION_GRANTED) {
                    if (shouldShowRequestPermissionRationale(storagePermission)) {
                        if (parentFragmentManager.findFragmentByTag(CONFIRM_DIALOG) == null) {
                            ConfirmDialogFragment.newInstance(getString(R.string.storage_access_permission_rationale), getString(R.string.proceed_request), true, PERMISSION_RATIONALE_REQUEST_DIALOG)
                                .show(parentFragmentManager, CONFIRM_DIALOG)
                        }
                    } else {
                        requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_NOSENSOR

                        snapseedPermissionRequestLauncher.launch(storagePermission)
                    }

                    // Set Snapseed integration to False if we don't have WRITE_EXTERNAL_STORAGE permission
                    (pref as SwitchPreferenceCompat).isChecked = false
                    false

                } else true
            }
        }

        findPreference<SwitchPreferenceCompat>(getString(R.string.cameraroll_backup_pref_key))?.apply {
            if (ContextCompat.checkSelfPermission(requireContext(), storagePermission) != PackageManager.PERMISSION_GRANTED) {
                isChecked = false
                toggleAutoSync(false)
            }

            onPreferenceChangeListener = Preference.OnPreferenceChangeListener { pref, _ ->
                if (ContextCompat.checkSelfPermission(requireContext(), storagePermission) != PackageManager.PERMISSION_GRANTED) {
                    requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_NOSENSOR

                    backupCameraRollPermissionRequestLauncher.launch(storagePermission)

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

        findPreference<SwitchPreferenceCompat>(getString(R.string.cameraroll_as_album_perf_key))?.apply {
            if (ContextCompat.checkSelfPermission(requireContext(), storagePermission) != PackageManager.PERMISSION_GRANTED) isChecked = false

            onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, _ ->
                if (ContextCompat.checkSelfPermission(requireContext(), storagePermission) != PackageManager.PERMISSION_GRANTED) {
                    requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_NOSENSOR

                    showCameraRollPermissionRequestLauncher.launch(storagePermission)
                    false
                } else true
            }
        }
    }

    override fun onStart() {
        super.onStart()

        // Put preference list under app toolbar
        TypedValue().also { tv-> if (requireActivity().theme.resolveAttribute(android.R.attr.actionBarSize, tv, true))
            (requireView().parent as ViewGroup).setPadding(0, TypedValue.complexToDimensionPixelSize(tv.data, resources.displayMetrics), 0, 0)
        }
    }

    override fun onResume() {
        super.onResume()

        (requireActivity() as AppCompatActivity).supportActionBar?.run {
            // Re-login function will hide the toolbar
            show()

            title = getString(R.string.settings_title)
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowTitleEnabled(true)
        }

        // Set statistic summary if available
        summaryString?.let { findPreference<Preference>(getString(R.string.storage_statistic_pref_key))?.summary = it }

        // Disable Snapseed integration setting if the app is not installed
        isSnapseedNotInstalled = requireContext().packageManager.getLaunchIntentForPackage(SNAPSEED_PACKAGE_NAME) == null
        if (isSnapseedNotInstalled) findPreference<SwitchPreferenceCompat>(getString(R.string.snapseed_pref_key))?.isChecked = false
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
                if (parentFragmentManager.findFragmentByTag(CONFIRM_DIALOG) == null) ConfirmDialogFragment.newInstance(getString(R.string.logout_dialog_msg, accounts[0].name), getString(R.string.yes_logout), true, LOGOUT_CONFIRM_DIALOG)
                    .show(parentFragmentManager, CONFIRM_DIALOG)
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
                    if (parentFragmentManager.findFragmentByTag(CONFIRM_DIALOG) == null) ConfirmDialogFragment.newInstance(getString(R.string.is_syncing_message), null, false)
                        .show(parentFragmentManager, CONFIRM_DIALOG)
                }
                else
                    if (parentFragmentManager.findFragmentByTag(TRANSFER_FILES_DIALOG) == null) TransferStorageDialog.newInstance(getString(R.string.confirm_transferring_message, preference.title)).show(parentFragmentManager, TRANSFER_FILES_DIALOG)
                true
            }
            getString(R.string.storage_statistic_pref_key) -> {
                summaryString ?: run { showStatistic(preference) }
                true
            }
            getString(R.string.relogin_pref_key)-> {
                parentFragmentManager.beginTransaction().replace(R.id.container_root, NCLoginFragment.newInstance(true), NCLoginFragment::class.java.canonicalName).addToBackStack(null).commit()
                true
            }
            getString(R.string.snapseed_pref_key)-> {
                if (preference.sharedPreferences.getBoolean(preference.key, false) && isSnapseedNotInstalled) {
                    // Prompt user to install Snapseed
                    if (parentFragmentManager.findFragmentByTag(CONFIRM_DIALOG) == null)
                        ConfirmDialogFragment.newInstance(getString(R.string.install_snapseed_dialog_msg), getString(android.R.string.ok), true, INSTALL_SNAPSEED_DIALOG).show(parentFragmentManager, CONFIRM_DIALOG)
                }
                true
            }
            else -> super.onPreferenceTreeClick(preference)
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
        private const val LOGOUT_CONFIRM_DIALOG = "LOGOUT_CONFIRM_DIALOG"
        private const val PERMISSION_RATIONALE_REQUEST_DIALOG = "PERMISSION_RATIONALE_REQUEST_DIALOG"
        private const val INSTALL_SNAPSEED_DIALOG = "INSTALL_SNAPSEED_DIALOG"

        private const val STATISTIC_SUMMARY_STRING = "STATISTIC_SUMMARY_STRING"
        private const val STATISTIC_TOTAL_SIZE = "STATISTIC_TOTAL_SIZE"

        const val LAST_BACKUP = "LAST_BACKUP_TIMESTAMP"
        const val KEY_STORAGE_LOCATION = "KEY_STORAGE_LOCATION"

        const val SNAPSEED_PACKAGE_NAME = "com.niksoftware.snapseed"
        const val SNAPSEED_MAIN_ACTIVITY_CLASS_NAME = "com.google.android.apps.snapseed.MainActivity"
    }

}