package site.leos.apps.lespas.album

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.AlbumNameValidator
import site.leos.apps.lespas.helper.LesPasDialogFragment

class AlbumRenameDialogFragment: LesPasDialogFragment(R.layout.fragment_albumrename_dialog) {
    private lateinit var renameTextInputLayout: TextInputLayout

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        renameTextInputLayout = view.findViewById<TextInputLayout>(R.id.rename_textinputlayout).apply {
            requestFocus()
        }

        view.findViewById<TextInputEditText>(R.id.rename_textinputedittext).run {
            // Use append to move cursor to the end of text
            if (savedInstanceState == null) append(arguments?.getString(OLD_NAME))

            addTextChangedListener(AlbumNameValidator(this, context))

            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_NULL) {
                    val name = this.text.toString().trim()    // Trim the leading and trailing blank

                    if (error != null)
                    else if (name.isEmpty())
                    else {
                        parentFragmentManager.setFragmentResult(RESULT_KEY_NEW_NAME, Bundle().apply { putString(RESULT_KEY_NEW_NAME, name) })
                        dismiss()
                    }
                }
                true
            }
        }
    }

    companion object {
        const val OLD_NAME = "OLD_NAME"
        const val RESULT_KEY_NEW_NAME = "RESULT_KEY_NEW_NAME"

        @JvmStatic
        fun newInstance(albumName: String) = AlbumRenameDialogFragment().apply {arguments = Bundle().apply { putString(OLD_NAME, albumName) }}
    }
}
