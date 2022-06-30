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