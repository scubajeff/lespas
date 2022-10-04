/*
 *   Copyright 2019 Jeffrey Liu (scubajeffrey@criptext.com)
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package site.leos.apps.lespas.helper

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.doOnNextLayout
import androidx.fragment.app.DialogFragment
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.CornerTreatment
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.shape.ShapePath
import site.leos.apps.lespas.R
import kotlin.math.roundToInt

open class LesPasDialogFragment(private val layoutId: Int, private val maxHeightLandscape: Float = 0.0f): DialogFragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(layoutId, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val rootLayout = view.findViewById<ViewGroup>(R.id.shape_background)
        val themeBackground = view.findViewById<ViewGroup>(R.id.background)

        // Limit
        if (maxHeightLandscape > 0.0f && rootLayout is ConstraintLayout) {
            rootLayout.doOnNextLayout {
                ConstraintSet().run {
                    // It's always 95% of screen height in landscape mode
                    val height = with(resources.displayMetrics) { (heightPixels.toFloat() * (if (heightPixels > widthPixels) maxHeightLandscape else 0.95f) - TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 88f, this)).roundToInt() }
                    clone(rootLayout)
                    constrainHeight(R.id.background, ConstraintSet.MATCH_CONSTRAINT)
                    constrainMaxHeight(R.id.background, height)
                    applyTo(rootLayout)
                }
            }
        }

        rootLayout.background = DialogShapeDrawable.newInstance(requireContext(), DialogShapeDrawable.NO_STROKE)
        themeBackground?.background = DialogShapeDrawable.newInstance(requireContext(), MaterialColors.getColor(view, R.attr.colorPrimaryVariant))
    }

    override fun onStart() {
        super.onStart()

        requireDialog().window!!.apply {
            // Set dialog width to a fixed ratio of screen width
            setLayout((resources.displayMetrics.widthPixels.toFloat() * resources.getInteger(R.integer.dialog_width_ratio) / 100).roundToInt(), WindowManager.LayoutParams.WRAP_CONTENT)

            // Dim the content behind this dialog window
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
                fillColor = ColorStateList.valueOf(Tools.getAttributeColor(context, android.R.attr.colorBackground))
                if (strokeColor != NO_STROKE) { setStroke(4.0f, strokeColor) }
            }
        }
    }
}