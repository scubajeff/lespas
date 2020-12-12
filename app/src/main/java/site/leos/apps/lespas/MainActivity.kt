package site.leos.apps.lespas

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.Toolbar
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        PreferenceManager.getDefaultSharedPreferences(applicationContext).getString(getString(R.string.auto_theme_perf_key), getString(R.string.theme_auto_values))?.let {
            AppCompatDelegate.setDefaultNightMode(it.toInt())
        }

        setContentView(R.layout.activity_main)

        // Make sure photo's folder existed
        // TODO try clearing the cache folder
        Executors.newSingleThreadExecutor().execute {
            File(application.filesDir, getString(R.string.lespas_base_folder_name)).mkdir()
        }

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        val account: Account = AccountManager.get(this).accounts[0]
        if (savedInstanceState == null) {
            // Syncing server changes at startup
            ContentResolver.requestSync(account, getString(R.string.sync_authority), Bundle().apply {
                putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                //putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
                putInt(SyncAdapter.ACTION, SyncAdapter.SYNC_REMOTE_CHANGES)
            })

            supportFragmentManager.beginTransaction().add(R.id.container_root, AlbumFragment.newInstance()).commit()
        }

        // Setup observer to fire up SyncAdapter
        ContentResolver.setSyncAutomatically(account, getString(R.string.sync_authority), true)
        actionsPendingModel.allActions.observe(this, { actions ->
            if (actions.isNotEmpty()) ContentResolver.requestSync(account, getString(R.string.sync_authority), Bundle().apply { putInt(SyncAdapter.ACTION, SyncAdapter.SYNC_LOCAL_CHANGES) })
        })
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