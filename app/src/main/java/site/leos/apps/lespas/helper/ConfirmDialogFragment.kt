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
import android.view.View
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import site.leos.apps.lespas.R

class ConfirmDialogFragment : LesPasDialogFragment(R.layout.fragment_confirm_dialog) {
    private val buttonClickListener: View.OnClickListener = View.OnClickListener { v ->
        v?.id.let { viewId ->
            parentFragmentManager.setFragmentResult(requireArguments().getString(REQUEST_KEY, CONFIRM_DIALOG_RESULT_KEY), Bundle().apply {
                putBoolean(CONFIRM_DIALOG_RESULT_KEY, viewId == R.id.ok_button)
                putString(INDIVIDUAL_REQUEST_KEY, requireArguments().getString(INDIVIDUAL_REQUEST_KEY, ""))
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

        view.findViewById<TextView>(R.id.message_textview).text = requireArguments().getString(MESSAGE)
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
        const val INDIVIDUAL_REQUEST_KEY = "INDIVIDUAL_REQUEST_KEY"
        private const val REQUEST_KEY = "REQUEST_KEY"
        private const val MESSAGE = "MESSAGE"
        private const val POSITIVE_BUTTON = "POSITIVE_BUTTON"
        private const val NEGATIVE_BUTTON = "NEGATIVE_BUTTON"
        private const val CANCELABLE = "CANCELABLE"

        @JvmStatic
        fun newInstance(message: String, positiveButtonText: String? = null, negativeButtonText: String? = null, cancelable: Boolean = true, individualKey: String = "", requestKey: String = CONFIRM_DIALOG_RESULT_KEY) = ConfirmDialogFragment().apply {
            arguments = Bundle().apply {
                putString(MESSAGE, message)
                putString(POSITIVE_BUTTON, positiveButtonText)
                putString(NEGATIVE_BUTTON, negativeButtonText)
                putBoolean(CANCELABLE, cancelable)
                putString(INDIVIDUAL_REQUEST_KEY, individualKey)
                putString(REQUEST_KEY, requestKey)
            }
        }
    }
}