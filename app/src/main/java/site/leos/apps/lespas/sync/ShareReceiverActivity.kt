package site.leos.apps.lespas.sync

import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.provider.OpenableColumns
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import site.leos.apps.lespas.R

class ShareReceiverActivity: AppCompatActivity() {
    private val files = ArrayList<Uri>()
    private val actionModel: ActionViewModel by viewModels()
    private val destinationModel: DestinationDialogFragment.DestinationViewModel by viewModels()
    private lateinit var acquiringModel: AcquiringDialogFragment.AcquiringViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if ((intent.action == Intent.ACTION_SEND) && (intent.type?.startsWith("image/")!!)) {
            files.add(intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as Uri)
        }
        if ((intent.action == Intent.ACTION_SEND_MULTIPLE) && (intent.type?.startsWith("image/")!!)) {
            intent.getParcelableArrayListExtra<Parcelable>(Intent.EXTRA_STREAM)?.forEach {
                files.add(it as Uri)
            }
        }

        if (files.isNotEmpty()) {
            destinationModel.getDestination().observe (this, Observer { album->
                // Acquire files
                acquiringModel = ViewModelProvider(this, AcquiringDialogFragment.AcquiringViewModelFactory(application, files)).get(AcquiringDialogFragment.AcquiringViewModel::class.java)
                acquiringModel.getProgress().observe(this, Observer { progress->
                    if (progress == files.size) {
                        // Files are under control, we can create sync action now
                        val actions = mutableListOf<Action>()

                        // Create new album first
                        if (album.id.isEmpty()) actions.add(Action(null, Action.ACTION_ADD_DIRECTORY_ON_SERVER, album.id, album.name, "", System.currentTimeMillis(), 1))

                        files.forEach {uri->
                            contentResolver.query(uri, null, null, null, null)?.apply {
                                val columnIndex = getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                moveToFirst()
                                actions.add(Action(null, Action.ACTION_ADD_FILES_ON_SERVER, album.id, album.name, getString(columnIndex), System.currentTimeMillis(), 1))
                                close()
                            }
                        }
                        actionModel.addActions(actions)

                        // Request sync immediately, since the viewmodel observing Action table might not be running at this moments
                        ContentResolver.requestSync(AccountManager.get(this).accounts[0], getString(R.string.sync_authority), Bundle().apply {
                            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
                        })

                        // Done
                        finish()
                        overridePendingTransition(0, 0)
                    }
                })

                if (supportFragmentManager.findFragmentByTag(TAG_ACQUIRING_DIALOG) == null)
                    AcquiringDialogFragment.newInstance(files).show(supportFragmentManager, TAG_ACQUIRING_DIALOG)
            })

            if (supportFragmentManager.findFragmentByTag(TAG_DESTINATION_DIALOG) == null)
                DestinationDialogFragment.newInstance().show(supportFragmentManager, TAG_DESTINATION_DIALOG)
        }
        else {
            finish()
            overridePendingTransition(0, 0)
        }
    }

    companion object {
        const val TAG_DESTINATION_DIALOG = "UPLOAD_ACTIVITY_DESTINATION_DIALOG"
        const val TAG_ACQUIRING_DIALOG = "UPLOAD_ACTIVITY_ACQUIRING_DIALOG"
    }
}