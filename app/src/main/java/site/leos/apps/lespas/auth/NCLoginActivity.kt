package site.leos.apps.lespas.auth

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import site.leos.apps.lespas.R

class NCLoginActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        supportActionBar?.hide()

        savedInstanceState ?: run { supportFragmentManager.beginTransaction().add(R.id.container_root, NCLoginFragment.newInstance(false)).commit() }
    }
}