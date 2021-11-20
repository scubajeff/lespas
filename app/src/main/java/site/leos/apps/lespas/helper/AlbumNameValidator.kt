package site.leos.apps.lespas.helper

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import com.google.android.material.textfield.TextInputEditText
import site.leos.apps.lespas.R
import java.util.regex.Pattern

class AlbumNameValidator(private val edittext: TextInputEditText, private val usedName: ArrayList<String>): TextWatcher {
    private val slashesPattern =  Pattern.compile("[^\\\\/]+\\z")
    private val devPattern = Pattern.compile("\\A(?!(?:COM[0-9]|CON|LPT[0-9]|NUL|PRN|AUX|com[0-9]|con|lpt[0-9]|nul|prn|aux)|\\s{2,}).{1,254}(?<![.])\\z")
    private val leadingDotPattern = Pattern.compile("^\\..*")
    private val context: Context = edittext.context

    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { edittext.error = null }
    override fun afterTextChanged(s: Editable?) {
        val txt = s!!.toString()

        when {
            txt.isEmpty() -> edittext.error = null
            txt.length > 200 -> edittext.error = context.getString(R.string.name_too_long)
            !slashesPattern.matcher(txt).matches() -> edittext.error = context.getString(R.string.invalid_character_found)
            leadingDotPattern.matcher(txt).matches() -> edittext.error = context.getString(R.string.leading_dots_found)
            !devPattern.matcher(txt).matches() -> edittext.error = context.getString(R.string.invalid_name_found)
            txt in usedName -> edittext.error = context.getString(R.string.album_existed)
        }
    }
}