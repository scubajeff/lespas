package site.leos.apps.lespas.helper

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.fragment.app.DialogFragment
import com.google.android.material.color.MaterialColors
import kotlinx.android.synthetic.main.fragment_confirm_dialog.*
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

        shape_background.background = DialogShapeDrawable.newInstance(requireContext(), DialogShapeDrawable.NO_STROKE)
        //background.background = DialogShapeDrawable.newInstance(requireContext(), resources.getColor(R.color.color_primary_variant, null))
        background.background = DialogShapeDrawable.newInstance(requireContext(), MaterialColors.getColor(view, R.attr.colorPrimaryVariant))
        message_textview.text = arguments?.getString(MESSAGE)
        ok_button.text = arguments?.getString(OK_TEXT) ?: getString(android.R.string.ok)
        ok_button.setOnClickListener { _->
            onResultListener.onResult(true, targetRequestCode)
            dismiss()
        }
        cancel_button.setOnClickListener { _->
            onResultListener.onResult(false, targetRequestCode)
            dismiss()
        }
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
    }

    companion object {
        const val MESSAGE = "MESSAGE"
        const val OK_TEXT = "OK_TEXT"

        fun newInstance(message: String, okButtonText: String?) = ConfirmDialogFragment().apply {
            arguments = Bundle().apply {
                putString(MESSAGE, message)
                putString(OK_TEXT, okButtonText)
            }
        }
    }
}