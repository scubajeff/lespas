package site.leos.apps.lespas

import android.accounts.AccountManager
import android.content.ContentResolver
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import site.leos.apps.lespas.album.AlbumFragment

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Start syncing with server
        ContentResolver.requestSync((AccountManager.get(this).accounts)[0], getString(R.string.sync_authority), Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
        })

        supportFragmentManager.beginTransaction().add(R.id.container_root, AlbumFragment.newInstance()).commit()
    }
}