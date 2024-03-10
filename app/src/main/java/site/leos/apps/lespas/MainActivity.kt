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

package site.leos.apps.lespas

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.view.MenuItem
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.work.WorkManager
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import site.leos.apps.lespas.album.Album
import site.leos.apps.lespas.album.AlbumDetailFragment
import site.leos.apps.lespas.album.AlbumFragment
import site.leos.apps.lespas.album.AlbumRepository
import site.leos.apps.lespas.auth.NCLoginFragment
import site.leos.apps.lespas.gallery.GalleryFragment
import site.leos.apps.lespas.helper.ConfirmDialogFragment
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.helper.TransferStorageWorker
import site.leos.apps.lespas.muzei.LesPasArtProvider
import site.leos.apps.lespas.settings.SettingsFragment
import site.leos.apps.lespas.sync.Action
import site.leos.apps.lespas.sync.ActionViewModel
import site.leos.apps.lespas.sync.SyncAdapter
import java.io.File

class MainActivity : AppCompatActivity() {
    private val actionsPendingModel: ActionViewModel by viewModels()
    private lateinit var sp: SharedPreferences
    private lateinit var toolbar: MaterialToolbar

    private lateinit var accounts: Array<Account>

    private var prefBackupNeeded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        Tools.applyTheme(this, R.style.Theme_LesPas, R.style.Theme_LesPas_TrueBlack)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById(R.id.toolbar))

        sp = PreferenceManager.getDefaultSharedPreferences(this)
        accounts = AccountManager.get(this).getAccountsByType(getString(R.string.account_type_nc))
        if (accounts.isEmpty() && savedInstanceState == null) supportFragmentManager.beginTransaction().add(R.id.container_root, NCLoginFragment()).commit()
        else {
            // Edge to edge
            toolbar = findViewById(R.id.toolbar)
            ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { _, windowInsets ->
                // Should apply horizontal padding, so that action mode would work in 3-button navigation mode
                val displayCutoutInset = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
                val systemBarInset = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                toolbar.updatePadding(top = systemBarInset.top, left = systemBarInset.left + displayCutoutInset.left, right = systemBarInset.right + displayCutoutInset.right)
                windowInsets
            }
            WindowCompat.setDecorFitsSystemWindows(window, false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES

            supportFragmentManager.setFragmentResultListener(MAIN_ACTIVITY_REQUEST_KEY, this) { _, bundle ->
                if (bundle.getBoolean(ConfirmDialogFragment.CONFIRM_DIALOG_RESULT_KEY, false)) {
                    when (bundle.getString(ConfirmDialogFragment.INDIVIDUAL_REQUEST_KEY, "")) {
                        CONFIRM_RESTART_DIALOG -> {
                            WorkManager.getInstance(this).pruneWork()
                            navigateUpTo(Intent(this, MainActivity::class.java))
                            startActivity(intent)
                        }
                        CONFIRM_REQUIRE_SD_DIALOG -> finish()
                    }
                }
            }

            if (savedInstanceState == null) {
                if (!sp.getBoolean(SettingsFragment.KEY_STORAGE_LOCATION, true) && ((getSystemService(Context.STORAGE_SERVICE) as StorageManager).storageVolumes.size < 2 || (getSystemService(Context.STORAGE_SERVICE) as StorageManager).storageVolumes[1].state != Environment.MEDIA_MOUNTED)) {
                    // We need external SD mounted writable
                    if (supportFragmentManager.findFragmentByTag(CONFIRM_REQUIRE_SD_DIALOG) == null) ConfirmDialogFragment.newInstance(getString(R.string.sd_card_not_ready), cancelable = false, individualKey = CONFIRM_REQUIRE_SD_DIALOG, requestKey = MAIN_ACTIVITY_REQUEST_KEY)
                        .show(supportFragmentManager, CONFIRM_REQUIRE_SD_DIALOG)
                } else {
                    lifecycleScope.launch(Dispatchers.IO) {
                        // Make sure photo's folder created
                        try { File(Tools.getLocalRoot(applicationContext)).mkdir() } catch (_: Exception) {}
                    }

                    intent.getStringExtra(LesPasArtProvider.FROM_MUZEI_ALBUM)?.let {
                        var album: Album? = null
                        lifecycleScope.launch(Dispatchers.IO) {
                            album = AlbumRepository(this@MainActivity.application).getThisAlbum(it)
                        }.invokeOnCompletion {
                            album?.let { supportFragmentManager.beginTransaction().add(R.id.container_root, AlbumDetailFragment.newInstance(it, intent.getStringExtra(LesPasArtProvider.FROM_MUZEI_PHOTO) ?: ""), AlbumDetailFragment::class.java.canonicalName).commit() }
                        }
                    } ?: run {
                        lifecycleScope.launch {
                            // If storage permission is granted, request ACCESS_MEDIA_LOCATION if running on Android Q and above, else disable Snapseed integration, camera roll backup and camera roll as album feature
                            if (Tools.shouldRequestStoragePermission(this@MainActivity))
                                sp.edit {
                                    putBoolean(getString(R.string.snapseed_pref_key), false)
                                    //putBoolean(getString(R.string.cameraroll_backup_pref_key), false)
                                    putBoolean(getString(R.string.gallery_as_album_perf_key), false)
                                }
                            else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(android.Manifest.permission.ACCESS_MEDIA_LOCATION)

                            // If Snapseed is not installed, disable Snapseed integration
                            packageManager.getLaunchIntentForPackage(SettingsFragment.SNAPSEED_PACKAGE_NAME) ?: run { sp.edit { putBoolean(getString(R.string.snapseed_pref_key), false) }}

                            // Sync when receiving network tickle
                            ContentResolver.setSyncAutomatically(accounts[0], getString(R.string.sync_authority), true)
                        }

                        when(intent.action) {
                            LAUNCH_GALLERY -> supportFragmentManager.beginTransaction().add(R.id.container_root, GalleryFragment(), GalleryFragment.TAG_FROM_LAUNCHER).commit()
                            Intent.ACTION_VIEW -> intent.data?.let { supportFragmentManager.beginTransaction().add(R.id.container_root, GalleryFragment.newInstance(it), GalleryFragment.TAG_FROM_LAUNCHER).commit() }
                            else -> supportFragmentManager.beginTransaction().add(R.id.container_root, AlbumFragment.newInstance()).commit()
                        }
                    }
                }

                // Create album meta file for all synced albums if needed
                //WorkManager.getInstance(this).enqueueUniqueWork(MetaFileMaintenanceWorker.WORKER_NAME, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<MetaFileMaintenanceWorker>().build())

                // Check internal storage free space, warn user if it's lower than 10% free
                window.decorView.post {
                    lifecycleScope.launch(Dispatchers.IO) {
                        StatFs(Environment.getDataDirectory().path).let {
                            if (it.availableBlocksLong < it.blockCountLong / 10) withContext(Dispatchers.Main) {
                                if (supportFragmentManager.findFragmentByTag(CONFIRM_LOW_STORAGE_SPACE_DIALOG) == null) ConfirmDialogFragment.newInstance(getString(R.string.msg_low_storage_space), cancelable = false, requestKey = MAIN_ACTIVITY_REQUEST_KEY).show(supportFragmentManager, CONFIRM_LOW_STORAGE_SPACE_DIALOG)
                            }
                        }
                    }
                }
            }

            // Setup observer to fire up SyncAdapter
            actionsPendingModel.allPendingActions.observe(this) { actions -> if (actions.isNotEmpty()) requestSync() }
        }
    }

    override fun onStart() {
        super.onStart()

        // Listener to any preference changes
        if (accounts.isNotEmpty()) sp.registerOnSharedPreferenceChangeListener(backupPreferenceListener)
    }

    override fun onResume() {
        super.onResume()
        // When user removed all accounts from system setting. User data is removed in SystemBroadcastReceiver
        if (accounts.isNotEmpty() && AccountManager.get(this).getAccountsByType(getString(R.string.account_type_nc)).isEmpty()) finishAndRemoveTask()
    }

    override fun onStop() {
        // Save preference changes on server
        if (prefBackupNeeded) {
            actionsPendingModel.addAction(Action(null, Action.ACTION_BACKUP_PREFERENCE, "", "", "", "", System.currentTimeMillis(), 1))
            prefBackupNeeded = false
            requestSync()
        }
        if (accounts.isNotEmpty()) sp.unregisterOnSharedPreferenceChangeListener(backupPreferenceListener)

        super.onStop()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when(item.itemId) {
            // Response to "up" affordance pressed in fragments
            android.R.id.home -> onBackPressedDispatcher.onBackPressed()
            else -> return super.onOptionsItemSelected(item)
        }

        return true
    }

    private fun requestSync() {
        ContentResolver.requestSync(accounts[0], getString(R.string.sync_authority), Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putInt(SyncAdapter.ACTION, SyncAdapter.SYNC_LOCAL_CHANGES)
        })
    }

    fun observeTransferWorker() {
        WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData(TransferStorageWorker.WORKER_NAME).observe(this) { workInfos ->
            try {
                workInfos?.get(0)?.apply {
                    if (state.isFinished) {
                        if (supportFragmentManager.findFragmentByTag(CONFIRM_RESTART_DIALOG) == null) ConfirmDialogFragment.newInstance(getString(R.string.need_to_restart), cancelable = false, individualKey = CONFIRM_RESTART_DIALOG, requestKey = MAIN_ACTIVITY_REQUEST_KEY)
                            .show(supportFragmentManager, CONFIRM_RESTART_DIALOG)
                    }
                }
            } catch (_: IndexOutOfBoundsException) {}
        }
    }

    // Should intercept preference changes here, since not every setting is managed by SettingsFragment
    private val backupPreferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when(key) {
            // TODO ignore changes of preferences that don't need backup
            AlbumFragment.KEY_RECEIVED_SHARE_TIMESTAMP,
            //SettingsFragment.LAST_BACKUP_CAMERA,
            //SettingsFragment.LAST_BACKUP_PICTURE,
            getString(R.string.sync_deletion_perf_key),
            getString(R.string.backup_status_pref_key),
            getString(R.string.sync_status_local_action_pref_key),
            getString(R.string.sync_status_pref_key),
            SyncAdapter.LATEST_ARCHIVE_FOLDER_ETAG -> {}

            else -> prefBackupNeeded = true
        }
    }

/*
    class MetaFileMaintenanceWorker(private val context: Context, workerParams: WorkerParameters): CoroutineWorker(context, workerParams) {
        override suspend fun doWork(): Result {
            val actionDao = LespasDatabase.getDatabase(context).actionDao()
            val albumDao = LespasDatabase.getDatabase(context).albumDao()
            val photoDao = LespasDatabase.getDatabase(context).photoDao()

            for (album in albumDao.getAllSyncedAlbum())
                if (!File(Tools.getLocalRoot(context), "${album.id}_v2.json").exists()) {
                    if (photoDao.getETag(album.cover).isNotEmpty()) actionDao.updateAlbumMeta(album.id, photoDao.getName(album.cover))
                }

            return Result.success()
        }

        companion object {
            const val WORKER_NAME = "${BuildConfig.APPLICATION_ID}.META_FILE_MAINTENANCE_WORKER"
        }
    }
*/

    companion object {
        private const val MAIN_ACTIVITY_REQUEST_KEY = "MAIN_ACTIVITY_REQUEST_KEY"
        private const val CONFIRM_RESTART_DIALOG = "CONFIRM_RESTART_DIALOG"
        private const val CONFIRM_REQUIRE_SD_DIALOG = "CONFIRM_REQUIRE_SD_DIALOG"
        private const val CONFIRM_LOW_STORAGE_SPACE_DIALOG = "CONFIRM_LOW_STORAGE_SPACE_DIALOG"

        const val LAUNCH_GALLERY = "LAUNCH_GALLERY"
    }
}