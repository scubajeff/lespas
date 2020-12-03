package site.leos.apps.lespas.sync

import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.util.Log
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.AlbumViewModel

class ShareReceiverActivity: AppCompatActivity() {
    private val files = ArrayList<Uri>()
    private val albumModel: AlbumViewModel by viewModels()
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
            destinationModel.getDestination().observe (this, { album->
                // Acquire files
                acquiringModel = ViewModelProvider(this, AcquiringDialogFragment.AcquiringViewModelFactory(application, files)).get(AcquiringDialogFragment.AcquiringViewModel::class.java)
                acquiringModel.getProgress().observe(this, { progress->
                    if (progress == files.size) {
                        // Files are under control, we can create sync action now
                        val actions = mutableListOf<Action>()
                        val newPhotos = acquiringModel.getNewPhotos()

                        // Create new album first
                        if (album.id.isEmpty()) {
                            // Set a fake ID, sync adapter will correct it when real id is available
                            album.id = System.currentTimeMillis().toString()

                            // Store cover, e.g. first photo in new album, in member filename
                            album.coverBaseline = (newPhotos[0].height - (newPhotos[0].width * 9 / 21)) / 2
                            album.coverWidth = newPhotos[0].width
                            album.coverHeight = newPhotos[0].height
                            actions.add(Action(null, Action.ACTION_ADD_DIRECTORY_ON_SERVER, album.id, album.name, "", newPhotos[0].name, System.currentTimeMillis(), 1))

                        }

                        newPhotos.forEach {
                            it.albumId = album.id
                            if (it.dateTaken < album.startDate) album.startDate = it.dateTaken
                            if (it.dateTaken > album.endDate) album.endDate = it.dateTaken
                            actions.add(Action(null, Action.ACTION_ADD_FILES_ON_SERVER, album.id, album.name, "", it.name, System.currentTimeMillis(), 1))
                        }

                        actionModel.addActions(actions)
                        GlobalScope.launch(Dispatchers.Default) {
                            albumModel.addPhotos(newPhotos)
                            albumModel.upsertAsync(album)
                        }

                        // Request sync immediately, since the viewmodel observing Action table might not be running at this moments
                        ContentResolver.requestSync(AccountManager.get(this).accounts[0], getString(R.string.sync_authority), Bundle().apply {
                            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
                        })
                    }
                })

                if (supportFragmentManager.findFragmentByTag(TAG_ACQUIRING_DIALOG) == null)
                    AcquiringDialogFragment.newInstance(files).show(supportFragmentManager, TAG_ACQUIRING_DIALOG)
            })

            if (supportFragmentManager.findFragmentByTag(TAG_DESTINATION_DIALOG) == null)
                DestinationDialogFragment.newInstance().show(supportFragmentManager, TAG_DESTINATION_DIALOG)
        }
        else {
            Log.e("+++++++++", "no files")
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