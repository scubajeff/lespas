package site.leos.apps.lespas.album

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.AlbumNameValidator
import site.leos.apps.lespas.helper.LesPasDialogFragment

class AlbumRenameDialogFragment: LesPasDialogFragment(R.layout.fragment_albumrename_dialog) {
    private lateinit var renameTextInputLayout: TextInputLayout
    private lateinit var onFinishListener: OnFinishListener

    interface OnFinishListener {
        fun onRenameFinished(newName: String)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onFinishListener = targetFragment as OnFinishListener
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        renameTextInputLayout = view.findViewById<TextInputLayout>(R.id.rename_textinputlayout).apply {
            requestFocus()
        }

        view.findViewById<TextInputEditText>(R.id.rename_textinputedittext).run {
            // Use append to move cursor to the end of text
            if (savedInstanceState == null) append(arguments?.getString(OLD_NAME))

            addTextChangedListener(AlbumNameValidator(this, context))

            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_NULL && event.action == KeyEvent.ACTION_UP) {
                    val name = this.text.toString().trim()    // Trim the leading and trailing blank

                    if (error != null)
                    else if (name.isEmpty())
                    else {
                        onFinishListener.onRenameFinished(name)
                        dismiss()
                    }
                }
                true
            }
        }
    }

    companion object {
        const val OLD_NAME = "OLD_NAME"

        @JvmStatic
        fun newInstance(albumName: String) = AlbumRenameDialogFragment().apply {arguments = Bundle().apply { putString(OLD_NAME, albumName) }}
    }
}
