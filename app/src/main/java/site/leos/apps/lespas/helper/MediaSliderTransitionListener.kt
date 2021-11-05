package site.leos.apps.lespas.helper

import android.widget.ImageView
import androidx.core.view.isVisible
import androidx.media3.ui.PlayerView
import androidx.transition.Transition
import androidx.viewpager2.widget.ViewPager2
import site.leos.apps.lespas.R

class MediaSliderTransitionListener(private val slider: ViewPager2): Transition.TransitionListener {
    override fun onTransitionStart(transition: Transition) {
        slider.getChildAt(0)?.apply {
            findViewById<ImageView>(R.id.media).isVisible = false
            findViewById<PlayerView>(R.id.player_view)?.isVisible = false
        }
    }
    override fun onTransitionEnd(transition: Transition) { doWhenEnd() }
    override fun onTransitionCancel(transition: Transition) { doWhenEnd() }
    override fun onTransitionPause(transition: Transition) {}
    override fun onTransitionResume(transition: Transition) {}

    private fun doWhenEnd() {
        slider.getChildAt(0)?.apply { findViewById<PlayerView>(R.id.player_view)?.let { it.isVisible = true } ?: run { findViewById<ImageView>(R.id.media)?.isVisible = true }}
    }
}