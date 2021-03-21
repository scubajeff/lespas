package site.leos.apps.lespas.settings

import android.content.Context
import android.util.AttributeSet
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import site.leos.apps.lespas.R

class AboutPreference: Preference {
    constructor(context: Context, attributeSet: AttributeSet, defStyleAttr: Int, defStyleRes: Int): super(context, attributeSet, defStyleAttr, defStyleRes) {}
    constructor(context: Context, attributeSet: AttributeSet, defStyleAttr: Int): super(context, attributeSet, defStyleAttr) {}
    constructor(context: Context, attributeSet: AttributeSet): super(context, attributeSet) {}
    constructor(context: Context): super(context) {}

    override fun onBindViewHolder(holder: PreferenceViewHolder?) {
        super.onBindViewHolder(holder)
        layoutResource = R.layout.preference_about
        holder?.itemView?.isClickable = false
    }
}