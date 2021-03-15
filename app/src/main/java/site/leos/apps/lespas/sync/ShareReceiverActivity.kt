package site.leos.apps.lespas.sync

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity

class ShareReceiverActivity: AppCompatActivity() {
    private val files = ArrayList<Uri>()
    private val destinationModel: DestinationDialogFragment.DestinationViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if ((intent.action == Intent.ACTION_SEND) && (intent.type?.startsWith("image/")!! || intent.type?.startsWith("video/")!!)) {
            files.add(intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as Uri)
        }
        if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            // MIME type checking will be done in AcquiringDialogFragment
            intent.getParcelableArrayListExtra<Parcelable>(Intent.EXTRA_STREAM)?.forEach {
                files.add(it as Uri)
            }
        }

        if (files.isNotEmpty()) {
            destinationModel.getDestination().observe (this, { album->
                album?.apply {
                    // Acquire files
                    if (supportFragmentManager.findFragmentByTag(TAG_ACQUIRING_DIALOG) == null)
                        AcquiringDialogFragment.newInstance(files, album).show(supportFragmentManager, TAG_ACQUIRING_DIALOG)
                }
            })

            if (supportFragmentManager.findFragmentByTag(TAG_DESTINATION_DIALOG) == null)
                DestinationDialogFragment.newInstance().show(supportFragmentManager, TAG_DESTINATION_DIALOG)
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
        super.onPause()
        overridePendingTransition(0, 0)
    }

    companion object {
        const val TAG_DESTINATION_DIALOG = "UPLOAD_ACTIVITY_DESTINATION_DIALOG"
        const val TAG_ACQUIRING_DIALOG = "UPLOAD_ACTIVITY_ACQUIRING_DIALOG"
    }
}