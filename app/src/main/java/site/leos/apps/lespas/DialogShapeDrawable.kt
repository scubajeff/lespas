package site.leos.apps.lespas

import android.content.Context
import android.content.res.ColorStateList
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel

class DialogShapeDrawable : MaterialShapeDrawable() {
    companion object {
        const val NO_STROKE = -1

        fun newInstance(context: Context, strokeColor: Int) = MaterialShapeDrawable(
            ShapeAppearanceModel.builder()
            .setAllCornerSizes(context.resources.getDimension(R.dimen.dialog_frame_radius))
            .setAllCorners(ConcaveRoundedCornerTreatment())
            .build()
        ).apply {
            if (strokeColor != NO_STROKE) {
                setStroke(4.0f, strokeColor)
                fillColor = ColorStateList.valueOf(context.resources.getColor(android.R.color.transparent))
            } else {
                fillColor = ColorStateList.valueOf(context.resources.getColor(R.color.color_background))
            }
        }
    }
}