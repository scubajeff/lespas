package site.leos.apps.lespas.helper

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.constraintlayout.widget.ConstraintLayout

class DullConstraintLayout constructor(context: Context, attrs: AttributeSet): ConstraintLayout(context, attrs) {
    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean {
        return true
    }
}