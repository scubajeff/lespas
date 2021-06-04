package site.leos.apps.lespas.cameraroll

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.ConfirmDialogFragment
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.settings.SettingsFragment


class CameraRollActivity : AppCompatActivity(), ConfirmDialogFragment.OnResultListener {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setSupportActionBar(findViewById(R.id.toolbar))

        if (savedInstanceState == null) {
            if (!PreferenceManager.getDefaultSharedPreferences(this).getBoolean(SettingsFragment.KEY_STORAGE_LOCATION, true) && (getSystemService(Context.STORAGE_SERVICE) as StorageManager).storageVolumes[1].state != Environment.MEDIA_MOUNTED) {
                // We need external SD mounted writable
                if (supportFragmentManager.findFragmentByTag(CONFIRM_REQUIRE_SD_DIALOG) == null) ConfirmDialogFragment.newInstance(getString(R.string.sd_card_not_ready), null, false).let {
                    it.setRequestCode(CONFIRM_REQUIRE_SD_REQUEST_CODE)
                    it.show(supportFragmentManager, CONFIRM_REQUIRE_SD_DIALOG)
                }
            } else {
                if (intent.action == Intent.ACTION_MAIN) supportFragmentManager.beginTransaction().add(R.id.container_root, CameraRollFragment(), CameraRollFragment::class.java.canonicalName).commit()
                else intent.data?.apply {supportFragmentManager.beginTransaction().add(R.id.container_root, CameraRollFragment.newInstance(this), CameraRollFragment::class.java.canonicalName).commit()} ?: run { finish() }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Tools.avoidCutOutArea(this.window)
    }

    override fun onResult(positive: Boolean, requestCode: Int) {
        if (positive && requestCode == CONFIRM_REQUIRE_SD_REQUEST_CODE) finish()
    }

    companion object {
        private const val CONFIRM_REQUIRE_SD_DIALOG = "CONFIRM_REQUIRE_SD_DIALOG"
        private const val CONFIRM_REQUIRE_SD_REQUEST_CODE = 5453
    }
}