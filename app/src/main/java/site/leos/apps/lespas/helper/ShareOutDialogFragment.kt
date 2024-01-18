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

import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import androidx.core.view.isVisible
import androidx.preference.PreferenceManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import site.leos.apps.lespas.R

class ShareOutDialogFragment : LesPasDialogFragment(R.layout.fragment_share_out_dialog) {
    private lateinit var stripExif: MaterialButtonToggleGroup
    private lateinit var useLowResolution: MaterialButtonToggleGroup
    private lateinit var removeAfterwards: CheckBox

    private val buttonClickListener: View.OnClickListener = View.OnClickListener { v ->
        v?.id.let { viewId ->
            parentFragmentManager.setFragmentResult(SHARE_OUT_DIALOG_RESULT_KEY, Bundle().apply {
                (viewId == R.id.ok_button).run {
                    putBoolean(SHARE_OUT_DIALOG_RESULT_KEY, this)
                    if (this) {
                        putBoolean(STRIP_RESULT_KEY, stripExif.checkedButtonId == R.id.strip_on)
                        putBoolean(LOW_RESOLUTION_RESULT_KEY, useLowResolution.checkedButtonId == R.id.thumbnail)
                        putBoolean(REMOVE_AFTERWARDS_RESULT_KEY, removeAfterwards.isChecked)
                    }
                }
            })
            dismiss()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isCancelable = true
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<MaterialButton>(R.id.ok_button).setOnClickListener(buttonClickListener)
        view.findViewById<MaterialButton>(R.id.cancel_button).setOnClickListener(buttonClickListener)

        stripExif = view.findViewById(R.id.strip_options)
        useLowResolution = view.findViewById(R.id.resolution_options)
        PreferenceManager.getDefaultSharedPreferences(context).run {
            stripExif.check(if (getBoolean(getString(R.string.remove_meta_data_before_sharing_pref_key), true)) R.id.strip_on else R.id.strip_off)
            useLowResolution.check(if (getBoolean(getString(R.string.use_low_resolution_to_share_pref_key), true)) R.id.thumbnail else R.id.original_picture)
            stripExif.isEnabled = requireArguments().getBoolean(SHOW_STRIP_OPTION, false)
            useLowResolution.isEnabled = requireArguments().getBoolean(SHOW_LOW_RESOLUTION_OPTION, false)
        }

        removeAfterwards = view.findViewById<CheckBox>(R.id.remove_after_shared).apply { isVisible = requireArguments().getBoolean(SHOW_REMOVE_AFTERWARDS_OPTION) }
    }

    override fun onCancel(dialog: DialogInterface) {
        parentFragmentManager.setFragmentResult(SHARE_OUT_DIALOG_RESULT_KEY, Bundle().apply {
            putBoolean(SHARE_OUT_DIALOG_RESULT_KEY, false)
        })
        super.onCancel(dialog)
    }

    companion object {
        const val SHARE_OUT_DIALOG_RESULT_KEY = "CONFIRM_DIALOG_REQUEST_KEY"
        const val STRIP_RESULT_KEY = "STRIP_RESULT_KEY"
        const val LOW_RESOLUTION_RESULT_KEY = "LOW_RESOLUTION_RESULT_KEY"
        const val REMOVE_AFTERWARDS_RESULT_KEY = "REMOVE_AFTERWARDS_RESULT_KEY"

        private const val SHOW_STRIP_OPTION = "SHOW_STRIP_OPTION"
        private const val SHOW_LOW_RESOLUTION_OPTION = "SHOW_LOW_RESOLUTION_OPTION"
        private const val SHOW_REMOVE_AFTERWARDS_OPTION = "SHOW_REMOVE_AFTERWARDS_OPTION"

        @JvmStatic
        fun newInstance(mimeTypes: List<String>, showRemoveAfterwards: Boolean = false): ShareOutDialogFragment? {
            var showStripOption = false
            var showLowResolutionOption = false

            mimeTypes.forEach { mimeType ->
                if (!Tools.isMediaPlayable(mimeType)) {
                    showLowResolutionOption = true
                    if (Tools.hasExif(mimeType)) showStripOption = true
                }
            }

            return if (showRemoveAfterwards || showStripOption || showLowResolutionOption) ShareOutDialogFragment().apply { arguments = Bundle().apply {
                    putBoolean(SHOW_STRIP_OPTION, showStripOption)
                    putBoolean(SHOW_LOW_RESOLUTION_OPTION, showLowResolutionOption)
                    putBoolean(SHOW_REMOVE_AFTERWARDS_OPTION, showRemoveAfterwards)
            }}
            // If all options turn grey, no need to show the dialog
            else null
        }
    }
}