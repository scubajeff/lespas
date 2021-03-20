package site.leos.apps.lespas.helper

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.DialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import site.leos.apps.lespas.R

class ConfirmDialogFragment : DialogFragment() {
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

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_confirm_dialog, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<LinearLayout>(R.id.shape_background).background = DialogShapeDrawable.newInstance(requireContext(), DialogShapeDrawable.NO_STROKE)
        //background.background = DialogShapeDrawable.newInstance(requireContext(), resources.getColor(R.color.color_primary_variant, null))
        view.findViewById<ConstraintLayout>(R.id.background).background = DialogShapeDrawable.newInstance(requireContext(), MaterialColors.getColor(view, R.attr.colorPrimaryVariant))
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

    override fun onStart() {
        super.onStart()

        requireDialog().window?.apply {
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