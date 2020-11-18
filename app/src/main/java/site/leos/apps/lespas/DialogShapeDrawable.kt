package site.leos.apps.lespas

import android.content.Context
import android.content.res.ColorStateList
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel

class DialogShapeDrawable : MaterialShapeDrawable() {
    companion object {
        fun newInstance(context: Context) = MaterialShapeDrawable(
            ShapeAppearanceModel.builder()
            .setAllCornerSizes(context.resources.getDimension(R.dimen.dialog_frame_radius))
            .setAllCorners(ConcaveRoundedCornerTreatment())
            .build()
        ).apply {
            fillColor = ColorStateList.valueOf(context.resources.getColor(R.color.color_background))
            createWithElevationOverlay(context, 8.0f)
        }
    }
}