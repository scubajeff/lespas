package site.leos.apps.lespas.cameraroll

import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import site.leos.apps.lespas.MainActivity
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.ConfirmDialogFragment
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.settings.SettingsFragment
import site.leos.apps.lespas.sync.SyncAdapter
import java.io.File


class CameraRollActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Make sure photo's folder, temporary cache folder created
        lifecycleScope.launch(Dispatchers.IO) {
            // Make sure photo's folder created
            try {
                File(Tools.getLocalRoot(applicationContext)).mkdir()
            } catch (e: Exception) {}
        }

        if (savedInstanceState == null) {
            // Sync with server at startup
            ContentResolver.requestSync(AccountManager.get(this).getAccountsByType(getString(R.string.account_type_nc))[0], getString(R.string.sync_authority), Bundle().apply {
                putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                //putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
                putInt(SyncAdapter.ACTION, SyncAdapter.SYNC_BOTH_WAY)
            })

            if (!PreferenceManager.getDefaultSharedPreferences(this).getBoolean(SettingsFragment.KEY_STORAGE_LOCATION, true) && (getSystemService(Context.STORAGE_SERVICE) as StorageManager).storageVolumes[1].state != Environment.MEDIA_MOUNTED) {
                // We need external SD mounted writable
                if (supportFragmentManager.findFragmentByTag(MainActivity.CONFIRM_REQUIRE_SD_DIALOG) == null) ConfirmDialogFragment.newInstance(getString(R.string.sd_card_not_ready), null, false, MainActivity.CONFIRM_REQUIRE_SD_DIALOG)
                    .show(supportFragmentManager, MainActivity.CONFIRM_REQUIRE_SD_DIALOG)
            } else {
                if (intent.action == Intent.ACTION_MAIN) supportFragmentManager.beginTransaction().add(R.id.container_root, CameraRollFragment.newInstance(), CameraRollFragment.TAG_FROM_CAMERAROLL_ACTIVITY).commit()
                else intent.data?.apply {supportFragmentManager.beginTransaction().add(R.id.container_root, CameraRollFragment.newInstance(this), CameraRollFragment.TAG_FROM_CAMERAROLL_ACTIVITY).commit()} ?: run { finish() }
            }
        }

        supportFragmentManager.setFragmentResultListener(MainActivity.ACTIVITY_DIALOG_REQUEST_KEY, this) { key, bundle ->
            if (key == MainActivity.ACTIVITY_DIALOG_REQUEST_KEY &&
                bundle.getBoolean(ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY, false) &&
                bundle.getString(ConfirmDialogFragment.INDIVIDUAL_REQUEST_KEY, "") == MainActivity.CONFIRM_REQUIRE_SD_DIALOG
            ) finish()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }
}