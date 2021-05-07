package site.leos.apps.lespas.helper

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import site.leos.apps.lespas.R

class ConfirmDialogFragment : LesPasDialogFragment(R.layout.fragment_confirm_dialog) {
    private lateinit var onResultListener: OnResultListener
    private var requestCodeFromActivity = -1

    interface OnResultListener {
        fun onResult(positive: Boolean, requestCode: Int)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when {
            targetFragment is OnResultListener -> onResultListener = targetFragment as OnResultListener
            activity is OnResultListener -> onResultListener = activity as OnResultListener
            else -> parentFragmentManager.popBackStack()
        }

        isCancelable = arguments?.getBoolean(CANCELABLE) ?: true
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<TextView>(R.id.message_textview).text = arguments?.getString(MESSAGE)
        view.findViewById<MaterialButton>(R.id.ok_button).apply {
            text = arguments?.getString(OK_TEXT) ?: getString(android.R.string.ok)
            setOnClickListener {
                onResultListener.onResult(true, if (onResultListener is Fragment) targetRequestCode else requestCodeFromActivity)
                dismiss()
            }
        }
        view.findViewById<MaterialButton>(R.id.cancel_button).apply {
            isCancelable.let {
                if (it) setOnClickListener {
                    onResultListener.onResult(false, if (onResultListener is Fragment) targetRequestCode else requestCodeFromActivity)
                    dismiss()
                }
                else {
                    isEnabled = false
                    visibility = View.GONE
                }
            }
        }
    }

    fun setRequestCode(requestCode: Int) {
        requestCodeFromActivity = requestCode
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