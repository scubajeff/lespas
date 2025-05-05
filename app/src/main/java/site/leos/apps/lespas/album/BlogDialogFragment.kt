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

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.transition.TransitionManager
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.selection.ItemDetailsLookup
import androidx.recyclerview.selection.ItemKeyProvider
import androidx.recyclerview.selection.SelectionTracker
import androidx.recyclerview.selection.StorageStrategy
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import kotlinx.coroutines.launch
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.LesPasDialogFragment
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.helper.Tools.parcelable
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.publication.NCShareViewModel
import site.leos.apps.lespas.sync.ActionViewModel
import site.leos.apps.lespas.sync.SyncAdapter
import kotlin.math.roundToInt

@androidx.annotation.OptIn(UnstableApi::class)
class BlogDialogFragment: LesPasDialogFragment(R.layout.fragment_blog_dialog, MAX_DIALOG_WINDOW_HEIGHT) {
    private lateinit var album: Album
    private lateinit var lespasPath: String

    private var inPortraitMode = true
    private lateinit var themeBackground: ViewGroup
    private lateinit var themeChoice: MaterialButtonToggleGroup
    private lateinit var container: ViewGroup
    private lateinit var blogInfo: ConstraintLayout
    private lateinit var shareBlogButton: MaterialButton
    private lateinit var publishBlogButton: MaterialButton
    private lateinit var removeBlogButton: MaterialButton
    private lateinit var holdYourHorsesTextView: TextView
    private lateinit var showPhotoListCheckBox: CheckBox

    private lateinit var photoList: RecyclerView
    private lateinit var photoAdapter: PhotoGridAdapter
    private lateinit var selectionTracker: SelectionTracker<String>

/*
    private lateinit var includeSocialButton: MaterialButton
    private lateinit var includCopyrightBlogButton: MaterialButton
*/

    private val shareModel: NCShareViewModel by activityViewModels()
    private val actionModel: ActionViewModel by activityViewModels()
    private val albumModel: AlbumViewModel by activityViewModels()

    private lateinit var blogLink: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requireArguments().apply {
            //@Suppress("DEPRECATION")
            //album = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) getParcelable(KEY_ALBUM, Album::class.java)!! else getParcelable(KEY_ALBUM)!!
            album = parcelable(KEY_ALBUM)!!
            blogLink = "${shareModel.getServerBaseUrl()}/apps/cms_pico/pico/${Tools.getBlogSiteName(shareModel.getUserLoginName())}/${album.id}"
        }
        lespasPath = Tools.getRemoteHome(requireContext())

        photoAdapter = PhotoGridAdapter(
            { photo, view, type -> shareModel.setImagePhoto(NCShareViewModel.RemotePhoto(photo, if (Tools.isRemoteAlbum(album) && photo.eTag != Album.ETAG_NOT_YET_UPLOADED) "${lespasPath}/${album.name}" else "", album.coverBaseline), view, type) },
            { view -> shareModel.cancelSetImagePhoto(view) }
        ).apply { stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY }

        shareModel.fetchBlogPostInfo(album.id)

        inPortraitMode = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (inPortraitMode) {
        // Set the photo list height to fit in the maximum dialog window height we set, this is mostly used when blog post is not created yet therefore shareBlogButton is invisible
            view.findViewById<ConstraintLayout>(R.id.inclusion_selection)?.let { rootLayout ->
                rootLayout.doOnNextLayout {
                    ConstraintSet().run {
                        val height = with(resources.displayMetrics) {
                            (heightPixels * MAX_DIALOG_WINDOW_HEIGHT - themeChoice.measuredHeight - showPhotoListCheckBox.measuredHeight * 2 - removeBlogButton.measuredHeight - TypedValue.applyDimension(
                                TypedValue.COMPLEX_UNIT_DIP,
                                EXTRA_SPACE_SIZE,
                                this
                            )).roundToInt()
                        }
                        clone(rootLayout)
                        constrainHeight(R.id.photo_grid, ConstraintSet.MATCH_CONSTRAINT)
                        constrainMaxHeight(R.id.photo_grid, height)
                        applyTo(rootLayout)
                    }
                }
            }
        }

        themeBackground = view.findViewById(R.id.background)
        themeChoice = view.findViewById<MaterialButtonToggleGroup?>(R.id.theme_options).apply {
            addOnButtonCheckedListener { _, _, _ -> resumePost() }
        }
        container = view.findViewById(R.id.container)
        blogInfo = view.findViewById(R.id.blog_info)
        shareBlogButton = view.findViewById<MaterialButton>(R.id.share_button).apply {
            setOnClickListener {
                startActivity(Intent.createChooser(Intent().apply {
                    action = Intent.ACTION_SEND
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, blogLink)
                }, null))
            }

            if (inPortraitMode) {
                doOnLayout {
                    // If post link QR code displayed, recalculate the maximum height of the dialog window to the size just enough to reveal the shareBlogButton
                    this@BlogDialogFragment.setMaxHeight(themeBackground.measuredHeight)
                    // Set the photo list height just enough to push shareBlogButton out of sight, avoid dialog window size changes when expanding/collapsing the photo list
                    view.findViewById<ConstraintLayout>(R.id.inclusion_selection)?.let { rootLayout ->
                        ConstraintSet().run {
                            clone(rootLayout)
                            constrainHeight(R.id.photo_grid, ConstraintSet.MATCH_CONSTRAINT)
                            constrainMaxHeight(R.id.photo_grid, (shareBlogButton.measuredHeight + holdYourHorsesTextView.measuredHeight))
                            applyTo(rootLayout)
                        }
                    }
                }
            }
        }
        holdYourHorsesTextView = view.findViewById(R.id.notice)
        showPhotoListCheckBox = view.findViewById<CheckBox>(R.id.include_all).apply {
            setOnCheckedChangeListener { _, isChecked -> photoList.isVisible = !isChecked }
        }
/*
        includeSocialButton = view.findViewById<MaterialButton?>(R.id.option_social_link).apply { isChecked = true }
        includCopyrightBlogButton = view.findViewById<MaterialButton?>(R.id.option_copyright).apply { isChecked = true }
*/

        publishBlogButton = view.findViewById<MaterialButton>(R.id.publish_button).apply {
            setOnClickListener {
                saveInclusionState()

                if (this.text != getString(R.string.button_text_done)) {
                    actionModel.createBlogPost(
                        album.id, album.name,
                        when (themeChoice.checkedButtonId) {
                            R.id.theme_cascade -> SyncAdapter.THEME_CASCADE
                            R.id.theme_magazine -> SyncAdapter.THEME_MAGAZINE
                            R.id.theme_timeline -> SyncAdapter.THEME_TIMELINE
                            else -> ""
                        },
                        //includeSocialButton.isChecked, includCopyrightBlogButton.isChecked
                    )

                    // Collapse photo list
                    if (inPortraitMode) showPhotoListCheckBox.isChecked = true

                    // Reveal QR code for link sharing
                    holdYourHorsesTextView.isVisible = true
                    showQRCode()

                    // Mark job done
                    this.text = getString(R.string.button_text_done)
                } else dismiss()
            }
        }
        removeBlogButton = view.findViewById<MaterialButton>(R.id.remove_button).apply {
            setOnClickListener {
                actionModel.deleteBlogPosts(listOf(album))
                dismiss()
            }
        }

        photoList = view.findViewById<RecyclerView?>(R.id.photo_grid).apply {
            adapter = photoAdapter

            selectionTracker = SelectionTracker.Builder(
                "blogSelection",
                this,
                PhotoGridAdapter.PhotoKeyProvider(photoAdapter),
                PhotoGridAdapter.PhotoDetailsLookup(this),
                StorageStrategy.createStringStorage()
            ).withSelectionPredicate(object : SelectionTracker.SelectionPredicate<String>() {
                override fun canSetStateForKey(key: String, nextState: Boolean): Boolean = key.isNotEmpty()
                override fun canSetStateAtPosition(position: Int, nextState: Boolean): Boolean = true
                override fun canSelectMultiple(): Boolean = true
            }).build().apply {
                addObserver(object : SelectionTracker.SelectionObserver<String>() {
                    override fun onItemStateChanged(key: String, selected: Boolean) {
                        super.onItemStateChanged(key, selected)

                        photoAdapter.setInclusionState(key, selected)
                        resumePost()
                    }
                })
            }
            photoAdapter.setSelectionTracker(selectionTracker)

            resources.getInteger(R.integer.cameraroll_grid_span_count).let { defaultSpanCount ->
                photoAdapter.setOverlayDrawable(
                    Tools.getPlayMarkDrawable(requireActivity(), 0.32f / defaultSpanCount),
                    Tools.getSelectedMarkDrawable(requireActivity(), 0.25f / defaultSpanCount),
                    Tools.getPanoramaMarkDrawable(requireActivity(), 0.32f / defaultSpanCount),
                )
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    shareModel.blogPostThemeId.collect { themeId ->
                        if (themeId.isNotEmpty()) {
                            when(themeId) {
                                SyncAdapter.THEME_CASCADE -> themeChoice.check(R.id.theme_cascade)
                                SyncAdapter.THEME_MAGAZINE -> themeChoice.check(R.id.theme_magazine)
                                SyncAdapter.THEME_TIMELINE -> themeChoice.check(R.id.theme_timeline)
                            }

                            removeBlogButton.isVisible = true
                            showQRCode()
                        }
                    }
                }
                launch {
                    albumModel.getAlbumDetail(album.id)?.collect { newAlbumDetail ->
                        photoAdapter.submitList(Tools.sortPhotos(newAlbumDetail.photos, newAlbumDetail.album.sortOrder))

                        photoAdapter.currentList.forEach { photo -> if (!Tools.isBitSet(photo.shareId, Photo.EXCLUDE_FROM_BLOG)) selectionTracker.select(photo.id) }
                    }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()

        saveInclusionState()
    }

    private fun saveInclusionState(): List<String> {
        // Save inclusion state in DB
        val inclusion = mutableListOf<String>()
        val exclusion = mutableListOf<String>()
        photoAdapter.currentList.forEach { photo -> if (Tools.isBitSet(photo.shareId, Photo.EXCLUDE_FROM_BLOG)) exclusion.add(photo.id) else inclusion.add(photo.id) }
        albumModel.setExcludeFromBlog(inclusion, exclusion)

        return inclusion
    }

    private fun resumePost() {
        publishBlogButton.text = getString(R.string.button_text_post_blog)
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

    class PhotoGridAdapter(private val imageLoader: (Photo, ImageView, String) -> Unit, private val cancelLoader: (View) -> Unit
    ) : ListAdapter<Photo, PhotoGridAdapter.PhotoViewHolder>(PhotoDiffCallback()) {
        private lateinit var selectionTracker: SelectionTracker<String>
        private var playMark: Drawable? = null
        private var selectedMark: Drawable? = null
        private var panoramaMark: Drawable? = null
        private val notIncludedFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0.0f) })

        inner class PhotoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private var currentPhotoName = ""
            private val ivPhoto: ImageView = itemView.findViewById<ImageView>(R.id.photo).apply {
                foregroundGravity = Gravity.CENTER
                setOnClickListener { selectionTracker.select(currentList[bindingAdapterPosition].id) }
            }
            private val captionIcon: ImageView = itemView.findViewById(R.id.has_caption)

            fun bind(photo: Photo) {
                with(ivPhoto) {
                    isSelected = selectionTracker.isSelected(photo.id)

                    if (currentPhotoName != photo.name) {
                        imageLoader(photo, this, NCShareViewModel.TYPE_GRID)
                        ViewCompat.setTransitionName(this, photo.id)
                        currentPhotoName = photo.name
                    }

                    captionIcon.isVisible = photo.caption.isNotEmpty()

                    foreground = when {
                        isSelected -> selectedMark
                        Tools.isMediaPlayable(photo.mimeType) -> playMark
                        photo.mimeType == Tools.PANORAMA_MIMETYPE -> panoramaMark
                        else -> null
                    }

                    if (isSelected) clearColorFilter() else colorFilter = notIncludedFilter
                }
            }

            fun getItemDetails() = object : ItemDetailsLookup.ItemDetails<String>() {
                override fun getPosition(): Int = bindingAdapterPosition
                override fun getSelectionKey(): String = getPhotoId(bindingAdapterPosition)
            }
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder = PhotoViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_photo_blog, parent, false))
        override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) { holder.bind(currentList[position]) }
        override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
            for (i in 0 until currentList.size) {
                recyclerView.findViewHolderForAdapterPosition(i)?.let { holder -> holder.itemView.findViewById<View>(R.id.photo)?.let { cancelLoader(it) }}
            }
            super.onDetachedFromRecyclerView(recyclerView)
        }


        // Foreground drawable for selected state and playable media
        fun setOverlayDrawable(playMark: Drawable, selectedMark: Drawable, panoramaMark: Drawable) {
            this.playMark = playMark
            this.selectedMark = selectedMark
            this.panoramaMark = panoramaMark
        }

        // Photo selection
        fun setInclusionState(key: String, selected: Boolean) {
            currentList.find { it.id == key }?.let { photo -> photo.shareId = if (selected) Tools.clearBit(photo.shareId, Photo.EXCLUDE_FROM_BLOG) else Tools.setBit(photo.shareId, Photo.EXCLUDE_FROM_BLOG) }
        }
        fun setSelectionTracker(selectionTracker: SelectionTracker<String>) { this.selectionTracker = selectionTracker }
        fun getPhotoId(position: Int): String = currentList[position].id
        fun getPhotoPosition(photoId: String): Int = currentList.indexOfFirst { it.id == photoId }

        class PhotoKeyProvider(private val adapter: PhotoGridAdapter): ItemKeyProvider<String>(SCOPE_CACHED) {
            override fun getKey(position: Int): String = adapter.getPhotoId(position)
            override fun getPosition(key: String): Int = adapter.getPhotoPosition(key)
        }
        class PhotoDetailsLookup(private val recyclerView: RecyclerView): ItemDetailsLookup<String>() {
            override fun getItemDetails(e: MotionEvent): ItemDetails<String> {
                recyclerView.findChildViewUnder(e.x, e.y)?.let {
                    recyclerView.getChildViewHolder(it).let { holder ->
                        if (holder is PhotoViewHolder) return holder.getItemDetails()
                    }
                }
                return stubItemDetails()
            }


            // Default ItemDetailsLookup stub, to avoid clearing selection by clicking the empty area in the list
            private fun stubItemDetails() = object : ItemDetails<String>() {
                override fun getPosition(): Int = Int.MIN_VALUE
                override fun getSelectionKey(): String = ""
            }
        }
    }

    class PhotoDiffCallback: DiffUtil.ItemCallback<Photo>() {
        override fun areItemsTheSame(oldItem: Photo, newItem: Photo): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Photo, newItem: Photo): Boolean = oldItem.shareId == newItem.shareId
    }

    companion object {
        private const val MAX_DIALOG_WINDOW_HEIGHT = 0.88f  // Best fit for 16:9 screen, also suitable for taller ones
        private const val KEY_ALBUM = "KEY_ALBUM"

        @JvmStatic
        fun newInstance(album: Album) = BlogDialogFragment().apply { arguments = Bundle().apply { putParcelable(KEY_ALBUM, album) } }
    }
}