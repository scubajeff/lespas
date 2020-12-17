package site.leos.apps.lespas.sync

import android.accounts.AccountManager
import android.app.Application
import android.content.ContentResolver
import android.content.DialogInterface
import android.content.Intent
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
import com.google.android.material.color.MaterialColors
import kotlinx.android.synthetic.main.fragment_acquiring_dialog.*
import kotlinx.android.synthetic.main.fragment_acquiring_dialog.background
import kotlinx.android.synthetic.main.fragment_acquiring_dialog.message_textview
import kotlinx.android.synthetic.main.fragment_acquiring_dialog.shape_background
import kotlinx.android.synthetic.main.fragment_confirm_dialog.*
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
        //background.background = DialogShapeDrawable.newInstance(requireContext(), resources.getColor(R.color.color_primary_variant, null))
        background.background = DialogShapeDrawable.newInstance(requireContext(), MaterialColors.getColor(view, R.attr.colorPrimaryVariant))

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
                message_textview.text = getString(R.string.it_takes_time, Tools.humanReadableByteCountSI(acquiringModel.getTotalBytes()))
                message_textview.visibility = View.VISIBLE
            } else if (progress >= 0) {
                dialog_title_textview.text = getString(R.string.preparing_files, progress + 1, total)
                filename_textview.text = acquiringModel.getCurrentName()
                current_progress.progress = progress
            } else if (progress < 0 ) {
                TransitionManager.beginDelayedTransition(background, TransitionInflater.from(requireContext()).inflateTransition(R.transition.destination_dialog_new_album))
                progress_linearlayout.visibility = View.GONE
                dialog_title_textview.text = getString(R.string.error_preparing_files)
                message_textview.text = getString(when(progress) {
                    AcquiringViewModel.ACCESS_RIGHT_EXCEPTION-> R.string.access_right_violation
                    AcquiringViewModel.NO_MEDIA_FILE_FOUND-> R.string.no_media_file_found
                    else-> 0
                })
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

    class AcquiringViewModel(application: Application, private val uris: ArrayList<Uri>, private val album: Album): AndroidViewModel(application) {
        private var currentProgress = MutableLiveData<Int>()
        private var currentName: String = ""
        private var totalBytes = 0L
        private val newPhotos = mutableListOf<Photo>()
        private val photoActions = mutableListOf<Action>()
        private val photoRepository = PhotoRepository(application)
        private val albumRepository = AlbumRepository(application)
        private val actionRepository = ActionRepository(application)

        init {
            viewModelScope.launch(Dispatchers.IO) {
                var fileName = ""
                var mimeType = ""
                val appRootFolder = "${application.filesDir}${application.getString(R.string.lespas_base_folder_name)}"
                val allPhotoName = photoRepository.getAllPhotoNameMap()
                var date: LocalDateTime
                val fakeAlbumId = System.currentTimeMillis().toString()
                val contentResolver = application.contentResolver

                uris.forEachIndexed { index, uri ->
                    if (album.id.isEmpty()) {
                        // New album, set a fake ID, sync adapter will correct it when real id is available
                        album.id = fakeAlbumId
                    }

                    // find out the real name
                    contentResolver.query(uri, null, null, null, null)?.apply {
                        val columnIndex = getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        moveToFirst()
                        fileName = getString(columnIndex)
                        mimeType = contentResolver.getType(uri)?.let { Intent.normalizeMimeType(it) } ?: ""
                        close()
                    }

                    // If it's not image, skip it
                    if (!(mimeType.startsWith("image/", true) || mimeType.startsWith("video/", true))) return@forEachIndexed

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
                            Tools.getPhotoParams("$appRootFolder/$fileName", mimeType, fileName).copy(id = fileName, albumId = album.id, name = fileName)
                        )
                    }
                    // Pass photo mimeType in Action's fileId property
                    photoActions.add(Action(null, Action.ACTION_ADD_FILES_ON_SERVER, album.id, album.name, mimeType, fileName, System.currentTimeMillis(), 1))

                    date = newPhotos.last().dateTaken
                    if (date < album.startDate) album.startDate = date
                    if (date > album.endDate) album.endDate = date
                }

                if (newPhotos.isEmpty()) withContext(Dispatchers.Main) { setProgress(NO_MEDIA_FILE_FOUND, "") }
                else {
                    if (album.id == fakeAlbumId) {
                        // Get first JPEG or PNG file, only these two format can be set as coverart because they are supported by BitmapRegionDecoder
                        // If we just can't find one single photo of these two formats in this new album, fall back to the first one in the list, cover will be shown as placeholder
                        var validCover = newPhotos.indexOfFirst { it.mimeType == "image/jpeg" || it.mimeType == "image/png" }
                        if (validCover == -1) validCover = 0

                        // New album, update cover information but without leaving cover column empty as the sign of local added new album
                        album.coverBaseline = (newPhotos[validCover].height - (newPhotos[validCover].width * 9 / 21)) / 2
                        album.coverWidth = newPhotos[validCover].width
                        album.coverHeight = newPhotos[validCover].height

                        // Create new album first, store cover, e.g. first photo in new album, in property filename
                        actionRepository.addAction(Action(null, Action.ACTION_ADD_DIRECTORY_ON_SERVER, album.id, album.name, "", newPhotos[validCover].name, System.currentTimeMillis(), 1))
                    }

                    actionRepository.addActions(photoActions)
                    photoRepository.insert(newPhotos)
                    albumRepository.upsert(album)

                    // By setting progress to more than 100%, signaling the calling fragment/activity
                    withContext(Dispatchers.Main) { setProgress(uris.size, fileName) }
                }
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
            const val NO_MEDIA_FILE_FOUND = -200
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