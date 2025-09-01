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

package site.leos.apps.lespas.album

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.transition.TransitionManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.textfield.TextInputEditText
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import kotlinx.coroutines.launch
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.Tools.parcelable
import site.leos.apps.lespas.publication.NCShareViewModel

class AlbumPublishExternalFragment: Fragment() {
    private lateinit var currentShare: NCShareViewModel.ShareByMe
    private var currentPublicRecipient: NCShareViewModel.Recipient? = null
    private lateinit var publicationTypeToggleGroup: MaterialButtonToggleGroup
    private lateinit var passwordTextInputEditText: TextInputEditText
    private lateinit var publishButton: MaterialButton
    private lateinit var unPublishButton: MaterialButton
    private lateinit var shareLinkButton: MaterialButton

    private val publishModel: NCShareViewModel by activityViewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentShare = requireArguments().parcelable(CURRENT_SHARE)!!
        for (recipient in currentShare.with) {
            if (recipient.sharee.type == NCShareViewModel.SHARE_TYPE_PUBLIC) {
                currentPublicRecipient = recipient
                break
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? = inflater.inflate(R.layout.fragment_album_publish_external, container, false)
    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        passwordTextInputEditText = view.findViewById<TextInputEditText>(R.id.password_textinputedittext).apply {
            requestFocus()
            doOnTextChanged { _, _, _, _ ->  error = null }
            setOnFocusChangeListener { v, hasFocus -> if (!hasFocus) hideSoftKeyboard(v) }
        }

        publicationTypeToggleGroup = view.findViewById<MaterialButtonToggleGroup>(R.id.publication_type).apply {
            setOnTouchListener { v, _ ->
                if (v.id == R.id.publication_type) hideSoftKeyboard(v)
                false
            }
        }
        // Show current public share link permission at first run
        if (savedInstanceState == null && currentPublicRecipient != null) updatePublicationType()

        view.findViewById<MaterialButton>(R.id.cancel_button).setOnClickListener {
            hideSoftKeyboard(it)
            (parentFragment as AlbumPublishDialogFragment).dismiss()
        }
        unPublishButton = view.findViewById<MaterialButton>(R.id.unpublish_button).apply {
            isEnabled = currentPublicRecipient != null
            setOnClickListener {
                hideSoftKeyboard(it)
                currentPublicRecipient?.let {
                    publishModel.unPublishExternal(currentPublicRecipient!!)
                    (parentFragment as AlbumPublishDialogFragment).dismiss()
                }
            }
        }
        publishButton = view.findViewById<MaterialButton>(R.id.ok_button).apply {
            setOnClickListener {
                hideSoftKeyboard(it)
                if (text == getString(R.string.publish_button_text)) {
                    // First click
                    publishModel.publishExternal(
                        NCShareViewModel.ShareByMe(
                            currentShare.fileId, currentShare.folderName,
                            mutableListOf(
                                NCShareViewModel.Recipient(
                                    currentPublicRecipient?.shareId ?: "",
                                    if (publicationTypeToggleGroup.checkedButtonId == R.id.solo_album) NCShareViewModel.PERMISSION_CAN_READ else NCShareViewModel.PERMISSION_JOINT,
                                    0L,
                                    "",
                                    NCShareViewModel.Sharee("", "", NCShareViewModel.SHARE_TYPE_PUBLIC)
                                )
                            )
                        ),
                        passwordTextInputEditText.text.toString()
                    )

                    // Disable multiple clicks on set button
                    it.isEnabled = false
                } else {
                    (parentFragment as AlbumPublishDialogFragment).dismiss()
                }
            }
        }

        shareLinkButton = view.findViewById<MaterialButton>(R.id.share_button).apply {
            setOnClickListener {
                hideSoftKeyboard(it)
                startActivity(Intent.createChooser(Intent().apply {
                    action = Intent.ACTION_SEND
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "${publishModel.getServerBaseUrl()}/s/${currentPublicRecipient!!.token}")
                }, null))
            }
        }
        showShareLinkButton()

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    publishModel.publicShare.collect {
                        currentPublicRecipient = it
                        updatePublicationType()
                        showShareLinkButton()
                        publishButton.text = getString(R.string.button_text_done)
                        publishButton.isEnabled = true
                        unPublishButton.isEnabled = true
                    }
                }
                launch {
                    publishModel.publicShareError.collect {
                        passwordTextInputEditText.error = it
                        publishButton.isEnabled = true
                    }
                }
            }
        }
    }

    private fun updatePublicationType() { currentPublicRecipient?.let { publicationTypeToggleGroup.check(if (it.permission > NCShareViewModel.PERMISSION_PUBLIC_CAN_READ)  R.id.joint_album else R.id.solo_album) }}

    private fun showShareLinkButton() {
        currentPublicRecipient?.let {
            val qrcode: BitMatrix = MultiFormatWriter().encode("${publishModel.getServerBaseUrl()}/s/${currentPublicRecipient!!.token}", BarcodeFormat.QR_CODE, 120, 120, null)

            val pixels = IntArray(qrcode.width * qrcode.height)
            for(y in 0 until qrcode.height) {
                val offset = y * qrcode.width
                for (x in 0 until qrcode.width) pixels[offset + x] = if (qrcode.get(x,y)) -1 else 0
            }
            shareLinkButton.icon = createBitmap(qrcode.width, qrcode.height).apply { setPixels(pixels, 0, qrcode.width, 0, 0, qrcode.width, qrcode.height) }.toDrawable(resources)

            TransitionManager.beginDelayedTransition(shareLinkButton.parent as ViewGroup, android.transition.Fade().apply { duration = 500 })
            shareLinkButton.isVisible = true
        }
    }

    private fun hideSoftKeyboard(view: View) { (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).run { hideSoftInputFromWindow(view.windowToken, 0) }}

    companion object {
        private const val CURRENT_SHARE = "CURRENT_SHARE"

        @JvmStatic
        fun newInstance(currentShare: NCShareViewModel.ShareByMe) = AlbumPublishExternalFragment().apply {
            arguments = Bundle().apply { putParcelable(CURRENT_SHARE, currentShare) }
        }
    }
}