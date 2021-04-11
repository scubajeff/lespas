package site.leos.apps.lespas.sync

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.MimeTypeMap
import android.widget.TextView
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.core.widget.ContentLoadingProgressBar
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.*
import androidx.lifecycle.Observer
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import androidx.transition.TransitionInflater
import androidx.transition.TransitionManager
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import site.leos.apps.lespas.BuildConfig
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
    private val destinationViewModel: DestinationDialogFragment.DestinationViewModel by activityViewModels()
    private val acquiringModel: AcquiringViewModel by viewModels { AcquiringViewModelFactory(requireActivity().application, arguments?.getParcelableArrayList(KEY_URIS)!!, arguments?.getParcelable(KEY_ALBUM)!!) }

    private lateinit var progressLinearLayout: LinearLayoutCompat
    private lateinit var dialogTitleTextView: TextView
    private lateinit var messageTextView: TextView
    private lateinit var fileNameTextView: TextView
    private lateinit var background: LinearLayoutCompat
    private lateinit var contentLoadingProgressBar: ContentLoadingProgressBar

    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        total = arguments?.getParcelableArrayList<Uri>(KEY_URIS)!!.size
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.fragment_acquiring_dialog, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<LinearLayoutCompat>(R.id.shape_background).background = DialogShapeDrawable.newInstance(requireContext(), DialogShapeDrawable.NO_STROKE)
        //background.background = DialogShapeDrawable.newInstance(requireContext(), resources.getColor(R.color.color_primary_variant, null))

        background = view.findViewById<LinearLayoutCompat>(R.id.background).apply {
            background = DialogShapeDrawable.newInstance(requireContext(), MaterialColors.getColor(view, R.attr.colorPrimaryVariant))
        }
        progressLinearLayout = view.findViewById(R.id.progress_linearlayout)
        dialogTitleTextView = view.findViewById(R.id.dialog_title_textview)
        messageTextView = view.findViewById(R.id.message_textview)
        fileNameTextView = view.findViewById(R.id.filename_textview)
        contentLoadingProgressBar = view.findViewById(R.id.current_progress)

        acquiringModel.getProgress().observe(viewLifecycleOwner, Observer { progress ->
            if (progress >= total) {
                finished = true

                TransitionManager.beginDelayedTransition(background, TransitionInflater.from(requireContext()).inflateTransition(R.transition.destination_dialog_new_album))
                progressLinearLayout.visibility = View.GONE
                dialogTitleTextView.text = getString(R.string.finished_preparing_files)
                var note = getString(R.string.it_takes_time, Tools.humanReadableByteCountSI(acquiringModel.getTotalBytes()))
                if (PreferenceManager.getDefaultSharedPreferences(context).getBoolean(context?.getString(R.string.wifionly_pref_key), true)) {
                    if ((context?.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered) {
                        note += context?.getString(R.string.mind_network_setting)
                    }
                }
                messageTextView.text = note
                messageTextView.visibility = View.VISIBLE
                dialog?.setCanceledOnTouchOutside(true)
            } else if (progress >= 0) {
                dialogTitleTextView.text = getString(R.string.preparing_files_progress, progress + 1, total)
                fileNameTextView.text = acquiringModel.getCurrentName()
                contentLoadingProgressBar.progress = progress
            } else if (progress < 0 ) {
                TransitionManager.beginDelayedTransition(background, TransitionInflater.from(requireContext()).inflateTransition(R.transition.destination_dialog_new_album))
                progressLinearLayout.visibility = View.GONE
                dialogTitleTextView.text = getString(R.string.error_preparing_files)
                messageTextView.text = getString(when(progress) {
                    AcquiringViewModel.ACCESS_RIGHT_EXCEPTION-> R.string.access_right_violation
                    AcquiringViewModel.NO_MEDIA_FILE_FOUND-> R.string.no_media_file_found
                    AcquiringViewModel.SAME_FILE_EXISTED-> R.string.same_file_found
                    else-> 0
                })
                messageTextView.visibility = View.VISIBLE
                dialog?.setCanceledOnTouchOutside(true)
            }
        })

        contentLoadingProgressBar.max = total
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

        dialog?.setCanceledOnTouchOutside(false)
    }


    override fun onDestroy() {
        LocalBroadcastManager.getInstance(requireContext()).sendBroadcast(Intent().apply {
            action = BROADCAST_REMOVE_ORIGINAL
            putExtra(BROADCAST_REMOVE_ORIGINAL_EXTRA, if (finished) arguments?.getBoolean(KEY_REMOVE_ORIGINAL) == true else false)
        })

        // Dirty hack to stop DestinationViewModel from emitting livedata again
        destinationViewModel.resetDestination()

        super.onDestroy()

        // If called by ShareReceiverActivity, quit immediately, otherwise return normally
        if (tag == ShareReceiverActivity.TAG_ACQUIRING_DIALOG) activity?.finish()
    }

    class AcquiringViewModelFactory(private val application: Application, private val uris: ArrayList<Uri>, private val album: Album): ViewModelProvider.NewInstanceFactory() {
        override fun <T : ViewModel?> create(modelClass: Class<T>): T = AcquiringViewModel(application, uris, album) as T
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
                var fileId = ""
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
                        fileId = ""
                        val columnIndex = getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        moveToFirst()
                        if (columnIndex != -1) {
                            try {
                                fileId = getString(columnIndex)
                            } catch (e: NullPointerException) {
                                if ("twidere.share".equals(uri.authority, true)) fileId = uri.path!!.substringAfterLast('/')
                            }
                        }
                        else {
                            if ("twidere.share".equals(uri.authority, true)) fileId = uri.path!!.substringAfterLast('/')
                        }
                        close()
                    } ?: run {
                        if ("file".equals(uri.scheme, true)) fileId = uri.path!!.substringAfterLast("/")
                    }
                    if (fileId.isEmpty()) return@forEachIndexed

                    // If no photo with same name exists in album, create new photo
                    if (!(allPhotoName.contains(AlbumPhotoName(album.id, fileId)))) {
                        //val fileName = "${fileId.substringBeforeLast('.')}_${System.currentTimeMillis()}.${fileId.substringAfterLast('.')}"

                        // TODO: Default type set to jpeg
                        val mimeType = contentResolver.getType(uri)?.let { Intent.normalizeMimeType(it) } ?: run {
                            MimeTypeMap.getSingleton().getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(uri.toString()).toLowerCase(Locale.getDefault())) ?: "image/jpeg"
                        }

                        // If it's not image, skip it
                        if (!(mimeType.startsWith("image/", true) || mimeType.startsWith("video/", true))) return@forEachIndexed

                        // Update progress in UI
                        withContext(Dispatchers.Main) { setProgress(index, fileId) }

                        // Copy the file to our private storage
                        try {
                            application.contentResolver.openInputStream(uri)?.use { input ->
                                File(appRootFolder, fileId).outputStream().use { output ->
                                    totalBytes += input.copyTo(output, 8192)
                                }
                            }
                        } catch (e:FileNotFoundException) {
                            // without access right to uri, will throw FileNotFoundException
                            withContext(Dispatchers.Main) { setProgress(ACCESS_RIGHT_EXCEPTION, "") }
                            return@launch   // TODO shall we loop to next file?
                        } catch (e:Exception) {
                            e.printStackTrace()
                            return@launch
                        }

                        newPhotos.add(Tools.getPhotoParams("$appRootFolder/$fileId", mimeType, fileId, true).copy(id = fileId, albumId = album.id, name = fileId))

                        // Update album start and end dates accordingly
                        date = newPhotos.last().dateTaken
                        if (date < album.startDate) album.startDate = date
                        if (date > album.endDate) album.endDate = date

                        // Pass photo mimeType in Action's folderId property
                        photoActions.add(Action(null, Action.ACTION_ADD_FILES_ON_SERVER, mimeType, album.name, fileId, fileId, System.currentTimeMillis(), 1))
                    } else {
                        // TODO show special error message when there are just some duplicate in uris
                        //photoRepository.changeName(album.id, fileId, fileName)
                        if (uris.size == 1) withContext(Dispatchers.Main) { setProgress(SAME_FILE_EXISTED, "") }
                        return@launch
                    }
                }

                if (newPhotos.isEmpty()) withContext(Dispatchers.Main) { setProgress(NO_MEDIA_FILE_FOUND, "") }
                else {
                    if (album.id == fakeAlbumId) {
                        // Get first JPEG or PNG file, only these two format can be set as coverart because they are supported by BitmapRegionDecoder
                        // If we just can't find one single photo of these two formats in this new album, fall back to the first one in the list, cover will be shown as placeholder
                        var validCover = newPhotos.indexOfFirst { it.mimeType == "image/jpeg" || it.mimeType == "image/png" }
                        if (validCover == -1) validCover = 0

                        // New album, update cover information but leaving cover column empty as the sign of local added new album
                        album.coverBaseline = (newPhotos[validCover].height - (newPhotos[validCover].width * 9 / 21)) / 2
                        album.coverWidth = newPhotos[validCover].width
                        album.coverHeight = newPhotos[validCover].height
                        album.cover = newPhotos[validCover].id

                        // Create new album first, store cover, e.g. first photo in new album, in property filename
                        actionRepository.addAction(Action(null, Action.ACTION_ADD_DIRECTORY_ON_SERVER, album.id, album.name, "", newPhotos[validCover].id, System.currentTimeMillis(), 1))
                    }

                    actionRepository.addActions(photoActions)
                    photoRepository.insert(newPhotos)
                    albumRepository.upsert(album)

                    // By setting progress to more than 100%, signaling the calling fragment/activity
                    withContext(Dispatchers.Main) { setProgress(uris.size, fileId) }
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
            const val SAME_FILE_EXISTED = -300
        }
    }

    companion object {
        const val KEY_URIS = "KEY_URIS"
        const val KEY_ALBUM = "KEY_ALBUM"
        const val KEY_REMOVE_ORIGINAL = "KEY_REMOVE_ORIGINAL"

        const val BROADCAST_REMOVE_ORIGINAL = "${BuildConfig.APPLICATION_ID}.BROADCAST_REMOVE_ORIGINAL"
        const val BROADCAST_REMOVE_ORIGINAL_EXTRA = "${BuildConfig.APPLICATION_ID}.BROADCAST_REMOVE_ORIGINAL_EXTRA"

        @JvmStatic
        fun newInstance(uris: ArrayList<Uri>, album: Album, removeOriginal: Boolean) = AcquiringDialogFragment().apply {
            arguments = Bundle().apply {
                putParcelableArrayList(KEY_URIS, uris)
                putParcelable(KEY_ALBUM, album)
                putBoolean(KEY_REMOVE_ORIGINAL, removeOriginal)
            }
        }
    }
}