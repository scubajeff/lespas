package site.leos.apps.lespas

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import site.leos.apps.lespas.album.AlbumFragment

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        supportFragmentManager
            .beginTransaction()
            .add(R.id.container_root, AlbumFragment.newInstance())
            .commit()
    }
}