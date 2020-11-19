package site.leos.apps.lespas.album

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.fragment.app.DialogFragment
import kotlinx.android.synthetic.main.fragment_confirm_dialog.*
import site.leos.apps.lespas.DialogShapeDrawable
import site.leos.apps.lespas.R

class ConfirmDialogFragment : DialogFragment() {
    private lateinit var onPositiveConfirmedListener: OnPositiveConfirmedListener

    interface OnPositiveConfirmedListener {
        fun onPositiveConfirmed()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onPositiveConfirmedListener = targetFragment as OnPositiveConfirmedListener
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_confirm_dialog, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        background.background = DialogShapeDrawable.newInstance(requireContext(), resources.getColor(R.color.color_primary_variant))
        message_textview.text = arguments?.getString(MESSAGE)
        ok_button.setOnClickListener { _-> onPositiveConfirmedListener.onPositiveConfirmed() }
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

            setBackgroundDrawable(DialogShapeDrawable.newInstance(context, DialogShapeDrawable.NO_STROKE))
            setWindowAnimations(R.style.Theme_LesPas_Dialog_Animation)
        }
    }

    companion object {
        const val MESSAGE = "MESSAGE"
        fun newInstance(message: String) = ConfirmDialogFragment().apply { arguments = Bundle().apply { putString(MESSAGE, message) } }
    }
}