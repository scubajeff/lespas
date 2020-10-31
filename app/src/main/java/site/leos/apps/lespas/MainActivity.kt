package site.leos.apps.lespas

import android.accounts.AccountManager
import android.content.ContentResolver
import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import site.leos.apps.lespas.album.AlbumFragment

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))

        if (savedInstanceState == null) {
            // Start syncing with server at first run
            ContentResolver.requestSync((AccountManager.get(this).accounts)[0], getString(R.string.sync_authority), Bundle().apply {
                putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
            })

            supportFragmentManager.beginTransaction().add(R.id.container_root, AlbumFragment.newInstance()).commit()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when(item.itemId) {
            // Response to "up" affordance pressed in fragments
            android.R.id.home -> onBackPressed()
        }
        return super.onOptionsItemSelected(item)
    }
}