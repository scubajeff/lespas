package site.leos.apps.lespas.helper

import android.view.View
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.Transition
import androidx.viewpager2.widget.ViewPager2
import com.google.android.exoplayer2.ui.PlayerView
import site.leos.apps.lespas.R

class MediaSliderTransitionListener(private val slider: ViewPager2): Transition.TransitionListener {
    var isVideo = false
    override fun onTransitionStart(transition: Transition) {
        slider.getChildAt(0)?.apply {
            findViewById<ImageView>(R.id.media)?.let { mediaView->
                // media imageview in exoplayer item view is always in invisible state
                if (mediaView.visibility != View.VISIBLE) {
                    isVideo = true
                    findViewById<PlayerView>(R.id.player_view)?.visibility = View.INVISIBLE
                }
                else {
                    mediaView.visibility = View.INVISIBLE
                    (slider.adapter as MediaSliderAdapter<*>).setAutoStart(true)
                }
            }
        }
    }
    override fun onTransitionEnd(transition: Transition) {
        (slider.getChildAt(0) as RecyclerView).apply {
            findViewById<ImageView>(R.id.media)?.visibility = View.VISIBLE
            try { if (isVideo) (findViewHolderForAdapterPosition(slider.currentItem) as MediaSliderAdapter<*>.VideoViewHolder).startOver() } catch (e: Exception) {}
        }
    }
    override fun onTransitionCancel(transition: Transition) {
        (slider.getChildAt(0) as RecyclerView).apply {
            findViewById<ImageView>(R.id.media)?.visibility = View.VISIBLE
            try { if (isVideo) (findViewHolderForAdapterPosition(slider.currentItem) as MediaSliderAdapter<*>.VideoViewHolder).startOver() } catch (e: Exception) {}
        }
    }
    override fun onTransitionPause(transition: Transition) {}
    override fun onTransitionResume(transition: Transition) {}
}