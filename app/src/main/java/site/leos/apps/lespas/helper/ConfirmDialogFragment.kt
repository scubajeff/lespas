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

package site.leos.apps.lespas.helper

import android.os.Bundle
import android.text.Html
import android.text.method.LinkMovementMethod
import android.view.View
import android.widget.CheckBox
import android.widget.TextView
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import site.leos.apps.lespas.R

class ConfirmDialogFragment : LesPasDialogFragment(R.layout.fragment_confirm_dialog) {
    private lateinit var checkBox: CheckBox
    private lateinit var checkBox2: CheckBox

    private val buttonClickListener: View.OnClickListener = View.OnClickListener { v ->
        v?.id.let { viewId ->
            parentFragmentManager.setFragmentResult(requireArguments().getString(REQUEST_KEY, CONFIRM_DIALOG_RESULT_KEY), Bundle().apply {
                putBoolean(CONFIRM_DIALOG_RESULT_KEY, viewId == R.id.ok_button)
                putString(INDIVIDUAL_REQUEST_KEY, requireArguments().getString(INDIVIDUAL_REQUEST_KEY, ""))
                putBoolean(CHECKBOX_RESULT_KEY, if (checkBox.isVisible) checkBox.isChecked else false)
                putBoolean(CHECKBOX2_RESULT_KEY, if (checkBox2.isVisible) checkBox2.isChecked else false)
            })
            dismiss()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isCancelable = requireArguments().getBoolean(CANCELABLE)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<TextView>(R.id.dialog_title).run {
            if (requireArguments().getBoolean(LINK)) {
                text = Html.fromHtml(requireArguments().getString(MESSAGE), Html.FROM_HTML_MODE_LEGACY)
                movementMethod = LinkMovementMethod.getInstance()
            } else text = requireArguments().getString(MESSAGE)
        }

        checkBox = view.findViewById<CheckBox>(R.id.checkbox).apply {
            (requireArguments().getString(CHECK_BOX_TEXT) ?: "").let {
                if (it.isNotEmpty()) {
                    text = it
                    isChecked = this@ConfirmDialogFragment.requireArguments().getBoolean(CHECK_BOX_CHECKED)
                    isVisible = true
                }
            }
        }

        checkBox2 = view.findViewById<CheckBox>(R.id.checkbox2).apply {
            (requireArguments().getString(CHECK_BOX2_TEXT) ?: "").let {
                if (it.isNotEmpty()) {
                    text = it
                    isChecked = this@ConfirmDialogFragment.requireArguments().getBoolean(CHECK_BOX2_CHECKED)
                    isVisible = true
                }
            }
        }

        view.findViewById<MaterialButton>(R.id.ok_button).apply {
            text = requireArguments().getString(POSITIVE_BUTTON) ?: getString(android.R.string.ok)
            setOnClickListener(buttonClickListener)
        }
        view.findViewById<MaterialButton>(R.id.cancel_button).apply {
            if (isCancelable) {
                text = requireArguments().getString(NEGATIVE_BUTTON) ?: getString(android.R.string.cancel)
                setOnClickListener(buttonClickListener)
            }
            else {
                requireArguments().getString(NEGATIVE_BUTTON)?.let { buttonText ->
                    // If not cancelable, but caller still supply negative button text, show it anyway
                    text = buttonText
                    setOnClickListener(buttonClickListener)
                } ?: run {
                    isEnabled = false
                    visibility = View.GONE
                }
            }
        }
    }

    companion object {
        const val CONFIRM_DIALOG_RESULT_KEY = "CONFIRM_DIALOG_REQUEST_KEY"
        const val CHECKBOX_RESULT_KEY = "CHECKBOX_RESULT_KEY"
        const val CHECKBOX2_RESULT_KEY = "CHECKBOX2_RESULT_KEY"
        const val INDIVIDUAL_REQUEST_KEY = "INDIVIDUAL_REQUEST_KEY"
        private const val REQUEST_KEY = "REQUEST_KEY"
        private const val MESSAGE = "MESSAGE"
        private const val LINK = "LINK"
        private const val POSITIVE_BUTTON = "POSITIVE_BUTTON"
        private const val NEGATIVE_BUTTON = "NEGATIVE_BUTTON"
        private const val CANCELABLE = "CANCELABLE"
        private const val CHECK_BOX_TEXT = "CHECK_BOX_TEXT"
        private const val CHECK_BOX_CHECKED = "CHECK_BOX_CHECKED"
        private const val CHECK_BOX2_TEXT = "CHECK_BOX2_TEXT"
        private const val CHECK_BOX2_CHECKED = "CHECK_BOX2_CHECKED"

        @JvmStatic
        fun newInstance(message: String, positiveButtonText: String? = null, negativeButtonText: String? = null, cancelable: Boolean = true, individualKey: String = "", requestKey: String = CONFIRM_DIALOG_RESULT_KEY,
                        checkBoxText: String = "", checkBoxChecked: Boolean = false, checkBox2Text: String = "", checkBox2Checked: Boolean = false, link: Boolean = false) = ConfirmDialogFragment().apply {
            arguments = Bundle().apply {
                putString(MESSAGE, message)
                putString(POSITIVE_BUTTON, positiveButtonText)
                putString(NEGATIVE_BUTTON, negativeButtonText)
                putBoolean(CANCELABLE, cancelable)
                putString(INDIVIDUAL_REQUEST_KEY, individualKey)
                putString(REQUEST_KEY, requestKey)
                putString(CHECK_BOX_TEXT, checkBoxText)
                putBoolean(CHECK_BOX_CHECKED, checkBoxChecked)
                putString(CHECK_BOX2_TEXT, checkBox2Text)
                putBoolean(CHECK_BOX2_CHECKED, checkBox2Checked)
                putBoolean(LINK, link)
            }
        }
    }
}