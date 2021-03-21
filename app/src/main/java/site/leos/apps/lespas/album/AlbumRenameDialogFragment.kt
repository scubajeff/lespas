package site.leos.apps.lespas.album

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.google.android.material.color.MaterialColors
import kotlinx.android.synthetic.main.fragment_albumrename_dialog.*
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.AlbumNameValidator
import site.leos.apps.lespas.helper.DialogShapeDrawable

class AlbumRenameDialogFragment: DialogFragment() {
    private lateinit var onFinishListener: OnFinishListener

    interface OnFinishListener {
        fun onRenameFinished(newName: String)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onFinishListener = targetFragment as OnFinishListener
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_albumrename_dialog, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        rename_textinputedittext.run {
            if (savedInstanceState == null) setText(arguments?.getString(OLD_NAME), TextView.BufferType.EDITABLE)

            addTextChangedListener(AlbumNameValidator(this, context))

            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_NULL) {
                    val name = this.text.toString().trim()    // Trim the leading and trailing blank

                    if (error != null)
                    else if (name.isEmpty())
                    else {
                        onFinishListener.onRenameFinished(name)
                        dismiss()
                    }
                    true
                } else false
            }
        }
        shape_background.background = DialogShapeDrawable.newInstance(requireContext(), DialogShapeDrawable.NO_STROKE)
        //background.background = DialogShapeDrawable.newInstance(requireContext(), resources.getColor(R.color.color_primary_variant, null))
        background.background = DialogShapeDrawable.newInstance(requireContext(), MaterialColors.getColor(view, R.attr.colorPrimaryVariant))
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

            rename_textinputlayout.requestFocus()
        }
    }

    companion object {
        const val OLD_NAME = "OLD_NAME"

        @JvmStatic
        fun newInstance(albumName: String) = AlbumRenameDialogFragment().apply {arguments = Bundle().apply { putString(OLD_NAME, albumName) }}
    }
}
