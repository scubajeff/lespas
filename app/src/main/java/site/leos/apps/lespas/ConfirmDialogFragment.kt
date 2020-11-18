package site.leos.apps.lespas

import android.app.Dialog
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment

class ConfirmDialogFragment() : DialogFragment() {
    private lateinit var onPositiveConfirmedListener: OnPositiveConfirmedListener

    interface OnPositiveConfirmedListener {
        fun onPositiveConfirmed()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onPositiveConfirmedListener = targetFragment as OnPositiveConfirmedListener
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        AlertDialog.Builder(requireContext(), R.style.Theme_LesPas_Dialog)
            .setMessage(arguments?.getString(MESSAGE))
            .setPositiveButton(android.R.string.ok) {_, _-> onPositiveConfirmedListener.onPositiveConfirmed() }
            .setNegativeButton(android.R.string.cancel) {_, _->}
            .create()

    override fun onResume() {
        // Set dialog width to a fixed ration of screen width
        val width = (resources.displayMetrics.widthPixels * resources.getInteger(R.integer.dialog_width_ratio) / 100)
        dialog!!.window!!.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)

        super.onResume()
    }
    companion object {
        const val MESSAGE = "MESSAGE"
        fun newInstance(message: String) = ConfirmDialogFragment().apply { arguments = Bundle().apply { putString(MESSAGE, message) } }
    }
}