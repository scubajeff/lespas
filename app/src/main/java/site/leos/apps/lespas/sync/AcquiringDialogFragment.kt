package site.leos.apps.lespas.sync

import android.accounts.AccountManager
import android.annotation.SuppressLint
import android.app.Application
import android.content.ContentResolver
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.*
import androidx.lifecycle.Observer
import androidx.transition.TransitionInflater
import androidx.transition.TransitionManager
import kotlinx.android.synthetic.main.fragment_acquiring_dialog.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.Album
import site.leos.apps.lespas.album.AlbumRepository
import site.leos.apps.lespas.helper.DialogShapeDrawable
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.photo.AlbumPhotoName
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.photo.PhotoRepository
import java.io.File
import java.io.FileNotFoundException
import java.text.CharacterIterator
import java.text.StringCharacterIterator
import java.time.LocalDateTime
import java.util.*


class AcquiringDialogFragment: DialogFragment() {
    private var total = -1
    private val acquiringModel: AcquiringViewModel by viewModels {
        AcquiringViewModelFactory(requireActivity().application, arguments?.getParcelableArrayList(URIS)!!, arguments?.getParcelable(ALBUM)!!)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        total = arguments?.getParcelableArrayList<Uri>(URIS)!!.size
    }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.fragment_acquiring_dialog, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        shape_background.background = DialogShapeDrawable.newInstance(requireContext(), DialogShapeDrawable.NO_STROKE)
        background.background = DialogShapeDrawable.newInstance(requireContext(), resources.getColor(R.color.color_primary_variant, null))

        acquiringModel.getProgress().observe(viewLifecycleOwner, Observer { progress ->
            if (progress >= total) {
                if (tag == ShareReceiverActivity.TAG_ACQUIRING_DIALOG) {
                    // Request sync immediately if called from ShareReceiverActivity, since the viewmodel observing Action table might not be running at this moments
                    ContentResolver.requestSync(AccountManager.get(requireContext()).accounts[0], getString(R.string.sync_authority), Bundle().apply {
                        putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                        //putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
                    })
                }

                TransitionManager.beginDelayedTransition(background, TransitionInflater.from(requireContext()).inflateTransition(R.transition.destination_dialog_new_album))
                progress_linearlayout.visibility = View.GONE
                dialog_title_textview.text = getString(R.string.finished_preparing_files)
                message_textview.text = getString(R.string.it_takes_time, humanReadableByteCountSI(acquiringModel.getTotalBytes()))
                message_textview.visibility = View.VISIBLE
            } else if (progress >= 0) {
                dialog_title_textview.text = getString(R.string.preparing_files, progress + 1, total)
                filename_textview.text = acquiringModel.getCurrentName()
                current_progress.progress = progress
            } else if (progress == AcquiringViewModel.ACCESS_RIGHT_EXCEPTION) {
                TransitionManager.beginDelayedTransition(background, TransitionInflater.from(requireContext()).inflateTransition(R.transition.destination_dialog_new_album))
                progress_linearlayout.visibility = View.GONE
                dialog_title_textview.text = getString(R.string.error_preparing_files)
                message_textview.text = getString(R.string.access_right_violation)
                message_textview.visibility = View.VISIBLE
            }
        })

        current_progress.max = total
    }

    override fun onStart() {
        super.onStart()

        dialog!!.window!!.apply {
            // Set dialog width to a fixed ration of screen width
            val width = (resources.displayMetrics.widthPixels * resources.getInteger(R.integer.dialog_width_ratio) / 100)
            setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
            attributes.apply {
                dimAmount = 0.6f
                flags or WindowManager.LayoutParams.FLAG_DIM_BEHIND
            }

            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setWindowAnimations(R.style.Theme_LesPas_Dialog_Animation)
        }
    }


    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)

        // If called by ShareReceiverActivity, quit immediately, otherwise return normally
        if (tag == ShareReceiverActivity.TAG_ACQUIRING_DIALOG) activity?.finish()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        // If called by ShareReceiverActivity, quit immediately, otherwise return normally
        if (tag == ShareReceiverActivity.TAG_ACQUIRING_DIALOG) activity?.finish()
    }

    @SuppressLint("DefaultLocale")
    private fun humanReadableByteCountSI(size: Long): String {
        var bytes = size
        if (-1000 < bytes && bytes < 1000) return "$bytes B"
        val ci: CharacterIterator = StringCharacterIterator("kMGTPE")
        while (bytes <= -999950 || bytes >= 999950) {
            bytes /= 1000
            ci.next()
        }
        return java.lang.String.format("%d%cB", bytes/1000, ci.current())
    }

    class AcquiringViewModel(application: Application, private val uris: ArrayList<Uri>, private val album: Album): AndroidViewModel(application) {
        private var currentProgress = MutableLiveData<Int>()
        private var currentName: String = ""
        private var totalBytes = 0L
        private val newPhotos = mutableListOf<Photo>()
        private val actions = mutableListOf<Action>()
        private val photoRepository = PhotoRepository(application)
        private val albumRepository = AlbumRepository(application)
        private val actionRepository = ActionRepository(application)

        init {
            viewModelScope.launch(Dispatchers.IO) {
                var fileName = ""
                val appRootFolder = "${application.filesDir}${application.getString(R.string.lespas_base_folder_name)}"
                val allPhotoName = photoRepository.getAllPhotoNameMap()
                var date: LocalDateTime
                val fakeAlbumId = System.currentTimeMillis().toString()

                uris.forEachIndexed { index, uri ->
                    if (album.id.isEmpty()) {
                        // New album, set a fake ID, sync adapter will correct it when real id is available
                        album.id = fakeAlbumId
                    }

                    // find out the real name
                    application.contentResolver.query(uri, null, null, null, null)?.apply {
                        val columnIndex = getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        moveToFirst()
                        fileName = getString(columnIndex)
                        close()
                    }

                    // Update progress in UI
                    withContext(Dispatchers.Main) { setProgress(index, fileName) }

                    // Copy the file to our private storage
                    try {
                        application.contentResolver.openInputStream(uri).use { input ->
                            File(appRootFolder, fileName).outputStream().use { output ->
                                totalBytes += input!!.copyTo(output, 8192)
                            }
                        }
                    } catch (e:FileNotFoundException) {
                        withContext(Dispatchers.Main) { setProgress(ACCESS_RIGHT_EXCEPTION, "") }
                        return@launch
                    } catch (e:Exception) {
                        e.printStackTrace()
                        return@launch
                    }

                    // If no photo with same name exists in album, create new photo
                    if (!(allPhotoName.contains(AlbumPhotoName(album.id, fileName)))) {
                        newPhotos.add(
                            Tools.getPhotoParams("$appRootFolder/$fileName").copy(id = fileName, albumId = album.id, name = fileName)
                        )
                    }
                    actions.add(Action(null, Action.ACTION_ADD_FILES_ON_SERVER, album.id, album.name, "", fileName, System.currentTimeMillis(), 1))

                    date = newPhotos.last().dateTaken
                    if (date < album.startDate) album.startDate = date
                    if (date > album.endDate) album.endDate = date
                }

                if (album.id == fakeAlbumId) {
                    // New album
                    album.coverBaseline = (newPhotos[0].height - (newPhotos[0].width * 9 / 21)) / 2
                    album.coverWidth = newPhotos[0].width
                    album.coverHeight = newPhotos[0].height

                    // Create new album first, store cover, e.g. first photo in new album, in property filename
                    actions.add(0, Action(null, Action.ACTION_ADD_DIRECTORY_ON_SERVER, album.id, album.name, "", newPhotos[0].name, System.currentTimeMillis(), 1))
                }

                actionRepository.addActions(actions)
                photoRepository.insert(newPhotos)
                albumRepository.upsert(album)

                // By setting progress to more than 100%, signaling the calling fragment/activity
                withContext(Dispatchers.Main) { setProgress(uris.size, fileName) }
            }
        }

        private fun setProgress(progress: Int, name: String) {
            currentProgress.value = progress
            currentName = name
        }

        fun getProgress(): LiveData<Int> = currentProgress
        fun getCurrentName() = currentName
        fun getTotalBytes(): Long = totalBytes

        companion object {
            const val ACCESS_RIGHT_EXCEPTION = -100
        }
    }

    class AcquiringViewModelFactory(private val application: Application, private val uris: ArrayList<Uri>, private val album: Album): ViewModelProvider.NewInstanceFactory() {
        override fun <T : ViewModel?> create(modelClass: Class<T>): T = AcquiringViewModel(application, uris, album) as T
    }

    companion object {
        const val URIS = "URIS"
        const val ALBUM = "ALBUM"

        fun newInstance(uris: ArrayList<Uri>, album: Album) = AcquiringDialogFragment().apply {
            arguments = Bundle().apply {
                putParcelableArrayList(URIS, uris)
                putParcelable(ALBUM, album)
            }
        }
    }
}