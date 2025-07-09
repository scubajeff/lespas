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

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.provider.MediaStore
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.BounceInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.core.app.SharedElementCallback
import androidx.core.content.ContextCompat
import androidx.core.text.isDigitsOnly
import androidx.core.view.MenuProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.forEach
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceManager
import androidx.recyclerview.selection.ItemDetailsLookup
import androidx.recyclerview.selection.ItemKeyProvider
import androidx.recyclerview.selection.SelectionTracker
import androidx.recyclerview.selection.StorageStrategy
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.Fade
import androidx.transition.TransitionManager
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.transition.MaterialElevationScale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.ConfirmDialogFragment
import site.leos.apps.lespas.helper.LesPasEmptyView
import site.leos.apps.lespas.helper.LesPasFastScroller
import site.leos.apps.lespas.helper.ShareOutDialogFragment
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.publication.NCShareViewModel
import site.leos.apps.lespas.sync.ActionViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.min

class GalleryFolderViewFragment : Fragment(), ActionMode.Callback {
    private lateinit var mediaAdapter: MediaAdapter
    private lateinit var mediaList: RecyclerView
    private lateinit var subFolderChipGroup: ChipGroup
    private lateinit var chipForAll: Chip
    private lateinit var yearIndicator: TextView
    private var actionMode: ActionMode? = null
    private lateinit var selectionTracker: SelectionTracker<String>
    private lateinit var selectionBackPressedCallback: OnBackPressedCallback
    private var spanCount = 0
    private lateinit var folderArgument: String

    private val actionModel: ActionViewModel by viewModels(ownerProducer = { requireParentFragment() })
    private val imageLoaderModel: NCShareViewModel by activityViewModels()
    private val galleryModel: GalleryFragment.GalleryViewModel by viewModels(ownerProducer = { requireParentFragment() }) { GalleryFragment.GalleryViewModelFactory(requireActivity(), imageLoaderModel, actionModel) }

    private val currentMediaList = mutableListOf<GalleryFragment.GalleryMedia>()

    private var currentCheckedTag = CHIP_FOR_ALL_TAG

    private var actionModeTitleUpdateJob: Job? = null

    private var downloadMenuItem: MenuItem? = null
    private var uploadMenuItem: MenuItem? = null

    private var selectionPendingRestored = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        folderArgument = requireArguments().getString(ARGUMENT_FOLDER) ?: GalleryFragment.ALL_FOLDER

        mediaAdapter = MediaAdapter(
            getString(R.string.today), getString(R.string.yesterday),
            { view, photoId, mimeType ->
                if (galleryModel.isPicker()) {
                    selectionTracker.select(photoId)
                } else {
                    galleryModel.setCurrentPhotoId(photoId)

                    if (mimeType.startsWith("video")) {
                        // Transition to surface view might crash some OEM phones, like Xiaomi
                        parentFragmentManager.beginTransaction().replace(R.id.container_child_fragment, GallerySlideFragment.newInstance(folderArgument, currentCheckedTag), GallerySlideFragment::class.java.canonicalName).addToBackStack(null).commit()
                    } else {
                        reenterTransition = MaterialElevationScale(false).apply {
                            duration = resources.getInteger(android.R.integer.config_mediumAnimTime).toLong()
                            //excludeTarget(view, true)
                        }
                        exitTransition = MaterialElevationScale(false).apply {
                            duration = resources.getInteger(android.R.integer.config_mediumAnimTime).toLong()
                            //excludeTarget(view, true)
                            //excludeTarget(android.R.id.statusBarBackground, true)
                            //excludeTarget(android.R.id.navigationBarBackground, true)
                        }

                        parentFragmentManager.beginTransaction()
                            .setReorderingAllowed(true)
                            .addSharedElement(view, view.transitionName)
                            .replace(R.id.container_child_fragment, GallerySlideFragment.newInstance(folderArgument, currentCheckedTag), GallerySlideFragment::class.java.canonicalName)
                            .addToBackStack(null)
                            .commit()
                    }
                }
            },
            { photo, imageView -> imageLoaderModel.setImagePhoto(photo, imageView, NCShareViewModel.TYPE_GRID) { startPostponedEnterTransition() }},
            { view -> imageLoaderModel.cancelSetImagePhoto(view) },
        ).apply { stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY }

        selectionBackPressedCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                if (selectionTracker.hasSelection()) {
                    selectionTracker.clearSelection()
                }
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(this, selectionBackPressedCallback)

        // Adjusting the shared element mapping
        setExitSharedElementCallback(object : SharedElementCallback() {
            override fun onMapSharedElements(names: MutableList<String>?, sharedElements: MutableMap<String, View>?) {
                galleryModel.getCurrentPhotoId().let { photoId ->
                    if (photoId.isNotEmpty() && names?.isNotEmpty() == true) mediaList.findViewHolderForAdapterPosition(mediaAdapter.getPhotoPosition(photoId))?.let { viewHolder ->
                        sharedElements?.put(names[0], viewHolder.itemView.findViewById(R.id.photo))
                    }
                }
            }
        })
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? = inflater.inflate(R.layout.fragment_gallery_folder, container, false)
    @SuppressLint("InflateParams")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        postponeEnterTransition()

        chipForAll = view.findViewById(R.id.chip_for_all)
        currentCheckedTag = galleryModel.getCurrentSubFolder()
        subFolderChipGroup = view.findViewById<ChipGroup>(R.id.sub_chips).apply {
            if (folderArgument == GalleryFragment.TRASH_FOLDER) isVisible = false

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

        yearIndicator = view.findViewById<TextView>(R.id.year_indicator).apply {
            doOnLayout {
                background = MaterialShapeDrawable().apply {
                    fillColor = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.color_error))
                    shapeAppearanceModel = ShapeAppearanceModel.builder().setTopLeftCorner(CornerFamily.CUT, yearIndicator.height.toFloat()).build()
                }
            }

            // Avoid window inset overlapping
            ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
                v.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    insets.getInsets(WindowInsetsCompat.Type.navigationBars()).let { navbar ->
                        bottomMargin = navbar.bottom
                        rightMargin = navbar.right + insets.getInsets(WindowInsetsCompat.Type.displayCutout()).right
                    }
                }
                insets
            }
        }
        mediaAdapter.setMarks(galleryModel.getPlayMark(), galleryModel.getSelectedMark(), galleryModel.getPanoramaMark())
        mediaList = view.findViewById<RecyclerView>(R.id.gallery_list).apply {
            adapter = mediaAdapter

            itemAnimator = null     // Disable recyclerview item animation to avoid ANR in AdapterHelper.findPositionOffset() when DiffResult applying at the moment that the list is scrolling

            spanCount = resources.getInteger(R.integer.cameraroll_grid_span_count)
            (layoutManager as GridLayoutManager).spanSizeLookup = object: GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    return if (mediaAdapter.getItemViewType(position) == MediaAdapter.TYPE_DATE) spanCount else 1
                }
            }

            selectionTracker = SelectionTracker.Builder(
                "galleryFolderFragmentSelection",
                this,
                MediaAdapter.PhotoKeyProvider(mediaAdapter),
                MediaAdapter.PhotoDetailsLookup(this),
                StorageStrategy.createStringStorage()
            ).withSelectionPredicate(object: SelectionTracker.SelectionPredicate<String>() {
                override fun canSetStateForKey(key: String, nextState: Boolean): Boolean {
                    return when {
                        galleryModel.isPreparingShareOut() -> false                                     // Can't select when sharing out
                        key.isEmpty() -> false                                                          // Empty space in list
                        key.startsWith("content") -> true                                        // Normal media items in device gallery
                        key.isDigitsOnly() -> true                                                      // fileId for archived items
                        else -> {                                                                       // Date items, select or deselect photos in the same day when user click on Date item
                            val startPos = mediaAdapter.getPhotoPosition(key) + 1   // There is at least one photos in this date
                            var endPos = startPos
                            while(endPos != mediaAdapter.currentList.size && mediaAdapter.currentList[endPos].location != GalleryFragment.GalleryMedia.IS_NOT_MEDIA) { endPos ++ }

                            // Enable selected state ping-pong effect, the selection state will be set true if the first or last item in this date is not selected
                            // Ideal way is to check all selected state in the date, but that seems to take a long time when there are a lot photos in the date
                            selectionTracker.setItemsSelected(mediaAdapter.currentList.subList(startPos, endPos).map { it.media.photo.id }, !selectionTracker.isSelected(mediaAdapter.currentList[endPos - 1].media.photo.id) || !selectionTracker.isSelected(mediaAdapter.currentList[startPos].media.photo.id))

                            false
                        }
                    }
                }
                override fun canSetStateAtPosition(position: Int, nextState: Boolean): Boolean = !galleryModel.isPreparingShareOut() && position > 0
                override fun canSelectMultiple(): Boolean = !galleryModel.isPicker()
            }).build()
            selectionTracker.addObserver(object : SelectionTracker.SelectionObserver<String>() {
                override fun onSelectionChanged() {
                    super.onSelectionChanged()
                    updateUI()
                }

                override fun onSelectionRestored() {
                    super.onSelectionRestored()
                    updateUI()
                }

                private fun updateUI() {
                    if (galleryModel.isPicker()) {
                        galleryModel.setPickedId(if (selectionTracker.hasSelection()) selectionTracker.selection.first() else "")
                    } else {
                        if (selectionTracker.hasSelection() && actionMode == null) {
                            actionMode = (requireActivity() as AppCompatActivity).startSupportActionMode(this@GalleryFolderViewFragment)
                            selectionBackPressedCallback.isEnabled = true
                        } else if (!(selectionTracker.hasSelection()) && actionMode != null) {
                            actionMode?.subtitle = ""
                            actionMode?.finish()
                            actionMode = null
                            selectionBackPressedCallback.isEnabled = false
                        }

                        // Update UI
                        actionModeTitleUpdateJob?.cancel()
                        actionModeTitleUpdateJob = lifecycleScope.launch {
                            selectionTracker.selection.size().let { selectionSize ->
                                actionMode?.let {
                                    delay(100)
                                    var totalSize = 0L
                                    selectionTracker.selection.forEach { selected ->
                                        ensureActive()
                                        totalSize += mediaAdapter.getFileSize(selected)
                                    }

                                    it.title = resources.getQuantityString(R.plurals.selected_count, selectionSize, selectionSize)
                                    it.subtitle = Tools.humanReadableByteCountSI(totalSize)
                                }

                                // Enable or disable sub folder chips base on selection mode
                                (selectionSize <= 0).let { state -> subFolderChipGroup.forEach { it.isClickable = state } }
                            }
                        }
                    }
                }
            })
            mediaAdapter.setSelectionTracker(selectionTracker)

            addOnScrollListener(object: RecyclerView.OnScrollListener() {
                private val hideHandler = Handler(Looper.getMainLooper())
                private val hideDateIndicator = kotlinx.coroutines.Runnable {
                    TransitionManager.beginDelayedTransition(mediaList.parent as ViewGroup, Fade().apply { duration = 800 })
                    yearIndicator.visibility = View.GONE
                }

                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)

                    if (dx == 0 && dy == 0) {
                        // First entry or fragment resume false call, by layout re-calculation, hide dataIndicator
                        yearIndicator.isVisible = false

                        // Convenient place to catch events triggered by scrollToPosition
                        if (flashDateId.isNotEmpty()) {
                            flashDate(mediaList.findViewWithTag(flashDateId))
                            flashDateId = ""
                        }
                    } else {
                        (recyclerView.layoutManager as GridLayoutManager).run {
                            if ((findLastCompletelyVisibleItemPosition() < mediaAdapter.itemCount - 1) || (findFirstCompletelyVisibleItemPosition() > 0)) {
                                hideHandler.removeCallbacksAndMessages(null)
                                yearIndicator.let {
                                    it.text = mediaAdapter.currentList[findLastVisibleItemPosition()].media.photo.lastModified.format(DateTimeFormatter.ofPattern("MMM uuuu"))
                                    it.isVisible = true
                                }
                                hideHandler.postDelayed(hideDateIndicator, 1500)
                            }
                        }
                    }
                }
            })

            addItemDecoration(LesPasEmptyView(ContextCompat.getDrawable(this.context, R.drawable.ic_baseline_device_24)!!))

            addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(v: View?, left: Int, top: Int, right: Int, bottom: Int, oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int) {
                    mediaList.removeOnLayoutChangeListener(this)
                    val position = mediaAdapter.getPhotoPosition(galleryModel.getCurrentPhotoId())
                    if (position >= 0) mediaList.layoutManager?.let { layoutManager ->
                        layoutManager.findViewByPosition(position).let { view ->
                            if (view == null || layoutManager.isViewPartiallyVisible(view, false, true)) mediaList.post {
                                layoutManager.scrollToPosition(if (position < (layoutManager as GridLayoutManager).findLastCompletelyVisibleItemPosition()) position else min(mediaAdapter.currentList.size - 1, position + spanCount))
                            }
                        }
                    }
                }
            })

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

        LesPasFastScroller(
            mediaList,
            ContextCompat.getDrawable(mediaList.context, R.drawable.fast_scroll_thumb) as StateListDrawable, ContextCompat.getDrawable(mediaList.context, R.drawable.fast_scroll_track)!!,
            ContextCompat.getDrawable(mediaList.context, R.drawable.fast_scroll_thumb) as StateListDrawable, ContextCompat.getDrawable(mediaList.context, R.drawable.fast_scroll_track)!!,
            resources.getDimensionPixelSize(R.dimen.fast_scroll_thumb_width), 0, 0, resources.getDimensionPixelSize(R.dimen.fast_scroll_thumb_height)
        )

        parentFragmentManager.setFragmentResultListener(GalleryDeletionDialogFragment.GALLERY_DELETION_DIALOG_RESULT_KEY, viewLifecycleOwner) { _, bundle ->
            if (bundle.getBoolean(GalleryDeletionDialogFragment.GALLERY_DELETION_DIALOG_RESULT_KEY)) galleryModel.remove(getSelectedPhotos(), removeLocal = bundle.getBoolean(GalleryDeletionDialogFragment.DELETE_LOCAL_RESULT_KEY), removeArchive = bundle.getBoolean(GalleryDeletionDialogFragment.DELETE_REMOTE_RESULT_KEY))
        }

        parentFragmentManager.setFragmentResultListener(GALLERY_FOLDERVIEW_REQUEST_KEY, viewLifecycleOwner) { _, bundle ->
            when (bundle.getString(ConfirmDialogFragment.INDIVIDUAL_REQUEST_KEY)) {
                EMPTY_TRASH_REQUEST_KEY -> if (bundle.getBoolean(ConfirmDialogFragment.CONFIRM_DIALOG_RESULT_KEY, false)) galleryModel.emptyTrash(arrayListOf<String>().apply { mediaAdapter.getAllItems().forEach { add(it.media.photo.id) }})
            }
        }

        // Share out dialog result handler
        parentFragmentManager.setFragmentResultListener(ShareOutDialogFragment.SHARE_OUT_DIALOG_RESULT_KEY, viewLifecycleOwner) { _, bundle ->
            if (bundle.getBoolean(ShareOutDialogFragment.SHARE_OUT_DIALOG_RESULT_KEY, true))
                galleryModel.shareOut(
                    photoIds = getSelectedPhotos(),
                    strip = bundle.getBoolean(ShareOutDialogFragment.STRIP_RESULT_KEY, false),
                    lowResolution = bundle.getBoolean(ShareOutDialogFragment.LOW_RESOLUTION_RESULT_KEY, false),
                    removeAfterwards = bundle.getBoolean(ShareOutDialogFragment.REMOVE_AFTERWARDS_RESULT_KEY, false),
                )
            else selectionTracker.clearSelection()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                when (folderArgument) {
                    GalleryFragment.TRASH_FOLDER -> {
                        galleryModel.trash.collect {
                            var theDate: LocalDate
                            val listGroupedByDate = mutableListOf<GalleryFragment.GalleryMedia>()
                            var currentDate = LocalDate.now().plusDays(1)
                            it?.forEach { media ->
                                theDate = media.media.photo.lastModified.toLocalDate()
                                if (theDate != currentDate) {
                                    currentDate = theDate
                                    // Add a fake photo item by taking default value for nearly all properties, denotes a date separator
                                    listGroupedByDate.add(GalleryFragment.GalleryMedia(
                                        location = GalleryFragment.GalleryMedia.IS_NOT_MEDIA,
                                        folder = GalleryFragment.TRASH_FOLDER,
                                        media = NCShareViewModel.RemotePhoto(Photo(id = currentDate.toString(), albumId = GalleryFragment.FROM_DEVICE_GALLERY, dateTaken = media.media.photo.dateTaken, lastModified = media.media.photo.lastModified))
                                    ))
                                }
                                listGroupedByDate.add(media)
                            }

                            if (listGroupedByDate.isEmpty()) parentFragmentManager.popBackStack()
                            else {
                                mediaAdapter.submitList(listGroupedByDate) { savedInstanceState?.let { states ->  selectionTracker.onRestoreInstanceState(states) }}
                                //updateSelectionInfo()
                            }
                        }
                    }
                    GalleryFragment.ALL_FOLDER -> galleryModel.medias.collect { it?.let { prepareList(it, savedInstanceState) }}
                    else -> galleryModel.mediasInFolder(folderArgument).collect { it?.let { prepareList(it, savedInstanceState) }}
                }
            }
        }

        if (!galleryModel.isPicker()) requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.gallery_folder_menu, menu)
            }

            override fun onPrepareMenu(menu: Menu) {
                if (folderArgument == GalleryFragment.TRASH_FOLDER) {
                    menu.findItem(R.id.option_menu_empty_trash)?.isVisible = true
                    menu.findItem(R.id.option_menu_archive).isVisible = false
                    menu.findItem(R.id.option_menu_archive_forced_refresh).isVisible = false
                    menu.findItem(R.id.option_menu_calendar_view).isVisible = false
                    menu.findItem(R.id.option_menu_search_gallery).isVisible = false
                }
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean = when(menuItem.itemId) {
                R.id.option_menu_calendar_view -> {
                    mediaAdapter.dateRange()?.let { dateRange ->
                        MaterialDatePicker.Builder.datePicker()
                            .setCalendarConstraints(CalendarConstraints.Builder().setValidator(object: CalendarConstraints.DateValidator {
                                override fun describeContents(): Int = 0
                                override fun writeToParcel(dest: Parcel, flags: Int) {}
                                override fun isValid(date: Long): Boolean = mediaAdapter.hasDate(date)
                            }).setStart(dateRange.first).setEnd(dateRange.second).setOpenAt(mediaAdapter.getDateByPosition((mediaList.layoutManager as GridLayoutManager).findFirstVisibleItemPosition())).build())
                            .setTheme(R.style.ThemeOverlay_LesPas_DatePicker)
                            .build()
                            .apply {
                                addOnPositiveButtonClickListener { picked ->
                                    val currentBottom = (mediaList.layoutManager as GridLayoutManager).findLastCompletelyVisibleItemPosition()
                                    mediaAdapter.getPositionByDate(picked).let { newPosition ->
                                        mediaList.findViewHolderForAdapterPosition(newPosition)?.itemView?.findViewById<TextView>(R.id.date)?.let { view ->
                                            // new position is visible on screen now
                                            if (newPosition >= currentBottom) mediaList.scrollToPosition( min(mediaAdapter.currentList.size -1, newPosition + spanCount))
                                            flashDate(view)
                                        } ?: run {
                                            // flash the date after scroll finished
                                            flashDateId = mediaAdapter.getPhotoId(newPosition)
                                            mediaList.scrollToPosition(if (newPosition < currentBottom) newPosition else min(mediaAdapter.currentList.size - 1, newPosition + spanCount))
                                        }
                                    }
                                }
                            }.show(parentFragmentManager, null)
                    }
                    true
                }
                R.id.option_menu_empty_trash -> {
                    if (parentFragmentManager.findFragmentByTag(CONFIRM_DIALOG) == null) ConfirmDialogFragment.newInstance(getString(R.string.confirm_empty_trash), positiveButtonText = getString(R.string.yes_delete), individualKey = EMPTY_TRASH_REQUEST_KEY, requestKey = GALLERY_FOLDERVIEW_REQUEST_KEY).show(parentFragmentManager, CONFIRM_DIALOG)
                    true
                }
                else -> false
            }

        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    override fun onResume() {
        super.onResume()

        (requireActivity() as AppCompatActivity).supportActionBar?.run {
            setDisplayHomeAsUpEnabled(true)
            title = requireArguments().getString(ARGUMENT_FOLDER)?.let {
                when(it) {
                    "DCIM" -> getString(R.string.camera_roll_name)
                    GalleryFragment.TRASH_FOLDER -> getString(R.string.trash_name)
                    GalleryFragment.ALL_FOLDER -> ""
                    else -> it
                }
            }
        }
    }

    override fun onPause() {
        galleryModel.saveCurrentSubFolder(currentCheckedTag)
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        try { selectionTracker.onSaveInstanceState(outState) } catch (_: UninitializedPropertyAccessException) {}
/*
        // Because we might need scrolling to a new position when returning from GallerySliderFragment, we have to save current scroll state in this way, though it's not as perfect as layoutManager.onSavedInstanceState
        (mediaList.layoutManager as GridLayoutManager).findFirstVisibleItemPosition().let { position ->
            if (position != RecyclerView.NO_POSITION) galleryModel.setCurrentPhotoId(mediaAdapter.getPhotoId(position))
        }
*/
    }

    override fun onDestroyView() {
        mediaList.clearOnScrollListeners()
        mediaList.adapter = null

        super.onDestroyView()
    }

    override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
        mode?.menuInflater?.inflate(R.menu.action_mode_gallery, menu)

        downloadMenuItem = menu?.findItem(R.id.download_to_device)
        uploadMenuItem = menu?.findItem(R.id.upload_to_archive)

        // When Trash folder is displaying, disable Download/Upload, change "Select All" into "Restore from Trash"
        if (folderArgument == GalleryFragment.TRASH_FOLDER) {
            menu?.findItem(R.id.remove)?.let {
                it.icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_baseline_restore_from_trash_24)
                it.title = getString(R.string.action_undelete)
            }

            downloadMenuItem?.isVisible = false
            uploadMenuItem?.isVisible = false
        }

        return true
    }
    override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean  {
        if (folderArgument != GalleryFragment.TRASH_FOLDER) {
            downloadMenuItem?.apply {
                isEnabled = false
                run breaking@ {
                    selectionTracker.selection.forEach { photoId ->
                        if (mediaAdapter.atRemote(photoId)) {
                            isEnabled = true
                            return@breaking
                        }
                    }
                }
            }

            uploadMenuItem?.apply {
                isEnabled = false
                run breaking@ {
                    selectionTracker.selection.forEach { photoId ->
                        if (mediaAdapter.atLocal(photoId)) {
                            isEnabled = true
                            return@breaking
                        }
                    }
                }
            }
        }
        return true
    }

    override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
        return when(item?.itemId) {
            R.id.add -> {
                galleryModel.add(getSelectedPhotos())
                true
            }
            R.id.remove -> {
                when {
                    folderArgument == GalleryFragment.TRASH_FOLDER -> galleryModel.restore(getSelectedPhotos())
                    parentFragmentManager.findFragmentByTag(GalleryDeletionDialogFragment.GALLERY_DELETION_DIALOG_RESULT_KEY) == null -> {
                        val location = mediaAdapter.locationOfSelected()
                        GalleryDeletionDialogFragment.newInstance(
                            GALLERY_FOLDERVIEW_REQUEST_KEY,
                            location != GalleryFragment.GalleryMedia.IS_REMOTE, true,
                            location != GalleryFragment.GalleryMedia.IS_LOCAL, PreferenceManager.getDefaultSharedPreferences(requireContext()).getBoolean(getString(R.string.sync_deletion_perf_key) , false)
                        ).show(parentFragmentManager, GalleryDeletionDialogFragment.GALLERY_DELETION_DIALOG_RESULT_KEY)
                    }
                }

                true
            }
            R.id.share -> {
                val photoIds = getSelectedPhotos(false)
                if (parentFragmentManager.findFragmentByTag(SHARE_OUT_DIALOG) == null)
                    ShareOutDialogFragment.newInstance(mimeTypes = galleryModel.getMimeTypes(photoIds), showRemoveAfterwards = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaStore.canManageMedia(requireContext()) else false)?.show(parentFragmentManager, SHARE_OUT_DIALOG)
                    ?: run {
                        selectionTracker.clearSelection()
                        galleryModel.shareOut(photoIds, strip = false, lowResolution = false, removeAfterwards = false)
                    }

                true
            }
            R.id.select_all -> {
                selectionTracker.setItemsSelected(mediaAdapter.currentList.filter { it.location != GalleryFragment.GalleryMedia.IS_NOT_MEDIA }.map { it.media.photo.id }, true)
                true
            }
            R.id.download_to_device -> {
                val remoteIds = mutableListOf<NCShareViewModel.RemotePhoto>()
                selectionTracker.selection.forEach { photoId -> mediaAdapter.getRemotePhoto(photoId)?.let { remoteIds.add(it) }}
                if (remoteIds.isNotEmpty()) galleryModel.download(requireContext(), remoteIds)

                selectionTracker.clearSelection()
                true
            }
            R.id.upload_to_archive -> {
                val photos = mutableListOf<GalleryFragment.GalleryMedia>()
                selectionTracker.selection.forEach { photoId -> mediaAdapter.getGalleryMedia(photoId)?.let { photos.add(it) }}
                if (photos.isNotEmpty()) galleryModel.upload(photos)

                selectionTracker.clearSelection()
                true
            }
            else -> false
        }
    }

    override fun onDestroyActionMode(mode: ActionMode?) {
        selectionTracker.clearSelection()
        actionMode = null
    }

    @SuppressLint("InflateParams")
    private fun prepareList(localMedias: List<GalleryFragment.GalleryMedia>, savedInstanceState: Bundle?) {
        if (localMedias.isEmpty()) parentFragmentManager.popBackStack()

        // Disable list setting for now
        subFolderChipGroup.setOnCheckedStateChangeListener(null)

        // List facilitating sub folder view
        currentMediaList.clear()
        currentMediaList.addAll(localMedias)

        // Generate sub folder chips
        if (subFolderChipGroup.childCount > 1) subFolderChipGroup.removeViews(1, subFolderChipGroup.childCount - 1)
        if (folderArgument == GalleryFragment.ALL_FOLDER) currentMediaList.groupBy { item -> item.appName }.forEach { subFolder ->
            subFolderChipGroup.addView(
                (LayoutInflater.from(requireContext()).inflate(R.layout.chip_sub_folder, null) as Chip).apply {
                    text = subFolder.key
                    tag = subFolder.key
                    setOnCheckedChangeListener { buttonView, isChecked -> (buttonView as Chip).typeface = if (isChecked) Typeface.DEFAULT_BOLD else Typeface.DEFAULT }
                }
            )
        }
        else currentMediaList.groupBy { item -> item.fullPath }.forEach { subFolder ->
            subFolderChipGroup.addView(
                (LayoutInflater.from(requireContext()).inflate(R.layout.chip_sub_folder, null) as Chip).apply {
                    text = subFolder.key.dropLast(1).substringAfterLast('/').run { this.ifEmpty { "/" } }
                    tag = subFolder.key
                    setOnCheckedChangeListener { buttonView, isChecked -> (buttonView as Chip).typeface = if (isChecked) Typeface.DEFAULT_BOLD else Typeface.DEFAULT }
                }
            )
        }
        subFolderChipGroup.check(
            subFolderChipGroup.findViewWithTag<Chip>(currentCheckedTag)?.id
            ?: run {
                // If currentCheckedTag is not found in the new sub folder list, fall back to 'All'
                currentCheckedTag = CHIP_FOR_ALL_TAG
                chipForAll.id
            }
        )
        subFolderChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            currentCheckedTag = subFolderChipGroup.findViewById<Chip>(checkedIds[0]).tag as String

            // Appview changed, clear selection
            selectionTracker.clearSelection()
            setList(null)
        }

        setList(savedInstanceState)

        //updateSelectionInfo()
    }

    private fun setList(savedInstanceState: Bundle?) {
        val listGroupedByDate = mutableListOf<GalleryFragment.GalleryMedia>()
        var theDate: LocalDate
        var currentDate = LocalDate.now().plusDays(1)

        when {
            currentCheckedTag == CHIP_FOR_ALL_TAG -> currentMediaList
            folderArgument == GalleryFragment.ALL_FOLDER -> currentMediaList.filter { it.appName == currentCheckedTag }
            else -> currentMediaList.filter { it.fullPath == currentCheckedTag }
        }.forEach { media ->
            theDate = media.media.photo.lastModified.toLocalDate()
            if (theDate != currentDate) {
                currentDate = theDate
                // Add a fake photo item by taking default value for nearly all properties, denotes a date separator
                listGroupedByDate.add(GalleryFragment.GalleryMedia(
                    location = GalleryFragment.GalleryMedia.IS_NOT_MEDIA,
                    folder = "",
                    media = NCShareViewModel.RemotePhoto(Photo(id = currentDate.toString(), albumId = GalleryFragment.FROM_DEVICE_GALLERY, dateTaken = media.media.photo.dateTaken, lastModified = media.media.photo.lastModified))
                ))
            }
            listGroupedByDate.add(media)
        }

        //if (listGroupedByDate.isEmpty()) parentFragmentManager.popBackStack() else mediaAdapter.submitList(listGroupedByDate)
        if (listGroupedByDate.isNotEmpty()) mediaAdapter.submitList(listGroupedByDate) {
            galleryModel.stopArchiveLoadingIndicator()
            savedInstanceState?.let { states ->
                if (selectionPendingRestored) {
                    selectionTracker.onRestoreInstanceState(states)
                    selectionPendingRestored = false
                }
            }
        }
    }

    private var flashDateId = ""
    private fun flashDate(view: View) {
        view.post {
            ObjectAnimator.ofPropertyValuesHolder(view, PropertyValuesHolder.ofFloat("translationX", 0f, 100f, 0f)).run {
                duration = 800
                repeatMode = ValueAnimator.REVERSE
                interpolator = BounceInterpolator()
                start()
            }
        }
    }

    private fun getSelectedPhotos(clearSelection: Boolean = true): List<String> = mutableListOf<String>().apply {
        selectionTracker.selection.forEach { add(it) }
        if (clearSelection) selectionTracker.clearSelection()
    }

    class MediaAdapter(
        private val today: String, private val yesterday: String,
        private val clickListener: (View, String, String) -> Unit, private val imageLoader: (NCShareViewModel.RemotePhoto, ImageView) -> Unit, private val cancelLoader: (View) -> Unit
    ): ListAdapter<GalleryFragment.GalleryMedia, RecyclerView.ViewHolder>(MediaDiffCallback()) {
        private lateinit var selectionTracker: SelectionTracker<String>
        private val selectedFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0.0f) })
        private var playMark: Drawable? = null
        private var selectedMark: Drawable? = null
        private var panoramaMark: Drawable? = null
        private val defaultOffset = OffsetDateTime.now().offset

        inner class MediaViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
            val ivPhoto: ImageView = itemView.findViewById<ImageView>(R.id.photo).apply {
                foregroundGravity = Gravity.CENTER
                setOnClickListener { if (!selectionTracker.hasSelection()) currentList[bindingAdapterPosition].media.photo.let { photo ->  clickListener(this, photo.id, photo.mimeType) }}
            }
            private val ivLocal: View = itemView.findViewById(R.id.local_media)
            private val ivArchive: View = itemView.findViewById(R.id.archive_media)

            fun bind(item: GalleryFragment.GalleryMedia) {
                val photo = item.media.photo
                itemView.let {
                    it.isSelected = selectionTracker.isSelected(photo.id)

                    with(ivPhoto) {
                        // Prevent re-loading image again which result in a flickering
                        if (getTag(R.id.PHOTO_ID) != item.media.photo.id) imageLoader(item.media, this)

                        bindLocationIndicator(item)

                        ViewCompat.setTransitionName(this, photo.id)

                        foreground = when {
                            it.isSelected -> selectedMark
                            Tools.isMediaPlayable(photo.mimeType) -> playMark
                            photo.mimeType == Tools.PANORAMA_MIMETYPE -> panoramaMark
                            else -> null
                        }

                        if (it.isSelected) colorFilter = selectedFilter
                        else clearColorFilter()
                    }
                }
            }

            fun bindLocationIndicator(item: GalleryFragment.GalleryMedia) {
                ivLocal.isActivated = item.atLocal()
                ivArchive.isActivated = item.atRemote()
            }

            fun getItemDetails() = object : ItemDetailsLookup.ItemDetails<String>() {
                override fun getPosition(): Int = bindingAdapterPosition
                override fun getSelectionKey(): String = getPhotoId(bindingAdapterPosition)
            }
        }

        inner class DateViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
            private val tvDate = itemView.findViewById<TextView>(R.id.date)

            @SuppressLint("SetTextI18n")
            fun bind(item: GalleryFragment.GalleryMedia) {
                with(item.media.photo.lastModified) {
                    val now = LocalDate.now()
                    val date = this.toLocalDate()
                    tvDate.text = when {
                        date == now -> today
                        date == now.minusDays(1) -> yesterday
                        date.year == now.year -> "${format(DateTimeFormatter.ofPattern("MMM d"))}, ${dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())}"
                        else -> "${format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))}, ${dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())}"
                    }
                    tvDate.tag = item.media.photo.id
                }
            }

            fun getItemDetails() = object : ItemDetailsLookup.ItemDetails<String>() {
                override fun getPosition(): Int = bindingAdapterPosition
                override fun getSelectionKey(): String = getPhotoId(bindingAdapterPosition)
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            if (viewType == TYPE_MEDIA) MediaViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_gallery, parent, false))
            else DateViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_gallery_date_horizontal, parent, false))

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is MediaViewHolder) holder.bind(currentList[position])
            else (holder as DateViewHolder).bind(currentList[position])
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
            if (holder is MediaViewHolder) {
                if (payloads.isEmpty()) holder.bind(currentList[position])
                else if (payloads[0] == PAYLOAD_LOCATION_CHANGED) holder.bindLocationIndicator(currentList[position]) else holder.bind(currentList[position])
            }
            else (holder as DateViewHolder).bind(currentList[position])
        }

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            if (holder is MediaViewHolder) cancelLoader(holder.ivPhoto)
            super.onViewRecycled(holder)
        }

/*
        override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
            for (i in 0 until currentList.size) {
                recyclerView.findViewHolderForAdapterPosition(i)?.let { holder -> if (holder is MediaViewHolder) holder.itemView.findViewById<View>(R.id.photo)?.let { cancelLoader(it) }}
            }
            super.onDetachedFromRecyclerView(recyclerView)
        }
*/

        override fun getItemViewType(position: Int): Int = if (currentList[position].location == GalleryFragment.GalleryMedia.IS_NOT_MEDIA) TYPE_DATE else TYPE_MEDIA

        internal fun getFileSize(selected: String): Long = currentList.find { it.media.photo.id == selected }?.media?.photo?.caption?.toLong() ?: 0L
        internal fun setMarks(playMark: Drawable, selectedMark: Drawable, panoramaMark: Drawable) {
            this.playMark = playMark
            this.selectedMark = selectedMark
            this.panoramaMark = panoramaMark
        }
        internal fun setSelectionTracker(selectionTracker: SelectionTracker<String>) { this.selectionTracker = selectionTracker }
        internal fun getPhotoId(position: Int): String = currentList[position].media.photo.id
        internal fun getPhotoPosition(photoId: String): Int = currentList.indexOfFirst { it.media.photo.id == photoId }

        fun hasDate(date: Long): Boolean {
            val theDate = LocalDateTime.ofInstant(Instant.ofEpochMilli(date), ZoneId.systemDefault()).toLocalDate()
            return (currentList.indexOfFirst { it.location == GalleryFragment.GalleryMedia.IS_NOT_MEDIA && it.media.photo.lastModified.toLocalDate().isEqual(theDate) }) != RecyclerView.NO_POSITION
        }
        fun dateRange(): Pair<Long, Long>? {
            return if (currentList.isNotEmpty()) Pair(currentList.last().media.photo.lastModified.atZone(defaultOffset).toInstant().toEpochMilli(), currentList.first().media.photo.lastModified.atZone(defaultOffset).toInstant().toEpochMilli()) else null
        }
        fun getPositionByDate(date: Long): Int  = currentList.indexOfFirst { it.location == GalleryFragment.GalleryMedia.IS_NOT_MEDIA && it.media.photo.lastModified.atZone(defaultOffset).toInstant().toEpochMilli() - date < 86400000 }
        fun getDateByPosition(position: Int): Long = currentList[position].media.photo.lastModified.atZone(defaultOffset).toInstant().toEpochMilli()
        fun getAllItems(): List<GalleryFragment.GalleryMedia> = currentList.filter { it.location != GalleryFragment.GalleryMedia.IS_NOT_MEDIA }

        internal fun atRemote(photoId: String): Boolean = currentList.find { it.media.photo.id == photoId }?.atRemote() ?: false
        internal fun atLocal(photoId: String): Boolean = currentList.find { it.media.photo.id == photoId }?.let { it.atLocal() || it.isLocal() }?: false
        internal fun getRemotePhoto(photoId: String): NCShareViewModel.RemotePhoto? =  currentList.find { it.media.photo.id == photoId }?.let { item -> if (item.atRemote()) item.media else null }
        internal fun getGalleryMedia(photoId: String?): GalleryFragment.GalleryMedia? = currentList.find { it.media.photo.id == photoId }?.let { item -> if (item.isLocal() || item.atLocal()) item else null }

        internal fun locationOfSelected(): Int {
            val x: Int = currentList.find { it.media.photo.id == selectionTracker.selection.elementAt(0) }?.location ?: GalleryFragment.GalleryMedia.IS_NOT_MEDIA

            for (i in 1 until selectionTracker.selection.size()) {
                if (x == (currentList.find { it.media.photo.id == selectionTracker.selection.elementAt(i) }?.location ?: GalleryFragment.GalleryMedia.IS_NOT_MEDIA)) continue
                else return GalleryFragment.GalleryMedia.IS_BOTH
            }

            return x
        }

        class PhotoKeyProvider(private val adapter: MediaAdapter): ItemKeyProvider<String>(SCOPE_CACHED) {
            override fun getKey(position: Int): String = adapter.getPhotoId(position)
            override fun getPosition(key: String): Int = adapter.getPhotoPosition(key)
        }
        class PhotoDetailsLookup(private val recyclerView: RecyclerView): ItemDetailsLookup<String>() {
            override fun getItemDetails(e: MotionEvent): ItemDetails<String> {
                recyclerView.findChildViewUnder(e.x, e.y)?.let {
                    recyclerView.getChildViewHolder(it).let { holder ->
                        if (holder is MediaViewHolder) return holder.getItemDetails()
                        if (holder is DateViewHolder) return holder.getItemDetails()
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

        companion object {
            private const val TYPE_MEDIA = 0
            const val TYPE_DATE = 1

            const val PAYLOAD_LOCATION_CHANGED = 1
        }
    }

    class MediaDiffCallback : DiffUtil.ItemCallback<GalleryFragment.GalleryMedia>() {
        override fun areItemsTheSame(oldItem: GalleryFragment.GalleryMedia, newItem: GalleryFragment.GalleryMedia): Boolean = oldItem.media.photo.id == newItem.media.photo.id
        override fun areContentsTheSame(oldItem: GalleryFragment.GalleryMedia, newItem: GalleryFragment.GalleryMedia): Boolean = oldItem.location == newItem.location
        override fun getChangePayload(oldItem: GalleryFragment.GalleryMedia, newItem: GalleryFragment.GalleryMedia): Any? {
            return when {
                oldItem.location != newItem.location -> MediaAdapter.PAYLOAD_LOCATION_CHANGED
                else -> null
            }
        }
    }

    companion object {
        private const val CONFIRM_DIALOG = "CONFIRM_DIALOG"
        private const val SHARE_OUT_DIALOG = "SHARE_OUT_DIALOG"
        private const val GALLERY_FOLDERVIEW_REQUEST_KEY = "GALLERY_FOLDERVIEW_REQUEST_KEY"
        private const val EMPTY_TRASH_REQUEST_KEY = "EMPTY_TRASH_REQUEST_KEY"

        // Default to All, same tag set in R.layout.fragment_gallery_list for view R.id.chip_for_all
        const val CHIP_FOR_ALL_TAG = "...."

        private const val ARGUMENT_FOLDER = "ARGUMENT_FOLDER"

        @JvmStatic
        fun newInstance(folder: String) = GalleryFolderViewFragment().apply { arguments = Bundle().apply { putString(ARGUMENT_FOLDER, folder) }}
    }
}