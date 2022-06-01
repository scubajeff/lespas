package site.leos.apps.lespas.cameraroll

import android.accounts.AccountManager
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ContentResolver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
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
import androidx.core.content.FileProvider
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
import androidx.transition.Transition
import com.google.android.material.badge.BadgeDrawable
import com.google.android.material.badge.BadgeUtils
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.transition.MaterialContainerTransform
import kotlinx.coroutines.*
import site.leos.apps.lespas.MainActivity
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.*
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.publication.NCShareViewModel
import site.leos.apps.lespas.publication.PublicationDetailFragment
import site.leos.apps.lespas.search.SearchResultFragment
import site.leos.apps.lespas.sync.*
import java.io.File
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.*
import kotlin.collections.contains
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class CameraRollFragment : Fragment(), MainActivity.OnWindowFocusChangedListener {
    private lateinit var bottomSheet: BottomSheetBehavior<ConstraintLayout>
    private lateinit var mediaPager: RecyclerView
    private lateinit var quickScroll: RecyclerView
    private lateinit var mediaPagerAdapter: MediaPagerAdapter
    private lateinit var quickScrollAdapter: QuickScrollAdapter
    private var quickScrollGridSpanCount = 0

    private lateinit var mediaPagerEmptyView: ImageView
    private lateinit var quickScrollEmptyView: ImageView
    private lateinit var divider: View
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
    private lateinit var cBadge: BadgeDrawable
    private lateinit var aBadge: BadgeDrawable

    private var savedStatusBarColor = 0
    private var savedNavigationBarColor = 0
    private var savedNavigationBarDividerColor = 0

    private var startWithThisMedia: String = ""
    private lateinit var selectionTracker: SelectionTracker<String>
    private var lastSelection = arrayListOf<Uri>()
    private var stripExif = "2"
    private var showListFirst = false
    private var ignoreHide = true
    private var allowToggleContent = true   // disable content toggle between device and remote if called from SearchResultFragment or started as a image viewer

    private val imageLoaderModel: NCShareViewModel by activityViewModels()
    private val destinationModel: DestinationDialogFragment.DestinationViewModel by activityViewModels()
    private val camerarollModel: CameraRollViewModel by viewModels { CameraRollViewModelFactory(requireActivity().application, requireArguments().getString(KEY_URI), requireArguments().getBoolean(KEY_IN_ARCHIVE)) }
    private val playerViewModel: VideoPlayerViewModel by viewModels { VideoPlayerViewModelFactory(requireActivity().application, imageLoaderModel.getCallFactory()) }
    private val actionModel: ActionViewModel by viewModels()

    private lateinit var removeOriginalBroadcastReceiver: RemoveOriginalBroadcastReceiver
    private lateinit var deleteMediaLauncher: ActivityResultLauncher<IntentSenderRequest>
    private lateinit var storagePermissionRequestLauncher: ActivityResultLauncher<String>
    private lateinit var accessMediaLocationPermissionRequestLauncher: ActivityResultLauncher<String>
    private lateinit var gestureDetector: GestureDetectorCompat

    private var shareOutJob: Job? = null

    private val sx = PropertyValuesHolder.ofFloat("scaleX", 1.0f, 0.8f, 1.0f)
    private val sy = PropertyValuesHolder.ofFloat("scaleY", 1.0f, 0.8f, 1.0f)
    private val tx = PropertyValuesHolder.ofFloat("translationX", 0f, 100f, 0f)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
        }

        // Create adapter here so that it won't leak
        mediaPagerAdapter = MediaPagerAdapter(
            imageLoaderModel.getResourceRoot(),
            Tools.getDisplayWidth(requireActivity().windowManager),
            playerViewModel,
            { state->
                // When in "Show media list at startup" mode, ignore the first hide bottom sheet call which fired by video auto play
                if (ignoreHide && showListFirst) ignoreHide = false
                else bottomSheet.state = if (state ?: run { bottomSheet.state == BottomSheetBehavior.STATE_HIDDEN }) BottomSheetBehavior.STATE_COLLAPSED else BottomSheetBehavior.STATE_HIDDEN
            },
            { photo, imageView, type->
                if (type == NCShareViewModel.TYPE_NULL) startPostponedEnterTransition()
                else imageLoaderModel.setImagePhoto(if (photo.albumId == FROM_CAMERA_ROLL) NCShareViewModel.RemotePhoto(photo) else NCShareViewModel.RemotePhoto(photo, "/DCIM"), imageView!!, type) {
                    startPostponedEnterTransition()
                    if (photo.width == 0 && photo.mimeType.startsWith("image")) {
                        // Patching photo's meta after it has been fetched
                        Thread { imageLoaderModel.getMediaExif(NCShareViewModel.RemotePhoto(photo, "/DCIM"))?.first?.let { exif -> mediaPagerAdapter.patchMeta(photo.id, exif) }}.run()
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
            { photo, imageView, type -> imageLoaderModel.setImagePhoto(if (photo.albumId == FROM_CAMERA_ROLL) NCShareViewModel.RemotePhoto(photo) else NCShareViewModel.RemotePhoto(photo, "/DCIM"), imageView, type) },
            { view -> imageLoaderModel.cancelSetImagePhoto(view) },
            { view -> flashPhoto(view) },
            { view -> flashDate(view) }
        ).apply { stateRestorationPolicy = Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY }

        savedInstanceState?.let {
            (requireActivity() as AppCompatActivity).supportActionBar?.hide()

            //startWithThisMedia = it.getString(KEY_SCROLL_TO) ?: ""
            lastSelection = it.getParcelableArrayList(KEY_LAST_SELECTION) ?: arrayListOf()

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
                if (toggleCameraRollButton.isChecked) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) deleteMediaLauncher.launch(IntentSenderRequest.Builder(MediaStore.createDeleteRequest(requireContext().contentResolver, lastSelection)).setFillInIntent(null).build())
                    else camerarollModel.removeMedias(lastSelection)
                }
            }

            // Immediately sync with server after adding photo to local album
            ContentResolver.requestSync(AccountManager.get(requireContext()).getAccountsByType(getString(R.string.account_type_nc))[0], getString(R.string.sync_authority), Bundle().apply {
                putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                //putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
                putInt(SyncAdapter.ACTION, SyncAdapter.SYNC_LOCAL_CHANGES)
            })
        }

        accessMediaLocationPermissionRequestLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
        storagePermissionRequestLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
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
                shareOutJob?.let {
                    if (it.isActive) {
                        it.cancel(cause = null)
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
            override fun onFling(e1: MotionEvent?, e2: MotionEvent?, velocityX: Float, velocityY: Float): Boolean {
                if (e1 != null && e2 != null) {
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
                }

                return super.onFling(e1, e2, velocityX, velocityY)
            }
        })

        quickScrollGridSpanCount = resources.getInteger(R.integer.cameraroll_grid_span_count)
        quickScrollAdapter.setPlayMarkDrawable(Tools.getPlayMarkDrawable(requireActivity(), (0.32f / quickScrollGridSpanCount)))
        quickScrollAdapter.setSelectedMarkDrawable(Tools.getSelectedMarkDrawable(requireActivity(), 0.25f / quickScrollGridSpanCount))

        playerViewModel.setWindow(requireActivity().window)

        // Save current system bar color
        (requireActivity() as AppCompatActivity).window?.run {
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

        mediaPagerEmptyView = view.findViewById(R.id.emptyview)
        quickScrollEmptyView = view.findViewById(R.id.quick_scroll_emptyview)
        divider = view.findViewById(R.id.divider)
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

        toggleCameraRollButton.doOnPreDraw { BadgeUtils.attachBadgeDrawable(cBadge, toggleCameraRollButton) }
        toggleBackupsButton.doOnPreDraw { BadgeUtils.attachBadgeDrawable(aBadge, toggleBackupsButton) }

        quickScroll = view.findViewById<RecyclerView>(R.id.quick_scroll).apply {
            adapter = quickScrollAdapter

            (layoutManager as GridLayoutManager).spanSizeLookup = object: GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    return if (quickScrollAdapter.getItemViewType(position) == QuickScrollAdapter.DATE_TYPE) quickScrollGridSpanCount else 1
                }
            }

            isNestedScrollingEnabled = true

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
                            if (!camerarollModel.shouldDisableRemove()) removeButton.isEnabled = true
                            shareButton.isEnabled = true
                            lespasButton.isEnabled = true
                            closeButton.setImageResource(R.drawable.ic_baseline_close_24)
                            selectionText.text = getString(R.string.selected_count, selection.size())
                            selectionText.isVisible = true

                            setButtonGroupState(true)
                            setSourceGroupState(false)

                            bottomSheet.isDraggable = false
                            isNestedScrollingEnabled = false
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
                            isNestedScrollingEnabled = true
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
        }
        quickScrollAdapter.setSelectionTracker(selectionTracker)

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
        }

        // Since we are not in immersive mode, set correct photo display width so that right edge can be detected
        mediaPager.doOnLayout { mediaPagerAdapter.setDisplayWidth(mediaPager.width) }

        // Start observing camera roll asap
        savedInstanceState?.let {
            observeCameraRoll()
        } ?: run {
            val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) android.Manifest.permission.READ_EXTERNAL_STORAGE else android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(requireContext(), permission) != PackageManager.PERMISSION_GRANTED) {
                requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
                storagePermissionRequestLauncher.launch(permission)
            }
            else {
                // Explicitly request ACCESS_MEDIA_LOCATION permission
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) accessMediaLocationPermissionRequestLauncher.launch(android.Manifest.permission.ACCESS_MEDIA_LOCATION)

                observeCameraRoll()
            }
        }

        mediaPagerAdapter.registerAdapterDataObserver(object : AdapterDataObserver() {
            override fun onChanged() {
                super.onChanged()
                toggleEmptyView()
            }

            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                super.onItemRangeInserted(positionStart, itemCount)
                toggleEmptyView()
            }

            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
                super.onItemRangeRemoved(positionStart, itemCount)
                toggleEmptyView()
            }

            private fun toggleEmptyView() {
                (mediaPagerAdapter.itemCount == 0).let { isEmpty ->
                    mediaPager.isVisible = !isEmpty
                    mediaPagerEmptyView.isVisible = isEmpty
                }
            }
        })
        mediaPagerEmptyView.setOnClickListener { bottomSheet.state = BottomSheetBehavior.STATE_EXPANDED }
        mediaPagerEmptyView.setOnTouchListener { _, event -> gestureDetector.onTouchEvent(event) }

        quickScrollEmptyView.setOnTouchListener { _, _ -> false }
        quickScrollAdapter.registerAdapterDataObserver(object : AdapterDataObserver() {
            override fun onChanged() {
                super.onChanged()
                toggleEmptyView()
            }

            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                super.onItemRangeInserted(positionStart, itemCount)
                toggleEmptyView()
            }

            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
                super.onItemRangeRemoved(positionStart, itemCount)
                toggleEmptyView()
            }

            private fun toggleEmptyView() {
                if (quickScrollAdapter.itemCount == 0) {
                    if (quickScroll.isVisible) {
                        quickScroll.isVisible = false
                        quickScrollEmptyView.isVisible = true
                    }
                } else {
                    if (quickScrollEmptyView.isVisible) {
                        quickScroll.isVisible = true
                        quickScrollEmptyView.isVisible = false
                    }
                }
            }
        })

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
                (if (photo.albumId == FROM_CAMERA_ROLL) MetaDataDialogFragment.newInstance(photo) else MetaDataDialogFragment.newInstance(NCShareViewModel.RemotePhoto(photo, "/DCIM"))).show(parentFragmentManager, INFO_DIALOG)
            }
        }
        shareButton.setOnClickListener {
            if (toggleCameraRollButton.isChecked) {
                // For device camera roll
                copyTargetUris()

                if (stripExif == getString(R.string.strip_ask_value)) {
                    if (hasExifInSelection()) {
                        if (parentFragmentManager.findFragmentByTag(CONFIRM_DIALOG) == null) YesNoDialogFragment.newInstance(getString(R.string.strip_exif_msg, getString(R.string.strip_exif_title)), STRIP_REQUEST_KEY).show(parentFragmentManager, CONFIRM_DIALOG)
                    } else shareOut(false)
                } else shareOut(stripExif == getString(R.string.strip_on_value))
            } else {
                // For camera backups on server
                val targets = mutableListOf<NCShareViewModel.RemotePhoto>()
                if (selectionTracker.hasSelection()) {
                    // Multiple photos
                    selectionTracker.selection.forEach { camerarollModel.getPhotoById(it)?.let { photo -> targets.add(NCShareViewModel.RemotePhoto(photo, "/DCIM")) }}
                    selectionTracker.clearSelection()
                } else {
                    // Current displayed photo
                    camerarollModel.getCurrentPhoto()?.let { targets.add(NCShareViewModel.RemotePhoto(it, "/DCIM")) }
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
                    selectionTracker.selection.forEach { photoId -> camerarollModel.getPhotoById(photoId)?.let { pList.add(NCShareViewModel.RemotePhoto(it, "DCIM")) }}
                    selectionTracker.clearSelection()
                } else camerarollModel.getCurrentPhoto()?.let { pList.add(NCShareViewModel.RemotePhoto(it, "DCIM")) }

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
                } else if (parentFragmentManager.findFragmentByTag(CONFIRM_DIALOG) == null) ConfirmDialogFragment.newInstance(getString(R.string.confirm_delete), getString(R.string.yes_delete), true, DELETE_REQUEST_KEY).show(parentFragmentManager, CONFIRM_DIALOG)
            } else
                // For camera backups on server
                if (parentFragmentManager.findFragmentByTag(CONFIRM_DIALOG) == null) ConfirmDialogFragment.newInstance(getString(R.string.confirm_delete), getString(R.string.yes_delete), true, DELETE_REQUEST_KEY).show(parentFragmentManager, CONFIRM_DIALOG)
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
            quickScrollAdapter.dateRange().let { dateRange ->
                MaterialDatePicker.Builder.datePicker()
                    .setCalendarConstraints(CalendarConstraints.Builder().setValidator(object: CalendarConstraints.DateValidator {
                        override fun describeContents(): Int = 0
                        override fun writeToParcel(dest: Parcel?, flags: Int) {}
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
                    val targetFolder = if (targetAlbum.id != PublicationDetailFragment.JOINT_ALBUM_ID) "${getString(R.string.lespas_base_folder_name)}/${targetAlbum.name}" else targetAlbum.coverFileName.substringBeforeLast('/')
                    val removeList = mutableListOf<String>()

                    when (targetAlbum.id) {
                        "" -> {
                            // Create new album first, since this whole operations will be carried out on server, we don't have to worry about cover here, SyncAdapter will handle all the rest during next sync
                            actions.add(0, Action(null, Action.ACTION_ADD_DIRECTORY_ON_SERVER, "", targetAlbum.name, "", "", System.currentTimeMillis(), 1))
                        }
                        PublicationDetailFragment.JOINT_ALBUM_ID -> Snackbar.make(mediaPager, getString(R.string.msg_joint_album_not_updated_locally), Snackbar.LENGTH_LONG).show()
                    }

                    destinationModel.getRemotePhotos().forEach { remotePhoto ->
                        remotePhoto.photo.let { photo ->
                            actions.add(Action(null, actionId, remotePhoto.remotePath, targetFolder, "", "${photo.name}|${targetAlbum.id == PublicationDetailFragment.JOINT_ALBUM_ID}", System.currentTimeMillis(), 1))
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
                                        //removeFiles.add("/DCIM${photo.name}")
                                        actions.add(Action(null, Action.ACTION_DELETE_CAMERA_BACKUP_FILE, photo.albumId, "DCIM", photo.id, photo.name, System.currentTimeMillis(), 1))
                                        removeList.add(photo.id)
                                    }
                                }
                            } else {
                                // Current displayed photo
                                camerarollModel.getCurrentPhoto()?.let { photo ->
                                    //removeFiles.add("/DCIM${photo.name}")
                                    actions.add(Action(null, Action.ACTION_DELETE_CAMERA_BACKUP_FILE, photo.albumId, "DCIM", photo.id, photo.name, System.currentTimeMillis(), 1))
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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        if (!hasFocus) playerViewModel.pause(Uri.EMPTY)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putParcelableArrayList(KEY_LAST_SELECTION, lastSelection)
        outState.putInt(KEY_BOTTOMSHEET_STATE, bottomSheet.state)
        camerarollModel.saveQuickScrollState(quickScroll.layoutManager?.onSaveInstanceState())
    }

    override fun onDestroyView() {
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(removeOriginalBroadcastReceiver)

        super.onDestroyView()
    }

    override fun onDestroy() {
        (requireActivity() as AppCompatActivity).run {
            supportActionBar?.show()
            with(window) {
                statusBarColor = savedStatusBarColor
                navigationBarColor = savedNavigationBarColor
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
    fun flashDate(view: View) {
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

    private fun prepareShares(strip: Boolean, job: Job?, cr: ContentResolver): ArrayList<Uri> {
        val uris = arrayListOf<Uri>()
        var destFile: File

        for (photoId in lastSelection) {
            // Quit asap when job cancelled
            job?.let { if (it.isCancelled) return arrayListOf() }

            //mediaPagerAdapter.getPhotoBy(photoId.toString()).also {  photo->
            camerarollModel.getPhotoById(photoId.toString())?.let { photo->
                destFile = File(requireContext().cacheDir, if (strip) "${UUID.randomUUID()}.${photo.name.substringAfterLast('.')}" else photo.name)

                // Copy the file from camera roll to cacheDir/name, strip EXIF base on setting
                if (strip && Tools.hasExif(photo.mimeType)) {
                    try {
                        // Strip EXIF, rotate picture if needed
                        BitmapFactory.decodeStream(cr.openInputStream(Uri.parse(photo.id)))?.let { bmp->
                            (if (photo.orientation != 0) Bitmap.createBitmap(bmp, 0, 0, photo.width, photo.height, Matrix().apply { preRotate(photo.orientation.toFloat()) }, true) else bmp)
                                .compress(Bitmap.CompressFormat.JPEG, 95, destFile.outputStream())
                            uris.add(FileProvider.getUriForFile(requireContext(), getString(R.string.file_authority), destFile))
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                }
                else uris.add(photoId)
            }
        }

        return uris
    }

    private fun shareOut(strip: Boolean) {
        val cr = requireContext().contentResolver
        val handler = Handler(Looper.getMainLooper())
        val waitingMsg = Tools.getPreparingSharesSnackBar(mediaPager, strip) { shareOutJob?.cancel(cause = null) }

        shareOutJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Temporarily prevent screen rotation
                requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED

                // Show a SnackBar if it takes too long (more than 500ms) preparing shares
                withContext(Dispatchers.Main) {
                    handler.removeCallbacksAndMessages(null)
                    handler.postDelayed({ waitingMsg.show() }, 500)
                }

                val uris = prepareShares(strip, shareOutJob, cr)

                withContext(Dispatchers.Main) {
                    // Call system share chooser
                    if (uris.isNotEmpty()) {
                        val clipData = ClipData.newUri(cr, "", uris[0])
                        for (i in 1 until uris.size)
                            if (isActive) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) clipData.addItem(cr, ClipData.Item(uris[i]))
                                else clipData.addItem(ClipData.Item(uris[i]))
                            }

                        // Dismiss snackbar before showing system share chooser, avoid unpleasant screen flicker
                        if (waitingMsg.isShownOrQueued) waitingMsg.dismiss()

                        if (isActive) startActivity(Intent.createChooser(Intent().apply {
                            if (uris.size > 1) {
                                action = Intent.ACTION_SEND_MULTIPLE
                                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                            } else {
                                // If sharing only one picture, use ACTION_SEND instead, so that other apps which won't accept ACTION_SEND_MULTIPLE will work
                                action = Intent.ACTION_SEND
                                putExtra(Intent.EXTRA_STREAM, uris[0])
                            }
                            type = cr.getType(uris[0]) ?: "image/*"
                            if (type!!.startsWith("image")) type = "image/*"
                            this.clipData = clipData
                            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                            putExtra(ShareReceiverActivity.KEY_SHOW_REMOVE_OPTION, true)
                        }, null))
                    }
                }
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    withContext(Dispatchers.Main) { lastSelection.clear() }
                }
            }
        }

        shareOutJob?.invokeOnCompletion {
            // Dismiss waiting SnackBar
            handler.removeCallbacksAndMessages(null)
            if (waitingMsg.isShownOrQueued) waitingMsg.dismiss()

            requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    @Suppress("UNCHECKED_CAST")
    class CameraRollViewModelFactory(private val application: Application, private val fileUri: String?, private val inArchive: Boolean): ViewModelProvider.NewInstanceFactory() {
        override fun <T : ViewModel> create(modelClass: Class<T>): T = CameraRollViewModel(application, fileUri, inArchive) as T
    }

    class CameraRollViewModel(private val ctx: Application, private val fileUri: String?, private val inArchive: Boolean): ViewModel() {
        private val mediaList = MutableLiveData<MutableList<Photo>>()
        private var cameraRoll = mutableListOf<Photo>()
        private var backups = mutableListOf<Photo>()
        private val cr = ctx.contentResolver
        private val vmState = MutableLiveData<Int>()
        private val position = arrayListOf(0, 0)
        private var shouldDisableRemove = false
        private var shouldDisableShare = false
        private val quickScrollState: Array<Parcelable?> = arrayOf(null, null)

        init {
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
                            dcimRoot = "$baseUrl${ctx.getString(R.string.dav_files_endpoint)}$userName/DCIM"
                            webDav = OkHttpWebDav(userName, peekAuthToken(account, baseUrl), baseUrl, getUserData(account, ctx.getString(R.string.nc_userdata_selfsigned)).toBoolean(), null, "LesPas_${ctx.getString(R.string.lespas_version)}", 0)
                        }

                        webDav.listWithExtraMeta(dcimRoot, OkHttpWebDav.RECURSIVE_DEPTH).forEach { dav ->
                            if (dav.contentType.startsWith("image/") || dav.contentType.startsWith("video/")) {
                                backups.add(
                                    Photo(
                                        id = dav.fileId, albumId = dav.albumId, name = dav.name, eTag = dav.eTag, mimeType = dav.contentType,
                                        dateTaken = dav.dateTaken, lastModified = dav.modified,
                                        width = dav.width, height = dav.height, orientation = dav.orientation,
                                        // Store file size in property shareId
                                        shareId = dav.size.toInt(),
                                    )
                                )
                            }
                        }
                    } catch (e: Exception) {}

                    if (backups.isNotEmpty()) {
                        backups.sortByDescending { it.dateTaken }

                        // If we have snapshot, make sure recyclerview's current position won't drift after data updated
                        if (snapshot.isNotEmpty()) (getCurrentPhoto()?.name.let { current -> backups.indexOfFirst { it.name == current } }).let { newPosition -> setCurrentPosition(if (newPosition == -1) 0 else newPosition) }
                    } else {
                        // If fail fetching backups from server, use snapshot
                        if (snapshot.isNotEmpty()) backups.addAll(snapshot)
                    }
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
                        Tools.getFolderFromUri(this, cr)?.let { uri ->
                            //Log.e(">>>>>", "${uri.first}   ${uri.second}")
                            cameraRoll.addAll(Tools.listMediaContent(uri.first, cr, imageOnly = false, strict = true))
                            setCurrentPosition(cameraRoll.indexOfFirst { it.id.substringAfterLast('/') == uri.second })
                        } ?: run {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) shouldDisableRemove = true

                            val uri = Uri.parse(this)
                            //val photo = Photo(this, FROM_CAMERA_ROLL, "", "0", LocalDateTime.now(), LocalDateTime.MIN, 0, 0, "", 0)
                            val photo = Photo(
                                id = this,      // fileUri shared in as photo's id in Camera Roll album
                                albumId = FROM_CAMERA_ROLL,
                                shareId = 0,     // Temporarily use shareId for saving file's size TODO maximum 4GB
                                dateTaken = LocalDateTime.now(),
                                lastModified = LocalDateTime.MIN,
                            )

                            photo.mimeType = cr.getType(uri)?.let { Intent.normalizeMimeType(it) } ?: run {
                                MimeTypeMap.getSingleton().getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(fileUri).lowercase()) ?: Photo.DEFAULT_MIMETYPE
                            }
                            when (uri.scheme) {
                                "content" -> {
                                    cr.query(uri, null, null, null, null)?.use { cursor ->
                                        cursor.moveToFirst()
                                        try { cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))?.let { photo.name = it } } catch (e: IllegalArgumentException) {}
                                        // Store file size in property shareId
                                        try { cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE))?.let { photo.shareId = it.toInt() } } catch (e: Exception) {}
                                    }
                                }
                                "file" -> {
                                    uri.path?.let { photo.name = it.substringAfterLast('/') }
                                    shouldDisableShare = true
                                }
                            }

                            if (photo.mimeType.startsWith("video/")) {
                                MediaMetadataRetriever().run {
                                    setDataSource(ctx, uri)
                                    Tools.getVideoFileDate(this, photo.name)?.let { photo.dateTaken = it }
                                    extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.let { photo.width = try { it.toInt() } catch (e: NumberFormatException) { 0 }}
                                    extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.let { photo.height = try { it.toInt() } catch (e: NumberFormatException) { 0 }}
                                    release()
                                }
                            } else {
                                when (photo.mimeType.substringAfter("image/", "")) {
                                    in Tools.FORMATS_WITH_EXIF -> {
                                        val exif = ExifInterface(cr.openInputStream(uri)!!)

                                        // Get date
                                        photo.dateTaken = Tools.getImageTakenDate(exif) ?: Tools.parseDateFromFileName(photo.name) ?: LocalDateTime.now()

                                        photo.orientation = exif.rotationDegrees
                                        exif.latLong?.let {
                                            photo.latitude = it[0]
                                            photo.longitude = it[1]
                                        }
                                        photo.altitude = exif.getAltitude(Photo.NO_GPS_DATA)
                                        exif.getAttribute(ExifInterface.TAG_GPS_DEST_BEARING)?.let {
                                            photo.bearing = try {
                                                it.toDouble()
                                            } catch (e: NumberFormatException) {
                                                Photo.NO_GPS_DATA
                                            }
                                        }
                                        if (photo.bearing == Photo.NO_GPS_DATA) exif.getAttribute(ExifInterface.TAG_GPS_IMG_DIRECTION)?.let {
                                            photo.bearing = try {
                                                it.toDouble()
                                            } catch (e: NumberFormatException) {
                                                Photo.NO_GPS_DATA
                                            }
                                        }
                                    }
                                }

                                BitmapFactory.Options().run {
                                    inJustDecodeBounds = true
                                    BitmapFactory.decodeStream(cr.openInputStream(uri), null, this)
                                    photo.width = outWidth
                                    photo.height = outHeight
                                }
                            }

                            cameraRoll.add(photo)
                        }
                    } ?: run {
                        cameraRoll.addAll(Tools.getCameraRoll(cr, false))
                    }
                }

                vmState.postValue(STATE_SHOWING_DEVICE)
                mediaList.postValue(cameraRoll)
            }

        }
        fun getMediaList(): LiveData<MutableList<Photo>> = mediaList

        fun getPhotoById(photoId: String): Photo? = mediaList.value?.let { list -> list.find { it.id == photoId }}
        fun findPhotoPosition(photoId: String): Int = mediaList.value?.let { list -> list.indexOfFirst { it.id == photoId }} ?: -1
        fun removeMedias(removeList: List<Uri>) {
            // Remove from system if running on Android 10 or lower
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) removeList.forEach { media-> cr.delete(media, null, null) }

            // Remove from our list
            cameraRoll.toMutableList().run {
                removeAll { removeList.contains(Uri.parse(it.id)) }

                if (position[0] >= size) position[0] = max(size - 1, 0)
                mediaList.postValue(this)
                cameraRoll = this
            }
        }
        fun removeBackup(removeList: List<String>) {
            // Remove from our list
            backups.toMutableList().run {
                removeAll { removeList.contains(it.id) }

                if (size == 0) {
                    vmState.postValue(STATE_BACKUP_NOT_AVAILABLE)
                    mediaList.postValue(cameraRoll)
                }
                else {
                    if (position[1] >= size) position[1] = max(size - 1, 0)
                    mediaList.postValue(this)
                    backups = this
                }
            }
        }

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

    class MediaPagerAdapter(private val basePath: String, displayWidth: Int, playerViewModel: VideoPlayerViewModel, val clickListener: (Boolean?) -> Unit, val imageLoader: (Photo, ImageView?, String) -> Unit, cancelLoader: (View) -> Unit
    ): SeamlessMediaSliderAdapter<Photo>(displayWidth, PhotoDiffCallback(), playerViewModel, clickListener, imageLoader, cancelLoader) {
        override fun getVideoItem(position: Int): VideoItem = with(getItem(position) as Photo) {
            if (albumId == FROM_CAMERA_ROLL) VideoItem(Uri.parse(id), mimeType, width, height, id.substringAfterLast('/'))
            else VideoItem(Uri.parse("${basePath}/DCIM${name}"), mimeType, width, height, id)
        }
        override fun getItemTransitionName(position: Int): String = (getItem(position) as Photo).id
        override fun getItemMimeType(position: Int): String = (getItem(position) as Photo).mimeType

        fun patchMeta(photoId: String, exif: ExifInterface) {
            currentList.find { it.id == photoId }?.let { photo ->
                photo.orientation = exif.rotationDegrees
                photo.width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
                photo.height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
                // TODO changing dateTaken property will change photo position in list
                exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL).let { timeString -> }
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
        private val defaultOffset = OffsetDateTime.now().offset     //ZoneId.ofOffset("UTC", ZoneOffset.UTC)

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
                        // Add a fake photo item by taking default value for nearly all properties, denotes a date seperator
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
        fun dateRange(): Pair<Long, Long> {
            return Pair(currentList.last().dateTaken.atZone(defaultOffset).toInstant().toEpochMilli(), currentList.first().dateTaken.atZone(defaultOffset).toInstant().toEpochMilli())
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
        override fun areContentsTheSame(oldItem: Photo, newItem: Photo): Boolean = oldItem == newItem
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