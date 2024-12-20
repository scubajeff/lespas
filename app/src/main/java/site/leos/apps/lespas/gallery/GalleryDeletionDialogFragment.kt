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

package site.leos.apps.lespas.gallery

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.view.View
import android.widget.CheckBox
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import site.leos.apps.lespas.BuildConfig
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.LesPasDialogFragment

class GalleryDeletionDialogFragment: LesPasDialogFragment(R.layout.fragment_gallery_deletion_dialog) {
    private lateinit var checkBoxLocal: CheckBox
    private var checkBoxLocalVisible = false
    private lateinit var checkBoxRemote: CheckBox
    private var checkBoxRemoteVisible = false
    private lateinit var authorizeMessage: TextView
    private lateinit var authorizeButton: MaterialButton
    private lateinit var manageMediaPermissionRequestLauncher: ActivityResultLauncher<Intent>

    private val buttonClickListener: View.OnClickListener = View.OnClickListener { v ->
        v?.id.let { viewId ->
            parentFragmentManager.setFragmentResult(GALLERY_DELETION_DIALOG_RESULT_KEY, Bundle().apply {
                putBoolean(GALLERY_DELETION_DIALOG_RESULT_KEY, viewId == R.id.ok_button)
                putString(INDIVIDUAL_REQUEST_KEY, requireArguments().getString(INDIVIDUAL_REQUEST_KEY, ""))
                putBoolean(DELETE_LOCAL_RESULT_KEY, if (checkBoxLocalVisible) checkBoxLocal.isChecked else false)
                putBoolean(DELETE_REMOTE_RESULT_KEY, if (checkBoxRemoteVisible) checkBoxRemote.isChecked else false)
            })
            dismiss()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isCancelable = true
        manageMediaPermissionRequestLauncher =  registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {}

        checkBoxLocalVisible = requireArguments().getBoolean(CHECK_BOX_LOCAL_VISIBLE)
        checkBoxRemoteVisible = requireArguments().getBoolean(CHECK_BOX_REMOTE_VISIBLE)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        checkBoxLocal = view.findViewById<CheckBox>(R.id.checkbox_local).apply {
            isVisible = checkBoxLocalVisible
            isChecked = requireArguments().getBoolean(CHECK_BOX_LOCAL_CHECKED)
        }
        checkBoxRemote = view.findViewById<CheckBox>(R.id.checkbox_remote).apply {
            isVisible= checkBoxRemoteVisible
            isChecked = requireArguments().getBoolean(CHECK_BOX_REMOTE_CHECKED)
        }
        view.findViewById<MaterialButton>(R.id.ok_button).setOnClickListener(buttonClickListener)
        view.findViewById<MaterialButton>(R.id.cancel_button).setOnClickListener(buttonClickListener)

        authorizeMessage = view.findViewById(R.id.msg_ask_for_gallery_manager_right)
        authorizeButton = view.findViewById<MaterialButton>(R.id.authorize_button).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setOnClickListener { manageMediaPermissionRequestLauncher.launch(Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.parse("package:${BuildConfig.APPLICATION_ID}"))) }
        }
    }

    override fun onResume() {
        super.onResume()

        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !MediaStore.canManageMedia(requireContext())).let { needAuthorization ->
            authorizeMessage.isVisible = needAuthorization
            authorizeButton.isVisible = needAuthorization
        }
    }

    companion object {
        const val GALLERY_DELETION_DIALOG_RESULT_KEY = "GALLERY_DELETION_DIALOG_RESULT_KEY"
        const val DELETE_LOCAL_RESULT_KEY = "DELETE_LOCAL_RESULT_KEY"
        const val DELETE_REMOTE_RESULT_KEY = "DELETE_REMOTE_RESULT_KEY"
        const val INDIVIDUAL_REQUEST_KEY = "INDIVIDUAL_REQUEST_KEY"
        private const val CHECK_BOX_LOCAL_VISIBLE = "CHECK_BOX_LOCAL_VISIBLE"
        private const val CHECK_BOX_LOCAL_CHECKED = "CHECK_BOX_LOCAL_CHECKED"
        private const val CHECK_BOX_REMOTE_VISIBLE = "CHECK_BOX_REMOTE_VISIBLE"
        private const val CHECK_BOX_REMOTE_CHECKED = "CHECK_BOX_REMOTE_CHECKED"

        @JvmStatic
        fun newInstance(individualKey: String, checkBoxLocalVisible: Boolean, checkBoxLocalChecked: Boolean, checkBoxRemoteVisible: Boolean, checkBoxRemoteChecked: Boolean) = GalleryDeletionDialogFragment().apply {
            arguments = Bundle().apply {
                putString(INDIVIDUAL_REQUEST_KEY, individualKey)
                putBoolean(CHECK_BOX_LOCAL_VISIBLE, checkBoxLocalVisible)
                putBoolean(CHECK_BOX_LOCAL_CHECKED, checkBoxLocalChecked)
                putBoolean(CHECK_BOX_REMOTE_VISIBLE, checkBoxRemoteVisible)
                putBoolean(CHECK_BOX_REMOTE_CHECKED, checkBoxRemoteChecked)
            }
        }
    }
}