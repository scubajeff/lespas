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

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import com.google.android.material.textfield.TextInputEditText
import site.leos.apps.lespas.R
import site.leos.apps.lespas.sync.SyncAdapter
import java.util.regex.Pattern

class FileNameValidator(private val edittext: TextInputEditText, private val usedName: ArrayList<String>, private val callBack: ((Boolean) -> Unit)? = null): TextWatcher {
    private val slashesPattern =  Pattern.compile("[^\\\\/]+\\z")
    private val devPattern = Pattern.compile("\\A(?!(?:COM[0-9]|CON|LPT[0-9]|NUL|PRN|AUX|com[0-9]|con|lpt[0-9]|nul|prn|aux)|\\s{2,}).{1,254}(?<![.])\\z")
    private val leadingDotPattern = Pattern.compile("^\\..*")
    private val context: Context = edittext.context
    private val blogFolder = SyncAdapter.BLOG_FOLDER.drop(1)    // remove leading '.'

    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { edittext.error = null }
    override fun afterTextChanged(s: Editable?) {
        val txt = s!!.toString()

        val errorText = when {
            txt.isEmpty() -> null
            txt.length > 200 -> context.getString(R.string.name_too_long)
            !slashesPattern.matcher(txt).matches() -> context.getString(R.string.invalid_character_found)
            leadingDotPattern.matcher(txt).matches() -> context.getString(R.string.leading_dots_found)
            !devPattern.matcher(txt).matches() -> context.getString(R.string.invalid_name_found)
            txt in usedName -> context.getString(R.string.name_existed)
            txt == blogFolder  -> context.getString(R.string.invalid_name_found)
            else -> null
        }
        callBack?.invoke(errorText != null)
        edittext.error = errorText
    }
}