package site.leos.apps.lespas.helper

import android.os.Bundle
import android.view.View
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import site.leos.apps.lespas.R

class ConfirmDialogFragment : LesPasDialogFragment(R.layout.fragment_confirm_dialog) {
    private lateinit var onResultListener: OnResultListener

    interface OnResultListener {
        fun onResult(positive: Boolean, requestCode: Int)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (activity is OnResultListener) onResultListener = activity as OnResultListener
        else if (targetFragment is OnResultListener) onResultListener = targetFragment as OnResultListener
        else parentFragmentManager.popBackStack()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<TextView>(R.id.message_textview).text = arguments?.getString(MESSAGE)
        view.findViewById<MaterialButton>(R.id.ok_button).apply {
            text = arguments?.getString(OK_TEXT) ?: getString(android.R.string.ok)
            setOnClickListener { _->
                onResultListener.onResult(true, targetRequestCode)
                dismiss()
            }
        }
        view.findViewById<MaterialButton>(R.id.cancel_button).apply {
            arguments?.getBoolean(CANCELABLE)?.let {
                if (it) setOnClickListener { _->
                    onResultListener.onResult(false, targetRequestCode)
                    dismiss()
                }
                else {
                    requireDialog().setCanceledOnTouchOutside(false)
                    isEnabled = false
                    visibility = View.GONE
                }
            }
        }
    }

    companion object {
        const val MESSAGE = "MESSAGE"
        const val OK_TEXT = "OK_TEXT"
        const val CANCELABLE = "CANCELABLE"

        @JvmStatic
        fun newInstance(message: String, okButtonText: String?) = newInstance(message, okButtonText, true)

        @JvmStatic
        fun newInstance(message: String, okButtonText: String?, cancelable: Boolean) = ConfirmDialogFragment().apply {
            arguments = Bundle().apply {
                putString(MESSAGE, message)
                putString(OK_TEXT, okButtonText)
                putBoolean(CANCELABLE, cancelable)
            }
        }
    }
}