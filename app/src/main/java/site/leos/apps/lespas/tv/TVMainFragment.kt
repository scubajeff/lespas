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

package site.leos.apps.lespas.tv

import android.accounts.AccountManager
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.ContentResolver
import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.view.doOnLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.leanback.widget.BaseGridView
import androidx.leanback.widget.HorizontalGridView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.Album
import site.leos.apps.lespas.album.AlbumFragment.AlbumDiffCallback
import site.leos.apps.lespas.album.AlbumViewModel
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.publication.NCShareViewModel
import site.leos.apps.lespas.publication.PublicationListFragment.ShareDiffCallback
import site.leos.apps.lespas.sync.SyncAdapter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

class TVMainFragment: Fragment() {
    private lateinit var myAlbumsAdapter: AlbumGridAdapter
    private lateinit var sharedWithAdapter: SharedGridAdapter
    private lateinit var myAlbumsView: HorizontalGridView
    private lateinit var sharedWithView: HorizontalGridView
    private lateinit var categoryScrollView: NoAutoScrollScrollView
    private var deltaToBottom = 0

    private lateinit var featureImageView: AppCompatImageView
    private lateinit var cinematicScrimView: View

    private lateinit var titleContainerView: LinearLayout
    private lateinit var albumTitleView: TextView
    private lateinit var albumSubTitleView: TextView
    private lateinit var sharedWithMeTitleView: TextView

    private lateinit var remoteBasePath: String
    private var primaryTextColor: Int = 0
    private var fadeInPoster = AnimatorSet()
    private lateinit var fadeOutTitle: ObjectAnimator

    private val imageLoaderViewModel: NCShareViewModel by activityViewModels()
    private val albumsModel: AlbumViewModel by activityViewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ContentResolver.requestSync(AccountManager.get(requireContext()).getAccountsByType(getString(R.string.account_type_nc))[0], getString(R.string.sync_authority), Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putInt(SyncAdapter.ACTION, SyncAdapter.SYNC_REMOTE_CHANGES)
        })
        imageLoaderViewModel.refresh()

        remoteBasePath = Tools.getRemoteHome(requireContext())
        primaryTextColor = Tools.getAttributeColor(requireContext(), android.R.attr.textColorPrimary)

        myAlbumsAdapter = AlbumGridAdapter(
            { album -> parentFragmentManager.beginTransaction().replace(R.id.container_root, TVSliderFragment.newInstance(album, null), TVSliderFragment::class.java.canonicalName).addToBackStack(null).commit() },
            { album, view ->
                album.run {
                    imageLoaderViewModel.setImagePhoto(NCShareViewModel.RemotePhoto(
                        Photo(
                            id = cover, albumId = id,
                            name = coverFileName, width = coverWidth, height = coverHeight, mimeType = coverMimeType, orientation = coverOrientation,
                            dateTaken = LocalDateTime.MIN, lastModified = LocalDateTime.MIN,
                            // TODO dirty hack, can't fetch cover photo's eTag here, hence by comparing it's id to name, for not yet uploaded file these two should be the same, otherwise use a fake one as long as it's not empty
                            eTag = if (cover == coverFileName) Photo.ETAG_NOT_YET_UPLOADED else Photo.ETAG_FAKE,
                        ),
                        if (Tools.isRemoteAlbum(album) && cover != coverFileName) "${remoteBasePath}/${name}" else "", coverBaseline
                    ), view, NCShareViewModel.TYPE_COVER)
                }
            },
            { position, focused, view ->
                if (position >= 0) myAlbumsAdapter.currentList[position].run {
                    zoomCoverView(focused, view)
                    if (focused) {
                        scrollCategoryArea(0)

                        imageLoaderViewModel.cancelSetImagePhoto(featureImageView)
                        changePoster(
                            NCShareViewModel.RemotePhoto(
                                Photo(
                                    id = cover, albumId = id,
                                    name = coverFileName, width = coverWidth, height = coverHeight, mimeType = coverMimeType, orientation = coverOrientation,
                                    dateTaken = LocalDateTime.MIN, lastModified = LocalDateTime.MIN,
                                    // TODO dirty hack, can't fetch cover photo's eTag here, hence by comparing it's id to name, for not yet uploaded file these two should be the same, otherwise use a fake one as long as it's not empty
                                    eTag = if (cover == coverFileName) Photo.ETAG_NOT_YET_UPLOADED else Photo.ETAG_FAKE,
                                ),
                                if (Tools.isRemoteAlbum(this) && cover != coverFileName) "${remoteBasePath}/${name}" else "", coverBaseline
                            ),
                            name, String.format("%s  -  %s", startDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)), endDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)))
                        )
                    }
                }
            },
            { view -> imageLoaderViewModel.cancelSetImagePhoto(view)}
        )

        sharedWithAdapter = SharedGridAdapter(
            { shared -> parentFragmentManager.beginTransaction().replace(R.id.container_root, TVSliderFragment.newInstance(null, shared), TVSliderFragment::class.java.canonicalName).addToBackStack(null).commit() },
            { shared, view ->
                imageLoaderViewModel.setImagePhoto(NCShareViewModel.RemotePhoto(
                    Photo(
                        id = shared.cover.cover, name = shared.cover.coverFileName, mimeType = shared.cover.coverMimeType, width = shared.cover.coverWidth, height = shared.cover.coverHeight, orientation = shared.cover.coverOrientation,
                        dateTaken = LocalDateTime.MIN, lastModified = LocalDateTime.MIN,
                        eTag = Photo.ETAG_FAKE,
                    ),
                    shared.sharePath, shared.cover.coverBaseline
                ), view, NCShareViewModel.TYPE_COVER)
            },
            { position, focused, view ->
                if (position >= 0) sharedWithAdapter.currentList[position].let { shared ->
                    zoomCoverView(focused, view)
                    if (focused) {
                        scrollCategoryArea(deltaToBottom)

                        imageLoaderViewModel.cancelSetImagePhoto(featureImageView)
                        changePoster(
                            NCShareViewModel.RemotePhoto(
                                Photo(
                                    id = shared.cover.cover, name = shared.cover.coverFileName, mimeType = shared.cover.coverMimeType, width = shared.cover.coverWidth, height = shared.cover.coverHeight, orientation = shared.cover.coverOrientation,
                                    dateTaken = LocalDateTime.MIN, lastModified = LocalDateTime.MIN,
                                    eTag = Photo.ETAG_FAKE,
                                ),
                                shared.sharePath, shared.cover.coverBaseline
                            ), shared.albumName, shared.shareByLabel
                        )
                    }
                }
            },
            { view -> imageLoaderViewModel.cancelSetImagePhoto(view)}
        )
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        (activity as AppCompatActivity).supportActionBar?.hide()
        Tools.setImmersive(requireActivity().window, true)

        return inflater.inflate(R.layout.fragment_tv_main, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        titleContainerView = view.findViewById<LinearLayout>(R.id.title_container)
        albumTitleView = view.findViewById<TextView>(R.id.title)
        albumSubTitleView = view.findViewById<TextView>(R.id.subtitle)
        sharedWithMeTitleView = view.findViewById<TextView>(R.id.title_share_with_me)

        cinematicScrimView = view.findViewById<View>(R.id.cinematic_scrim)
        featureImageView = view.findViewById<AppCompatImageView>(R.id.feature_image)

        myAlbumsView = view.findViewById<HorizontalGridView>(R.id.my_albums).apply {
            adapter = myAlbumsAdapter

            onUnhandledKeyListener = object : BaseGridView.OnUnhandledKeyListener {
                override fun onUnhandledKey(event: KeyEvent): Boolean {
                    if (event.action == KeyEvent.ACTION_UP && event.keyCode in arrayOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                        myAlbumsView.findContainingViewHolder(myAlbumsView.focusedChild)?.bindingAdapterPosition?.let { position ->
                            parentFragmentManager.beginTransaction().replace(R.id.container_root, TVSliderFragment.newInstance(myAlbumsAdapter.currentList[position], null), TVSliderFragment::class.java.canonicalName).addToBackStack(null).commit()
                        }
                        return true
                    }

                    return false
                }
            }
        }
        sharedWithView = view.findViewById<HorizontalGridView>(R.id.shared_with_me).apply {
            adapter = sharedWithAdapter

            onUnhandledKeyListener = object : BaseGridView.OnUnhandledKeyListener {
                override fun onUnhandledKey(event: KeyEvent): Boolean {
                    if (event.action == KeyEvent.ACTION_UP && event.keyCode in arrayOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                        sharedWithView.findContainingViewHolder(sharedWithView.focusedChild)?.bindingAdapterPosition?.let { position ->
                            parentFragmentManager.beginTransaction().replace(R.id.container_root, TVSliderFragment.newInstance(null, sharedWithAdapter.currentList[position]), TVSliderFragment::class.java.canonicalName).addToBackStack(null).commit()
                        }
                        return true
                    }

                    return false
                }
            }
        }
        categoryScrollView = view.findViewById<NoAutoScrollScrollView>(R.id.scroller).apply {
            doOnLayout { deltaToBottom = categoryScrollView.getChildAt(0).height - categoryScrollView.height }
        }

        fadeInPoster.playTogether(ObjectAnimator.ofFloat(featureImageView, View.ALPHA, 0.5f, 1.0f).setDuration(300), ObjectAnimator.ofFloat(titleContainerView, View.ALPHA, 0.5f, 1.0f).setDuration(300))
        fadeOutTitle = ObjectAnimator.ofFloat(titleContainerView, View.ALPHA, 1.0f, 0.0f).setDuration(50)

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { albumsModel.allAlbumsByEndDate.collect { albums -> myAlbumsAdapter.submitList(albums) }}
                launch { imageLoaderViewModel.shareWithMe.collect { shares ->
                    sharedWithAdapter.submitList(shares) {
                        sharedWithMeTitleView.isEnabled = true
                        sharedWithView.visibility = View.VISIBLE
                    }
                }}
            }
        }

        parentFragmentManager.setFragmentResultListener(TVSliderFragment.RESULT_REQUEST_KEY, viewLifecycleOwner) { _, result ->
            if (result.getBoolean(TVSliderFragment.KEY_SHARED)) sharedWithView.requestFocus()
        }
    }

    private fun getDarkerColor(color: Int, factor: Float): Int = Color.argb(0xFF,
        255.coerceAtMost((Color.red(color) * factor).roundToInt()),
        255.coerceAtMost((Color.green(color) * factor).roundToInt()),
        255.coerceAtMost((Color.blue(color) * factor).roundToInt()),
    )

    private fun changePoster(rp: NCShareViewModel.RemotePhoto, title: String, subTitle: String) {
        fadeOutTitle.start()

        imageLoaderViewModel.setImagePhoto(
            rp, featureImageView, NCShareViewModel.TYPE_TV_FULL,
            paletteCallBack = { palette ->
                (palette?.getMutedColor(0xFF000000.toInt()) ?: 0xFF000000).toInt().let { color -> getDarkerColor(color, 0.16f).let { tint ->
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) cinematicScrimView.background.setTint(tint)
                    else cinematicScrimView.background.colorFilter = BlendModeColorFilter(tint, BlendMode.SRC_ATOP)
                }}
                (palette?.getLightVibrantColor(primaryTextColor) ?: primaryTextColor).toInt().let { color ->
                    albumTitleView.text = title
                    albumTitleView.setTextColor(color)
                    albumSubTitleView.text = subTitle
                }
            }
        ) { fadeInPoster.start() }
    }

    private fun scrollCategoryArea(y: Int) {
        categoryScrollView.scrollY.let { currentTop ->
            if (y != currentTop) ObjectAnimator.ofInt(categoryScrollView, "scrollY", y).run {
                setDuration(800)
                interpolator = DecelerateInterpolator()
                start()
            }
        }
    }

    private fun zoomCoverView(zoomIn: Boolean, view: View) { view.startAnimation(AnimationUtils.loadAnimation(requireContext(), if (zoomIn) R.anim.tv_cover_zoom_in else R.anim.tv_cover_zoom_out)) }

    class AlbumGridAdapter(private val clickListener: (Album) -> Unit, private val imageLoader: (Album, ImageView) -> Unit, private val onFocusListener: (Int, Boolean, View) -> Unit, private val cancelLoader: (View) -> Unit
    ): ListAdapter<Album, AlbumGridAdapter.AlbumViewHolder>(AlbumDiffCallback()) {
        inner class AlbumViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
            val ivCover: AppCompatImageView = itemView.findViewById<AppCompatImageView>(R.id.coverart).apply { setOnClickListener { clickListener(currentList[bindingAdapterPosition]) } }

            init {
                itemView.findViewById<FocusTrackingConstraintLayout>(R.id.container).apply { setOnFocusChangedListener { focused -> onFocusListener(bindingAdapterPosition, focused, this) }}
            }

            fun bind(album: Album) {
                imageLoader(album, ivCover)
                if (album.syncProgress < 1.0f) ivCover.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(album.syncProgress) }) else ivCover.clearColorFilter()
            }
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlbumViewHolder = AlbumViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_album_tv, parent, false))

        override fun onBindViewHolder(holder: AlbumViewHolder, position: Int) {
            holder.bind(currentList[position])
        }

        override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
            for (i in 0 until currentList.size) { recyclerView.findViewHolderForAdapterPosition(i)?.let { holder -> cancelLoader((holder as AlbumViewHolder).ivCover) }}
            super.onDetachedFromRecyclerView(recyclerView)
        }
    }

    class SharedGridAdapter(private val clickListener: (NCShareViewModel.ShareWithMe) -> Unit, private val imageLoader: (NCShareViewModel.ShareWithMe, AppCompatImageView) -> Unit, private val onFocusListener: (Int, Boolean, View) -> Unit, private val cancelLoader: (View) -> Unit
    ): ListAdapter<NCShareViewModel.ShareWithMe, SharedGridAdapter.SharedItemViewHolder>(ShareDiffCallback()) {
        inner class SharedItemViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
            val ivCover: AppCompatImageView = itemView.findViewById<AppCompatImageView>(R.id.coverart).apply { setOnClickListener { clickListener(currentList[bindingAdapterPosition]) } }

            init {
                itemView.findViewById<FocusTrackingConstraintLayout>(R.id.container).apply { setOnFocusChangedListener { focused -> onFocusListener(bindingAdapterPosition, focused, this) }}
            }

            fun bind(item: NCShareViewModel.ShareWithMe) {
                imageLoader(item, ivCover)
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SharedItemViewHolder = SharedItemViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_album_tv, parent, false))

        override fun onBindViewHolder(holder: SharedItemViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
            for (i in 0 until currentList.size) { recyclerView.findViewHolderForAdapterPosition(i)?.let { holder -> cancelLoader((holder as SharedItemViewHolder).ivCover) }}
            super.onDetachedFromRecyclerView(recyclerView)
        }
    }
}