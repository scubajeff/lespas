package site.leos.apps.lespas.settings

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.doOnAttach
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import site.leos.apps.lespas.R

class AboutPreference (context: Context, attributeSet: AttributeSet): Preference(context, attributeSet) {
    init {
        layoutResource = R.layout.preference_about
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder?) {
        super.onBindViewHolder(holder)
        holder?.itemView?.let { itemView->
            itemView.isClickable = false
            itemView.doOnAttach {
                val versionText = itemView.findViewById<TextView>(R.id.lespas_version)
                itemView.findViewById<ImageView>(R.id.logo).apply {
                    versionText.visibility = View.INVISIBLE
                    alpha = 0.3f
                    pivotX = 70f
                    pivotY = 50f
                    scaleX = 4f
                    scaleY = 4f
                    animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(500).setInterpolator(AccelerateDecelerateInterpolator()).setListener(object: AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator?) {
                            super.onAnimationEnd(animation)
                            versionText.apply {
                                alpha = 0.5f
                                animate().alpha(1f).setDuration(200).setInterpolator(AccelerateDecelerateInterpolator()).setListener(object: AnimatorListenerAdapter() {
                                    override fun onAnimationEnd(animation: Animator?) {
                                        super.onAnimationEnd(animation)
                                        visibility = View.VISIBLE
                                    }
                                })
                            }
                        }
                    })
                }
            }
        }
    }
}