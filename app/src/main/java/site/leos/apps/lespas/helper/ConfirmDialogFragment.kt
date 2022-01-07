package site.leos.apps.lespas.helper

import android.os.Bundle
import android.view.View
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import site.leos.apps.lespas.MainActivity
import site.leos.apps.lespas.R

class ConfirmDialogFragment : LesPasDialogFragment(R.layout.fragment_confirm_dialog) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isCancelable = requireArguments().getBoolean(CANCELABLE)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<TextView>(R.id.message_textview).text = requireArguments().getString(MESSAGE)
        view.findViewById<MaterialButton>(R.id.ok_button).apply {
            text = requireArguments().getString(OK_TEXT) ?: getString(android.R.string.ok)
            setOnClickListener {
                val requestKey = requireArguments().getString(INDIVIDUAL_REQUEST_KEY) ?: ""
                parentFragmentManager.setFragmentResult(if (requestKey == MainActivity.CONFIRM_REQUIRE_SD_DIALOG || requestKey == MainActivity.CONFIRM_RESTART_DIALOG) MainActivity.ACTIVITY_DIALOG_REQUEST_KEY else CONFIRM_DIALOG_REQUEST_KEY, Bundle().apply {
                    putBoolean(CONFIRM_DIALOG_REQUEST_KEY, true)
                    putString(INDIVIDUAL_REQUEST_KEY, requestKey)
                })
                dismiss()
            }
        }
        view.findViewById<MaterialButton>(R.id.cancel_button).apply {
            isCancelable.let {
                if (it) setOnClickListener {
                    val requestKey = requireArguments().getString(INDIVIDUAL_REQUEST_KEY) ?: ""
                    parentFragmentManager.setFragmentResult(if (requestKey == MainActivity.CONFIRM_REQUIRE_SD_DIALOG || requestKey == MainActivity.CONFIRM_RESTART_DIALOG) MainActivity.ACTIVITY_DIALOG_REQUEST_KEY else CONFIRM_DIALOG_REQUEST_KEY, Bundle().apply {
                        putBoolean(CONFIRM_DIALOG_REQUEST_KEY, false)
                        putString(INDIVIDUAL_REQUEST_KEY, requestKey)
                    })
                    dismiss()
                }
                else {
                    isEnabled = false
                    visibility = View.GONE
                }
            }
        }
    }

    companion object {
        const val CONFIRM_DIALOG_REQUEST_KEY = "CONFIRM_DIALOG_REQUEST_KEY"

        private const val MESSAGE = "MESSAGE"
        private const val OK_TEXT = "OK_TEXT"
        private const val CANCELABLE = "CANCELABLE"
        const val INDIVIDUAL_REQUEST_KEY = "INDIVIDUAL_REQUEST_KEY"

        @JvmStatic
        @JvmOverloads
        fun newInstance(message: String, okButtonText: String?, cancelable: Boolean = true, requestKey: String = "") = ConfirmDialogFragment().apply {
            arguments = Bundle().apply {
                putString(MESSAGE, message)
                putString(OK_TEXT, okButtonText)
                putBoolean(CANCELABLE, cancelable)
                putString(INDIVIDUAL_REQUEST_KEY, requestKey)
            }
        }
    }
}