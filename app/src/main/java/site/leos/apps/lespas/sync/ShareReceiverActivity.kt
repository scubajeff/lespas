/*
 *   Copyright 2019 Jeffrey Liu (scubajeffrey@protonmail.ch)
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package site.leos.apps.lespas.sync

import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.helper.Tools.parcelable
import site.leos.apps.lespas.helper.Tools.parcelableArrayList
import java.io.File

class ShareReceiverActivity: AppCompatActivity() {
    private val files = ArrayList<Uri>()
    private val destinationModel: DestinationDialogFragment.DestinationViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        Tools.applyTheme(this, R.style.Theme_LesPas_Transparent, R.style.Theme_LesPas_Transparent_TrueBlack)
        super.onCreate(savedInstanceState)

        if (AccountManager.get(this).getAccountsByType(getString(R.string.account_type_nc)).isEmpty()) {
            Toast.makeText(this, getString(R.string.msg_login_first), Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // Make sure photo's folder created
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                File(Tools.getLocalRoot(applicationContext)).mkdir()
            } catch (_: Exception) {}
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES

        if ((intent.action == Intent.ACTION_SEND) && (intent.type?.startsWith("image/")!! || intent.type?.startsWith("video/")!!)) {
            //files.add(intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as Uri)
            files.add(intent.parcelable<Parcelable>(Intent.EXTRA_STREAM) as Uri)
        }
        if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            // MIME type checking will be done in AcquiringDialogFragment
            //intent.getParcelableArrayListExtra<Parcelable>(Intent.EXTRA_STREAM)?.forEach {
            intent.parcelableArrayList<Parcelable>(Intent.EXTRA_STREAM)?.forEach {
                files.add(it as Uri)
            }
        }

        if (files.isNotEmpty()) {
            destinationModel.getDestination().observe (this) { album ->
                album?.apply {
                    // Acquire files
                    if (supportFragmentManager.findFragmentByTag(TAG_ACQUIRING_DIALOG) == null)
                        AcquiringDialogFragment.newInstance(files, album, destinationModel.shouldRemoveOriginal()).show(supportFragmentManager, TAG_ACQUIRING_DIALOG)
                }
            }

            if (supportFragmentManager.findFragmentByTag(TAG_DESTINATION_DIALOG) == null)
                DestinationDialogFragment.newInstance(files, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION > 0 || intent.getBooleanExtra(KEY_SHOW_REMOVE_OPTION, false)).show(supportFragmentManager, TAG_DESTINATION_DIALOG)
        }
        else {
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        overridePendingTransition(0, 0)
    }

    override fun onPause() {
        overridePendingTransition(0, 0)
        super.onPause()
    }

    override fun onDestroy() {
        if (AccountManager.get(this).getAccountsByType(getString(R.string.account_type_nc)).isNotEmpty() && !intent.hasExtra(KEY_SHOW_REMOVE_OPTION)) {
            // Request sync immediately if called from other apps
            ContentResolver.requestSync(AccountManager.get(this).getAccountsByType(getString(R.string.account_type_nc))[0], getString(R.string.sync_authority), Bundle().apply {
                putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                //putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
                putInt(SyncAdapter.ACTION, SyncAdapter.SYNC_LOCAL_CHANGES)
            })
        }

        super.onDestroy()
    }

    companion object {
        const val TAG_DESTINATION_DIALOG = "UPLOAD_ACTIVITY_DESTINATION_DIALOG"
        const val TAG_ACQUIRING_DIALOG = "UPLOAD_ACTIVITY_ACQUIRING_DIALOG"

        const val KEY_SHOW_REMOVE_OPTION = "KEY_SHOW_REMOVE_OPTION"
    }
}