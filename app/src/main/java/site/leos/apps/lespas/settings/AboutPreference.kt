package site.leos.apps.lespas.settings

import android.content.Context
import android.util.AttributeSet
import androidx.preference.Preference
import site.leos.apps.lespas.R

class AboutPreference (context: Context, attributeSet: AttributeSet): Preference(context, attributeSet) {
    init { layoutResource = R.layout.preference_about }
}