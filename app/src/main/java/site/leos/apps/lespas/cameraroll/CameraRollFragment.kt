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

package site.leos.apps.lespas.cameraroll

import android.accounts.AccountManager
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.*
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.TypedValue
import android.view.*
import android.view.animation.BounceInterpolator
import android.webkit.MimeTypeMap
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.SharedElementCallback
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.GestureDetectorCompat
import androidx.core.view.doOnLayout
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.exifinterface.media.ExifInterface
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.*
import androidx.lifecycle.Observer
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import androidx.recyclerview.selection.ItemDetailsLookup
import androidx.recyclerview.selection.ItemKeyProvider
import androidx.recyclerview.selection.SelectionTracker
import androidx.recyclerview.selection.StorageStrategy
import androidx.recyclerview.widget.*
import androidx.recyclerview.widget.RecyclerView.*
import androidx.transition.Fade
import androidx.transition.Transition
import androidx.transition.TransitionManager
import com.google.android.material.badge.BadgeDrawable
import com.google.android.material.badge.BadgeUtils
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.transition.MaterialContainerTransform
import kotlinx.coroutines.*
import site.leos.apps.lespas.MainActivity
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.Album
import site.leos.apps.lespas.helper.*
import site.leos.apps.lespas.helper.Tools.parcelableArrayList
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.publication.NCShareViewModel
import site.leos.apps.lespas.search.SearchResultFragment
import site.leos.apps.lespas.sync.*
import java.io.File
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.*
import kotlin.math.*

class CameraRollFragment : Fragment() {
    private lateinit var bottomSheet: BottomSheetBehavior<ConstraintLayout>
    private lateinit var mediaPager: RecyclerView
    private lateinit var quickScroll: RecyclerView
    private lateinit var mediaPagerAdapter: MediaPagerAdapter
    private lateinit var quickScrollAdapter: QuickScrollAdapter
    private var quickScrollGridSpanCount = 0

    private lateinit var dateTextView: TextView
    private lateinit var sizeTextView: TextView
    private lateinit var buttonGroup: ConstraintLayout
    private lateinit var infoButton: ImageButton
    private lateinit var shareButton: ImageButton
    private lateinit var removeButton: ImageButton
    private lateinit var lespasButton: ImageButton
    private lateinit var closeButton: ImageButton
    private lateinit var selectionText: TextView
    private lateinit var sourceToggleGroup: MaterialButtonToggleGroup
    private lateinit var toggleCameraRollButton: MaterialButton
    private lateinit var toggleBackupsButton: MaterialButton
    private lateinit var datePickerButton: ImageButton
    private lateinit var yearIndicator: TextView

    private lateinit var cBadge: BadgeDrawable
    private lateinit var aBadge: BadgeDrawable

    private var savedStatusBarColor = 0
    private var savedNavigationBarColor = 0
    private var savedNavigationBarDividerColor = 0

    private var startWithThisMedia: String = ""
    private lateinit var selectionTracker: SelectionTracker<String>
    private var lastSelection = arrayListOf<Uri>()
    private var stripExif = "2"
    private var stripOrNot = false
    private var waitingMsg: Snackbar? = null
    private val handler = Handler(Looper.getMainLooper())

    private var showListFirst = false
    private var ignoreHide = true
    private var allowToggleContent = true   // disable content toggle between device and remote if called from SearchResultFragment or started as a image viewer

    private val imageLoaderModel: NCShareViewModel by activityViewModels()
    private val destinationModel: DestinationDialogFragment.DestinationViewModel by activityViewModels()
    private val camerarollModel: CameraRollViewModel by viewModels { CameraRollViewModelFactory(requireActivity().application, requireArguments().getString(KEY_URI), requireArguments().getBoolean(KEY_IN_ARCHIVE)) }
    private val playerViewModel: VideoPlayerViewModel by viewModels { VideoPlayerViewModelFactory(requireActivity(), imageLoaderModel.getCallFactory(), imageLoaderModel.getPlayerCache()) }
    private val actionModel: ActionViewModel by viewModels()

    private lateinit var removeOriginalBroadcastReceiver: RemoveOriginalBroadcastReceiver
    private lateinit var deleteMediaLauncher: ActivityResultLauncher<IntentSenderRequest>
    private lateinit var storagePermissionRequestLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var accessMediaLocationPermissionRequestLauncher: ActivityResultLauncher<String>
    private lateinit var gestureDetector: GestureDetectorCompat

    private val sx = PropertyValuesHolder.ofFloat("scaleX", 1.0f, 0.8f, 1.0f)
    private val sy = PropertyValuesHolder.ofFloat("scaleY", 1.0f, 0.8f, 1.0f)
    private val tx = PropertyValuesHolder.ofFloat("translationX", 0f, 100f, 0f)

    private lateinit var remoteArchiveBaseFolder: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        remoteArchiveBaseFolder = Tools.getCameraArchiveHome(requireContext())

        allowToggleContent = tag != SearchResultFragment::class.java.canonicalName
        PreferenceManager.getDefaultSharedPreferences(requireContext()).apply {
            stripExif = getString(getString(R.string.strip_exif_pref_key), getString(R.string.strip_ask_value))!!
            showListFirst = getBoolean(getString(R.string.roll_list_first_perf_key), false)
            // If called by SearchResultFragment, disable 'show list first'
            if (tag == SearchResultFragment::class.java.canonicalName) showListFirst = false
            // If start as viewer then disable 'show list first'
            arguments?.getString(KEY_URI)?.let {
                showListFirst = false
                allowToggleContent = false
            }

            if (getBoolean(getString(R.string.cameraroll_backup_pref_key), false)) {
                // Kick start server backup
                AccountManager.get(requireContext()).getAccountsByType(getString(R.string.account_type_nc)).let { accounts ->
                    if (accounts.isNotEmpty()) ContentResolver.requestSync(accounts[0], getString(R.string.sync_authority), Bundle().apply {
                        putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                        //putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
                        putInt(SyncAdapter.ACTION, SyncAdapter.BACKUP_CAMERA_ROLL)
                    })
                }
            }
        }

        // Create adapter here so that it won't leak
        mediaPagerAdapter = MediaPagerAdapter(
            requireContext(),
            "${imageLoaderModel.getResourceRoot()}${remoteArchiveBaseFolder}",
            Tools.getDisplayDimension(requireActivity()).first,
            playerViewModel,
            { state->
                // When in "Show media list at startup" mode, ignore the first hide bottom sheet call which fired by video auto play
                if (ignoreHide && showListFirst) ignoreHide = false
                else bottomSheet.state = if (state ?: run { bottomSheet.state == BottomSheetBehavior.STATE_HIDDEN }) BottomSheetBehavior.STATE_COLLAPSED else BottomSheetBehavior.STATE_HIDDEN
            },
            { photo, imageView, type->
                if (type == NCShareViewModel.TYPE_NULL) startPostponedEnterTransition()
                else imageLoaderModel.setImagePhoto(if (photo.albumId == FROM_CAMERA_ROLL) NCShareViewModel.RemotePhoto(photo) else NCShareViewModel.RemotePhoto(photo, remoteArchiveBaseFolder), imageView!!, type) {
                    startPostponedEnterTransition()
                    if (photo.width == 0 && photo.mimeType.startsWith("image")) {
                        // Patching photo's meta after it has been fetched
                        Thread { imageLoaderModel.getMediaExif(NCShareViewModel.RemotePhoto(photo, remoteArchiveBaseFolder))?.first?.let { exif -> mediaPagerAdapter.patchMeta(photo.id, exif) }}.run()
                    }
                }},
            { view-> imageLoaderModel.cancelSetImagePhoto(view) }
        ).apply { stateRestorationPolicy = Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY }

        quickScrollAdapter = QuickScrollAdapter(
            { photo ->
                //mediaPagerAdapter.findMediaPosition(photo).let { pos ->
                camerarollModel.findPhotoPosition(photo.id).let { pos ->
                    if (pos != -1) {
                        mediaPager.scrollToPosition(pos)
                        camerarollModel.setCurrentPosition(pos)
                    }
                }
                bottomSheet.state = BottomSheetBehavior.STATE_HIDDEN
                if (photo.mimeType.startsWith("image")) ignoreHide = false
            },
            { photo, imageView, type -> imageLoaderModel.setImagePhoto(if (photo.albumId == FROM_CAMERA_ROLL) NCShareViewModel.RemotePhoto(photo) else NCShareViewModel.RemotePhoto(photo, remoteArchiveBaseFolder), imageView, type) },
            { view -> imageLoaderModel.cancelSetImagePhoto(view) },
            { view -> flashPhoto(view) },
            { view -> flashDate(view) }
        ).apply { stateRestorationPolicy = Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY }

        savedInstanceState?.let {
            (requireActivity() as AppCompatActivity).supportActionBar?.hide()

            //startWithThisMedia = it.getString(KEY_SCROLL_TO) ?: ""
            //lastSelection = it.getParcelableArrayList(KEY_LAST_SELECTION) ?: arrayListOf()
            lastSelection = it.parcelableArrayList(KEY_LAST_SELECTION) ?: arrayListOf()

            // Don't ignore call to hide bottom sheet after activity recreated
            ignoreHide = false
        } ?: run {
            startWithThisMedia = arguments?.getString(KEY_SCROLL_TO) ?: ""
        }

        sharedElementEnterTransition = MaterialContainerTransform().apply {
            duration = resources.getInteger(android.R.integer.config_shortAnimTime).toLong()
            scrimColor = Color.TRANSPARENT
            fadeMode = MaterialContainerTransform.FADE_MODE_CROSS
        }.addListener(object: Transition.TransitionListener {
            // Prevent viewpager from showing content before transition ends
            override fun onTransitionStart(transition: Transition) {
                mediaPager.findChildViewUnder(500.0f, 500.0f)?.visibility = View.INVISIBLE
                (requireActivity() as AppCompatActivity).supportActionBar?.hide()
            }

            override fun onTransitionEnd(transition: Transition) {
                mediaPager.findChildViewUnder(500.0f, 500.0f)?.visibility = View.VISIBLE
            }

            override fun onTransitionCancel(transition: Transition) {
                mediaPager.findChildViewUnder(500.0f, 500.0f)?.visibility = View.VISIBLE
            }

            override fun onTransitionPause(transition: Transition) {}
            override fun onTransitionResume(transition: Transition) {}
        })
        // Set a return transition here to avoid transition listener being called during fragment exit
        sharedElementReturnTransition = MaterialContainerTransform().apply {
            duration = resources.getInteger(android.R.integer.config_shortAnimTime).toLong()
            scrimColor = Color.TRANSPARENT
            fadeMode = MaterialContainerTransform.FADE_MODE_CROSS
        }
        // Adjusting the shared element mapping
        setEnterSharedElementCallback(object : SharedElementCallback() {
            override fun onMapSharedElements(names: MutableList<String>?, sharedElements: MutableMap<String, View>?) {
                if (names?.isNotEmpty() == true) mediaPager.findViewHolderForAdapterPosition(camerarollModel.getCurrentPosition())?.itemView?.findViewById<View>(R.id.media)?.apply { sharedElements?.put(names[0], this) }
            }
        })

        removeOriginalBroadcastReceiver = RemoveOriginalBroadcastReceiver {
            if (it) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) deleteMediaLauncher.launch(IntentSenderRequest.Builder(MediaStore.createDeleteRequest(requireContext().contentResolver, lastSelection)).setFillInIntent(null).build())
                else camerarollModel.removeMedias(lastSelection)
            }

            // Immediately sync with server after adding photo to local album
            AccountManager.get(requireContext()).getAccountsByType(getString(R.string.account_type_nc)).let { accounts ->
                if (accounts.isNotEmpty()) ContentResolver.requestSync(accounts[0], getString(R.string.sync_authority), Bundle().apply {
                    putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                    //putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
                    putInt(SyncAdapter.ACTION, SyncAdapter.SYNC_LOCAL_CHANGES)
                })
            }
        }

        accessMediaLocationPermissionRequestLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
        storagePermissionRequestLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

            var isGranted = true
            for(result in results) isGranted = isGranted && result.value
            when {
                isGranted -> {
                    // Explicitly request ACCESS_MEDIA_LOCATION permission
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) accessMediaLocationPermissionRequestLauncher.launch(android.Manifest.permission.ACCESS_MEDIA_LOCATION)

                    observeCameraRoll()
                }
                requireActivity() is MainActivity -> parentFragmentManager.popBackStack()
                else -> requireActivity().finish()
            }
        }

        // Back key handler for BottomSheet
        requireActivity().onBackPressedDispatcher.addCallback(this, object: OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Cancel EXIF stripping job if it's running
                waitingMsg?.let {
                    if (it.isShownOrQueued) {
                        imageLoaderModel.cancelShareOut()
                        it.dismiss()
                        return
                    }
                }

                @SuppressLint("SwitchIntDef")
                when(bottomSheet.state) {
                    BottomSheetBehavior.STATE_HIDDEN -> if (showListFirst) bottomSheet.state = BottomSheetBehavior.STATE_EXPANDED else quit()
                    BottomSheetBehavior.STATE_COLLAPSED -> bottomSheet.state = BottomSheetBehavior.STATE_HIDDEN
                    BottomSheetBehavior.STATE_EXPANDED -> when {
                        selectionTracker.hasSelection() -> selectionTracker.clearSelection()
                        showListFirst -> quit()
                        else -> bottomSheet.state = BottomSheetBehavior.STATE_HIDDEN
                    }
                }
            }

            private fun quit() {
                isEnabled = false

                if (parentFragmentManager.backStackEntryCount == 0) requireActivity().finish()
                else parentFragmentManager.popBackStack()
            }
        })

        // Detect swipe up gesture and show BottomSheet
        gestureDetector = GestureDetectorCompat(requireContext(), object: GestureDetector.SimpleOnGestureListener() {
            // Overwrite onFling rather than onScroll, since onScroll will be called multiple times during one scroll
            override fun onFling(e1: MotionEvent, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                // Ignore scroll
                if (abs(velocityY) < 1000) return false

                when(Math.toDegrees(atan2(e1.y - e2.y, e2.x - e1.x).toDouble())) {
                    in 55.0..125.0-> {
                        //bottomSheet.state = if (mediaPagerAdapter.itemCount > 1) BottomSheetBehavior.STATE_EXPANDED else BottomSheetBehavior.STATE_COLLAPSED
                        bottomSheet.state = BottomSheetBehavior.STATE_EXPANDED
                        return true
                    }
                    in -125.0..-55.0-> {
                        if (bottomSheet.state == BottomSheetBehavior.STATE_EXPANDED || bottomSheet.state == BottomSheetBehavior.STATE_COLLAPSED) {
                            bottomSheet.state = BottomSheetBehavior.STATE_HIDDEN
                            return true
                        }
                    }
                }

                return super.onFling(e1, e2, velocityX, velocityY)
            }
        })

        quickScrollGridSpanCount = resources.getInteger(R.integer.cameraroll_grid_span_count)
        quickScrollAdapter.setPlayMarkDrawable(Tools.getPlayMarkDrawable(requireActivity(), (0.32f / quickScrollGridSpanCount)))
        quickScrollAdapter.setSelectedMarkDrawable(Tools.getSelectedMarkDrawable(requireActivity(), 0.25f / quickScrollGridSpanCount))

        // Save current system bar color
        (requireActivity() as AppCompatActivity).window?.run {
            // Clear the display cutout area while photo in landscape position
            //setBackgroundDrawable(ColorDrawable(0))

            savedStatusBarColor = statusBarColor
            savedNavigationBarColor = navigationBarColor
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) savedNavigationBarDividerColor = navigationBarDividerColor
        }

        cBadge = BadgeDrawable.create(requireContext()).apply {
            isVisible = false
            maxCharacterCount = 9999
            badgeTextColor = ContextCompat.getColor(requireContext(), R.color.lespas_white)
            backgroundColor = ContextCompat.getColor(requireContext(), R.color.color_primary)
            horizontalOffset = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 34f, resources.displayMetrics).roundToInt()
            verticalOffset = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10f, resources.displayMetrics).roundToInt()
        }
        aBadge = BadgeDrawable.create(requireContext()).apply {
            isVisible = false
            maxCharacterCount = 9999
            badgeTextColor = ContextCompat.getColor(requireContext(), R.color.lespas_white)
            backgroundColor = ContextCompat.getColor(requireContext(), R.color.color_primary)
            horizontalOffset = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 34f, resources.displayMetrics).roundToInt()
            verticalOffset = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10f, resources.displayMetrics).roundToInt()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? = inflater.inflate(R.layout.fragment_camera_roll, container, false)
    @SuppressLint("ClickableViewAccessibility", "UnsafeOptInUsageError")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (!showListFirst) postponeEnterTransition()

        dateTextView = view.findViewById(R.id.date)
        sizeTextView = view.findViewById(R.id.size)
        infoButton = view.findViewById(R.id.info_button)
        shareButton = view.findViewById(R.id.share_button)
        removeButton = view.findViewById(R.id.remove_button)
        lespasButton = view.findViewById(R.id.lespas_button)
        closeButton = view.findViewById(R.id.close_button)
        selectionText = view.findViewById(R.id.selection_text)
        buttonGroup = view.findViewById(R.id.button_group)
        sourceToggleGroup = view.findViewById(R.id.source_toggle_group)
        toggleCameraRollButton = view.findViewById(R.id.source_device)
        toggleBackupsButton = view.findViewById(R.id.source_backups)
        datePickerButton = view.findViewById(R.id.date_picker_button)
        yearIndicator = view.findViewById<TextView>(R.id.year_indicator).apply {
            doOnLayout {
                background = MaterialShapeDrawable().apply {
                    fillColor = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.color_error))
                    shapeAppearanceModel = ShapeAppearanceModel.builder().setTopLeftCorner(CornerFamily.CUT, yearIndicator.height.toFloat()).build()
                }
            }
        }

        toggleCameraRollButton.doOnPreDraw { BadgeUtils.attachBadgeDrawable(cBadge, toggleCameraRollButton) }
        toggleBackupsButton.doOnPreDraw { BadgeUtils.attachBadgeDrawable(aBadge, toggleBackupsButton) }

        quickScroll = view.findViewById<RecyclerView>(R.id.quick_scroll).apply {
            adapter = quickScrollAdapter

            layoutManager = object : GridLayoutManager(this.context, resources.getInteger(R.integer.cameraroll_grid_span_count)) {
                // Overscroll at the top for a period of time will hide the bottom sheet
                var threshold = 0
                override fun scrollVerticallyBy(dy: Int, recycler: Recycler?, state: State?): Int {
                    super.scrollVerticallyBy(dy, recycler, state).run {
                        // state.remainingScrollVertical will not be 0 when flinging the list to top
                        // dy will be negative if over scrolling at the top
                        if (state?.remainingScrollVertical == 0 && dy < 0) {
                            threshold++
                            if (threshold > 35) {
                                threshold = 0
                                bottomSheet.state = BottomSheetBehavior.STATE_HIDDEN
                            }
                        } else threshold = 0
                        return this
                    }
                }
            }

            (layoutManager as GridLayoutManager).spanSizeLookup = object: GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    return if (quickScrollAdapter.getItemViewType(position) == QuickScrollAdapter.DATE_TYPE) quickScrollGridSpanCount else 1
                }
            }

            selectionTracker = SelectionTracker.Builder(
                "camerarollSelection",
                this,
                QuickScrollAdapter.PhotoKeyProvider(quickScrollAdapter),
                QuickScrollAdapter.PhotoDetailsLookup(this),
                StorageStrategy.createStringStorage()
            ).withSelectionPredicate(object: SelectionTracker.SelectionPredicate<String>() {
                override fun canSetStateForKey(key: String, nextState: Boolean): Boolean = key.isNotEmpty()
                override fun canSetStateAtPosition(position: Int, nextState: Boolean): Boolean = position > 0
                override fun canSelectMultiple(): Boolean = true
            }).build().apply {
                addObserver(object: SelectionTracker.SelectionObserver<String>() {
                    override fun onSelectionChanged() {
                        super.onSelectionChanged()

                        if (selectionTracker.hasSelection()) {
                            val selectionSize = selectionTracker.selection.size()

                            if (!camerarollModel.shouldDisableRemove()) removeButton.isEnabled = true
                            shareButton.isEnabled = true
                            lespasButton.isEnabled = true
                            closeButton.setImageResource(R.drawable.ic_baseline_close_24)
                            selectionText.text = resources.getQuantityString(R.plurals.selected_count, selectionSize, selectionSize)
                            selectionText.isVisible = true

                            setButtonGroupState(true)
                            setSourceGroupState(false)

                            bottomSheet.isDraggable = false
                        } else {
                            removeButton.isEnabled = false
                            shareButton.isEnabled = false
                            lespasButton.isEnabled = false
                            closeButton.setImageResource(R.drawable.ic_baseline_arrow_downward_24)
                            selectionText.isVisible = false
                            selectionText.text = ""

                            setButtonGroupState(!allowToggleContent)
                            setSourceGroupState(true)

                            bottomSheet.isDraggable = true
                        }
                    }
                })
            }
/*
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) this.systemGestureExclusionRects = listOf(Rect(this.left, this.top, sideTouchAreaWidth, this.bottom), Rect(this.right - sideTouchAreaWidth, this.top, this.right, this.bottom))

             addItemDecoration(HeaderItemDecoration(this) { itemPosition->
                (adapter as QuickScrollAdapter).getItemViewType(itemPosition) == QuickScrollAdapter.DATE_TYPE
            })

            addOnScrollListener(object: RecyclerView.OnScrollListener() {
                var toRight = true
                val separatorWidth = resources.getDimension(R.dimen.camera_roll_date_grid_size).roundToInt()
                val mediaGridWidth = resources.getDimension(R.dimen.camera_roll_grid_size).roundToInt()

                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)
                    toRight = dx < 0
                }

                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    super.onScrollStateChanged(recyclerView, newState)
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        if ((recyclerView.layoutManager as LinearLayoutManager).findLastVisibleItemPosition() < recyclerView.adapter?.itemCount!! - 1) {
                            // if date separator is approaching the header, perform snapping
                            recyclerView.findChildViewUnder(separatorWidth.toFloat(), 0f)?.apply {
                                if (width == separatorWidth) snapTo(this, recyclerView)
                                else recyclerView.findChildViewUnder(separatorWidth.toFloat() + mediaGridWidth / 3, 0f)?.apply {
                                    if (width == separatorWidth) snapTo(this, recyclerView)
                                }
                            }
                        }
                    }
                }

                private fun snapTo(view: View, recyclerView: RecyclerView) {
                    // Snap to this View if scrolling to left, or it's previous one if scrolling to right
                    if (toRight) recyclerView.smoothScrollBy(view.left - separatorWidth - mediaGridWidth, 0, null, 1000)
                    else recyclerView.smoothScrollBy(view.left, 0, null, 500)
                }
            })
*/
            addOnScrollListener(object: RecyclerView.OnScrollListener() {
                private val hideHandler = Handler(Looper.getMainLooper())
                private val hideDateIndicator = Runnable {
                    TransitionManager.beginDelayedTransition(quickScroll.parent as ViewGroup, Fade().apply { duration = 800 })
                    yearIndicator.visibility = View.GONE
                }

                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)

                    if (dx == 0 && dy == 0) {
                        // First entry or fragment resume false call, by layout re-calculation, hide dataIndicator
                        yearIndicator.isVisible = false
                    } else {
                        (recyclerView.layoutManager as GridLayoutManager).run {
                            // Hints the date (or 1st character of the name if sorting order is by name) of last photo shown in the list
                            if ((findLastCompletelyVisibleItemPosition() < quickScrollAdapter.itemCount - 1) || (findFirstCompletelyVisibleItemPosition() > 0)) {
                                hideHandler.removeCallbacksAndMessages(null)
                                yearIndicator.let {
                                    it.text = quickScrollAdapter.currentList[findLastVisibleItemPosition()].dateTaken.format(DateTimeFormatter.ofPattern("MMM uuuu"))
                                    it.isVisible = true
                                }
                                hideHandler.postDelayed(hideDateIndicator, 1500)
                            }
                        }
                    }
                }
            })

            addItemDecoration(LesPasEmptyView(ContextCompat.getDrawable(this.context, R.drawable.ic_baseline_camera_roll_24)!!))
        }
        quickScrollAdapter.setSelectionTracker(selectionTracker)
        LesPasFastScroller(
            quickScroll,
            ContextCompat.getDrawable(quickScroll.context, R.drawable.fast_scroll_thumb) as StateListDrawable, ContextCompat.getDrawable(quickScroll.context, R.drawable.fast_scroll_track)!!,
            ContextCompat.getDrawable(quickScroll.context, R.drawable.fast_scroll_thumb) as StateListDrawable, ContextCompat.getDrawable(quickScroll.context, R.drawable.fast_scroll_track)!!,
            resources.getDimensionPixelSize(R.dimen.fast_scroll_thumb_width), 0, 0, resources.getDimensionPixelSize(R.dimen.fast_scroll_thumb_height)
        )

        mediaPager = view.findViewById<RecyclerView>(R.id.media_pager).apply {
            adapter = mediaPagerAdapter

            // Snap like a ViewPager
            PagerSnapHelper().attachToRecyclerView(this)

            // Detect swipe up gesture and show BottomSheet
            addOnItemTouchListener(object: SimpleOnItemTouchListener() {
                override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean = gestureDetector.onTouchEvent(e)
            })

            addOnScrollListener(object: OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    super.onScrollStateChanged(recyclerView, newState)

                    when(newState) {
                        SCROLL_STATE_DRAGGING -> {
                            // Dismiss BottomSheet when user starts scrolling
                            bottomSheet.state = BottomSheetBehavior.STATE_HIDDEN
                        }
                        SCROLL_STATE_IDLE -> {
                            // Save current position in VM
                            (mediaPager.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition().let { pos -> camerarollModel.setCurrentPosition(if (pos == NO_POSITION) 0 else pos) }

                            // Update meta display textview after scrolled
                            updateMetaDisplay()
                        }
                    }
                }
            })

            addItemDecoration(LesPasEmptyView(ContextCompat.getDrawable(this.context, R.drawable.ic_baseline_camera_roll_24)!!))
        }

        // Since we are not in immersive mode, set correct photo display width so that right edge can be detected
        mediaPager.doOnLayout { mediaPagerAdapter.setDisplayWidth(mediaPager.width) }

        // Start observing camera roll asap
        savedInstanceState?.let {
            observeCameraRoll()
        } ?: run {
            if (Tools.shouldRequestStoragePermission(requireContext())) {
                requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
                storagePermissionRequestLauncher.launch(Tools.getStoragePermissionsArray())
            }
            else {
                // Explicitly request ACCESS_MEDIA_LOCATION permission
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) accessMediaLocationPermissionRequestLauncher.launch(android.Manifest.permission.ACCESS_MEDIA_LOCATION)

                observeCameraRoll()
            }
        }

        // TODO dirty hack to reduce mediaPager's scroll sensitivity to get smoother zoom experience
        (RecyclerView::class.java.getDeclaredField("mTouchSlop")).apply {
            isAccessible = true
            set(mediaPager, (get(mediaPager) as Int) * 4)
        }

        bottomSheet = BottomSheetBehavior.from(view.findViewById(R.id.bottom_sheet) as ConstraintLayout).apply {
            val primaryColor = Tools.getAttributeColor(requireContext(), android.R.attr.textColorPrimary)
            val backgroundColor = Tools.getAttributeColor(requireContext(), android.R.attr.colorBackground)

            state = BottomSheetBehavior.STATE_HIDDEN
            saveFlags = BottomSheetBehavior.SAVE_ALL
            skipCollapsed = true

            addBottomSheetCallback(object: BottomSheetBehavior.BottomSheetCallback() {

                @SuppressLint( "SwitchIntDef")
                override fun onStateChanged(view: View, newState: Int) {
                    when (newState) {
                        BottomSheetBehavior.STATE_EXPANDED -> {
/*
                            if (mediaPagerAdapter.itemCount == 1) {
                                // If there is only 1 item in the list, user swipe up to reveal the expanded sheet, then we should update the text here
                                // TODO any better way to detect mediaPager's scroll to event?
                                updateMetaDisplay()
                            } else {
                                // If there are more than 1 media in the list, get ready to show BottomSheet expanded state
                                if (!closeButton.isVisible) {
                                    updateExpandedDisplay()
                                    camerarollModel.getCurrentPhoto()?.let { quickScroll.scrollToPosition(quickScrollAdapter.getPhotoPosition(it.id)) }
                                }
                            }

 */
                            updateExpandedDisplay()

                            // Flash photo to indicate it's position
                            camerarollModel.getCurrentPhoto()?.id?.let { photoId ->
                                quickScrollAdapter.getPhotoPosition(photoId).let { pos ->
                                    quickScroll.findViewHolderForAdapterPosition(pos)?.itemView?.findViewById<ImageView>(R.id.photo)?.let {
                                        // Flash current photo in list if it's within current range
                                        view -> flashPhoto(view)
                                    } ?: run {
                                        // Scroll to the current photo and flash it after it's view ready
                                        quickScroll.scrollToPosition(pos)
                                        quickScrollAdapter.setFlashPhoto(photoId)
                                    }
                                }
                            }
                        }
                        BottomSheetBehavior.STATE_HIDDEN -> {
                            selectionTracker.clearSelection()
                            if (closeButton.isVisible) {
                                dateTextView.isVisible = true
                                sizeTextView.isVisible = true
                                dateTextView.setTextColor(primaryColor)
                                sizeTextView.setTextColor(primaryColor)
                                removeButton.isEnabled = !camerarollModel.shouldDisableRemove()
                                shareButton.isEnabled = !camerarollModel.shouldDisableShare()
                                lespasButton.isEnabled = true

                                infoButton.isVisible = !camerarollModel.shouldDisableShare()
                                infoButton.isEnabled = !camerarollModel.shouldDisableShare()

                                setButtonGroupState(true)
                                setSourceGroupState(false)

                                closeButton.isEnabled = false
                                closeButton.isVisible = false
                                selectionText.isVisible = false
                            }
                        }
                        BottomSheetBehavior.STATE_COLLAPSED -> {
                            removeButton.isEnabled = !camerarollModel.shouldDisableRemove()
                            shareButton.isEnabled = !camerarollModel.shouldDisableShare()
                            infoButton.isVisible = !camerarollModel.shouldDisableShare()
                            infoButton.isEnabled = !camerarollModel.shouldDisableShare()
                            selectionTracker.clearSelection()
                            setButtonGroupState(true)
                            setSourceGroupState(false)
                            // Update media meta when in collapsed state, should do it here since when media list updated, like after deletion, list's addOnScrollListener callback won't be called
                            // TODO any better way to detect mediaPager's scroll to event?
                            updateMetaDisplay()
                        }
                    }
                }

                override fun onSlide(view: View, slideOffset: Float) {
                    if (slideOffset >= 0) {
                        buttonGroup.isVisible = true
                        sourceToggleGroup.isVisible = allowToggleContent
                        datePickerButton.isVisible = allowToggleContent

                        var alpha = 1.0f - min(0.25f, slideOffset) * 4

                        buttonGroup.alpha = if (allowToggleContent) alpha else slideOffset / 2
                        with(ColorUtils.setAlphaComponent(primaryColor, (alpha * 255).toInt())) {
                            dateTextView.setTextColor(this)
                            sizeTextView.setTextColor(this)
                        }

                        quickScroll.foreground = ColorDrawable(ColorUtils.setAlphaComponent(backgroundColor, (alpha * 255).toInt()))
                        alpha = (max(slideOffset, 0.75f) - 0.75f) * 4
                        sourceToggleGroup.alpha = alpha
                        datePickerButton.alpha = alpha
                    }
                }
            })
        }

        infoButton.setOnClickListener {
            bottomSheet.state = BottomSheetBehavior.STATE_HIDDEN
            if (parentFragmentManager.findFragmentByTag(INFO_DIALOG) == null) camerarollModel.getCurrentPhoto()?.let { photo ->
                (if (photo.albumId == FROM_CAMERA_ROLL) MetaDataDialogFragment.newInstance(photo) else MetaDataDialogFragment.newInstance(NCShareViewModel.RemotePhoto(photo, remoteArchiveBaseFolder))).show(parentFragmentManager, INFO_DIALOG)
            }
        }
        shareButton.setOnClickListener {
            if (toggleCameraRollButton.isChecked) {
                // For device camera roll
                copyTargetUris()

                if (stripExif == getString(R.string.strip_ask_value)) {
                    if (hasExifInSelection()) {
                        if (parentFragmentManager.findFragmentByTag(CONFIRM_DIALOG) == null) ConfirmDialogFragment.newInstance(getString(R.string.strip_exif_msg, getString(R.string.strip_exif_title)), requestKey = STRIP_REQUEST_KEY, positiveButtonText = getString(R.string.strip_exif_yes), negativeButtonText = getString(R.string.strip_exif_no), cancelable = true).show(parentFragmentManager, CONFIRM_DIALOG)
                    } else shareOut(false)
                } else shareOut(stripExif == getString(R.string.strip_on_value))
            } else {
                // For camera backups on server
                val targets = mutableListOf<NCShareViewModel.RemotePhoto>()
                if (selectionTracker.hasSelection()) {
                    // Multiple photos
                    selectionTracker.selection.forEach { camerarollModel.getPhotoById(it)?.let { photo -> targets.add(NCShareViewModel.RemotePhoto(photo, remoteArchiveBaseFolder)) }}
                    selectionTracker.clearSelection()
                } else {
                    // Current displayed photo
                    camerarollModel.getCurrentPhoto()?.let { targets.add(NCShareViewModel.RemotePhoto(it, remoteArchiveBaseFolder)) }
                    // Dismiss BottomSheet when it's in collapsed mode
                    bottomSheet.state = BottomSheetBehavior.STATE_HIDDEN
                }

                if (targets.isNotEmpty()) imageLoaderModel.batchDownload(requireContext(), targets)
            }
        }
        lespasButton.setOnClickListener {
            if (toggleCameraRollButton.isChecked) {
                copyTargetUris()
                if (parentFragmentManager.findFragmentByTag(TAG_DESTINATION_DIALOG) == null) DestinationDialogFragment.newInstance(lastSelection, true).show(parentFragmentManager, if (tag == TAG_FROM_CAMERAROLL_ACTIVITY) TAG_FROM_CAMERAROLL_ACTIVITY else TAG_DESTINATION_DIALOG)
            } else {
                val pList = arrayListOf<NCShareViewModel.RemotePhoto>()
                if (selectionTracker.hasSelection()) {
                    selectionTracker.selection.forEach { photoId -> camerarollModel.getPhotoById(photoId)?.let { pList.add(NCShareViewModel.RemotePhoto(it, remoteArchiveBaseFolder)) }}
                    selectionTracker.clearSelection()
                } else camerarollModel.getCurrentPhoto()?.let { pList.add(NCShareViewModel.RemotePhoto(it, remoteArchiveBaseFolder)) }

                if (parentFragmentManager.findFragmentByTag(TAG_DESTINATION_DIALOG) == null) DestinationDialogFragment.newInstance(pList, "", true).show(parentFragmentManager, if (tag == TAG_FROM_CAMERAROLL_ACTIVITY) TAG_FROM_CAMERAROLL_ACTIVITY else TAG_DESTINATION_DIALOG)
            }
        }
        removeButton.setOnClickListener {
            if (toggleCameraRollButton.isChecked) {
                // For device camera roll
                copyTargetUris()

                // Get confirmation from user
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    deleteMediaLauncher.launch(IntentSenderRequest.Builder(MediaStore.createDeleteRequest(requireContext().contentResolver, lastSelection)).setFillInIntent(null).build())
                } else if (parentFragmentManager.findFragmentByTag(CONFIRM_DIALOG) == null) ConfirmDialogFragment.newInstance(getString(R.string.confirm_delete), positiveButtonText = getString(R.string.yes_delete), requestKey = DELETE_REQUEST_KEY).show(parentFragmentManager, CONFIRM_DIALOG)
            } else
                // For camera backups on server
                if (parentFragmentManager.findFragmentByTag(CONFIRM_DIALOG) == null) ConfirmDialogFragment.newInstance(getString(R.string.confirm_delete), positiveButtonText = getString(R.string.yes_delete), requestKey = DELETE_REQUEST_KEY).show(parentFragmentManager, CONFIRM_DIALOG)
        }
        toggleCameraRollButton.setOnClickListener {
            if (camerarollModel.getVMState().value != CameraRollViewModel.STATE_SHOWING_DEVICE) {
                camerarollModel.saveQuickScrollState(quickScroll.layoutManager?.onSaveInstanceState())
                camerarollModel.fetchCameraRoll()
            }
        }
        toggleBackupsButton.setOnClickListener {
            if (camerarollModel.getVMState().value == CameraRollViewModel.STATE_SHOWING_DEVICE) {
                camerarollModel.saveQuickScrollState(quickScroll.layoutManager?.onSaveInstanceState())
                datePickerButton.isEnabled = false
                camerarollModel.fetchPhotoFromServerBackup()
            }
        }
        datePickerButton.setOnClickListener {
            quickScrollAdapter.dateRange()?.let { dateRange ->
                MaterialDatePicker.Builder.datePicker()
                    .setCalendarConstraints(CalendarConstraints.Builder().setValidator(object: CalendarConstraints.DateValidator {
                        override fun describeContents(): Int = 0
                        override fun writeToParcel(dest: Parcel, flags: Int) {}
                        override fun isValid(date: Long): Boolean = quickScrollAdapter.hasDate(date)
                    }).setStart(dateRange.first).setEnd(dateRange.second).setOpenAt(quickScrollAdapter.getDateByPosition((quickScroll.layoutManager as GridLayoutManager).findFirstVisibleItemPosition())).build())
                    .setTheme(R.style.ThemeOverlay_LesPas_DatePicker)
                    .build()
                    .apply {
                        addOnPositiveButtonClickListener { picked ->
                            val currentBottom = (quickScroll.layoutManager as GridLayoutManager).findLastCompletelyVisibleItemPosition()
                            quickScrollAdapter.getPositionByDate(picked).let { newPosition ->
                                quickScroll.findViewHolderForAdapterPosition(newPosition)?.itemView?.findViewById<TextView>(R.id.date)?.let { view ->
                                    // new position is visible on screen now
                                    if (newPosition == currentBottom) quickScroll.scrollToPosition(newPosition + 1)
                                    flashDate(view)
                                } ?: run {
                                    // flash the date after it has revealed
                                    quickScrollAdapter.setFlashDate(picked)
                                    quickScroll.scrollToPosition(if (newPosition < currentBottom) newPosition else min(quickScrollAdapter.currentList.size - 1, newPosition + quickScrollGridSpanCount))
                                }
                            }
                        }
                    }.show(parentFragmentManager, null)
            }
        }
        closeButton.setOnClickListener { if (selectionTracker.hasSelection()) selectionTracker.clearSelection() else bottomSheet.state = BottomSheetBehavior.STATE_HIDDEN }

        // Acquiring new medias
        destinationModel.getDestination().observe(viewLifecycleOwner, Observer {
            it?.let { targetAlbum ->
                if (destinationModel.doOnServer()) {
                    val actions = mutableListOf<Action>()
                    val actionId = if (destinationModel.shouldRemoveOriginal()) Action.ACTION_MOVE_ON_SERVER else Action.ACTION_COPY_ON_SERVER
                    val targetFolder = if (targetAlbum.id != Album.JOINT_ALBUM_ID) "${Tools.getRemoteHome(requireContext())}/${targetAlbum.name}" else targetAlbum.coverFileName.substringBeforeLast('/')
                    val removeList = mutableListOf<String>()

                    when (targetAlbum.id) {
                        "" -> {
                            // Create new album first, since this whole operations will be carried out on server, we don't have to worry about cover here, SyncAdapter will handle all the rest during next sync
                            actions.add(0, Action(null, Action.ACTION_ADD_DIRECTORY_ON_SERVER, "", targetAlbum.name, "", "", System.currentTimeMillis(), 1))
                        }
                        Album.JOINT_ALBUM_ID -> Snackbar.make(mediaPager, getString(R.string.msg_joint_album_not_updated_locally), Snackbar.LENGTH_LONG).show()
                    }

                    destinationModel.getRemotePhotos().forEach { remotePhoto ->
                        remotePhoto.photo.let { photo ->
                            actions.add(Action(null, actionId, remotePhoto.remotePath, targetFolder, "", "${photo.name}|${targetAlbum.id == Album.JOINT_ALBUM_ID}", System.currentTimeMillis(), 1))
                            removeList.add(photo.id)
                        }
                    }

                    if (destinationModel.shouldRemoveOriginal() && removeList.isNotEmpty()) camerarollModel.removeBackup(removeList)

                    if (actions.isNotEmpty()) actionModel.addActions(actions)
                } else {
                    // Acquire files
                    if (parentFragmentManager.findFragmentByTag(TAG_ACQUIRING_DIALOG) == null) AcquiringDialogFragment.newInstance(lastSelection, targetAlbum, destinationModel.shouldRemoveOriginal()).show(parentFragmentManager, TAG_ACQUIRING_DIALOG)
                }
            }
        })

        camerarollModel.getVMState().observe(viewLifecycleOwner, Observer { vmState ->
            when(vmState) {
                CameraRollViewModel.STATE_FETCHING_BACKUP -> {
                    toggleBackupsButton.run {
                        icon = ContextCompat.getDrawable(requireContext(), R.drawable.animated_archive_refresh)
                        (icon as AnimatedVectorDrawable).start()
                        iconTint = null
                        isEnabled = false
                    }
                }
                CameraRollViewModel.STATE_SHOWING_DEVICE -> {
                    shareButton.setImageDrawable(ContextCompat.getDrawable(requireContext(), R.drawable.ic_baseline_share_24))
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) shareButton.tooltipText = getString(R.string.button_text_share)
                }
                CameraRollViewModel.STATE_SHOWING_BACKUP -> {
                    toggleBackupsButton.run {
                        if (icon is AnimatedVectorDrawable) (icon as AnimatedVectorDrawable).stop()
                        icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_baseline_archive_24)
                        iconTint = ContextCompat.getColorStateList(requireContext(), R.color.toggle_group_button_bw)
                        isEnabled = true
                    }
                    shareButton.setImageDrawable(ContextCompat.getDrawable(requireContext(), R.drawable.ic_baseline_archivev_download_24))
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) shareButton.tooltipText = getString(R.string.button_text_download)
                }
                CameraRollViewModel.STATE_BACKUP_NOT_AVAILABLE -> {
                    toggleBackupsButton.run {
                        if (icon is AnimatedVectorDrawable) (icon as AnimatedVectorDrawable).stop()
                        icon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_baseline_archive_24)
                        iconTint = ContextCompat.getColorStateList(requireContext(), R.color.toggle_group_button_bw)
                    }
                    setSourceGroupState(false)
                    setButtonGroupState(true)
                    allowToggleContent = false
                    Snackbar.make(mediaPager, getString(R.string.msg_empty_server_backup), Snackbar.LENGTH_LONG).show()
                }
            }
        })

        viewLifecycleOwner.lifecycleScope.launch {
            imageLoaderModel.shareOutUris.collect { uris ->
                handler.removeCallbacksAndMessages(null)
                if (waitingMsg?.isShownOrQueued == true) waitingMsg?.dismiss()

                val cr = requireActivity().contentResolver
                val clipData = ClipData.newUri(cr, "", uris[0])
                for (i in 1 until uris.size) {
                    if (isActive) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) clipData.addItem(cr, ClipData.Item(uris[i]))
                        else clipData.addItem(ClipData.Item(uris[i]))
                    }
                }
                startActivity(Intent.createChooser(Intent().apply {
                    if (uris.size > 1) {
                        action = Intent.ACTION_SEND_MULTIPLE
                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    } else {
                        // If sharing only one picture, use ACTION_SEND instead, so that other apps which won't accept ACTION_SEND_MULTIPLE will work
                        action = Intent.ACTION_SEND
                        putExtra(Intent.EXTRA_STREAM, uris[0])
                    }
                    type = requireContext().contentResolver.getType(uris[0]) ?: "image/*"
                    if (type!!.startsWith("image")) type = "image/*"
                    this.clipData = clipData
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    putExtra(ShareReceiverActivity.KEY_SHOW_REMOVE_OPTION, true)
                }, null))
            }
        }.invokeOnCompletion {
            handler.removeCallbacksAndMessages(null)
            if (waitingMsg?.isShownOrQueued == true) waitingMsg?.dismiss()
        }

        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(removeOriginalBroadcastReceiver, IntentFilter(AcquiringDialogFragment.BROADCAST_REMOVE_ORIGINAL))

        // Removing medias confirm result handler
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            deleteMediaLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK) camerarollModel.removeMedias(lastSelection)
            }
        }

        parentFragmentManager.setFragmentResultListener(ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY, viewLifecycleOwner) { _, bundle ->
            when(bundle.getString(ConfirmDialogFragment.INDIVIDUAL_REQUEST_KEY)) {
                DELETE_REQUEST_KEY -> {
                    if (bundle.getBoolean(ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY, false)) {
                        if (toggleCameraRollButton.isChecked) camerarollModel.removeMedias(lastSelection)
                        else {
                            // For camera backs on server
                            val actions = mutableListOf<Action>()
                            val removeList = mutableListOf<String>()
                            if (selectionTracker.hasSelection()) {
                                // Multiple photos
                                selectionTracker.selection.forEach { photoId ->
                                    camerarollModel.getPhotoById(photoId)?.let { photo ->
                                        //removeFiles.add("${remoteArchiveBaseFolder}/${photo.name}")
                                        actions.add(Action(null, Action.ACTION_DELETE_CAMERA_BACKUP_FILE, photo.albumId, "", photo.id, photo.name, System.currentTimeMillis(), 1))
                                        removeList.add(photo.id)
                                    }
                                }
                            } else {
                                // Current displayed photo
                                camerarollModel.getCurrentPhoto()?.let { photo ->
                                    //removeFiles.add("${remoteArchiveBaseFolder}/${photo.name}")
                                    actions.add(Action(null, Action.ACTION_DELETE_CAMERA_BACKUP_FILE, photo.albumId, "", photo.id, photo.name, System.currentTimeMillis(), 1))
                                    removeList.add(photo.id)
                                }
                                // Dismiss BottomSheet when it's in collapsed mode, which means it's working on the current item
                                bottomSheet.state = BottomSheetBehavior.STATE_HIDDEN
                            }
                            camerarollModel.removeBackup(removeList)

                            selectionTracker.clearSelection()

                            if (actions.isNotEmpty()) actionModel.addActions(actions)
                        }
                    }
                }
                STRIP_REQUEST_KEY -> shareOut(bundle.getBoolean(ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY, false))
            }
        }

        // Restore bottom sheet state here when all the observer and listener should be working now
        savedInstanceState?.let {
            it.getInt(KEY_BOTTOMSHEET_STATE).let { state ->
                when(state) {
                    BottomSheetBehavior.STATE_EXPANDED -> {
                        updateExpandedDisplay()
                    }
                }
                bottomSheet.state = state
            }
        } ?: run {
            if (showListFirst) {
                updateExpandedDisplay()
                bottomSheet.state = BottomSheetBehavior.STATE_EXPANDED
            }
        }

        savedInstanceState?.let {
            if (it.getBoolean(KEY_SHAREOUT_RUNNING)) {
                stripOrNot = it.getBoolean(KEY_SHAREOUT_STRIP)
                waitingMsg = Tools.getPreparingSharesSnackBar(mediaPager, stripOrNot) { imageLoaderModel.cancelShareOut() }.apply { show() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        (requireActivity() as AppCompatActivity).window.run {
            savedStatusBarColor = statusBarColor
            savedNavigationBarColor = navigationBarColor
            statusBarColor = Color.BLACK
            navigationBarColor = Color.BLACK

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                savedNavigationBarDividerColor = navigationBarDividerColor
                navigationBarDividerColor = Color.BLACK
            }
        }

        // Hide action bar if called from CameraRollActivity, or a video item from AlbumDetailFragment, e.g. cases that will not support transition animation. Otherwise, action bar will be hided after transition finished
        if (tag == TAG_FROM_CAMERAROLL_ACTIVITY || tag == null || showListFirst) (requireActivity() as AppCompatActivity).supportActionBar?.hide()
    }

    override fun onPause() {
        super.onPause()
        playerViewModel.pause(Uri.EMPTY)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putParcelableArrayList(KEY_LAST_SELECTION, lastSelection)
        outState.putInt(KEY_BOTTOMSHEET_STATE, bottomSheet.state)
        camerarollModel.saveQuickScrollState(quickScroll.layoutManager?.onSaveInstanceState())

        outState.putBoolean(KEY_SHAREOUT_RUNNING, waitingMsg?.isShownOrQueued == true)
        outState.putBoolean(KEY_SHAREOUT_STRIP, stripOrNot)
    }

    override fun onDestroyView() {
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(removeOriginalBroadcastReceiver)
        mediaPager.adapter = null
        quickScroll.adapter = null

        super.onDestroyView()
    }

    override fun onDestroy() {
        (requireActivity() as AppCompatActivity).run {
            supportActionBar?.show()
            with(window) {
                statusBarColor = savedStatusBarColor
                navigationBarColor = savedNavigationBarColor

                // Restore display cutout area background
                //setBackgroundDrawable(ColorDrawable(Tools.getAttributeColor(requireContext(), R.attr.backgroundColor)))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) window.navigationBarDividerColor = savedNavigationBarDividerColor
        }

        super.onDestroy()
    }

    private fun observeCameraRoll() {
        // Observing media list update
        camerarollModel.getMediaList().observe(viewLifecycleOwner, Observer {
/*
            when(it.size) {

                0-> {
                    Toast.makeText(requireContext(), getString(R.string.empty_camera_roll), Toast.LENGTH_SHORT).show()
                    if (requireActivity() is MainActivity) parentFragmentManager.popBackStack() else requireActivity().finish()
                }
                1-> {
                    // Disable quick scroll if there is only one media
                    quickScroll.isVisible = false
                    divider.isVisible = false
                    //if (bottomSheet.state == BottomSheetBehavior.STATE_EXPANDED) bottomSheet.state = BottomSheetBehavior.STATE_COLLAPSED
                    bottomSheet.state = BottomSheetBehavior.STATE_HIDDEN

                    // Disable share function if scheme of the uri shared with us is "file", this only happened when viewing a single file
                    //if (mediaPagerAdapter.getMediaAtPosition(0).id.startsWith("file")) shareButton.isEnabled = false
                }
            }
*/
            // Populate list and scroll to correct position
            mediaPagerAdapter.submitList(it) {
                // Set initial position if passed in arguments or restore from last session
                if (startWithThisMedia.isNotEmpty()) {
                    camerarollModel.setCurrentPosition(it.indexOfFirst { media -> media.id == startWithThisMedia })
                    startWithThisMedia = ""
                }
                mediaPager.scrollToPosition(camerarollModel.getCurrentPosition())

                if (it.size == 0) {
                    (requireActivity() as AppCompatActivity).supportActionBar?.hide()
                    startPostponedEnterTransition()
                }
            }

            if (bottomSheet.state == BottomSheetBehavior.STATE_COLLAPSED) updateMetaDisplay()

            datePickerButton.isEnabled = false
            (quickScroll.adapter as QuickScrollAdapter).submitList(it) {
                camerarollModel.getQuickScrollState()?.let { savedState -> quickScroll.layoutManager?.onRestoreInstanceState(savedState) }
                datePickerButton.isEnabled = true
            }

            if (toggleBackupsButton.isChecked) {
                aBadge.number = it.size
                aBadge.isVisible = true
            }
            else {
                cBadge.number = it.size
                cBadge.isVisible = true
            }
        })

        // If launched as picture viewer and last photo get removed, quit immediately. See isCameraRollEmpty function in CameraRollModel for detail
        camerarollModel.isCameraRollEmpty().observe(viewLifecycleOwner, Observer {
            if (it) requireActivity().finish()
        })
    }

    @SuppressLint("SetTextI18n")
    private fun updateMetaDisplay() {
        try {
            //with(mediaPagerAdapter.getMediaAtPosition(getCurrentVisibleItemPosition())) {
            camerarollModel.getCurrentPhoto()?.run {
                dateTextView.text = "${dateTaken.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())}, ${dateTaken.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))}"
                dateTextView.isVisible = true

                shareId.toLong().let { size ->
                    sizeTextView.text = Tools.humanReadableByteCountSI(size)
                    sizeTextView.isVisible = size > 0
                }
            }
        } catch (e: IndexOutOfBoundsException) {}

        val primaryTextColor = Tools.getAttributeColor(requireContext(), android.R.attr.textColorPrimary)
        dateTextView.setTextColor(primaryTextColor)
        sizeTextView.setTextColor(primaryTextColor)
    }

    private fun updateExpandedDisplay() {
        dateTextView.isVisible = false
        sizeTextView.isVisible = false
        removeButton.isEnabled = false
        shareButton.isEnabled = false
        lespasButton.isEnabled = false

        infoButton.isVisible = false
        infoButton.isEnabled = false

        closeButton.isEnabled = true
        closeButton.isVisible = true
        selectionText.isVisible = true

        quickScroll.foreground = null

        setButtonGroupState(!allowToggleContent)
        setSourceGroupState(true)
    }

    fun setButtonGroupState(state: Boolean) {
        buttonGroup.run {
            isVisible = state
            isEnabled = state
            alpha = if (state) 1f else 0f
        }
    }
    fun setSourceGroupState(state: Boolean) {
        val mState = state && allowToggleContent
        sourceToggleGroup.run {
            isVisible = mState
            isEnabled = mState
            alpha = if (mState) 1f else 0f
        }
        datePickerButton.run {
            isVisible = mState
            isEnabled = mState
            alpha = if (mState) 1f else 0f
        }
    }

    fun flashPhoto(view: View) {
        ObjectAnimator.ofPropertyValuesHolder(view, sx, sy).run {
            duration = 800
            repeatMode = ValueAnimator.REVERSE
            interpolator = BounceInterpolator()
            start()
        }
    }
    private fun flashDate(view: View) {
        ObjectAnimator.ofPropertyValuesHolder(view, tx).run {
            duration = 800
            repeatMode = ValueAnimator.REVERSE
            interpolator = BounceInterpolator()
            start()
        }
    }

    private fun copyTargetUris() {
        // Save target media item list
        lastSelection =
            if (selectionTracker.hasSelection()) {
                val uris = arrayListOf<Uri>()
                for (uri in selectionTracker.selection) uris.add(Uri.parse(uri))
                selectionTracker.clearSelection()
                uris
            } else camerarollModel.getCurrentPhoto()?.let { arrayListOf(Uri.parse(it.id)) } ?: arrayListOf()

        // Dismiss BottomSheet when it's in collapsed mode, which means it's working on the current item
        if (bottomSheet.state == BottomSheetBehavior.STATE_COLLAPSED) bottomSheet.state = BottomSheetBehavior.STATE_HIDDEN
    }

    private fun hasExifInSelection(): Boolean {
        for (photoId in lastSelection) {
            //if (Tools.hasExif(mediaPagerAdapter.getPhotoBy(photoId.toString()).mimeType)) return true
            return camerarollModel.getPhotoById(photoId.toString())?.let { Tools.hasExif(it.mimeType) } ?: false
        }

        return false
    }

    private fun shareOut(strip: Boolean) {
        stripOrNot = strip
        waitingMsg = Tools.getPreparingSharesSnackBar(mediaPager, strip) { imageLoaderModel.cancelShareOut() }

        // Show a SnackBar if it takes too long (more than 500ms) preparing shares
        handler.postDelayed({ waitingMsg?.show() }, 500)

        // Collect photos for sharing
        val photos = mutableListOf<Photo>()
        for (id in lastSelection) camerarollModel.getPhotoById(id.toString())?.let { photos.add(it) }
        selectionTracker.clearSelection()

        // Prepare media files for sharing
        imageLoaderModel.prepareFileForShareOut(photos, strip, false, "")
    }

    @Suppress("UNCHECKED_CAST")
    class CameraRollViewModelFactory(private val application: Application, private val fileUri: String?, private val inArchive: Boolean): ViewModelProvider.NewInstanceFactory() {
        override fun <T : ViewModel> create(modelClass: Class<T>): T = CameraRollViewModel(application, fileUri, inArchive) as T
    }

    class CameraRollViewModel(private val ctx: Application, private val fileUri: String?, inArchive: Boolean): ViewModel() {
        private val mediaList = MutableLiveData<MutableList<Photo>>()
        private var cameraRoll = mutableListOf<Photo>()
        private var backups = mutableListOf<Photo>()
        private val cr = ctx.contentResolver
        private val vmState = MutableLiveData<Int>()
        private val position = arrayListOf(0, 0)
        private var shouldDisableRemove = false
        private var shouldDisableShare = false
        private val quickScrollState: Array<Parcelable?> = arrayOf(null, null)
        private val snapshotRemovedList = mutableListOf<String>()
        private val camerarollIsEmpty = SingleLiveEvent<Boolean>()

        init {
            camerarollIsEmpty.postValue(false)

            if (inArchive) {
                vmState.postValue(STATE_FETCHING_BACKUP)
                fetchPhotoFromServerBackup()
            } else {
                vmState.postValue(STATE_SHOWING_DEVICE)
                fetchCameraRoll()
            }
        }

        fun getVMState(): LiveData<Int> = vmState

        fun fetchPhotoFromServerBackup() {
            viewModelScope.launch(Dispatchers.IO) {
                if (backups.isEmpty()) {
                    var snapshot = mutableListOf<Photo>()
                    val backupList = mutableListOf<Photo>()
                    try {
                        // Fetch backups from server if needed
                        vmState.postValue(STATE_FETCHING_BACKUP)

                        // Show last snapshot
                        snapshot = readSnapshot()
                        if (snapshot.isNotEmpty()) { mediaList.postValue(snapshot) }

                        val webDav: OkHttpWebDav
                        val dcimRoot: String

                        AccountManager.get(ctx).run {
                            val account = getAccountsByType(ctx.getString(R.string.account_type_nc))[0]
                            val userName = getUserData(account, ctx.getString(R.string.nc_userdata_username))
                            val baseUrl = getUserData(account, ctx.getString(R.string.nc_userdata_server))
                            dcimRoot = "$baseUrl${ctx.getString(R.string.dav_files_endpoint)}$userName${Tools.getCameraArchiveHome(ctx)}"
                            webDav = OkHttpWebDav(userName, getUserData(account, ctx.getString(R.string.nc_userdata_secret)), baseUrl, getUserData(account, ctx.getString(R.string.nc_userdata_selfsigned)).toBoolean(), getUserData(account, ctx.getString(R.string.nc_userdata_certificate)), null, "LesPas_${ctx.getString(R.string.lespas_version)}", 0)
                        }

                        webDav.listWithExtraMeta(dcimRoot, OkHttpWebDav.RECURSIVE_DEPTH).forEach { dav ->
                            if (dav.contentType.startsWith("image/") || dav.contentType.startsWith("video/")) {
                                backupList.add(
                                    Photo(
                                        id = dav.fileId, albumId = dav.albumId, name = dav.name, eTag = dav.eTag, mimeType = dav.contentType,
                                        dateTaken = dav.dateTaken, lastModified = dav.modified,
                                        width = dav.width, height = dav.height, orientation = dav.orientation,
                                        // Store file size in property shareId
                                        shareId = dav.size.toInt(),
                                        latitude = dav.latitude, longitude = dav.longitude, altitude = dav.altitude, bearing = dav.bearing,
                                    )
                                )
                            }
                        }
                    } catch (e: Exception) { e.printStackTrace() }

                    if (backupList.isNotEmpty()) {
                        backupList.sortByDescending { it.dateTaken }
                    } else {
                        // If fail fetching backups from server, use snapshot
                        if (snapshot.isNotEmpty()) backupList.addAll(snapshot)
                    }

                    // Remove those removed in snapshot before archive synced
                    for (photoId in snapshotRemovedList) { backupList.indexOfFirst { it.id == photoId }.let { index -> if (index != -1) backupList.removeAt(index) }}

                    // If we have snapshot, make sure recyclerview's current position won't drift after data updated
                    if (snapshot.isNotEmpty()) (getCurrentPhoto()?.name.let { current -> backupList.indexOfFirst { it.name == current } }).let { newPosition -> setCurrentPosition(if (newPosition == -1) 0 else newPosition) }

                    setBackup(backupList)
                    snapshotRemovedList.clear()
                }

                if (backups.isNotEmpty()) {
                    vmState.postValue(STATE_SHOWING_BACKUP)
                    mediaList.postValue(backups)
                } else vmState.postValue(STATE_BACKUP_NOT_AVAILABLE)
            }
        }

        fun fetchCameraRoll() {
            viewModelScope.launch(Dispatchers.IO) {
                if (cameraRoll.isEmpty()) {
                    fileUri?.apply {
                        // Launched as picture viewer
                        Tools.getFolderFromUri(this, cr)?.let { uri ->
                            // If we can access the folder
                            //Log.e(">>>>>", "${uri.first}   ${uri.second}")
                            cameraRoll.addAll(Tools.listMediaContent(uri.first, cr, imageOnly = false, strict = true))
                            setCurrentPosition(cameraRoll.indexOfFirst { it.id.substringAfterLast('/') == uri.second })
                        } ?: run {
                            // Single file, need to do housework here

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) shouldDisableRemove = true

                            val uri = Uri.parse(this)
                            val mimeType = cr.getType(uri)?.let { Intent.normalizeMimeType(it) } ?: run {
                                MimeTypeMap.getSingleton().getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(fileUri).lowercase()) ?: Photo.DEFAULT_MIMETYPE
                            }
                            var filename = ""
                            var size = 0
                            when (uri.scheme) {
                                "content" -> {
                                    try {
                                        cr.query(uri, null, null, null, null)?.use { cursor ->
                                            cursor.moveToFirst()
                                            try { cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))?.let { filename = it } } catch (e: IllegalArgumentException) { }
                                            try { cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE))?.let { size = it.toInt() }} catch (e: Exception) {
                                            }
                                        }
                                    } catch (e: Exception) {}
                                }
                                "file" -> {
                                    uri.path?.let { filename = it.substringAfterLast('/') }
                                    shouldDisableShare = true
                                }
                            }

                            var metadataRetriever: MediaMetadataRetriever? = null
                            var exifInterface: ExifInterface? = null
                            if (mimeType.startsWith("video/")) metadataRetriever = try { MediaMetadataRetriever().apply { setDataSource(ctx, uri) }} catch (e: SecurityException) { null } catch (e: RuntimeException) { null }
                            else if (Tools.hasExif(mimeType)) try { exifInterface = cr.openInputStream(uri)?.let { ExifInterface(it) }} catch (e: Exception) {}
                            val photo = Tools.getPhotoParams(metadataRetriever, exifInterface,"", mimeType, filename, keepOriginalOrientation = true, uri = uri, cr = cr).copy(
                                albumId = FROM_CAMERA_ROLL,
                                name = filename,
                                id = this,                  // fileUri shared in as photo's id in Camera Roll album
                                shareId = size,             // Temporarily use shareId for saving file's size TODO maximum 4GB
                            )
                            metadataRetriever?.release()

                            cameraRoll.add(photo)
                        }
                    } ?: run {
                        // Launched as camera roll manager
                        cameraRoll.addAll(Tools.getCameraRoll(cr, false))
                    }
                }

                vmState.postValue(STATE_SHOWING_DEVICE)
                mediaList.postValue(cameraRoll)
            }

        }
        fun getMediaList(): LiveData<MutableList<Photo>> = mediaList
        fun isCameraRollEmpty(): SingleLiveEvent<Boolean> = camerarollIsEmpty

        fun getPhotoById(photoId: String): Photo? = mediaList.value?.let { list -> list.find { it.id == photoId }}
        fun findPhotoPosition(photoId: String): Int = mediaList.value?.let { list -> list.indexOfFirst { it.id == photoId }} ?: -1
        fun removeMedias(removeList: List<Uri>) {
            // Remove from system if running on Android 10 or lower
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) removeList.forEach { media-> cr.delete(media, null, null) }

            // Remove from our list
            cameraRoll.toMutableList().run {
                removeAll { removeList.contains(Uri.parse(it.id)) }

                if (size == 0 && fileUri != null)
                    // If launched as picture viewer and last photo get removed, quit immediately
                    camerarollIsEmpty.postValue(true)
                else {
                    if (position[0] >= size) position[0] = max(size - 1, 0)
                    mediaList.postValue(this)
                    cameraRoll = this
                }
            }
        }
        fun removeBackup(removeList: List<String>) {
            // Remove from our list
            mediaList.value?.toMutableList()?.let { pList ->
                for (id in removeList) { pList.indexOfFirst { it.id == id }.let { index -> if (index != -1) pList.removeAt(index) }}

                if (pList.size == 0) {
                    // All element removed
                    vmState.postValue(STATE_BACKUP_NOT_AVAILABLE)
                    mediaList.postValue(cameraRoll)
                } else {
                    // If last element removed
                    if (position[1] >= pList.size) position[1] = max(pList.size - 1, 0)

                    mediaList.postValue(pList)

                    if (backups.isNotEmpty()) setBackup(pList)
                    else {
                        // Removing in snapshot, maintain a removed list in order to show correct list after archive synced
                        // TODO partial removal
                        snapshotRemovedList.addAll(removeList)
                    }
                }
            }
        }

        @Synchronized private fun setBackup(newList: MutableList<Photo>) { backups = newList }
        private fun getCurrentSource() = vmState.value?.let { if (it > 0) 1 else 0 } ?: 0
        fun setCurrentPosition(newPosition: Int) { position[getCurrentSource()] = newPosition }
        fun getCurrentPosition(): Int = position[getCurrentSource()]
        fun getCurrentPhoto(): Photo? = mediaList.value?.let { if (it.size == 0) null else it[position[getCurrentSource()]] }

        fun shouldDisableRemove(): Boolean = this.shouldDisableRemove
        fun shouldDisableShare(): Boolean = this.shouldDisableShare

        override fun onCleared() {
            if (backups.isNotEmpty()) writeSnapshot(backups)
            super.onCleared()
        }
        private fun writeSnapshot(photos: List<Photo>) {
            try {
                File(Tools.getLocalRoot(ctx), SNAPSHOT_FILENAME).writer().use {
                    it.write(Tools.photosToMetaJSONString(photos))
                }
            } catch (e: Exception) {}
        }
        private fun readSnapshot(): MutableList<Photo> {
            val result = mutableListOf<Photo>()
            try {
                File(Tools.getLocalRoot(ctx), SNAPSHOT_FILENAME).inputStream().use { fs ->
                    Tools.readContentMeta(fs, "DCIM").forEach { result.add(it.photo) }
                }
            } catch (e: Exception) {}

            return result
        }

        fun saveQuickScrollState(state: Parcelable?) { quickScrollState[getCurrentSource()] = state }
        fun getQuickScrollState(): Parcelable? = quickScrollState[getCurrentSource()]

        companion object {
            const val STATE_SHOWING_DEVICE = 0
            const val STATE_SHOWING_BACKUP = 1
            const val STATE_FETCHING_BACKUP = 2
            const val STATE_BACKUP_NOT_AVAILABLE = 3

            const val SNAPSHOT_FILENAME = "camera_backup_snapshot.json"
        }
    }

    class MediaPagerAdapter(context: Context, private val basePath: String, displayWidth: Int, playerViewModel: VideoPlayerViewModel, val clickListener: (Boolean?) -> Unit, val imageLoader: (Photo, ImageView?, String) -> Unit, cancelLoader: (View) -> Unit
    ): SeamlessMediaSliderAdapter<Photo>(context, displayWidth, PhotoDiffCallback(), playerViewModel, clickListener, imageLoader, cancelLoader) {
        override fun getVideoItem(position: Int): VideoItem = with(getItem(position) as Photo) {
            if (albumId == FROM_CAMERA_ROLL) VideoItem(Uri.parse(id), mimeType, width, height, id.substringAfterLast('/'))
            else VideoItem(Uri.parse("${basePath}/${name}"), mimeType, width, height, id)
        }
        override fun getItemTransitionName(position: Int): String = (getItem(position) as Photo).id
        override fun getItemMimeType(position: Int): String = (getItem(position) as Photo).mimeType

        fun patchMeta(photoId: String, exif: ExifInterface) {
            currentList.find { it.id == photoId }?.let { photo ->
                photo.orientation = exif.rotationDegrees
                photo.width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
                photo.height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
                // TODO changing dateTaken property will change photo position in list
                //exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL).let { timeString -> }
                exif.latLong?.let { latLong ->
                    photo.latitude = latLong[0]
                    photo.longitude = latLong[1]
                    photo.altitude = exif.getAltitude(Photo.NO_GPS_DATA)
                    photo.bearing = Tools.getBearing(exif)
                }
            }
        }
    }

    class QuickScrollAdapter(private val clickListener: (Photo) -> Unit, private val imageLoader: (Photo, ImageView, String) -> Unit, private val cancelLoader: (View) -> Unit, private val flashPhoto: (View) -> Unit, private val flashDate: (View) -> Unit
    ): ListAdapter<Photo, ViewHolder>(PhotoDiffCallback()) {
        private lateinit var selectionTracker: SelectionTracker<String>
        private val selectedFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0.0f) })
        private var playMark: Drawable? = null
        private var selectedMark: Drawable? = null
        private var flashPhotoId = ""
        private var flashDateId = LocalDate.MIN
        private val defaultOffset = OffsetDateTime.now().offset

        inner class MediaViewHolder(itemView: View): ViewHolder(itemView) {
            private var currentId = ""
            private val ivPhoto = itemView.findViewById<ImageView>(R.id.photo).apply { foregroundGravity = Gravity.CENTER }

            fun bind(item: Photo) {
                itemView.let {
                    it.isSelected = selectionTracker.isSelected(item.id)

                    with(ivPhoto) {
                        if (currentId != item.id) {
                            this.setImageResource(0)
                            imageLoader(item, this, NCShareViewModel.TYPE_GRID)
                            currentId = item.id
                        }

                        foreground = when {
                            it.isSelected -> selectedMark
                            Tools.isMediaPlayable(item.mimeType) -> playMark
                            else -> null
                        }

                        if (this.isSelected) colorFilter = selectedFilter
                        else clearColorFilter()

                        setOnClickListener { if (!selectionTracker.hasSelection()) clickListener(item) }

                        if (flashPhotoId == item.id) {
                            flashPhotoId = ""
                            flashPhoto(ivPhoto)
                        }
                    }
                }
            }

            fun getItemDetails() = object : ItemDetailsLookup.ItemDetails<String>() {
                override fun getPosition(): Int = bindingAdapterPosition
                override fun getSelectionKey(): String = getPhotoId(bindingAdapterPosition)
            }
        }

/*
        inner class DateViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
            fun bind(item: Photo) {
                with(item.dateTaken) {
                    itemView.findViewById<TextView>(R.id.month).text = this.monthValue.toString()
                    itemView.findViewById<TextView>(R.id.day).text = this.dayOfMonth.toString()
                }
            }
        }
*/

        inner class HorizontalDateViewHolder(itemView: View): ViewHolder(itemView) {
            private val tvDate = itemView.findViewById<TextView>(R.id.date)

            @SuppressLint("SetTextI18n")
            fun bind(item: Photo) {
                with(item.dateTaken) {
                    //itemView.findViewById<TextView>(R.id.date).text = "${format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG))}, ${dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())}   |   ${String.format(itemView.context.getString(R.string.total_photo), item.shareId)}"
                    tvDate.text = "${format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG))}, ${dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())}"
                }

                tvDate.setOnLongClickListener {
                    var index = currentList.indexOf(item)
                    while(true) {
                        index++
                        if (index == currentList.size) break
                        if (currentList[index].mimeType.isEmpty()) break
                        selectionTracker.select(currentList[index].id)
                    }
                    true
                }

                if (item.dateTaken.toLocalDate().isEqual(flashDateId)) {
                    flashDateId = LocalDate.MIN
                    flashDate(tvDate)
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
/*
            if (viewType == MEDIA_TYPE) MediaViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_cameraroll, parent, false))
            else DateViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_cameraroll_date, parent, false))
*/
            if (viewType == MEDIA_TYPE) MediaViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_photo, parent, false))
            else HorizontalDateViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_cameraroll_date_horizontal, parent, false))

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            if (holder is MediaViewHolder) holder.bind(currentList[position])
            //else if (holder is DateViewHolder) holder.bind(currentList[position])
            else if (holder is HorizontalDateViewHolder) holder.bind(currentList[position])
        }

/*
        override fun onViewDetachedFromWindow(holder: ViewHolder) {
            if (holder is MediaViewHolder) holder.itemView.findViewById<View>(R.id.photo).let { cancelLoader(it) }
            super.onViewDetachedFromWindow(holder)
        }
*/

        override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
            for (i in 0 until currentList.size) {
                recyclerView.findViewHolderForAdapterPosition(i)?.let { holder -> if (holder is MediaViewHolder) holder.itemView.findViewById<View>(R.id.photo)?.let { cancelLoader(it) }}
            }
            super.onDetachedFromRecyclerView(recyclerView)
        }

        override fun submitList(list: MutableList<Photo>?, commitCallback: Runnable?) {
            list?.apply {
                // Group by date
                val listGroupedByDate = mutableListOf<Photo>()
                var currentDate = LocalDate.now().plusDays(1)
                for (media in list) {
                    if (media.dateTaken.toLocalDate() != currentDate) {
                        currentDate = media.dateTaken.toLocalDate()
                        // Add a fake photo item by taking default value for nearly all properties, denotes a date separator
                        listGroupedByDate.add(Photo(albumId = FROM_CAMERA_ROLL, dateTaken = media.dateTaken, lastModified = media.dateTaken, mimeType = ""))
                        //listGroupedByDate.add(Photo("", FROM_CAMERA_ROLL, "", "", media.dateTaken, media.dateTaken, 0, 0, "", 0))
                    }
                    listGroupedByDate.add(media)
                }
/*
                // Get total for each date
                var sectionCount = 0
                for (i in listGroupedByDate.size-1 downTo 0) {
                    if (listGroupedByDate[i].id.isEmpty()) {
                        listGroupedByDate[i].shareId = sectionCount
                        sectionCount = 0
                    }
                    else sectionCount++
                }
*/

                super.submitList(listGroupedByDate, commitCallback)
            }
        }

        internal fun setPlayMarkDrawable(newDrawable: Drawable) { playMark = newDrawable }
        internal fun setSelectedMarkDrawable(newDrawable: Drawable) { selectedMark = newDrawable }

        override fun getItemViewType(position: Int): Int = if (currentList[position].id.isEmpty()) DATE_TYPE else MEDIA_TYPE

        internal fun setSelectionTracker(selectionTracker: SelectionTracker<String>) { this.selectionTracker = selectionTracker }
        internal fun getPhotoId(position: Int): String = currentList[position].id
        internal fun getPhotoPosition(photoId: String): Int = currentList.indexOfLast { it.id == photoId }

        fun hasDate(date: Long): Boolean {
            val theDate = LocalDateTime.ofInstant(Instant.ofEpochMilli(date), ZoneId.systemDefault()).toLocalDate()
            return (currentList.indexOfFirst { it.mimeType.isEmpty() && it.dateTaken.toLocalDate().isEqual(theDate) }) != NO_POSITION
        }
        fun dateRange(): Pair<Long, Long>? {
            return if (currentList.isNotEmpty()) Pair(currentList.last().dateTaken.atZone(defaultOffset).toInstant().toEpochMilli(), currentList.first().dateTaken.atZone(defaultOffset).toInstant().toEpochMilli()) else null
        }
        fun getPositionByDate(date: Long): Int = currentList.indexOfFirst { it.mimeType.isEmpty() && it.dateTaken.atZone(defaultOffset).toInstant().toEpochMilli() - date < 86400000 }
        fun getDateByPosition(position: Int): Long = currentList[position].dateTaken.atZone(defaultOffset).toInstant().toEpochMilli()
        fun setFlashPhoto(photoId: String) { flashPhotoId = photoId }
        fun setFlashDate(date: Long) { flashDateId = LocalDateTime.ofInstant(Instant.ofEpochMilli(date), ZoneId.systemDefault()).toLocalDate() }

        class PhotoKeyProvider(private val adapter: QuickScrollAdapter): ItemKeyProvider<String>(SCOPE_CACHED) {
            override fun getKey(position: Int): String = adapter.getPhotoId(position)
            override fun getPosition(key: String): Int = adapter.getPhotoPosition(key)
        }
        class PhotoDetailsLookup(private val recyclerView: RecyclerView): ItemDetailsLookup<String>() {
            override fun getItemDetails(e: MotionEvent): ItemDetails<String>? {
                recyclerView.findChildViewUnder(e.x, e.y)?.let {
                    val holder = recyclerView.getChildViewHolder(it)
                    return if (holder is MediaViewHolder) holder.getItemDetails() else null
                }
                return null
            }
        }

        companion object {
            private const val MEDIA_TYPE = 0
            const val DATE_TYPE = 1
        }
    }

    class PhotoDiffCallback : DiffUtil.ItemCallback<Photo>() {
        override fun areItemsTheSame(oldItem: Photo, newItem: Photo): Boolean = if (oldItem.id.isEmpty() || newItem.id.isEmpty()) false else oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Photo, newItem: Photo): Boolean = true
    }

    companion object {
        //const val FROM_CAMERA_ROLL = "!@#$%^&*()_+alkdfj4654"
        const val FROM_CAMERA_ROLL = "0"
        const val EMPTY_ROLL_COVER_ID = "0"

        private const val KEY_SCROLL_TO = "KEY_SCROLL_TO"
        private const val KEY_IN_ARCHIVE = "KEY_IN_ARCHIVE"
        private const val KEY_URI = "KEY_URI"
        private const val KEY_LAST_SELECTION = "KEY_LAST_SELECTION"
        private const val KEY_BOTTOMSHEET_STATE = "KEY_BOTTOMSHEET_STATE"
        private const val KEY_SHAREOUT_RUNNING = "KEY_SHAREOUT_RUNNING"
        private const val KEY_SHAREOUT_STRIP = "KEY_SHAREOUT_STRIP"

        const val TAG_FROM_CAMERAROLL_ACTIVITY = "TAG_DESTINATION_DIALOG"
        const val TAG_DESTINATION_DIALOG = "CAMERAROLL_DESTINATION_DIALOG"
        const val TAG_ACQUIRING_DIALOG = "CAMERAROLL_ACQUIRING_DIALOG"
        private const val CONFIRM_DIALOG = "CONFIRM_DIALOG"
        private const val INFO_DIALOG = "INFO_DIALOG"
        private const val DELETE_REQUEST_KEY = "CAMERA_ROLL_DELETE_REQUEST_KEY"
        private const val STRIP_REQUEST_KEY = "CAMERA_ROLL_STRIP_REQUEST_KEY"

        @JvmStatic
        fun newInstance(scrollTo: String = "", inArchive: Boolean = false) = CameraRollFragment().apply {
            arguments = Bundle().apply {
                putString(KEY_SCROLL_TO, scrollTo)
                putBoolean(KEY_IN_ARCHIVE, inArchive)
            }
        }

        @JvmStatic
        fun newInstance(uri: Uri) = CameraRollFragment().apply { arguments = Bundle().apply { putString(KEY_URI, uri.toString()) }}
    }
}