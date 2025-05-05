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

package site.leos.apps.lespas.publication

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.app.SharedElementCallback
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.MenuProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.transition.MaterialContainerTransform
import com.google.android.material.transition.MaterialElevationScale
import com.google.android.material.transition.MaterialSharedAxis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.Album
import site.leos.apps.lespas.helper.LesPasGetMediaContract
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.helper.Tools.parcelable
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.search.PhotosInMapFragment
import site.leos.apps.lespas.story.StoryFragment
import site.leos.apps.lespas.sync.AcquiringDialogFragment
import site.leos.apps.lespas.sync.SyncAdapter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

class PublicationDetailFragment: Fragment() {
    private lateinit var share: NCShareViewModel.ShareWithMe

    private lateinit var photoListAdapter: PhotoListAdapter
    private lateinit var photoList: RecyclerView

    private val shareModel: NCShareViewModel by activityViewModels()
    private val currentPositionModel: CurrentPublicationViewModel by activityViewModels()

    private var loadingIndicator: MenuItem? = null
    private var showMetaMenuItem: MenuItem? = null
    private var addPhotoMenuItem: MenuItem? = null
    private var searchMenuItem: MenuItem? = null
    private var mapMenuItem: MenuItem? = null
    private var slideshowMenuItem: MenuItem? = null

    private var currentItem = -1

    private lateinit var addFileLauncher: ActivityResultLauncher<String>

    private var showName = false
    private lateinit var currentQuery: String

    private lateinit var nameFilterBackPressedCallback: OnBackPressedCallback

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        //share = arguments?.getParcelable(ARGUMENT_SHARE)!!
        share = requireArguments().parcelable(ARGUMENT_SHARE)!!
        showName = Tools.isWideListAlbum(share.sortOrder)

        savedInstanceState?.apply {
            currentItem = getInt(KEY_CURRENT_ITEM)
        }

        photoListAdapter = PhotoListAdapter(
            { view, mediaList, position->
                currentItem = position

                if (showName) {
                    with(photoList.layoutManager as GridLayoutManager) {
                        currentPositionModel.saveCurrentRange(findFirstVisibleItemPosition(), findLastVisibleItemPosition())
                    }
                } else {
                    with(photoList.layoutManager as StaggeredGridLayoutManager) {
                        currentPositionModel.saveCurrentRange(findFirstVisibleItemPositions(null)[0], findLastVisibleItemPositions(null)[spanCount - 1])
                    }
                }

                if (photoListAdapter.currentList[position].photo.mimeType.startsWith("video")) {
                    // Transition to surface view might crash some OEM phones, like Xiaomi
                    parentFragmentManager.beginTransaction()
                        .replace(R.id.container_root, RemoteMediaFragment.newInstance(mediaList, position, share.albumId), RemoteMediaFragment::class.java.canonicalName)
                        .addToBackStack(null)
                        .commit()
                } else {
                    reenterTransition = MaterialElevationScale(false).apply { duration = resources.getInteger(android.R.integer.config_mediumAnimTime).toLong() }
                    exitTransition = MaterialElevationScale(false).apply {
                        duration = resources.getInteger(android.R.integer.config_mediumAnimTime).toLong()
                        //excludeTarget(view, true)
                        //excludeTarget(android.R.id.statusBarBackground, true)
                        //excludeTarget(android.R.id.navigationBarBackground, true)
                    }

                    parentFragmentManager.beginTransaction()
                        .setReorderingAllowed(true)
                        .addSharedElement(view, view.transitionName)
                        .replace(R.id.container_root, RemoteMediaFragment.newInstance(mediaList, position, share.albumId), RemoteMediaFragment::class.java.canonicalName)
                        .addToBackStack(null)
                        .commit()
                }
            },
            { photo, view-> shareModel.setImagePhoto(photo, view, NCShareViewModel.TYPE_GRID) { startPostponedEnterTransition() }},
            { view-> shareModel.cancelSetImagePhoto(view) }
        ).apply {
            stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            if (savedInstanceState?.run { getBoolean(KEY_SHOW_META, false) } == true) {
                toggleMetaDisplay()
            }
        }

        sharedElementEnterTransition = MaterialContainerTransform().apply {
            duration = resources.getInteger(android.R.integer.config_shortAnimTime).toLong()
            scrimColor = Color.TRANSPARENT
        }

        // Adjusting the shared element mapping
        setExitSharedElementCallback(object : SharedElementCallback() {
            override fun onMapSharedElements(names: MutableList<String>?, sharedElements: MutableMap<String, View>?) {
                if (names?.isNotEmpty() == true) photoList.findViewHolderForAdapterPosition(currentItem)?.let {
                    sharedElements?.put(names[0], it.itemView.findViewById(R.id.photo))
                }
            }
        })

        addFileLauncher = registerForActivityResult(LesPasGetMediaContract(arrayOf("image/*", "video/*"))) { uris ->
            if (uris.isNotEmpty()) {
                parentFragmentManager.findFragmentByTag(TAG_ACQUIRING_DIALOG) ?: run {
                    AcquiringDialogFragment.newInstance(
                        uris as ArrayList<Uri>,
                        //Album(JOINT_ALBUM_ID, share.sharePath, LocalDateTime.MIN, LocalDateTime.MAX, "", 0, 0, 0, LocalDateTime.now(), 0, share.albumId, 0, 1F),
                        Album(
                            lastModified = LocalDateTime.now(),
                            id = Album.JOINT_ALBUM_ID, name = share.albumName,
                            coverFileName = "${share.sharePath}/",
                            eTag = share.albumId,
                        ),
                        false
                    ).show(parentFragmentManager, TAG_ACQUIRING_DIALOG)
                }
            }
        }

        nameFilterBackPressedCallback = object: OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                searchMenuItem?.run {
                    (actionView as SearchView).setQuery("", false)
                    collapseActionView()
                }
                currentQuery = ""
                currentPositionModel.saveCurrentQuery(currentQuery)

                isEnabled = false
            }
        }
        if (showName) requireActivity().onBackPressedDispatcher.addCallback(this, nameFilterBackPressedCallback)

        // Get publication photo list and possibly BGM here instead of onViewCreated to avoid redundant fetching when getting back from RemoteMediaFragment or StoryFragment
        // TODO forceNetwork is alway true here, should try saving some traffic later
        lifecycleScope.launch(Dispatchers.IO) { shareModel.getRemotePhotoList(share, true) }

    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val vg = inflater.inflate(R.layout.fragment_publication_detail, container, false)

        currentQuery = currentPositionModel.getLastQuery()

        photoList = vg.findViewById<RecyclerView>(R.id.photo_list).apply {
            val defaultSpanCount: Int
            if (showName) {
                defaultSpanCount = 2
                layoutManager = GridLayoutManager(requireContext(), defaultSpanCount)
                photoListAdapter.setShowName(true)
            } else {
                defaultSpanCount = resources.getInteger(R.integer.publication_detail_grid_span_count)
                layoutManager = StaggeredGridLayoutManager(defaultSpanCount, StaggeredGridLayoutManager.VERTICAL).apply { gapStrategy = StaggeredGridLayoutManager.GAP_HANDLING_MOVE_ITEMS_BETWEEN_SPANS }
            }

            adapter = photoListAdapter
            photoListAdapter.setOverlayDrawable(
                Tools.getPlayMarkDrawable(requireActivity(), 0.25f / defaultSpanCount),
                Tools.getPanoramaMarkDrawable(requireActivity(), 0.25f / defaultSpanCount)
            )


            // Avoid window inset overlapping
            ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
                val displayCutoutInset = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
                val navigationBarInset = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
                v.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    rightMargin = displayCutoutInset.right + navigationBarInset.right
                    leftMargin = displayCutoutInset.left + navigationBarInset.left
                }
                insets
            }
        }

        return vg
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { currentPositionModel.currentPosition.collect { currentItem = it } }
                launch {
                    shareModel.publicationContentMeta.collect {
                        if (it.isNotEmpty()) {
                            photoListAdapter.setList(it, currentQuery) {
                                // Setup UI in this submitList commitCallback
                                loadingIndicator?.run {
                                    isEnabled = false
                                    isVisible = false
                                }
                                slideshowMenuItem?.run {
                                    isEnabled = true
                                    isVisible = true
                                }
                                (if (showName) searchMenuItem else showMetaMenuItem)?.run {
                                    isVisible = true
                                    isEnabled = true
                                }
                                if (share.permission == NCShareViewModel.PERMISSION_JOINT) addPhotoMenuItem?.run {
                                    isVisible = true
                                    isEnabled = true
                                }

                                it.forEach { remotePhoto ->
                                    if (remotePhoto.photo.mimeType.startsWith("image/") && remotePhoto.photo.latitude != Photo.NO_GPS_DATA) {
                                        mapMenuItem?.isVisible = true
                                        mapMenuItem?.isEnabled = true

                                        return@setList
                                    }
                                }
                            }

                            if (currentItem != -1) with(currentPositionModel.getLastRange()) {
                                if (currentItem < this.first || currentItem > this.second) (photoList.layoutManager as RecyclerView.LayoutManager).scrollToPosition(currentItem)
                            }
                        }
                    }
                }
            }
        }

        if (currentItem != -1 && photoListAdapter.itemCount > 0) postponeEnterTransition()

        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
                inflater.inflate(R.menu.publication_detail_menu, menu)

                loadingIndicator = menu.findItem(R.id.option_menu_search_progress)
                (loadingIndicator?.actionView?.findViewById(R.id.search_progress) as CircularProgressIndicator).run {
                    isIndeterminate = true
                    show()
                }
                slideshowMenuItem = menu.findItem(R.id.option_menu_slideshow)
                addPhotoMenuItem = menu.findItem(R.id.option_menu_add_photo)
                mapMenuItem = menu.findItem(R.id.option_menu_in_map)
                showMetaMenuItem = menu.findItem(R.id.option_menu_show_meta).apply {
                    icon = ContextCompat.getDrawable(requireContext(), if (photoListAdapter.isMetaDisplayed()) R.drawable.ic_baseline_meta_on_24 else R.drawable.ic_baseline_meta_off_24)
                }
                searchMenuItem = menu.findItem(R.id.option_menu_search)
                searchMenuItem?.apply {
                    (actionView as SearchView).run {
                        // When resume from device rotation
                        if (currentQuery.isNotEmpty()) {
                            searchMenuItem?.expandActionView()
                            setQuery(currentQuery, false)
                            nameFilterBackPressedCallback.isEnabled = true
                        }

                        queryHint = getString(R.string.option_menu_search)

                        setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                            override fun onQueryTextSubmit(query: String?): Boolean = true
                            override fun onQueryTextChange(newText: String?): Boolean {
                                (newText ?: "").let { query ->
                                    photoListAdapter.filter(query)
                                    currentQuery = query
                                }
                                return true
                            }
                        })

                        setOnCloseListener {
                            nameFilterBackPressedCallback.isEnabled = false
                            false
                        }

                        findViewById<EditText>(androidx.appcompat.R.id.search_src_text)?.apply {
                            ContextCompat.getColor(requireContext(), R.color.lespas_white).let {
                                setTextColor(it)
                                setHintTextColor(ColorUtils.setAlphaComponent(it, 0xA0))
                            }
                        }
                    }
                }

                if (photoListAdapter.currentList.isNotEmpty()) {
                    loadingIndicator?.isEnabled = false
                    loadingIndicator?.isVisible = false

                    slideshowMenuItem?.isVisible = true
                    slideshowMenuItem?.isEnabled = true

                    if (showName) {
                        searchMenuItem?.isVisible = true
                        searchMenuItem?.isEnabled = true
                    } else {
                        showMetaMenuItem?.isEnabled = true
                        showMetaMenuItem?.isVisible = true
                    }

                    if (share.permission == NCShareViewModel.PERMISSION_JOINT) {
                        addPhotoMenuItem?.isEnabled = true
                        addPhotoMenuItem?.isVisible = true
                    }

                    run map@{
                        mutableListOf<NCShareViewModel.RemotePhoto>().apply { addAll(photoListAdapter.currentList) }.forEach {
                            if (it.photo.mimeType.startsWith("image/") && it.photo.latitude != Photo.NO_GPS_DATA) {
                                mapMenuItem?.isEnabled = true
                                mapMenuItem?.isVisible = true

                                return@map
                            }
                        }
                    }
                }
            }

            override fun onMenuItemSelected(item: MenuItem): Boolean =
                when(item.itemId) {
                    R.id.option_menu_show_meta-> {
                        photoListAdapter.toggleMetaDisplay()
                        item.icon = ContextCompat.getDrawable(requireContext(), if (photoListAdapter.isMetaDisplayed()) R.drawable.ic_baseline_meta_on_24 else R.drawable.ic_baseline_meta_off_24)
                        true
                    }
                    R.id.option_menu_add_photo-> {
                        addFileLauncher.launch("*/*")
                        true
                    }
                    R.id.option_menu_in_map-> {
                        reenterTransition = MaterialSharedAxis(MaterialSharedAxis.Z, false).apply { duration = resources.getInteger(android.R.integer.config_longAnimTime).toLong() }
                        exitTransition = MaterialSharedAxis(MaterialSharedAxis.Z, true).apply { duration = resources.getInteger(android.R.integer.config_longAnimTime).toLong() }
                        parentFragmentManager.beginTransaction().replace(R.id.container_root, PhotosInMapFragment.newInstance(
                            Album(
                                id = share.albumId,
                                name = share.albumName,
                                eTag = Photo.ETAG_FAKE,
                                shareId = Album.REMOTE_ALBUM,
                                lastModified = LocalDateTime.MIN,
                                bgmId = "${shareModel.getResourceRoot()}${share.sharePath}/${SyncAdapter.BGM_FILENAME_ON_SERVER}",
                            ),
                            Tools.getPhotosWithCoordinate(
                                mutableListOf<Photo>().apply { photoListAdapter.currentList.forEach { add(it.photo) }},
                                PreferenceManager.getDefaultSharedPreferences(requireContext()).getBoolean(getString(R.string.nearby_convergence_pref_key), true),
                                share.sortOrder
                            )
                        ), PhotosInMapFragment::class.java.canonicalName).addToBackStack(null).commit()
                        true
                    }
                    R.id.option_menu_slideshow-> {
                        reenterTransition = MaterialSharedAxis(MaterialSharedAxis.Z, false).apply { duration = resources.getInteger(android.R.integer.config_longAnimTime).toLong() }
                        exitTransition = MaterialSharedAxis(MaterialSharedAxis.Z, true).apply { duration = resources.getInteger(android.R.integer.config_longAnimTime).toLong() }
                        parentFragmentManager.beginTransaction().replace(R.id.container_root, StoryFragment.newInstance(
                            Album(
                                id = share.albumId,
                                name = share.albumName,
                                eTag = Photo.ETAG_FAKE,
                                shareId = Album.REMOTE_ALBUM,
                                lastModified = LocalDateTime.MIN,
                                bgmId = "${shareModel.getResourceRoot()}${share.sharePath}/${SyncAdapter.BGM_FILENAME_ON_SERVER}"
                            )
                        ), StoryFragment::class.java.canonicalName).addToBackStack(null).commit()
                        true
                    }
                    R.id.option_menu_search-> {
                        nameFilterBackPressedCallback.isEnabled = true
                        false
                    }
                    else-> false
                }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    @SuppressLint("InflateParams")
    override fun onResume() {
        super.onResume()

        val titleView = layoutInflater.inflate(R.layout.textview_actionbar_title_with_icon, null)
        titleView.findViewById<TextView>(R.id.title).apply {
            text = share.albumName
            shareModel.getAvatar(NCShareViewModel.Sharee(share.shareBy, share.shareByLabel, NCShareViewModel.SHARE_TYPE_USER), this, null)
            compoundDrawablePadding = context.resources.getDimension(R.dimen.small_padding).toInt()
        }
        (requireActivity() as AppCompatActivity).supportActionBar?.run {
            customView = titleView
            displayOptions = androidx.appcompat.app.ActionBar.DISPLAY_HOME_AS_UP or androidx.appcompat.app.ActionBar.DISPLAY_SHOW_CUSTOM
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_CURRENT_ITEM, currentItem)
        outState.putBoolean(KEY_SHOW_META, photoListAdapter.isMetaDisplayed())
    }

    override fun onStop() {
        currentPositionModel.saveCurrentQuery(currentQuery)
        super.onStop()
    }

    override fun onDestroyView() {
        photoList.adapter = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        (requireActivity() as AppCompatActivity).supportActionBar?.run {
            displayOptions = androidx.appcompat.app.ActionBar.DISPLAY_HOME_AS_UP or androidx.appcompat.app.ActionBar.DISPLAY_SHOW_TITLE
            customView = null
        }

        super.onDestroy()
    }

    class PhotoListAdapter(private val clickListener: (ImageView, List<NCShareViewModel.RemotePhoto>, Int) -> Unit, private val imageLoader: (NCShareViewModel.RemotePhoto, ImageView) -> Unit, private val cancelLoading: (View) -> Unit
    ): ListAdapter<NCShareViewModel.RemotePhoto, PhotoListAdapter.ViewHolder>(PhotoDiffCallback()) {
        private val pList = mutableListOf<NCShareViewModel.RemotePhoto>()
        private val mBoundViewHolders = mutableSetOf<ViewHolder>()
        private var showName = false
        private var displayMeta = false
        private var playMark: Drawable? = null
        private var panoramaMark: Drawable? = null

        inner class ViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
            private var currentPhotoId = ""
            private val ivPhoto = itemView.findViewById<ImageView>(R.id.photo).apply { foregroundGravity = Gravity.CENTER }
            private val tvMeta = itemView.findViewById<TextView>(R.id.meta)
            private val tvName = itemView.findViewById<TextView>(R.id.title)

            fun bind(item: NCShareViewModel.RemotePhoto) {
                ivPhoto.apply {
                    if (currentPhotoId != item.photo.id) {
                        imageLoader(item, this)
                        ViewCompat.setTransitionName(this, item.photo.id)
                        currentPhotoId = item.photo.id
                        if (!showName) {
                            (itemView as ConstraintLayout).let {
                                ConstraintSet().apply {
                                    clone(it)
                                    setDimensionRatio(R.id.photo, "H,${item.photo.width}:${item.photo.height}")
                                    applyTo(it)
                                }
                            }
                        }
                    }
                    foreground = when {
                        Tools.isMediaPlayable(item.photo.mimeType) -> playMark
                        item.photo.mimeType == Tools.PANORAMA_MIMETYPE -> panoramaMark
                        else -> null
                    }
                    setOnClickListener { clickListener(this, currentList, currentList.indexOf(item)) }
                }

                tvName?.text = item.photo.name.substringBeforeLast('.')

                tvMeta?.apply {
                    text = String.format("%s, %s", item.photo.dateTaken.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()), item.photo.dateTaken.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)))
                    visibility = if (displayMeta) View.VISIBLE else View.GONE
                }
            }

            fun toggleMeta() {
                tvMeta?.visibility = if (displayMeta) View.VISIBLE else View.GONE
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoListAdapter.ViewHolder =
            ViewHolder(LayoutInflater.from(parent.context).inflate(if (showName) R.layout.recyclerview_item_photo_wide else R.layout.recyclerview_item_remote_media, parent, false))

        override fun onBindViewHolder(holder: PhotoListAdapter.ViewHolder, position: Int) {
            holder.bind(getItem(position))
            mBoundViewHolders.add(holder)
        }

        override fun onViewRecycled(holder: ViewHolder) {
            mBoundViewHolders.remove(holder)
            cancelLoading(holder.itemView.findViewById(R.id.photo) as View)
            super.onViewRecycled(holder)
        }

        override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
            for (i in 0 until currentList.size) recyclerView.findViewHolderForAdapterPosition(i)?.let { holder -> holder.itemView.findViewById<View>(R.id.photo)?.let { cancelLoading(it) }}
            super.onDetachedFromRecyclerView(recyclerView)
        }

        fun setList(list: List<NCShareViewModel.RemotePhoto>, query: String, callback: Runnable) {
            pList.clear()
            pList.addAll(list)
            submitList(pList.filter { it.photo.name.contains(query) }.toMutableList(), callback)
        }
        fun setShowName(showName: Boolean) { this.showName = showName }
        fun filter(query: String) { submitList(pList.filter { it.photo.name.contains(query, true) }.toMutableList()) }

        fun isMetaDisplayed(): Boolean = displayMeta
        fun toggleMetaDisplay() {
            displayMeta = !displayMeta
            for (holder in mBoundViewHolders) holder.toggleMeta()
        }

        fun setOverlayDrawable(playMark: Drawable, panoramaMark: Drawable) {
            this.playMark = playMark
            this.panoramaMark = panoramaMark
        }
    }

    class PhotoDiffCallback: DiffUtil.ItemCallback<NCShareViewModel.RemotePhoto>() {
        override fun areItemsTheSame(oldItem: NCShareViewModel.RemotePhoto, newItem: NCShareViewModel.RemotePhoto): Boolean = oldItem.photo.id == newItem.photo.id
        override fun areContentsTheSame(oldItem: NCShareViewModel.RemotePhoto, newItem: NCShareViewModel.RemotePhoto): Boolean = oldItem.photo.id == newItem.photo.id
    }

    class CurrentPublicationViewModel: ViewModel() {
/*
        private val currentPosition = SingleLiveEvent<Int>()
        fun setCurrentPosition(pos: Int) { currentPosition.value = pos }
        fun getCurrentPosition(): SingleLiveEvent<Int> = currentPosition
        fun getCurrentPositionValue(): Int = currentPosition.value ?: -1
*/
        private val _currentPosition = MutableStateFlow(-1)
        val currentPosition: StateFlow<Int> = _currentPosition
        fun setCurrentPosition(position: Int) { _currentPosition.tryEmit(position) }

        private var firstItem = -1
        private var lastItem = -1
        fun saveCurrentRange(start: Int, end: Int) {
            firstItem = start
            lastItem = end
        }
        fun getLastRange(): Pair<Int, Int> = Pair(firstItem, lastItem)

        private var currentQuery = ""
        fun saveCurrentQuery(query: String) { currentQuery = query }
        fun getLastQuery() = currentQuery
    }

    companion object {
        private const val TAG_ACQUIRING_DIALOG = "JOINT_ALBUM_ACQUIRING_DIALOG"

        private const val ARGUMENT_SHARE = "ARGUMENT_SHARE"

        private const val KEY_CURRENT_ITEM = "KEY_CURRENT_ITEM"
        private const val KEY_SHOW_META = "KEY_SHOW_META"

        @JvmStatic
        fun newInstance(share: NCShareViewModel.ShareWithMe) = PublicationDetailFragment().apply { arguments = Bundle().apply { putParcelable(ARGUMENT_SHARE, share) }}
    }
}