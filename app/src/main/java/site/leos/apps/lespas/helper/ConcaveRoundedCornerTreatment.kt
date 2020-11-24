package site.leos.apps.lespas.helper

import com.google.android.material.shape.CornerTreatment
import com.google.android.material.shape.ShapePath

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