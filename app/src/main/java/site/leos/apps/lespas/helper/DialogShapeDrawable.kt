package site.leos.apps.lespas.helper

import android.content.Context
import android.content.res.ColorStateList
import com.google.android.material.shape.CornerTreatment
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.shape.ShapePath
import site.leos.apps.lespas.R

class DialogShapeDrawable : MaterialShapeDrawable() {
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
        fun newInstance(context: Context, strokeColor: Int) = Companion.newInstance(context, strokeColor, false)
        fun newInstance(context: Context, strokeColor: Int, smallRadius: Boolean) = MaterialShapeDrawable(
            ShapeAppearanceModel.builder().setAllCornerSizes(context.resources.getDimension(if (smallRadius) R.dimen.dialog_frame_radius_small else R.dimen.dialog_frame_radius)).setAllCorners(ConcaveRoundedCornerTreatment()).build()
        ).apply {
            fillColor = ColorStateList.valueOf(context.resources.getColor(R.color.color_background, null))
            if (strokeColor != NO_STROKE) { setStroke(4.0f, strokeColor) }
        }
    }
}