package site.leos.apps.lespas.sync

import android.app.Application
import android.content.Context
import android.content.DialogInterface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.widget.ContentLoadingProgressBar
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import site.leos.apps.lespas.R
import java.io.InputStream
import java.io.OutputStream
import java.lang.Thread.sleep

class AcquiringDialogFragment: DialogFragment() {
    private var total = -1
    private var progressBar: ContentLoadingProgressBar? = null
    private var name: AppCompatTextView? = null
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

        name = view.findViewById(R.id.name)
        progressBar = view.findViewById<ContentLoadingProgressBar>(R.id.progress_horizontal).apply {
            max = total
        }
        acquiringModel.getProgress().observe(viewLifecycleOwner, Observer { progress->
            if (progress == total) dismiss()
            name?.text = acquiringModel.getCurrentName()
            progressBar?.progress = progress
        })
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)

        // If called by UploadActivity, quit immediately, otherwise return normally
        if (tag == UploadActivity.TAG_ACQUIRING_DIALOG) activity?.apply {
            finish()
            overridePendingTransition(0, 0)
        }
    }

    class AcquiringViewModel(application: Application, private val uris: ArrayList<Uri>): AndroidViewModel(application) {
        private var currentProgress = MutableLiveData<Int>()
        private var currentName: String = ""

        init {
            viewModelScope.launch(Dispatchers.IO) {
                var fileName = ""
                var inputStream: InputStream
                var outputStream: OutputStream
                val buf = ByteArray(4096)
                var len: Int

                uris.forEachIndexed { index, uri ->
                    // find out the real name
                    application.contentResolver.query(uri, null, null, null, null)?.apply {
                        val columnIndex = getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        moveToFirst()
                        fileName = getString(columnIndex)
                        close()
                    }
                    withContext(Dispatchers.Main) { setProgress(index, fileName) }
                    Log.e(">>>>>>>>>>", "$uri >>>>>>> $fileName")

                    inputStream = application.contentResolver.openInputStream(uri)!!
                    outputStream = application.openFileOutput(fileName, Context.MODE_PRIVATE)
                    len = inputStream.read(buf)
                    while (len > 0) {
                        outputStream.write(buf, 0, len)
                        len = inputStream.read(buf)
                    }
                    inputStream.close()
                    outputStream.close()
                    Log.e("======", "finished copying $fileName")
                    sleep(500)
                    withContext(Dispatchers.Main) { setProgress(index + 1, fileName) }    // Try to finish at the end by setting progress to more than 100%
                }
            }
        }

        private fun setProgress(progress: Int, name: String) {
            currentProgress.value = progress
            currentName = name
        }
        fun getProgress(): LiveData<Int> = currentProgress
        fun getCurrentName() = currentName
    }

    class AcquiringViewModelFactory(private val application: Application, private val uris: ArrayList<Uri>): ViewModelProvider.NewInstanceFactory() {
        override fun <T : ViewModel?> create(modelClass: Class<T>): T = AcquiringViewModel(application, uris) as T
    }

    companion object {
        const val URIS = "URIS"

        fun newInstance(uris: ArrayList<Uri>) = AcquiringDialogFragment().apply { arguments = Bundle().apply { putParcelableArrayList(URIS, uris) }}
    }
}