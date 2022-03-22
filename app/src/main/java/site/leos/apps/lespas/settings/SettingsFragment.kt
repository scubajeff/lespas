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
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.doOnPreDraw
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.preference.*
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.transition.MaterialSharedAxis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import site.leos.apps.lespas.BuildConfig
import site.leos.apps.lespas.MainActivity
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.AlbumRepository
import site.leos.apps.lespas.auth.NCAuthenticationFragment
import site.leos.apps.lespas.auth.NCLoginFragment
import site.leos.apps.lespas.helper.ConfirmDialogFragment
import site.leos.apps.lespas.helper.LesPasDialogFragment
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.helper.TransferStorageWorker
import site.leos.apps.lespas.photo.PhotoRepository
import site.leos.apps.lespas.sync.SyncAdapter
import java.io.File

class SettingsFragment : PreferenceFragmentCompat(), SharedPreferences.OnSharedPreferenceChangeListener {
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
    private lateinit var accessMediaLocationPermissionRequestLauncher: ActivityResultLauncher<String>

    private val authenticateModel: NCLoginFragment.AuthenticateViewModel by activityViewModels()

    private var actionBarHeight = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        savedInstanceState?.let {
            summaryString = it.getString(STATISTIC_SUMMARY_STRING)
            totalSize = it.getLong(STATISTIC_TOTAL_SIZE)
        }

        accessMediaLocationPermissionRequestLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
        snapseedPermissionRequestLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted->
            requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

            findPreference<SwitchPreferenceCompat>(getString(R.string.snapseed_pref_key))?.isChecked = isGranted

            // Explicitly request ACCESS_MEDIA_LOCATION permission
            if (isGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) accessMediaLocationPermissionRequestLauncher.launch(android.Manifest.permission.ACCESS_MEDIA_LOCATION)
        }
        showCameraRollPermissionRequestLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted->
            requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

            findPreference<SwitchPreferenceCompat>(getString(R.string.cameraroll_as_album_perf_key))?.isChecked = isGranted

            // Explicitly request ACCESS_MEDIA_LOCATION permission
            if (isGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) accessMediaLocationPermissionRequestLauncher.launch(android.Manifest.permission.ACCESS_MEDIA_LOCATION)
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

                // Explicitly request ACCESS_MEDIA_LOCATION permission
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) accessMediaLocationPermissionRequestLauncher.launch(android.Manifest.permission.ACCESS_MEDIA_LOCATION)
            }
        }

        enterTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
        returnTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)

        actionBarHeight = savedInstanceState?.getInt(KEY_ACTION_BAR_HEIGHT) ?: (requireActivity() as AppCompatActivity).supportActionBar?.height ?: 0
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Set content below action toolbar
        view.setPadding(0, actionBarHeight,0, 0)

        postponeEnterTransition()
        view.doOnPreDraw { startPostponedEnterTransition() }

        // Confirm dialog result handler
        parentFragmentManager.setFragmentResultListener(ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY, viewLifecycleOwner) { _, bundle ->
            if (bundle.getBoolean(ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY, false)) {
                when(bundle.getString(ConfirmDialogFragment.INDIVIDUAL_REQUEST_KEY, "")) {
                    LOGOUT_CONFIRM_DIALOG -> {
                        AccountManager.get(context).apply { removeAccountExplicitly(getAccountsByType(getString(R.string.account_type_nc))[0]) }
                        (requireContext().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).clearApplicationUserData()
                        requireActivity().packageManager.setComponentEnabledSetting(ComponentName(BuildConfig.APPLICATION_ID, "${BuildConfig.APPLICATION_ID}.Gallery"), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
                        requireActivity().finish()
                    }
                    SNAPSEED_PERMISSION_RATIONALE_REQUEST_DIALOG -> {
                        requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
                        snapseedPermissionRequestLauncher.launch(storagePermission)
                    }
                    INSTALL_SNAPSEED_DIALOG ->
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${SNAPSEED_PACKAGE_NAME}")))
                        } catch (e: ActivityNotFoundException) {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${SNAPSEED_PACKAGE_NAME}")))
                        }
                    CLEAR_CACHE_CONFIRM_DIALOG -> {
                        File("${Tools.getLocalRoot(requireContext())}/cache").deleteRecursively()
                    }
                }
            } else {
                when(bundle.getString(ConfirmDialogFragment.INDIVIDUAL_REQUEST_KEY, "")) {
                    INSTALL_SNAPSEED_DIALOG, SNAPSEED_PERMISSION_RATIONALE_REQUEST_DIALOG-> findPreference<SwitchPreferenceCompat>(getString(R.string.snapseed_pref_key))?.isChecked = false
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
                val inInternal = it.sharedPreferences?.getBoolean(KEY_STORAGE_LOCATION, true) ?: true
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
                            ConfirmDialogFragment.newInstance(getString(R.string.storage_access_permission_rationale), getString(R.string.proceed_request), true, SNAPSEED_PERMISSION_RATIONALE_REQUEST_DIALOG).show(parentFragmentManager, CONFIRM_DIALOG)
                        }
                    } else {
                        requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED

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
                    requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED

                    backupCameraRollPermissionRequestLauncher.launch(storagePermission)

                    (pref as SwitchPreferenceCompat).isChecked = false
                    toggleAutoSync(false)
                    false
                }
                else {
                    // Preference check state is about to be toggled, but not toggled yet
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
                    showBackupSummary()
                    true
                }
            }

            // Make sure SYNC preference acts accordingly
            if (isChecked) findPreference<SwitchPreferenceCompat>(getString(R.string.sync_pref_key))?.let {
                it.isChecked = true
                showBackupSummary()
                it.isEnabled = false
            }
        }

        findPreference<SwitchPreferenceCompat>(getString(R.string.cameraroll_as_album_perf_key))?.apply {
            if (ContextCompat.checkSelfPermission(requireContext(), storagePermission) != PackageManager.PERMISSION_GRANTED) isChecked = false

            onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, _ ->
                if (ContextCompat.checkSelfPermission(requireContext(), storagePermission) != PackageManager.PERMISSION_GRANTED) {
                    requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED

                    showCameraRollPermissionRequestLauncher.launch(storagePermission)
                    false
                } else true
            }
        }

        findPreference<Preference>(getString(R.string.cache_size_pref_key))?.run {
            summary = getString(R.string.cache_size_summary, sharedPreferences?.getInt(CACHE_SIZE, 800) ?: 800)
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

        preferenceScreen.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onPause() {
        preferenceScreen.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        summaryString?.let { outState.putString(STATISTIC_SUMMARY_STRING, it) }
        outState.putLong(STATISTIC_TOTAL_SIZE, totalSize)
        outState.putInt(KEY_ACTION_BAR_HEIGHT, actionBarHeight)
    }

    override fun onStop() {
        (requireView().parent as ViewGroup).setPadding(0, 0, 0, 0)

        super.onStop()
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean =
        when (preference.key) {
            getString(R.string.sync_pref_key) -> {
                toggleAutoSync(preference.sharedPreferences?.getBoolean(preference.key, false) ?: false)
                true
            }
            getString(R.string.logout_pref_key) -> {
                if (parentFragmentManager.findFragmentByTag(CONFIRM_DIALOG) == null) ConfirmDialogFragment.newInstance(getString(R.string.msg_logout_dialog, accounts[0].name), getString(R.string.yes_logout), true, LOGOUT_CONFIRM_DIALOG)
                    .show(parentFragmentManager, CONFIRM_DIALOG)
                true
            }
            getString(R.string.auto_theme_perf_key) -> {
                preference.sharedPreferences?.getString(getString(R.string.auto_theme_perf_key), getString(R.string.theme_auto_values))?.let {
                    AppCompatDelegate.setDefaultNightMode(it.toInt())
                }

                true
            }
            getString(R.string.gallery_launcher_pref_key) -> {
                requireActivity().packageManager.apply {
                    setComponentEnabledSetting(
                        ComponentName(BuildConfig.APPLICATION_ID, "${BuildConfig.APPLICATION_ID}.Gallery"),
                        if (preferenceManager.sharedPreferences?.getBoolean(preference.key, false) == true) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                    )
                    setComponentEnabledSetting(
                        ComponentName(BuildConfig.APPLICATION_ID, "${BuildConfig.APPLICATION_ID}.MainActivity"),
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
            getString(R.string.relogin_pref_key) -> {
                // Retrieve current account information from AccountManager's vault
                AccountManager.get(requireContext()).run {
                    val account = getAccountsByType(getString(R.string.account_type_nc))[0]
                    authenticateModel.setToken(getUserData(account, getString(R.string.nc_userdata_username)), "", getUserData(account, getString(R.string.nc_userdata_server)))
                    authenticateModel.setSelfSigned(getUserData(account, getString(R.string.nc_userdata_selfsigned)).toBoolean())
                }

                // Launch authentication webview
                parentFragmentManager.beginTransaction().replace(R.id.container_root, NCAuthenticationFragment.newInstance(true), NCAuthenticationFragment::class.java.canonicalName).addToBackStack(null).commit()

                true
            }
            getString(R.string.snapseed_pref_key) -> {
                if (preference.sharedPreferences?.getBoolean(preference.key, false) == true && isSnapseedNotInstalled) {
                    // Prompt user to install Snapseed
                    if (parentFragmentManager.findFragmentByTag(CONFIRM_DIALOG) == null)
                        ConfirmDialogFragment.newInstance(getString(R.string.install_snapseed_dialog_msg), getString(android.R.string.ok), true, INSTALL_SNAPSEED_DIALOG).show(parentFragmentManager, CONFIRM_DIALOG)
                }
                true
            }
            getString(R.string.clear_cache_pref_key) -> {
                if (parentFragmentManager.findFragmentByTag(CONFIRM_DIALOG) == null) ConfirmDialogFragment.newInstance(getString(R.string.msg_clear_cache), null, true, CLEAR_CACHE_CONFIRM_DIALOG)
                    .show(parentFragmentManager, CONFIRM_DIALOG)
                true
            }
            getString(R.string.cache_size_pref_key) -> {
                if (parentFragmentManager.findFragmentByTag(CACHE_SIZE_DIALOG) == null) CacheSizeSettingDialog().show(parentFragmentManager, CACHE_SIZE_DIALOG)
                true
            }
            else -> super.onPreferenceTreeClick(preference)
        }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when(key) {
            LAST_BACKUP -> showBackupSummary()
            CACHE_SIZE -> sharedPreferences?.let { findPreference<Preference>(getString(R.string.cache_size_pref_key))?.summary = getString(R.string.cache_size_summary, it.getInt(CACHE_SIZE, 800))}
            else -> {}
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

    private fun isEnoughSpace(sp: SharedPreferences?): Boolean =
        // Add 100MB redundant
        (if (sp?.getBoolean(KEY_STORAGE_LOCATION, true) != false) requireContext().getExternalFilesDirs(null)[1] else requireContext().filesDir).freeSpace > totalSize + 100 * 1024 * 1024
        //(if (sp.getBoolean(KEY_STORAGE_LOCATION, true)) requireContext().getExternalFilesDirs(null)[1] else requireContext().filesDir).freeSpace > totalSize

    private fun showBackupSummary() {
        lifecycleScope.launch(Dispatchers.IO) {
            var items = 0
            @Suppress("DEPRECATION")
            val pathSelection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Files.FileColumns.RELATIVE_PATH else MediaStore.Files.FileColumns.DATA
            requireContext().contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                null,
                "(${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})" + " AND " +
                        "($pathSelection LIKE '%DCIM%')" + " AND " + "(${MediaStore.Files.FileColumns.DATE_ADDED} > ${PreferenceManager.getDefaultSharedPreferences(requireContext().applicationContext).getLong(LAST_BACKUP, System.currentTimeMillis() / 1000)})",
                null,
                null
            )?.use { items = it.count }

            withContext(Dispatchers.Main) {
                findPreference<SwitchPreferenceCompat>(getString(R.string.cameraroll_backup_pref_key))?.summaryOn = getString(R.string.cameraroll_backup_summary, Tools.getDeviceModel()) + "\n" +
                    if (items > 0) String.format(getString(R.string.backup_waiting), items) else getString(R.string.backup_done)
            }
        }
    }

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

    class CacheSizeSettingDialog: LesPasDialogFragment(R.layout.fragment_cache_size_dialog) {
        private lateinit var sp: SharedPreferences

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            sp = PreferenceManager.getDefaultSharedPreferences(requireContext())
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            view.findViewById<AutoCompleteTextView>(R.id.cache_size)?.run {
                setText(sp.getInt(CACHE_SIZE, 800).toString())
                setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, mutableListOf<Int>().apply { for (i in 1..10) add(i*100) }))
                setOnItemClickListener { _, _, position, _ ->
                    sp.edit().putInt(CACHE_SIZE, (position + 1) * 100).apply()
                    dismiss()
                }
            }
        }
    }

    companion object {
        private const val CONFIRM_DIALOG = "CONFIRM_DIALOG"
        private const val TRANSFER_FILES_DIALOG = "CONFIRM_MOVING_DIALOG"
        private const val LOGOUT_CONFIRM_DIALOG = "LOGOUT_CONFIRM_DIALOG"
        private const val CLEAR_CACHE_CONFIRM_DIALOG = "CLEAR_CACHE_CONFIRM_DIALOG"
        private const val CACHE_SIZE_DIALOG = "CACHE_SIZE_DIALOG"
        private const val SNAPSEED_PERMISSION_RATIONALE_REQUEST_DIALOG = "SNAPSEED_PERMISSION_RATIONALE_REQUEST_DIALOG"
        private const val INSTALL_SNAPSEED_DIALOG = "INSTALL_SNAPSEED_DIALOG"

        private const val STATISTIC_SUMMARY_STRING = "STATISTIC_SUMMARY_STRING"
        private const val STATISTIC_TOTAL_SIZE = "STATISTIC_TOTAL_SIZE"

        const val LAST_BACKUP = "LAST_BACKUP_TIMESTAMP"
        const val KEY_STORAGE_LOCATION = "KEY_STORAGE_LOCATION"

        const val SNAPSEED_PACKAGE_NAME = "com.niksoftware.snapseed"
        const val SNAPSEED_MAIN_ACTIVITY_CLASS_NAME = "com.google.android.apps.snapseed.MainActivity"

        const val CACHE_SIZE = "WEB_CACHE_SIZE"

        private const val KEY_ACTION_BAR_HEIGHT = "KEY_ACTION_BAR_HEIGHT"
    }
}