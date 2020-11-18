package site.leos.apps.lespas.album

import android.app.Dialog
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
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

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        AlertDialog.Builder(requireContext())
            .setMessage(arguments?.getString(MESSAGE))
            .setPositiveButton(android.R.string.ok) {_, _-> onPositiveConfirmedListener.onPositiveConfirmed() }
            .setNegativeButton(android.R.string.cancel) {_, _->}
            .create()

    override fun onStart() {
        super.onStart()

        dialog!!.window!!.apply {
            // Set dialog width to a fixed ration of screen width
            val width = (resources.displayMetrics.widthPixels * resources.getInteger(R.integer.dialog_width_ratio) / 100)
            setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)

            setBackgroundDrawable(DialogShapeDrawable.newInstance(context))
            setWindowAnimations(R.style.DialogAnimation_Window)
        }
    }

    companion object {
        const val MESSAGE = "MESSAGE"
        fun newInstance(message: String) = ConfirmDialogFragment().apply { arguments = Bundle().apply { putString(MESSAGE, message) } }
    }
}