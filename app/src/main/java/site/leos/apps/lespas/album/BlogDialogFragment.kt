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

package site.leos.apps.lespas.album

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Bundle
import android.transition.TransitionManager
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.asLiveData
import androidx.media3.common.util.UnstableApi
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.LesPasDialogFragment
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.publication.NCShareViewModel
import site.leos.apps.lespas.sync.ActionViewModel
import site.leos.apps.lespas.sync.SyncAdapter

@androidx.annotation.OptIn(UnstableApi::class)
class BlogDialogFragment: LesPasDialogFragment(R.layout.fragment_blog_dialog) {
    private lateinit var album: Album

    private lateinit var themeChoice: MaterialButtonToggleGroup
    private lateinit var container: ConstraintLayout
    private lateinit var blogInfo: ConstraintLayout
    private lateinit var shareBlogButton: MaterialButton
    private lateinit var removeBlogButton: MaterialButton
/*
    private lateinit var includeSocialButton: MaterialButton
    private lateinit var includCopyrightBlogButton: MaterialButton
*/

    private val shareModel: NCShareViewModel by activityViewModels()
    private val actionModel: ActionViewModel by activityViewModels()

    private lateinit var blogLink: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requireArguments().apply {
            @Suppress("DEPRECATION")
            album = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) getParcelable(KEY_ALBUM, Album::class.java)!! else getParcelable(KEY_ALBUM)!!
            blogLink = "${shareModel.getServerBaseUrl()}/apps/cms_pico/pico/${Tools.getBlogSiteName(shareModel.getUserLoginName())}/${album.id}"
        }

        shareModel.listBlogs(album.id)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        themeChoice = view.findViewById(R.id.theme_options)
        container = view.findViewById(R.id.background)
        blogInfo = view.findViewById(R.id.blog_info)
        shareBlogButton = view.findViewById<MaterialButton>(R.id.share_button).apply {
            setOnClickListener {
                startActivity(Intent.createChooser(Intent().apply {
                    action = Intent.ACTION_SEND
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, blogLink)
                }, null))
            }
        }
/*
        includeSocialButton = view.findViewById<MaterialButton?>(R.id.option_social_link).apply { isChecked = true }
        includCopyrightBlogButton = view.findViewById<MaterialButton?>(R.id.option_copyright).apply { isChecked = true }
*/

        view.findViewById<MaterialButton>(R.id.publish_button).apply {
            setOnClickListener {
                if (this.text != getString(R.string.button_text_done)) {
                    actionModel.createBlogPost(album.id, album.name,
                        when(themeChoice.checkedButtonId) {
                            R.id.theme_cascade -> SyncAdapter.THEME_CASCADE
                            R.id.theme_magazine -> SyncAdapter.THEME_MAGAZINE
                            else -> ""
                        },
                        //includeSocialButton.isChecked, includCopyrightBlogButton.isChecked
                    )
                    showQRCode()
                    this.text = getString(R.string.button_text_done)
/*
                    Log.e(">>>>>>>>", "onViewCreated: ${String.format(YAML_HEADER_INDEX.trimIndent(), PreferenceManager.getDefaultSharedPreferences(requireContext()).getString(getString(R.string.blog_name_pref_key), getString(R.string.blog_name_default)), shareModel.getUserDisplayName())}")
                    Log.e(
                        ">>>>>>>>",
                        String.format(YAML_HEADER_BLOG.trimIndent(), album.name, album.endDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)), album.cover, album.cover, shareModel.getServerBaseUrl()) +
                                "\n" +
                                String.format(CONTENT_CASCADE.trimIndent(), ITEM_CASCADE.trimIndent(), ITEM_CASCADE.trimIndent())
                    )
*/
                } else dismiss()
            }
        }
        removeBlogButton = view.findViewById<MaterialButton>(R.id.remove_button).apply {
            setOnClickListener {
                actionModel.deleteBlogPost(album.id)
                dismiss()
            }
        }
        
        shareModel.blogPostExisted.asLiveData().observe(viewLifecycleOwner) { if (it) {
            showQRCode()
            removeBlogButton.isVisible = true
        }}
    }

    private fun showQRCode() {
        val qrcode: BitMatrix = MultiFormatWriter().encode(blogLink, BarcodeFormat.QR_CODE, 120, 120, null)

        val pixels = IntArray(qrcode.width * qrcode.height)
        for(y in 0 until qrcode.height) {
            val offset = y * qrcode.width
            for (x in 0 until qrcode.width) pixels[offset + x] = if (qrcode.get(x,y)) -1 else 0
        }
        shareBlogButton.icon = BitmapDrawable(resources, Bitmap.createBitmap(qrcode.width, qrcode.height, Bitmap.Config.ARGB_8888).apply { setPixels(pixels, 0, qrcode.width, 0, 0, qrcode.width, qrcode.height) })

        TransitionManager.beginDelayedTransition(container, android.transition.Fade().apply { duration = 500 })
        blogInfo.isVisible = true
    }

    companion object {
        private const val KEY_ALBUM = "KEY_ALBUM"

        @JvmStatic
        fun newInstance(album: Album) = BlogDialogFragment().apply { arguments = Bundle().apply { putParcelable(KEY_ALBUM, album) } }
    }
}