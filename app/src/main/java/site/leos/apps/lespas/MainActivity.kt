package site.leos.apps.lespas

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.drawToBitmap
import androidx.preference.PreferenceManager
import site.leos.apps.lespas.album.AlbumFragment
import site.leos.apps.lespas.sync.ActionViewModel
import site.leos.apps.lespas.sync.SyncAdapter
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val actionsPendingModel: ActionViewModel by viewModels()
    private lateinit var toolbar: Toolbar
    private lateinit var sp: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        sp = PreferenceManager.getDefaultSharedPreferences(this)
        sp.getString(getString(R.string.auto_theme_perf_key), getString(R.string.theme_auto_values))?.let { AppCompatDelegate.setDefaultNightMode(it.toInt()) }

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Make sure photo's folder existed
        // TODO try clearing the cache folder
        Executors.newSingleThreadExecutor().execute { File(application.filesDir, getString(R.string.lespas_base_folder_name)).mkdir() }

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        if (savedInstanceState == null) {
            val account: Account = AccountManager.get(this).accounts[0]

            // Syncing server changes at startup and set it to run when receiving network tickle
            ContentResolver.requestSync(account, getString(R.string.sync_authority), Bundle().apply {
                putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                //putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
                putInt(SyncAdapter.ACTION, SyncAdapter.SYNC_REMOTE_CHANGES)
            })
            ContentResolver.setSyncAutomatically(account, getString(R.string.sync_authority), true)

            supportFragmentManager.beginTransaction().add(R.id.container_root, AlbumFragment.newInstance()).commit()

            // Setup observer to fire up SyncAdapter
            actionsPendingModel.allActions.observe(this, { actions ->
                if (actions.isNotEmpty()) ContentResolver.requestSync(account, getString(R.string.sync_authority), Bundle().apply {
                    putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                    putInt(SyncAdapter.ACTION, SyncAdapter.SYNC_LOCAL_CHANGES)
                })
            })

            // If WRITE_EXTERNAL_STORAGE permission not granted, disable Snapseed integration
            if (ContextCompat.checkSelfPermission(this, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) android.Manifest.permission.READ_EXTERNAL_STORAGE else android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) sp.edit {
                putBoolean(getString(R.string.snapseed_pref_key), false)
                putBoolean(getString(R.string.cameraroll_backup_pref_key), false)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // When user removed all accounts from system setting. User data is removed in SystemBroadcastReceiver
        if (AccountManager.get(this).accounts.isEmpty()) finishAndRemoveTask()
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
}