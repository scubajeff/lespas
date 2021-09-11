package site.leos.apps.lespas.helper

import android.os.Bundle
import android.view.View
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import site.leos.apps.lespas.R

class YesNoDialogFragment : LesPasDialogFragment(R.layout.fragment_confirm_dialog) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<TextView>(R.id.message_textview).text = arguments?.getString(MESSAGE)
        view.findViewById<MaterialButton>(R.id.ok_button).apply {
            text = getString(R.string.strip_exif_yes)
            setOnClickListener { returnWithResult(true) }
        }
        view.findViewById<MaterialButton>(R.id.cancel_button).apply {
            text = getString(R.string.strip_exif_no)
            setOnClickListener { returnWithResult(false) }
        }
    }

    private fun returnWithResult(result: Boolean) {
        parentFragmentManager.setFragmentResult(ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY, Bundle().apply {
            putBoolean(ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY, result)
            putString(ConfirmDialogFragment.INDIVIDUAL_REQUEST_KEY, requireArguments().getString(INDIVIDUAL_REQUEST_KEY) ?: "")
        })
        dismiss()
    }

    companion object {

        private const val MESSAGE = "MESSAGE"
        const val INDIVIDUAL_REQUEST_KEY = "INDIVIDUAL_REQUEST_KEY"

        @JvmStatic
        @JvmOverloads
        fun newInstance(message: String, requestKey: String = "") = YesNoDialogFragment().apply {
            arguments = Bundle().apply {
                putString(MESSAGE, message)
                putString(INDIVIDUAL_REQUEST_KEY, requestKey)
            }
        }
    }
}