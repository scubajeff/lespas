package site.leos.apps.lespas.cameraroll

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import site.leos.apps.lespas.MainActivity
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.ConfirmDialogFragment
import site.leos.apps.lespas.settings.SettingsFragment


class CameraRollActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setSupportActionBar(findViewById(R.id.toolbar))

        if (savedInstanceState == null) {
            if (!PreferenceManager.getDefaultSharedPreferences(this).getBoolean(SettingsFragment.KEY_STORAGE_LOCATION, true) && (getSystemService(Context.STORAGE_SERVICE) as StorageManager).storageVolumes[1].state != Environment.MEDIA_MOUNTED) {
                // We need external SD mounted writable
                if (supportFragmentManager.findFragmentByTag(MainActivity.CONFIRM_REQUIRE_SD_DIALOG) == null) ConfirmDialogFragment.newInstance(getString(R.string.sd_card_not_ready), null, false, MainActivity.CONFIRM_REQUIRE_SD_DIALOG)
                    .show(supportFragmentManager, MainActivity.CONFIRM_REQUIRE_SD_DIALOG)
            } else {
                if (intent.action == Intent.ACTION_MAIN) supportFragmentManager.beginTransaction().add(R.id.container_root, CameraRollFragment(), CameraRollFragment::class.java.canonicalName).commit()
                else intent.data?.apply {supportFragmentManager.beginTransaction().add(R.id.container_root, CameraRollFragment.newInstance(this), CameraRollFragment::class.java.canonicalName).commit()} ?: run { finish() }
            }
        }

        supportFragmentManager.setFragmentResultListener(MainActivity.ACTIVITY_DIALOG_REQUEST_KEY, this) { key, bundle ->
            if (key == MainActivity.ACTIVITY_DIALOG_REQUEST_KEY &&
                bundle.getBoolean(ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY, false) &&
                bundle.getString(ConfirmDialogFragment.INDIVIDUAL_REQUEST_KEY, "") == MainActivity.CONFIRM_REQUIRE_SD_DIALOG
            ) finish()
        }
    }
}