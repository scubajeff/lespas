package site.leos.apps.lespas.helper

import android.view.View
import android.widget.ImageView
import androidx.core.view.isVisible
import androidx.transition.Transition
import androidx.viewpager2.widget.ViewPager2
import site.leos.apps.lespas.R

class MediaSliderTransitionListener(private val slider: ViewPager2): Transition.TransitionListener {
    private var view: View? = null
    override fun onTransitionStart(transition: Transition) {
        view = slider.getChildAt(0)?.findViewById<View>(R.id.media)?.apply {
            if (this is ImageView) isVisible = false
        }
    }
    override fun onTransitionEnd(transition: Transition) { doWhenEnd() }
    override fun onTransitionCancel(transition: Transition) { doWhenEnd() }
    override fun onTransitionPause(transition: Transition) {}
    override fun onTransitionResume(transition: Transition) {}

    private fun doWhenEnd() { view?.isVisible = true }
}