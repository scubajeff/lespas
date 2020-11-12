package site.leos.apps.lespas

import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class ConfirmDialogFragment(private val message: String) : DialogFragment() {
    private lateinit var onPositiveConfirmedListener: OnPositiveConfirmedListener

    interface OnPositiveConfirmedListener {
        fun onPositiveConfirmed()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onPositiveConfirmedListener = targetFragment as OnPositiveConfirmedListener
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) {_, _-> onPositiveConfirmedListener.onPositiveConfirmed() }
            .setNegativeButton(android.R.string.cancel) {_, _->}
            .create()
}