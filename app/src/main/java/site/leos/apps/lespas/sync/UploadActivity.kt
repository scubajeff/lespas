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
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.observe
import site.leos.apps.lespas.R

class UploadActivity: AppCompatActivity() {
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
            destinationModel.getDestination().observe(this, { album->
                //destinationDialogFragment.dismiss()

                if (album.id.isEmpty()) {
                    finish()
                } else {
                    // Choose existing album
                    acquiringModel = ViewModelProvider(this, AcquiringDialogFragment.AcquiringViewModelFactory(application, files)).get(AcquiringDialogFragment.AcquiringViewModel::class.java)
                    acquiringModel.getProgress().observe(this, {progress->
                        if (progress == files.size) {
                            // Files are under control, we can create sync action now
                            val actions = mutableListOf<Action>()
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

                            finish()
                        }
                    })
                    if (savedInstanceState == null) { AcquiringDialogFragment.newInstance(files).show(supportFragmentManager, TAG) }
                }
            })

            if (savedInstanceState == null) { DestinationDialogFragment.newInstance().show(supportFragmentManager, TAG) }
        }
        else finish()
    }

    companion object {
        const val TAG = "UPLOAD_ACTIVITY"
    }
}