package site.leos.apps.lespas.album

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
import site.leos.apps.lespas.helper.DialogShapeDrawable

class ConfirmDialogFragment : DialogFragment() {
    private lateinit var onPositiveConfirmedListener: OnPositiveConfirmedListener

    interface OnPositiveConfirmedListener {
        fun onPositiveConfirmed()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (targetFragment is OnPositiveConfirmedListener) onPositiveConfirmedListener = targetFragment as OnPositiveConfirmedListener
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
        arguments?.getString(OK_TEXT)?.let { ok_button.text = it }
        ok_button.setOnClickListener { _->
            onPositiveConfirmedListener.onPositiveConfirmed()
            dismiss()
        }
        cancel_button.setOnClickListener { _-> dismiss() }
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

        fun newInstance(message: String, okButtonText: String) =
            ConfirmDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(MESSAGE, message)
                    putString(OK_TEXT, okButtonText)
                }
            }
    }
}