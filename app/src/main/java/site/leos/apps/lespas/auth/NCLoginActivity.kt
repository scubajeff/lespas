package site.leos.apps.lespas.auth

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import site.leos.apps.lespas.R

class NCLoginActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<MaterialToolbar>(R.id.toolbar).visibility = View.GONE

        savedInstanceState ?: run { supportFragmentManager.beginTransaction().add(R.id.container_root, NCLoginFragment.newInstance(false)).commit() }
    }
}