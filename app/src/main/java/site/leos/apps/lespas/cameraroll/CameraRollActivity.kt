package site.leos.apps.lespas.cameraroll

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import site.leos.apps.lespas.R


class CameraRollActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setSupportActionBar(findViewById(R.id.toolbar))

        savedInstanceState ?: run {
            if (intent.action == Intent.ACTION_MAIN) supportFragmentManager.beginTransaction().add(R.id.container_root, CameraRollFragment(), CameraRollFragment::class.java.canonicalName).commit()
            else intent.data?.apply { supportFragmentManager.beginTransaction().add(R.id.container_root, CameraRollFragment.newInstance(this), CameraRollFragment::class.java.canonicalName).commit() } ?: run { finish() }
        }
    }
}