package site.leos.apps.lespas

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import site.leos.apps.lespas.album.AlbumFragment
import site.leos.apps.lespas.settings.SettingsFragment
import site.leos.apps.lespas.sync.ActionViewModel
import site.leos.apps.lespas.sync.SyncAdapter

class MainActivity : AppCompatActivity() {
    private val actionsPendingModel: ActionViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setSupportActionBar(findViewById(R.id.toolbar))

        val account: Account = AccountManager.get(this).accounts[0]
        if (savedInstanceState == null) {
            // Syncing server changes at startup
            ContentResolver.requestSync(account, getString(R.string.sync_authority), Bundle().apply {
                putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
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

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.actions_main, menu)

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when(item.itemId) {
            // Response to "up" affordance pressed in fragments
            android.R.id.home -> onBackPressed()
            R.id.action_settings -> supportFragmentManager.beginTransaction().replace(R.id.container_root, SettingsFragment()).addToBackStack(null).commit()
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
}