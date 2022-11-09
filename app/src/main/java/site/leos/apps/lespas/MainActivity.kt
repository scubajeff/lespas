/*
 *   Copyright 2019 Jeffrey Liu (scubajeffrey@criptext.com)
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
import android.os.storage.StorageManager
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import site.leos.apps.lespas.album.AlbumDetailFragment
import site.leos.apps.lespas.album.AlbumFragment
import site.leos.apps.lespas.album.AlbumRepository
import site.leos.apps.lespas.auth.NCLoginFragment
import site.leos.apps.lespas.cameraroll.CameraRollFragment
import site.leos.apps.lespas.helper.ConfirmDialogFragment
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.helper.TransferStorageWorker
import site.leos.apps.lespas.muzei.LesPasArtProvider
import site.leos.apps.lespas.settings.SettingsFragment
import site.leos.apps.lespas.sync.ActionViewModel
import site.leos.apps.lespas.sync.SyncAdapter
import java.io.File

class MainActivity : AppCompatActivity() {
    private val actionsPendingModel: ActionViewModel by viewModels()
    private lateinit var sp: SharedPreferences

    private lateinit var accounts: Array<Account>
    private var loggedIn = true

    override fun onCreate(savedInstanceState: Bundle?) {
        sp = PreferenceManager.getDefaultSharedPreferences(this)

        accounts = AccountManager.get(this).getAccountsByType(getString(R.string.account_type_nc))
        if (accounts.isEmpty()) {
            loggedIn = false
            setTheme(R.style.Theme_LesPas_NoTitleBar)
            sp.getString(getString(R.string.auto_theme_perf_key), getString(R.string.theme_auto_values))?.let { AppCompatDelegate.setDefaultNightMode(it.toInt()) }
            super.onCreate(savedInstanceState)
            setContentView(R.layout.activity_main)
            if (savedInstanceState == null) supportFragmentManager.beginTransaction().add(R.id.container_root, NCLoginFragment()).commit()
        } else {
            Tools.applyTheme(this, R.style.Theme_LesPas, R.style.Theme_LesPas_TrueBlack)
            super.onCreate(savedInstanceState)
            setContentView(R.layout.activity_main)

            supportFragmentManager.setFragmentResultListener(ACTIVITY_DIALOG_REQUEST_KEY, this) { key, bundle ->
                if (key == ACTIVITY_DIALOG_REQUEST_KEY && bundle.getBoolean(ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY, false)) {
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
                if (!sp.getBoolean(SettingsFragment.KEY_STORAGE_LOCATION, true) && (getSystemService(Context.STORAGE_SERVICE) as StorageManager).storageVolumes[1].state != Environment.MEDIA_MOUNTED) {
                    // We need external SD mounted writable
                    if (supportFragmentManager.findFragmentByTag(CONFIRM_REQUIRE_SD_DIALOG) == null) ConfirmDialogFragment.newInstance(getString(R.string.sd_card_not_ready), cancelable = false, requestKey = CONFIRM_REQUIRE_SD_DIALOG)
                        .show(supportFragmentManager, CONFIRM_REQUIRE_SD_DIALOG)
                } else {
                    lifecycleScope.launch(Dispatchers.IO) {
                        // Make sure photo's folder created
                        try {
                            File(Tools.getLocalRoot(applicationContext)).mkdir()
                        } catch (e: Exception) {}
                    }

                    intent.getStringExtra(LesPasArtProvider.FROM_MUZEI_ALBUM)?.let {
                        Thread {
                            val album = AlbumRepository(this.application).getThisAlbum(it)
                            supportFragmentManager.beginTransaction().add(R.id.container_root, AlbumDetailFragment.newInstance(album, intent.getStringExtra(LesPasArtProvider.FROM_MUZEI_PHOTO) ?: ""), AlbumDetailFragment::class.java.canonicalName).commit()
                        }.start()
                    } ?: run {
                        lifecycleScope.launch {
                            // If storage permission is granted, request ACCESS_MEDIA_LOCATION if running on Android Q and above, else disable Snapseed integration, camera roll backup and camera roll as album feature
                            if (Tools.shouldRequestStoragePermission(this@MainActivity))
                                sp.edit {
                                    putBoolean(getString(R.string.snapseed_pref_key), false)
                                    putBoolean(getString(R.string.cameraroll_backup_pref_key), false)
                                    putBoolean(getString(R.string.cameraroll_as_album_perf_key), false)
                                    putBoolean(getString(R.string.cameraroll_as_album_perf_key), false)
                                }
                            else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) registerForActivityResult(ActivityResultContracts.RequestPermission(), {}).launch(android.Manifest.permission.ACCESS_MEDIA_LOCATION)

                            // If Snapseed is not installed, disable Snapseed integration
                            packageManager.getLaunchIntentForPackage(SettingsFragment.SNAPSEED_PACKAGE_NAME) ?: run { sp.edit { putBoolean(getString(R.string.snapseed_pref_key), false) }}

                            // Sync when receiving network tickle
                            ContentResolver.setSyncAutomatically(accounts[0], getString(R.string.sync_authority), true)
                        }

                        when(intent.action) {
                            LAUNCH_CAMERAROLL -> supportFragmentManager.beginTransaction().add(R.id.container_root, CameraRollFragment.newInstance(), CameraRollFragment.TAG_FROM_CAMERAROLL_ACTIVITY).commit()
                            Intent.ACTION_VIEW -> intent.data?.let { supportFragmentManager.beginTransaction().add(R.id.container_root, CameraRollFragment.newInstance(it), CameraRollFragment.TAG_FROM_CAMERAROLL_ACTIVITY).commit() }
                            else -> supportFragmentManager.beginTransaction().add(R.id.container_root, AlbumFragment.newInstance()).commit()
                        }
                    }
                }

                // Create album meta file for all synced albums if needed
                //WorkManager.getInstance(this).enqueueUniqueWork(MetaFileMaintenanceWorker.WORKER_NAME, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<MetaFileMaintenanceWorker>().build())
            }

            // Setup observer to fire up SyncAdapter
            actionsPendingModel.allPendingActions.observe(this) { actions ->
                if (actions.isNotEmpty()) ContentResolver.requestSync(accounts[0], getString(R.string.sync_authority), Bundle().apply {
                    putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                    putInt(SyncAdapter.ACTION, SyncAdapter.SYNC_LOCAL_CHANGES)
                })
            }

            //if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    override fun onResume() {
        super.onResume()
        // When user removed all accounts from system setting. User data is removed in SystemBroadcastReceiver
        if (loggedIn && AccountManager.get(this).getAccountsByType(getString(R.string.account_type_nc)).isEmpty()) finishAndRemoveTask()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when(item.itemId) {
            // Response to "up" affordance pressed in fragments
            android.R.id.home -> onBackPressed()
            else -> return super.onOptionsItemSelected(item)
        }

        return true
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        supportFragmentManager.fragments.apply {
            if (this.isNotEmpty()) this.last().let { if (it is OnWindowFocusChangedListener) it.onWindowFocusChanged(hasFocus) }
        }
    }

    interface OnWindowFocusChangedListener {
        fun onWindowFocusChanged(hasFocus: Boolean)
    }

    fun observeTransferWorker() {
        WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData(TransferStorageWorker.WORKER_NAME).observe(this) { workInfos ->
            try {
                workInfos?.get(0)?.apply {
                    if (state.isFinished) {
                        if (supportFragmentManager.findFragmentByTag(CONFIRM_RESTART_DIALOG) == null) ConfirmDialogFragment.newInstance(getString(R.string.need_to_restart), cancelable = false, requestKey = CONFIRM_RESTART_DIALOG)
                            .show(supportFragmentManager, CONFIRM_RESTART_DIALOG)
                    }
                }
            } catch (e: IndexOutOfBoundsException) {}
        }
    }

/*
    fun themeToolbar(themeColor: Int) {
        toolbar.background = TransitionDrawable(arrayOf(ColorDrawable(ContextCompat.getColor(this, R.color.color_primary)), ColorDrawable(themeColor))).apply { startTransition(2000) }
    }

*/
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
        const val ACTIVITY_DIALOG_REQUEST_KEY = "ACTIVITY_DIALOG_REQUEST_KEY"
        const val CONFIRM_RESTART_DIALOG = "CONFIRM_RESTART_DIALOG"
        const val CONFIRM_REQUIRE_SD_DIALOG = "CONFIRM_REQUIRE_SD_DIALOG"
        const val LAUNCH_CAMERAROLL = "LAUNCH_CAMERAROLL"
    }
}
