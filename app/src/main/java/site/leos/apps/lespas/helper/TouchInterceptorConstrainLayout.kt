package site.leos.apps.lespas.helper

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.constraintlayout.widget.ConstraintLayout

class TouchInterceptorConstrainLayout constructor(context: Context, attrs: AttributeSet): ConstraintLayout(context, attrs) {
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        requestDisallowInterceptTouchEvent(true)
        return super.dispatchTouchEvent(ev)
    }
}