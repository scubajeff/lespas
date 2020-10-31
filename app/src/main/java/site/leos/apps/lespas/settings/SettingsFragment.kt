package site.leos.apps.lespas.settings

import android.os.Bundle
import android.view.Menu
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceFragmentCompat
import site.leos.apps.lespas.R

class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey)

        setHasOptionsMenu(true)
    }

    override fun onResume() {
        super.onResume()

        (activity as? AppCompatActivity)?.supportActionBar?.run {
            title = getString(R.string.settings_title)
            setDisplayHomeAsUpEnabled(true)
        }
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)

        menu.run {
            findItem(R.id.action_settings).isVisible = false
        }
    }
}