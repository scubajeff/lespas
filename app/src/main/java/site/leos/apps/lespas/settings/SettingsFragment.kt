/*
 *   Copyright 2019 Jeffrey Liu (scubajeffrey@protonmail.ch)
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package site.leos.apps.lespas.settings

import android.accounts.Account
import android.accounts.AccountManager
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.MediaStore
import android.provider.Settings
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.MenuProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnPreDraw
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.ByteString.Companion.decodeBase64
import site.leos.apps.lespas.BuildConfig
import site.leos.apps.lespas.MainActivity
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.AlbumRepository
import site.leos.apps.lespas.auth.NCAuthenticationFragment
import site.leos.apps.lespas.auth.NCLoginFragment
import site.leos.apps.lespas.gallery.GalleryOverviewFragment
import site.leos.apps.lespas.helper.ConfirmDialogFragment
import site.leos.apps.lespas.helper.LesPasDialogFragment
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.helper.TransferStorageWorker
import site.leos.apps.lespas.photo.PhotoRepository
import site.leos.apps.lespas.publication.NCShareViewModel
import site.leos.apps.lespas.sync.ActionViewModel
import site.leos.apps.lespas.sync.SyncAdapter
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate

class SettingsFragment : PreferenceFragmentCompat(), SharedPreferences.OnSharedPreferenceChangeListener {
    private var storageStatisticSummaryString: String? = null
    private var totalSize = -1L
    private lateinit var volume: MutableList<StorageVolume>
    private lateinit var accounts: Array<Account>
    private var isSnapseedNotInstalled = true
    private var syncWhenClosing = false

    // For Android 11 and above, use MediaStore trash request pending intent to prompt for user's deletion confirmation, so we don't need WRITE_EXTERNAL_STORAGE
    private val storagePermission = Tools.getStoragePermissionsArray()
    private lateinit var snapseedPermissionRequestLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var showGalleryPermissionRequestLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var accessMediaLocationPermissionRequestLauncher: ActivityResultLauncher<String>
    private lateinit var installSnapseedLauncher: ActivityResultLauncher<Intent>
    private lateinit var manageMediaPermissionRequestLauncher: ActivityResultLauncher<Intent>

    private val authenticateModel: NCLoginFragment.AuthenticateViewModel by activityViewModels { NCLoginFragment.AuthenticateViewModelFactory(requireActivity()) }

    private var archiveMenuItem: MenuItem? = null
    private var refreshArchiveMenuItem: MenuItem? = null


    //private var syncPreference: SwitchPreferenceCompat? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        savedInstanceState?.let {
            storageStatisticSummaryString = it.getString(STATISTIC_SUMMARY_STRING)
            totalSize = it.getLong(STATISTIC_TOTAL_SIZE)
        }

        accessMediaLocationPermissionRequestLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
        snapseedPermissionRequestLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

            var isGranted = true
            for(result in results) isGranted = isGranted && result.value
            findPreference<SwitchPreferenceCompat>(getString(R.string.snapseed_pref_key))?.isChecked = isGranted

            if (isGranted) {
                // Explicitly request ACCESS_MEDIA_LOCATION permission
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) accessMediaLocationPermissionRequestLauncher.launch(android.Manifest.permission.ACCESS_MEDIA_LOCATION)

                installSnapseedIfNeeded()
            }
        }
        showGalleryPermissionRequestLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

            var isGranted = true
            for(result in results) isGranted = isGranted && result.value
            findPreference<SwitchPreferenceCompat>(getString(R.string.gallery_as_album_perf_key))?.isChecked = isGranted

            // Explicitly request ACCESS_MEDIA_LOCATION permission
            if (isGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) accessMediaLocationPermissionRequestLauncher.launch(android.Manifest.permission.ACCESS_MEDIA_LOCATION)
        }
/*
        backupCameraRollPermissionRequestLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

            var isGranted = true
            for(result in results) isGranted = isGranted && result.value
            findPreference<SwitchPreferenceCompat>(getString(R.string.cameraroll_backup_pref_key))?.isChecked = isGranted
            toggleBackup(isGranted, LAST_BACKUP_CAMERA)
            // Explicitly request ACCESS_MEDIA_LOCATION permission
            if (isGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) accessMediaLocationPermissionRequestLauncher.launch(android.Manifest.permission.ACCESS_MEDIA_LOCATION)
        }
        backupPicturesPermissionRequestLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

            var isGranted = true
            for(result in results) isGranted = isGranted && result.value
            findPreference<SwitchPreferenceCompat>(getString(R.string.pictures_backup_pref_key))?.isChecked = isGranted
            toggleBackup(isGranted, LAST_BACKUP_PICTURE)
            // Explicitly request ACCESS_MEDIA_LOCATION permission
            if (isGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) accessMediaLocationPermissionRequestLauncher.launch(android.Manifest.permission.ACCESS_MEDIA_LOCATION)
        }
*/
        installSnapseedLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            isSnapseedNotInstalled = requireContext().packageManager.getLaunchIntentForPackage(SNAPSEED_PACKAGE_NAME) == null
            if (!isSnapseedNotInstalled) findPreference<SwitchPreferenceCompat>(getString(R.string.snapseed_pref_key))?.isChecked = true
        }

        manageMediaPermissionRequestLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {}

        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {}

            override fun onPrepareMenu(menu: Menu) {
                super.onPrepareMenu(menu)
                if (tag == GalleryOverviewFragment.LAUNCH_BY_GALLERY) {
                    archiveMenuItem = menu.findItem(R.id.option_menu_archive)?.apply { isVisible = false }
                    refreshArchiveMenuItem = menu.findItem(R.id.option_menu_archive_forced_refresh)?.apply { isVisible = false }
                }
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean = false
        })
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        volume = (requireContext().getSystemService(Context.STORAGE_SERVICE) as StorageManager).storageVolumes
        accounts = AccountManager.get(requireContext()).getAccountsByType(getString(R.string.account_type_nc))
        //val deviceModel = Tools.getDeviceModel()

        setPreferencesFromResource(R.xml.root_preferences, rootKey)
        //syncPreference = findPreference(getString(R.string.sync_pref_key))

        findPreference<SwitchPreferenceCompat>(getString(R.string.true_black_pref_key))?.run {
            if (sharedPreferences.getString(getString(R.string.auto_theme_perf_key), getString(R.string.theme_auto_values)) == getString(R.string.theme_light_values)) {
                // Disable true black theme switch if fixed light theme selected
                isEnabled = false
                isChecked = false
            } else isEnabled = true
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

/*
        findPreference<SwitchPreferenceCompat>(getString(R.string.cameraroll_backup_pref_key))?.run {
            summaryOn = getString(R.string.backup_summary, Tools.getCameraArchiveHome(context), deviceModel)
            // Make sure SYNC preference acts accordingly
            if (isChecked) {
                syncPreference?.let {
                    it.isChecked = true
                    it.isEnabled = false
                }
            }
        }

        findPreference<SwitchPreferenceCompat>(getString(R.string.pictures_backup_pref_key))?.run {
            summaryOn = getString(R.string.backup_summary, Tools.getPicturesArchiveHome(context), deviceModel)
            // Make sure SYNC preference acts accordingly
            if (isChecked) {
                syncPreference?.let {
                    it.isChecked = true
                    it.isEnabled = false
                }
            }
        }
*/

        findPreference<Preference>(getString(R.string.cache_size_pref_key))?.run {
            summary = getString(R.string.cache_size_summary, sharedPreferences.getInt(CACHE_SIZE, 800))
        }

        // Toggle some switches off when Storage Access permission is not granted
        if (Tools.shouldRequestStoragePermission(requireContext())) {
            findPreference<SwitchPreferenceCompat>(getString(R.string.gallery_as_album_perf_key))?.isChecked = false
            //findPreference<SwitchPreferenceCompat>(getString(R.string.cameraroll_backup_pref_key))?.isChecked = false
            findPreference<SwitchPreferenceCompat>(getString(R.string.snapseed_pref_key))?.isChecked = false
            //toggleAutoSync(false)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        postponeEnterTransition()
        view.doOnPreDraw { startPostponedEnterTransition() }

        // Confirm dialog result handler
        parentFragmentManager.setFragmentResultListener(SETTING_REQUEST_KEY, viewLifecycleOwner) { _, bundle ->
            if (bundle.getBoolean(ConfirmDialogFragment.CONFIRM_DIALOG_RESULT_KEY, false)) {
                when(bundle.getString(ConfirmDialogFragment.INDIVIDUAL_REQUEST_KEY, "")) {
                    LOGOUT_CONFIRM_DIALOG -> {
                        AccountManager.get(context).apply { removeAccountExplicitly(getAccountsByType(getString(R.string.account_type_nc))[0]) }
                        (requireContext().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).clearApplicationUserData()
                        requireActivity().finish()
                    }
                    SNAPSEED_PERMISSION_RATIONALE_REQUEST_DIALOG -> {
                        requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
                        snapseedPermissionRequestLauncher.launch(storagePermission)
                    }
                    INSTALL_SNAPSEED_DIALOG ->
                        try {
                            installSnapseedLauncher.launch(Intent(Intent.ACTION_VIEW, "market://details?id=${SNAPSEED_PACKAGE_NAME}".toUri()))
                        } catch (e: ActivityNotFoundException) {
                            installSnapseedLauncher.launch(Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=${SNAPSEED_PACKAGE_NAME}".toUri()))
                        }
                    CLEAR_CACHE_CONFIRM_DIALOG -> {
                        File("${Tools.getLocalRoot(requireContext())}/cache").deleteRecursively()
                    }
/*
                    BACKUP_OLD_CAMERA_ROLL_DIALOG -> {
                        preferenceManager.sharedPreferences.edit().apply {
                            // User choose to backup all oldies
                            putLong(LAST_BACKUP_CAMERA, 1L)
                            apply()
                        }
                    }
                    BACKUP_OLD_PICTURES_DIALOG -> {
                        preferenceManager.sharedPreferences.edit().apply {
                            // User choose to backup all oldies
                            putLong(LAST_BACKUP_PICTURE, 1L)
                            apply()
                        }
                    }
*/
                    MANAGE_MEDIA_PERMISSION_RATIONALE_REQUEST -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) manageMediaPermissionRequestLauncher.launch(Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, "package:${BuildConfig.APPLICATION_ID}".toUri()))
                }
            } else {
                when(bundle.getString(ConfirmDialogFragment.INDIVIDUAL_REQUEST_KEY, "")) {
                    INSTALL_SNAPSEED_DIALOG, SNAPSEED_PERMISSION_RATIONALE_REQUEST_DIALOG-> findPreference<SwitchPreferenceCompat>(getString(R.string.snapseed_pref_key))?.isChecked = false
/*
                    BACKUP_OLD_CAMERA_ROLL_DIALOG -> {
                        preferenceManager.sharedPreferences.edit().apply {
                            // User choose to backup new files only, note down the current timestamp, photos taken later on will be backup
                            putLong(LAST_BACKUP_CAMERA, System.currentTimeMillis() / 1000)
                            apply()
                        }
                    }
                    BACKUP_OLD_PICTURES_DIALOG -> {
                        preferenceManager.sharedPreferences.edit().apply {
                            // User choose to backup new files only, note down the current timestamp, photos taken later on will be backup
                            putLong(LAST_BACKUP_PICTURE, System.currentTimeMillis() / 1000)
                            apply()
                        }
                    }
*/
                }
            }
        }

        findPreference<PreferenceCategory>(getString(R.string.blog_category_pref_key))?.isVisible = ViewModelProvider(requireActivity())[NCShareViewModel::class.java].isBlogSiteAvailable()

        listView.run {
            // Avoid window inset overlapping
            ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
                val displayCutoutInset = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
                val navigationBarInset = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
                v.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    rightMargin = displayCutoutInset.right + navigationBarInset.right
                    leftMargin = displayCutoutInset.left + navigationBarInset.left
                }
                insets
            }
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
        storageStatisticSummaryString?.let { findPreference<Preference>(getString(R.string.storage_statistic_pref_key))?.summary = it }

        // Disable Snapseed integration setting if the app is not installed
        isSnapseedNotInstalled = requireContext().packageManager.getLaunchIntentForPackage(SNAPSEED_PACKAGE_NAME) == null
        if (isSnapseedNotInstalled) findPreference<SwitchPreferenceCompat>(getString(R.string.snapseed_pref_key))?.isChecked = false

        preferenceScreen.sharedPreferences.registerOnSharedPreferenceChangeListener(this)

/*
        // Get list of sub folder of 'Pictures'
        var picturesSubFolder = listOf<String>()
        lifecycleScope.launch {
            picturesSubFolder = Tools.listSubFolders("Pictures", requireActivity().contentResolver)
        }.apply {
            invokeOnCompletion {
                preferenceManager.findPreference<MultiSelectListPreference>(getString(R.string.pictures_sub_folder_exclusion_pref_key))?.run {
                    picturesSubFolder.toTypedArray().let { subFolders ->
                        entries = subFolders
                        entryValues = subFolders
                    }
                    updatePicturesBackupExclusionSummary(this)
                }
            }
        }
*/
    }

    override fun onPause() {
        preferenceScreen.sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        storageStatisticSummaryString?.let { outState.putString(STATISTIC_SUMMARY_STRING, it) }
        outState.putLong(STATISTIC_TOTAL_SIZE, totalSize)
    }

    override fun onStop() {
        (requireView().parent as ViewGroup).setPadding(0, 0, 0, 0)

        super.onStop()
    }

    override fun onDestroyView() {
        if (syncWhenClosing) {
            ContentResolver.requestSync(accounts[0], getString(R.string.sync_authority), Bundle().apply {
                putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                //putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
                putInt(SyncAdapter.ACTION, SyncAdapter.SYNC_LOCAL_CHANGES)
            })
        }

        if (tag == GalleryOverviewFragment.LAUNCH_BY_GALLERY) {
            archiveMenuItem?.isVisible = true
            refreshArchiveMenuItem?.isVisible = true
        }

        super.onDestroyView()
    }

    override fun onPreferenceTreeClick(preference: Preference?): Boolean =
        when (preference?.key) {
/*
            getString(R.string.sync_pref_key) -> {
                toggleAutoSync(preference.sharedPreferences.getBoolean(preference.key, false))
                true
            }
*/
            getString(R.string.logout_pref_key) -> {
                if (parentFragmentManager.findFragmentByTag(CONFIRM_DIALOG) == null) ConfirmDialogFragment.newInstance(getString(R.string.msg_logout_dialog, accounts[0].name), positiveButtonText = getString(R.string.yes_logout), individualKey = LOGOUT_CONFIRM_DIALOG, requestKey = SETTING_REQUEST_KEY).show(parentFragmentManager, CONFIRM_DIALOG)
                true
            }
            getString(R.string.transfer_pref_key) -> {
                if (ContentResolver.isSyncActive(accounts[0], getString(R.string.sync_authority))) {
                    if (parentFragmentManager.findFragmentByTag(CONFIRM_DIALOG) == null) ConfirmDialogFragment.newInstance(getString(R.string.is_syncing_message), cancelable = false, requestKey = SETTING_REQUEST_KEY).show(parentFragmentManager, CONFIRM_DIALOG)
                }
                else
                    if (parentFragmentManager.findFragmentByTag(TRANSFER_FILES_DIALOG) == null) TransferStorageDialog.newInstance(getString(R.string.confirm_transferring_message, preference.title)).show(parentFragmentManager, TRANSFER_FILES_DIALOG)
                true
            }
            getString(R.string.storage_statistic_pref_key) -> {
                storageStatisticSummaryString ?: run { showStatistic(preference) }
                true
            }
            getString(R.string.relogin_pref_key) -> {
                // Retrieve current account information from AccountManager's vault
                AccountManager.get(requireContext()).run {
                    val account = getAccountsByType(getString(R.string.account_type_nc))[0]
                    val loginName = getUserData(account, getString(R.string.nc_userdata_loginname)) ?: getUserData(account, getString(R.string.nc_userdata_username))
                    authenticateModel.run {
                        val serverUrl = getUserData(account, getString(R.string.nc_userdata_server))
                        setToken(loginName, "", serverUrl)
                        setSelfSigned(getUserData(account, getString(R.string.nc_userdata_selfsigned)).toBoolean())
                        try {
                            getUserData(account, getString(R.string.nc_userdata_certificate))?.let { certificateString ->
                                // Restore self-signed certificate if there's one. TODO if self-signed certificate expired, user need to logout and login again
                                if (certificateString.isNotEmpty()) {
                                    setSelfSignedCertificateString(certificateString)
                                    certificateString.decodeBase64()?.let { cert ->
                                        KeyStore.getInstance(KeyStore.getDefaultType()).let {
                                            it.load(ByteArrayInputStream(cert.toByteArray()), null)
                                            setSelfSignedCertificate(it.getCertificate(serverUrl.substringAfterLast("//").substringBefore('/')) as X509Certificate)
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception){
                            e.printStackTrace()
                        }
                    }
                }

                // Launch authentication webview
                parentFragmentManager.beginTransaction().replace(R.id.container_root, NCAuthenticationFragment.newInstance(true), NCAuthenticationFragment::class.java.canonicalName).addToBackStack(null).commit()

                true
            }
            getString(R.string.snapseed_pref_key) -> {
                if (Tools.shouldRequestStoragePermission(requireContext())) {
                    // Set Snapseed integration to False if we don't have storage access permission
                    (preference as SwitchPreferenceCompat).isChecked = false

                    if (shouldShowRequestPermissionRationale(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) android.Manifest.permission.READ_MEDIA_IMAGES else android.Manifest.permission.READ_EXTERNAL_STORAGE)) {
                        if (parentFragmentManager.findFragmentByTag(CONFIRM_DIALOG) == null) {
                            ConfirmDialogFragment.newInstance(getString(R.string.storage_access_permission_rationale), positiveButtonText = getString(R.string.proceed_request), individualKey = SNAPSEED_PERMISSION_RATIONALE_REQUEST_DIALOG, requestKey = SETTING_REQUEST_KEY).show(parentFragmentManager, CONFIRM_DIALOG)
                        }
                    } else {
                        requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED

                        snapseedPermissionRequestLauncher.launch(storagePermission)
                    }
                } else installSnapseedIfNeeded()
                true
            }
            getString(R.string.clear_cache_pref_key) -> {
                if (parentFragmentManager.findFragmentByTag(CONFIRM_DIALOG) == null) ConfirmDialogFragment.newInstance(getString(R.string.msg_clear_cache), individualKey = CLEAR_CACHE_CONFIRM_DIALOG, requestKey = SETTING_REQUEST_KEY).show(parentFragmentManager, CONFIRM_DIALOG)
                true
            }
            getString(R.string.cache_size_pref_key) -> {
                if (parentFragmentManager.findFragmentByTag(CACHE_SIZE_DIALOG) == null) CacheSizeSettingDialog().show(parentFragmentManager, CACHE_SIZE_DIALOG)
                true
            }
            getString(R.string.gallery_as_album_perf_key) -> {
                if (Tools.shouldRequestStoragePermission(requireContext())) {
                    requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED

                    showGalleryPermissionRequestLauncher.launch(storagePermission)

                    (preference as SwitchPreferenceCompat).isChecked = false
                }
                true
            }
/*
            getString(R.string.cameraroll_backup_pref_key) -> {
                if (Tools.shouldRequestStoragePermission(requireContext())) {
                    requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
                    backupCameraRollPermissionRequestLauncher.launch(storagePermission)
                    (preference as SwitchPreferenceCompat).isChecked = false
                    //toggleAutoSync(false)
                }
                else toggleBackup((preference as SwitchPreferenceCompat).isChecked, LAST_BACKUP_CAMERA)
                true
            }
            getString(R.string.pictures_backup_pref_key) -> {
                if (Tools.shouldRequestStoragePermission(requireContext())) {
                    requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
                    backupPicturesPermissionRequestLauncher.launch(storagePermission)
                    (preference as SwitchPreferenceCompat).isChecked = false
                    //toggleAutoSync(false)
                }
                else toggleBackup((preference as SwitchPreferenceCompat).isChecked, LAST_BACKUP_PICTURE)
                true
            }
*/
            getString(R.string.true_black_pref_key) -> {
                //if ((resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)) requireActivity().recreate()
                requireActivity().recreate()
                true
            }
            getString(R.string.sync_status_pref_key) -> {
                if (parentFragmentManager.findFragmentByTag(SYNC_STATUS_DIALOG) == null) SyncStatusDialogFragment().show(parentFragmentManager, SYNC_STATUS_DIALOG)
                true
            }
            else -> super.onPreferenceTreeClick(preference)
        }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when(key) {
            getString(R.string.auto_theme_perf_key) -> {
                sharedPreferences?.getString(key, getString(R.string.theme_auto_values))?.let { newValue ->
                    findPreference<SwitchPreferenceCompat>(getString(R.string.true_black_pref_key))?.run {
                        if (newValue == getString(R.string.theme_light_values)) {
                            // Disable true black theme switch if fixed light theme selected
                            isEnabled = false
                            isChecked = false
                        } else isEnabled = true
                    }

                    AppCompatDelegate.setDefaultNightMode(newValue.toInt())
                }
            }
            CACHE_SIZE -> sharedPreferences?.let { findPreference<Preference>(getString(R.string.cache_size_pref_key))?.summary = getString(R.string.cache_size_summary, it.getInt(CACHE_SIZE, 800))}
            getString(R.string.wifionly_pref_key) -> syncWhenClosing = true
            getString(R.string.blog_name_pref_key) -> ViewModelProvider(requireActivity())[ActionViewModel::class.java].updateBlogSiteTitle()
            //getString(R.string.pictures_sub_folder_exclusion_pref_key) -> { findPreference<MultiSelectListPreference>(key)?.run { updatePicturesBackupExclusionSummary(this) }}
            getString(R.string.sync_deletion_perf_key) ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !MediaStore.canManageMedia(requireContext()))
                    if (parentFragmentManager.findFragmentByTag(MANAGE_MEDIA_PERMISSION_DIALOG) == null) ConfirmDialogFragment.newInstance(getString(R.string.sync_deletion_rational), positiveButtonText = getString(R.string.proceed_request), individualKey = MANAGE_MEDIA_PERMISSION_RATIONALE_REQUEST, requestKey = SETTING_REQUEST_KEY).show(parentFragmentManager, MANAGE_MEDIA_PERMISSION_DIALOG)
            else -> {}
        }
    }

/*
    private fun updatePicturesBackupExclusionSummary(exclusionPref: MultiSelectListPreference) {
        val allSubFolders = exclusionPref.entryValues
        exclusionPref.summary = exclusionPref.values.filter { allSubFolders.contains(it) }.toString().drop(1).dropLast(1)
    }
*/

    private fun installSnapseedIfNeeded() {
        if (preferenceManager.sharedPreferences.getBoolean(getString(R.string.snapseed_pref_key), false) && isSnapseedNotInstalled) {
            // Prompt user to install Snapseed
            if (parentFragmentManager.findFragmentByTag(CONFIRM_DIALOG) == null)
                ConfirmDialogFragment.newInstance(getString(R.string.install_snapseed_dialog_msg), individualKey = INSTALL_SNAPSEED_DIALOG, requestKey = SETTING_REQUEST_KEY).show(parentFragmentManager, CONFIRM_DIALOG)
        }
    }

/*
    private fun toggleAutoSync(on: Boolean) {
        if (on) {
            ContentResolver.setSyncAutomatically(accounts[0], getString(R.string.sync_authority), true)
            ContentResolver.addPeriodicSync(accounts[0], getString(R.string.sync_authority), Bundle().apply { putInt(SyncAdapter.ACTION, SyncAdapter.SYNC_REMOTE_CHANGES) }, 6 * 3600L)
            */
/*
            ContentResolver.requestSync(SyncRequest.Builder()
                .setSyncAdapter(accounts[0], getString(R.string.sync_authority))
                .setExtras(Bundle().apply { putInt(SyncAdapter.ACTION, SyncAdapter.SYNC_REMOTE_CHANGES) })
                .syncPeriodic(6 * 3600L, 20 * 60L)
                .build())

             *//*

        }
        else ContentResolver.removePeriodicSync(accounts[0], getString(R.string.sync_authority), Bundle.EMPTY)

        syncWhenClosing = on
    }
*/

/*
    private fun toggleBackup(isOn: Boolean, whichFolder: String) {
        val sp = preferenceManager.sharedPreferences
        val folderName = if (whichFolder == LAST_BACKUP_CAMERA) "DCIM" else "Pictures"

        if (isOn) {
            // Check and disable periodic sync setting if user enable camera roll backup
            syncPreference?.let {
                it.isChecked = true
                it.isEnabled = false
            }
            if (sp.getLong(whichFolder, 0L) == 0L) {
                // First time toggling, offer choices of backup old pictures or not
                parentFragmentManager.findFragmentByTag(CONFIRM_DIALOG) ?: run {
                    Tools.getFolderStatistic(requireContext().contentResolver, folderName).let {
                        if (it.first > 0)
                            // If there are existing photos in camera roll, offer choice to backup those too
                            ConfirmDialogFragment.newInstance(getString(R.string.msg_backup_existing, folderName, it.first, Tools.humanReadableByteCountSI(it.second)),
                                positiveButtonText = getString(R.string.strip_exif_yes), negativeButtonText = getString(R.string.strip_exif_no), cancelable = false, requestKey = if (whichFolder == LAST_BACKUP_CAMERA) BACKUP_OLD_CAMERA_ROLL_DIALOG else BACKUP_OLD_PICTURES_DIALOG).show(parentFragmentManager, CONFIRM_DIALOG)
                        else sp.edit().apply {
                            putLong(whichFolder, 1L)
                            apply()
                        }
                    }
                }
            }
            toggleAutoSync(true)
        } else {
            // If both Camera Roll backup and Pictures folder backup has been turned off, enable Periodic Sync setting, then turn it off because when we enabled backup last time, this setting was turned on with no regard to it's previous state
            if (!sp.getBoolean(getString(if (whichFolder == LAST_BACKUP_CAMERA) R.string.pictures_backup_pref_key else R.string.cameraroll_backup_pref_key), false)) syncPreference?.let {
                it.isChecked = false
                it.isEnabled = true
                toggleAutoSync(false)
            }
        }
    }
*/

    private fun showStatistic(preference: Preference) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            // Database statistic
            storageStatisticSummaryString = getString(R.string.statistic_db_message, PhotoRepository(requireActivity().application).getPhotoTotal(), AlbumRepository(requireActivity().application).getAlbumTotal())
            withContext(Dispatchers.Main) { preference.summary = storageStatisticSummaryString }

            // Storage space statistic
            storageStatisticSummaryString = storageStatisticSummaryString + "\n" +
                    getString(R.string.statistic_storage_message,
                        Tools.humanReadableByteCountSI(if (totalSize == -1L) Tools.getStorageSize(requireContext()) else totalSize),
                        getString(if (PreferenceManager.getDefaultSharedPreferences(requireContext()).getBoolean(KEY_STORAGE_LOCATION, true)) R.string.internal_storage else R.string.external_storage)
                    )
            withContext(Dispatchers.Main) { preference.summary = storageStatisticSummaryString }
            storageStatisticSummaryString = storageStatisticSummaryString + "\n" + getString(R.string.statistic_free_space_message, Tools.humanReadableByteCountSI(requireContext().filesDir.freeSpace), getString(R.string.internal_storage))

            if (volume.size > 1 && volume[1].state == Environment.MEDIA_MOUNTED )
                try {
                    storageStatisticSummaryString = storageStatisticSummaryString + "\n" + getString(R.string.statistic_free_space_message, Tools.humanReadableByteCountSI(requireContext().getExternalFilesDirs(null)[1].freeSpace), getString(R.string.external_storage))
                } catch (_: NullPointerException) {}

            withContext(Dispatchers.Main) { preference.summary = storageStatisticSummaryString }
        }
    }

    private fun isEnoughSpace(sp: SharedPreferences): Boolean =
        // Add 100MB redundant
        try {
            (if (sp.getBoolean(KEY_STORAGE_LOCATION, true)) requireContext().getExternalFilesDirs(null)[1] else requireContext().filesDir).freeSpace > totalSize + 100 * 1024 * 1024
            //(if (sp.getBoolean(KEY_STORAGE_LOCATION, true)) requireContext().getExternalFilesDirs(null)[1] else requireContext().filesDir).freeSpace > totalSize
        } catch (e: Exception) { false }

    class TransferStorageDialog: LesPasDialogFragment(R.layout.fragment_transfer_storage_dialog) {
        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            view.findViewById<TextView>(R.id.dialog_title).apply { text = arguments?.getString(MESSAGE) }
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
                setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, mutableListOf<Int>().apply {
                    for (i in 1..10) add(i*100)
                    add(5000)
                }))
                setOnItemClickListener { _, _, position, _ ->
                    sp.edit { putInt(CACHE_SIZE, (if (position < 10) position + 1 else 50) * 100) }
                    dismiss()
                }
            }
        }
    }

    companion object {
        private const val TRANSFER_FILES_DIALOG = "CONFIRM_MOVING_DIALOG"
        private const val CONFIRM_DIALOG = "CONFIRM_DIALOG"
        private const val SETTING_REQUEST_KEY = "SETTING_REQUEST_KEY"
        private const val LOGOUT_CONFIRM_DIALOG = "LOGOUT_CONFIRM_DIALOG"
        private const val CLEAR_CACHE_CONFIRM_DIALOG = "CLEAR_CACHE_CONFIRM_DIALOG"
        private const val CACHE_SIZE_DIALOG = "CACHE_SIZE_DIALOG"
        private const val SNAPSEED_PERMISSION_RATIONALE_REQUEST_DIALOG = "SNAPSEED_PERMISSION_RATIONALE_REQUEST_DIALOG"
        private const val INSTALL_SNAPSEED_DIALOG = "INSTALL_SNAPSEED_DIALOG"
        private const val SYNC_STATUS_DIALOG = "SYNC_STATUS_DIALOG"
/*
        private const val BACKUP_OLD_CAMERA_ROLL_DIALOG = "BACKUP_OLD_CAMERA_ROLL_DIALOG"
        private const val BACKUP_OLD_PICTURES_DIALOG = "BACKUP_OLD_PICTURES_DIALOG"
*/
        private const val MANAGE_MEDIA_PERMISSION_DIALOG = "MANAGE_MEDIA_PERMISSION_DIALOG"
        private const val MANAGE_MEDIA_PERMISSION_RATIONALE_REQUEST = "MANAGE_MEDIA_PERMISSION_RATIONALE_REQUEST"

        private const val STATISTIC_SUMMARY_STRING = "STATISTIC_SUMMARY_STRING"
        private const val STATISTIC_TOTAL_SIZE = "STATISTIC_TOTAL_SIZE"

/*
        const val LAST_BACKUP_CAMERA = "LAST_BACKUP_TIMESTAMP"
        const val LAST_BACKUP_PICTURE = "LAST_BACKUP_TIMESTAMP_PICTURES"
*/
        const val KEY_STORAGE_LOCATION = "KEY_STORAGE_LOCATION"
        const val SERVER_HOME_FOLDER = "SERVER_HOME_FOLDER"
        // Setting to mark this instance uses new home folder setup on server, e.g., eliminated the requirement of /lespas sub-folder (the change in release 2.8.5). This preference and all it related functions can be removed in the future upgrade
        const val NEW_HOME_SETTING = "NEW_HOME_SETTING"

        const val SNAPSEED_PACKAGE_NAME = "com.niksoftware.snapseed"
        const val SNAPSEED_MAIN_ACTIVITY_CLASS_NAME = "com.google.android.apps.snapseed.MainActivity"

        const val CACHE_SIZE = "WEB_CACHE_SIZE"
        const val PICO_BLOG_ID = "PICO_BLOG_ID"
        const val PICO_BLOG_FOLDER = "PICO_BLOG_FOLDER"
    }
}