package site.leos.apps.lespas.sync

import android.annotation.SuppressLint
import android.app.Application
import android.content.DialogInterface
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.exifinterface.media.ExifInterface
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.*
import androidx.lifecycle.Observer
import androidx.transition.TransitionInflater
import androidx.transition.TransitionManager
import kotlinx.android.synthetic.main.fragment_acquiring_dialog.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.DialogShapeDrawable
import site.leos.apps.lespas.photo.Photo
import java.io.File
import java.text.CharacterIterator
import java.text.SimpleDateFormat
import java.text.StringCharacterIterator
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*


class AcquiringDialogFragment: DialogFragment() {
    private var total = -1
    private val acquiringModel: AcquiringViewModel by activityViewModels { AcquiringViewModelFactory(requireActivity().application, arguments?.getParcelableArrayList(URIS)!!) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        total = arguments?.getParcelableArrayList<Uri>(URIS)!!.size
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_acquiring_dialog, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        background.background = DialogShapeDrawable.newInstance(requireContext(), resources.getColor(R.color.color_primary_variant, null))

        acquiringModel.getProgress().observe(viewLifecycleOwner, Observer { progress ->
            if (progress >= total) {
                TransitionManager.beginDelayedTransition(background, TransitionInflater.from(requireContext()).inflateTransition(R.transition.destination_dialog_new_album))
                progress_linearlayout.visibility = View.GONE
                dialog_title_textview.text = getString(R.string.finished_preparing_files)
                message_textview.text = getString(R.string.it_takes_time, humanReadableByteCountSI(acquiringModel.getTotalBytes()))
                message_textview.visibility = View.VISIBLE
            } else {
                dialog_title_textview.text = getString(R.string.preparing_files, progress + 1, total)
                filename_textview.text = acquiringModel.getCurrentName()
                current_progress.progress = progress
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

            setBackgroundDrawable(DialogShapeDrawable.newInstance(context, DialogShapeDrawable.NO_STROKE))
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

    class AcquiringViewModel(application: Application, private val uris: ArrayList<Uri>): AndroidViewModel(application) {
        private var currentProgress = MutableLiveData<Int>()
        private var currentName: String = ""
        private var totalBytes = 0L
        private val newPhotos = mutableListOf<Photo>()

        init {
            viewModelScope.launch(Dispatchers.IO) {
                var fileName = ""
                val appRootFolder = "${application.filesDir}${application.getString(R.string.lespas_base_folder_name)}"
                var exif: ExifInterface
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                var timeString: String?
                val dateFormatter = SimpleDateFormat("yyyy:MM:dd HH:mm:ss").apply { timeZone = TimeZone.getDefault() }
                var exifRotation: Int
                var lastModified: Date


                uris.forEachIndexed { index, uri ->
                    // find out the real name
                    application.contentResolver.query(uri, null, null, null, null)?.apply {
                        val columnIndex = getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        moveToFirst()
                        fileName = getString(columnIndex)
                        close()
                    }

                    // Update dialog view
                    withContext(Dispatchers.Main) { setProgress(index, fileName) }

                    // Copy the file to our private storage
                    application.contentResolver.openInputStream(uri).use { input ->
                        File(appRootFolder, fileName).outputStream().use { output ->
                            totalBytes += input!!.copyTo(output, 8192)
                        }
                    }

                    // Update dateTaken, width, height fields
                    lastModified = Date(File(appRootFolder, fileName).lastModified())
                    exif = ExifInterface("$appRootFolder/$fileName")
                    timeString = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    if (timeString == null) timeString = exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED)
                    if (timeString == null) timeString = exif.getAttribute(ExifInterface.TAG_DATETIME)
                    if (timeString == null) timeString = dateFormatter.format(lastModified)

                    exifRotation = exif.rotationDegrees
                    if (exifRotation != 0) {
                        Bitmap.createBitmap(
                            BitmapFactory.decodeFile("$appRootFolder/$fileName"),
                            0, 0,
                            exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0),
                            exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0),
                            Matrix().apply{ preRotate(exifRotation.toFloat()) },
                            true).apply {
                            compress(Bitmap.CompressFormat.JPEG, 100, File(appRootFolder, fileName).outputStream())
                            recycle()
                        }

                        exif.resetOrientation()
                        val w = exif.getAttribute(ExifInterface.TAG_IMAGE_WIDTH)
                        exif.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, exif.getAttribute(ExifInterface.TAG_IMAGE_LENGTH))
                        exif.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, w)
                        exif.saveAttributes()
                    }

                    // Get width and height
                    BitmapFactory.decodeFile("$appRootFolder/$fileName", options)

                    newPhotos.add(Photo(fileName, "", fileName, "",
                        LocalDateTime.parse(timeString, DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")),
                        lastModified.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime(),
                        options.outWidth, options.outHeight, 0))


                    // Try to finish at the end by setting progress to more than 100%
                    withContext(Dispatchers.Main) { setProgress(index + 1, fileName) }
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
        fun getNewPhotos(): List<Photo> = newPhotos
    }

    class AcquiringViewModelFactory(private val application: Application, private val uris: ArrayList<Uri>): ViewModelProvider.NewInstanceFactory() {
        override fun <T : ViewModel?> create(modelClass: Class<T>): T = AcquiringViewModel(application, uris) as T
    }

    companion object {
        const val URIS = "URIS"

        fun newInstance(uris: ArrayList<Uri>) = AcquiringDialogFragment().apply { arguments = Bundle().apply { putParcelableArrayList(URIS, uris) }}
    }
}