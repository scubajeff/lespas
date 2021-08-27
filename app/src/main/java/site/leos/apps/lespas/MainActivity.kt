package site.leos.apps.lespas

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.drawToBitmap
import androidx.preference.PreferenceManager
import androidx.work.*
import com.google.android.material.appbar.MaterialToolbar
import site.leos.apps.lespas.album.AlbumDetailFragment
import site.leos.apps.lespas.album.AlbumFragment
import site.leos.apps.lespas.album.AlbumRepository
import site.leos.apps.lespas.helper.ConfirmDialogFragment
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.helper.TransferStorageWorker
import site.leos.apps.lespas.muzei.LesPasArtProvider
import site.leos.apps.lespas.settings.SettingsFragment
import site.leos.apps.lespas.sync.ActionViewModel
import site.leos.apps.lespas.sync.SyncAdapter
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val actionsPendingModel: ActionViewModel by viewModels()
    private lateinit var toolbar: MaterialToolbar
    private lateinit var sp: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        sp = PreferenceManager.getDefaultSharedPreferences(this)
        sp.getString(getString(R.string.auto_theme_perf_key), getString(R.string.theme_auto_values))?.let { AppCompatDelegate.setDefaultNightMode(it.toInt()) }

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Make sure photo's folder existed
        Executors.newSingleThreadExecutor().execute { File(Tools.getLocalRoot(applicationContext)).mkdir() }

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        supportFragmentManager.setFragmentResultListener(ACTIVITY_DIALOG_REQUEST_KEY, this) { key, bundle ->
            if (key == ACTIVITY_DIALOG_REQUEST_KEY && bundle.getBoolean(ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY, false)) {
                when(bundle.getString(ConfirmDialogFragment.INDIVIDUAL_REQUEST_KEY, "")) {
                    CONFIRM_RESTART_DIALOG-> {
                        WorkManager.getInstance(this).pruneWork()
                        navigateUpTo(Intent(this, MainActivity::class.java))
                        startActivity(intent)
                    }
                    CONFIRM_REQUIRE_SD_DIALOG-> finish()
                }
            }
        }

        val account: Account = AccountManager.get(this).getAccountsByType(getString(R.string.account_type_nc))[0]
        if (savedInstanceState == null) {
            if (!sp.getBoolean(SettingsFragment.KEY_STORAGE_LOCATION, true) && (getSystemService(Context.STORAGE_SERVICE) as StorageManager).storageVolumes[1].state != Environment.MEDIA_MOUNTED) {
                // We need external SD mounted writable
                if (supportFragmentManager.findFragmentByTag(CONFIRM_REQUIRE_SD_DIALOG) == null) ConfirmDialogFragment.newInstance(getString(R.string.sd_card_not_ready), null, false, CONFIRM_REQUIRE_SD_DIALOG)
                    .show(supportFragmentManager, CONFIRM_REQUIRE_SD_DIALOG)
            } else {
                // Syncing server changes at startup
                ContentResolver.requestSync(account, getString(R.string.sync_authority), Bundle().apply {
                    putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                    //putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
                    putInt(SyncAdapter.ACTION, SyncAdapter.SYNC_REMOTE_CHANGES)
                })

                // If WRITE_EXTERNAL_STORAGE permission not granted, disable Snapseed integration and camera roll backup
                if (ContextCompat.checkSelfPermission(this, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) android.Manifest.permission.READ_EXTERNAL_STORAGE else android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) sp.edit {
                    putBoolean(getString(R.string.snapseed_pref_key), false)
                    putBoolean(getString(R.string.cameraroll_backup_pref_key), false)
                    putBoolean(getString(R.string.cameraroll_as_album_perf_key), false)
                }

                intent.getStringExtra(LesPasArtProvider.FROM_MUZEI_ALBUM)?.let {
                    Thread {
                        val album = AlbumRepository(this.application).getThisAlbum(it)[0]
                        supportFragmentManager.beginTransaction().add(R.id.container_root, AlbumDetailFragment.newInstance(album, intent.getStringExtra(LesPasArtProvider.FROM_MUZEI_PHOTO) ?: "")).commit()
                    }.start()
                } ?: run {
                    supportFragmentManager.beginTransaction().add(R.id.container_root, AlbumFragment.newInstance()).commit()
                }
            }

            // Create album meta file for all synced albums if needed
            WorkManager.getInstance(this).enqueueUniqueWork(MetaFileMaintenanceWorker.WORKER_NAME, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<MetaFileMaintenanceWorker>().build())
        }

        // Sync when receiving network tickle
        ContentResolver.setSyncAutomatically(account, getString(R.string.sync_authority), true)
        // Setup observer to fire up SyncAdapter
        actionsPendingModel.allActions.observe(this, { actions ->
            if (actions.isNotEmpty()) ContentResolver.requestSync(account, getString(R.string.sync_authority), Bundle().apply {
                putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                putInt(SyncAdapter.ACTION, SyncAdapter.SYNC_LOCAL_CHANGES)
            })
        })
    }

    override fun onResume() {
        super.onResume()
        // When user removed all accounts from system setting. User data is removed in SystemBroadcastReceiver
        if (AccountManager.get(this).getAccountsByType(getString(R.string.account_type_nc)).isEmpty()) finishAndRemoveTask()
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
        supportFragmentManager.run {
            if (backStackEntryCount > 0)
                getBackStackEntryAt(backStackEntryCount - 1).let { if (it is OnWindowFocusChangedListener) it.onWindowFocusChanged(hasFocus) }
        }
    }

    interface OnWindowFocusChangedListener {
        fun onWindowFocusChanged(hasFocus: Boolean)
    }

    fun getToolbarViewContent(): Drawable {
        return BitmapDrawable(resources, toolbar.drawToBitmap(Bitmap.Config.ARGB_8888))
    }

    fun observeTransferWorker() {
        WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData(TransferStorageWorker.WORKER_NAME).observe(this, { workInfos->
            try {
                workInfos?.get(0)?.apply {
                    if (state.isFinished) {
                        if (supportFragmentManager.findFragmentByTag(CONFIRM_RESTART_DIALOG) == null) ConfirmDialogFragment.newInstance(getString(R.string.need_to_restart), null, false, CONFIRM_RESTART_DIALOG)
                            .show(supportFragmentManager, CONFIRM_RESTART_DIALOG)
                    }
                }
            } catch (e: IndexOutOfBoundsException) { e.printStackTrace() }
        })
    }

/*
    fun themeToolbar(themeColor: Int) {
        toolbar.background = TransitionDrawable(arrayOf(ColorDrawable(ContextCompat.getColor(this, R.color.color_primary)), ColorDrawable(themeColor))).apply { startTransition(2000) }
    }

*/
    // TODO no need to do this after several release updates later?
    class MetaFileMaintenanceWorker(private val context: Context, workerParams: WorkerParameters): CoroutineWorker(context, workerParams) {
        override suspend fun doWork(): Result {
            val actionDao = LespasDatabase.getDatabase(context).actionDao()
            val albumDao = LespasDatabase.getDatabase(context).albumDao()
            val photoDao = LespasDatabase.getDatabase(context).photoDao()

            for (album in albumDao.getAllSyncedAlbum())
                if (!File(Tools.getLocalRoot(context), "${album.id}.json").exists()) {
                    if (photoDao.getETag(album.cover).isNotEmpty()) actionDao.updateMeta(album.id, photoDao.getName(album.cover))
                }

            return Result.success()
        }

        companion object {
            const val WORKER_NAME = "${BuildConfig.APPLICATION_ID}.META_FILE_MAINTENANCE_WORKER"
        }
    }

    companion object {
        const val ACTIVITY_DIALOG_REQUEST_KEY = "ACTIVITY_DIALOG_REQUEST_KEY"
        const val CONFIRM_RESTART_DIALOG = "CONFIRM_RESTART_DIALOG"
        const val CONFIRM_REQUIRE_SD_DIALOG = "CONFIRM_REQUIRE_SD_DIALOG"
    }
}