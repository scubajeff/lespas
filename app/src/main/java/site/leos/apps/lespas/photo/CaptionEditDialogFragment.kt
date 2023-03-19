/*
 *   Copyright 2019 Jeffrey Liu (scubajeffrey@protonmail.ch)
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

package site.leos.apps.lespas.photo

import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.LesPasDialogFragment

class CaptionEditDialogFragment: LesPasDialogFragment(R.layout.fragment_caption_dialog) {
    private lateinit var captionEditText: TextInputEditText

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        requireDialog().window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)

        captionEditText = view.findViewById<TextInputEditText>(R.id.caption_edittext).apply {
            // Use append to move cursor to the end of text
            savedInstanceState ?: run { requireArguments().getString(CURRENT_CAPTION)?.let { append(it) }}
            requestFocus()
        }

        view.findViewById<MaterialButton>(R.id.save_button).setOnClickListener { returnCaption(captionEditText.text.toString()) }
        view.findViewById<MaterialButton>(R.id.cancel_button).setOnClickListener { returnCaption(null) }
    }

    override fun onCancel(dialog: DialogInterface) {
        returnCaption(null)
        super.onCancel(dialog)
    }

    private fun returnCaption(newCaption: String?) {
        // Won't allow character '|'
        parentFragmentManager.setFragmentResult(RESULT_KEY_NEW_CAPTION, Bundle().apply { putString(RESULT_KEY_NEW_CAPTION, newCaption?.replace("|", "")) })
        dismiss()
    }

    companion object {
        const val CURRENT_CAPTION = "CURRENT_CAPTION"
        const val RESULT_KEY_NEW_CAPTION = "RESULT_KEY_NEW_CAPTION"

        @JvmStatic
        fun newInstance(currentCaption: String) = CaptionEditDialogFragment().apply { arguments = Bundle().apply { putString(CURRENT_CAPTION, currentCaption) }}
    }
}