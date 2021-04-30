package site.leos.apps.lespas.helper

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.fragment.app.DialogFragment
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.CornerTreatment
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.shape.ShapePath
import site.leos.apps.lespas.R

open class LesPasDialogFragment(private val layoutId: Int): DialogFragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(layoutId, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<ViewGroup>(R.id.shape_background)?.background = DialogShapeDrawable.newInstance(requireContext(), DialogShapeDrawable.NO_STROKE)
        view.findViewById<ViewGroup>(R.id.background)?.background = DialogShapeDrawable.newInstance(requireContext(), MaterialColors.getColor(view, R.attr.colorPrimaryVariant))
    }

    override fun onStart() {
        super.onStart()

        requireDialog().window!!.apply {
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

    private class DialogShapeDrawable: MaterialShapeDrawable() {
        class ConcaveRoundedCornerTreatment : CornerTreatment() {

            override fun getCornerPath(shapePath: ShapePath, angle: Float, interpolation: Float, radius: Float) {
                val interpolatedRadius = radius * interpolation
                shapePath.reset(0f, interpolatedRadius, ANGLE_LEFT, ANGLE_LEFT - angle)
                shapePath.addArc(-interpolatedRadius, -interpolatedRadius, interpolatedRadius, interpolatedRadius, ANGLE_BOTTOM, -angle)
            }

            companion object {
                const val ANGLE_LEFT = 180f
                const val ANGLE_BOTTOM = 90f
            }
        }

        companion object {
            const val NO_STROKE = -1

            @JvmStatic
            fun newInstance(context: Context, strokeColor: Int) = newInstance(context, strokeColor, false)

            @JvmStatic
            fun newInstance(context: Context, strokeColor: Int, smallRadius: Boolean) = MaterialShapeDrawable(
                ShapeAppearanceModel.builder().setAllCornerSizes(context.resources.getDimension(if (smallRadius) R.dimen.dialog_frame_radius_small else R.dimen.dialog_frame_radius)).setAllCorners(ConcaveRoundedCornerTreatment()).build()
            ).apply {
                fillColor = ColorStateList.valueOf(context.resources.getColor(R.color.color_background, null))
                if (strokeColor != NO_STROKE) { setStroke(4.0f, strokeColor) }
            }
        }
    }
}