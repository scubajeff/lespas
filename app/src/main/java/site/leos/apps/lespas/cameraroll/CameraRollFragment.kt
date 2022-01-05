package site.leos.apps.lespas.cameraroll

import android.accounts.AccountManager
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
import android.graphics.drawable.ColorDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.*
import android.webkit.MimeTypeMap
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
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
import androidx.transition.Transition
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.transition.MaterialContainerTransform
import kotlinx.coroutines.*
import site.leos.apps.lespas.MainActivity
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.*
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.sync.AcquiringDialogFragment
import site.leos.apps.lespas.sync.DestinationDialogFragment
import site.leos.apps.lespas.sync.ShareReceiverActivity
import site.leos.apps.lespas.sync.SyncAdapter
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.*
import kotlin.math.atan2

class CameraRollFragment : Fragment(), MainActivity.OnWindowFocusChangedListener {
    private lateinit var bottomSheet: BottomSheetBehavior<ConstraintLayout>

    private lateinit var mediaPager: RecyclerView
    private lateinit var quickScroll: RecyclerView
    private lateinit var mediaPagerAdapter: MediaPagerAdapter
    private lateinit var quickScrollAdapter: QuickScrollAdapter
    private var quickScrollGridSpanCount = 0

    private lateinit var divider: View
    private lateinit var dateTextView: TextView
    private lateinit var sizeTextView: TextView
    private lateinit var infoButton: ImageButton
    private lateinit var shareButton: ImageButton
    private lateinit var removeButton: ImageButton
    private lateinit var lespasButton: ImageButton
    private lateinit var closeButton: ImageButton
    private lateinit var selectionText: TextView

    private var savedStatusBarColor = 0
    private var savedNavigationBarColor = 0
    private var savedNavigationBarDividerColor = 0

    private var viewReCreated = false   // Flag for resuming video playing after regained window focus

    private lateinit var startWithThisMedia: String
    private lateinit var selectionTracker: SelectionTracker<String>
    private var lastSelection = arrayListOf<Uri>()
    private var stripExif = "2"
    private var showListFirst = false
    private var ignoreHide = true
    //private var sideTouchAreaWidth  = 0

    private val imageLoaderModel: ImageLoaderViewModel by activityViewModels()
    private val destinationModel: DestinationDialogFragment.DestinationViewModel by activityViewModels()
    private val camerarollModel: CameraRollViewModel by viewModels { CameraRollViewModelFactory(requireActivity().application, arguments?.getString(KEY_URI)) }
    private val playerViewModel: VideoPlayerViewModel by viewModels { VideoPlayerViewModelFactory(requireActivity().application, null) }

    private lateinit var removeOriginalBroadcastReceiver: RemoveOriginalBroadcastReceiver
    private lateinit var deleteMediaLauncher: ActivityResultLauncher<IntentSenderRequest>
    private lateinit var storagePermissionRequestLauncher: ActivityResultLauncher<String>
    private lateinit var gestureDetector: GestureDetectorCompat

    private var shareOutJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        PreferenceManager.getDefaultSharedPreferences(requireContext()).apply {
            stripExif = getString(getString(R.string.strip_exif_pref_key), getString(R.string.strip_ask_value))!!
            showListFirst = getBoolean(getString(R.string.roll_list_first_perf_key), false)
            // If start as viewer then don't show list first
            arguments?.getString(KEY_URI)?.let { showListFirst = false }
        }

        // Create adapter here so that it won't leak
        mediaPagerAdapter = MediaPagerAdapter(
            Tools.getDisplayWidth(requireActivity().windowManager),
            playerViewModel,
            { state->
                // When in "Show media list at startup" mode, ignore the first hide bottom sheet call which fired by video auto play
                if (ignoreHide && showListFirst) ignoreHide = false
                else bottomSheet.state = if (state ?: run { bottomSheet.state == BottomSheetBehavior.STATE_HIDDEN }) BottomSheetBehavior.STATE_COLLAPSED else BottomSheetBehavior.STATE_HIDDEN
            },
            { photo, imageView, type-> imageLoaderModel.loadPhoto(photo, imageView, type) { startPostponedEnterTransition() }},
            { view-> imageLoaderModel.cancelLoading(view as ImageView) }
        ).apply { stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY }

        quickScrollAdapter = QuickScrollAdapter(
            { photo ->
                // TODO test this
                //if (showListFirst) mediaPagerAdapter.setAutoStart(true)
                mediaPager.scrollToPosition(mediaPagerAdapter.findMediaPosition(photo))
                bottomSheet.state = BottomSheetBehavior.STATE_HIDDEN
            },
            { photo, imageView, type -> imageLoaderModel.loadPhoto(photo, imageView, type) }
        ).apply { stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY }

        startWithThisMedia = arguments?.getString(KEY_SCROLL_TO) ?: ""
        savedInstanceState?.let {
            startWithThisMedia = it.getString(KEY_SCROLL_TO) ?: ""
            lastSelection = it.getParcelableArrayList(KEY_LAST_SELECTION) ?: arrayListOf()

            // Don't ignore call to hide bottom sheet after activity recreated
            ignoreHide = false
        }

        sharedElementEnterTransition = MaterialContainerTransform().apply {
            duration = resources.getInteger(android.R.integer.config_shortAnimTime).toLong()
            scrimColor = Color.TRANSPARENT
        }.addListener(object: Transition.TransitionListener {
            override fun onTransitionStart(transition: Transition) {
                mediaPager.findChildViewUnder(500.0f, 500.0f)?.visibility = View.INVISIBLE
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

        // Adjusting the shared element mapping
        setEnterSharedElementCallback(object : SharedElementCallback() {
            override fun onMapSharedElements(names: MutableList<String>?, sharedElements: MutableMap<String, View>?) {
                if (names?.isNotEmpty() == true) mediaPager.findViewHolderForAdapterPosition(getCurrentVisibleItemPosition())?.itemView?.findViewById<View>(R.id.media)?.apply { sharedElements?.put(names[0], this) }
            }
        })

        removeOriginalBroadcastReceiver = RemoveOriginalBroadcastReceiver {
            if (it) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    deleteMediaLauncher.launch(IntentSenderRequest.Builder(MediaStore.createDeleteRequest(requireContext().contentResolver, lastSelection)).setFillInIntent(null).build())
                } else camerarollModel.removeMedias(lastSelection)
            }

            // Immediately sync with server after adding photo to local album
            ContentResolver.requestSync(AccountManager.get(requireContext()).getAccountsByType(getString(R.string.account_type_nc))[0], getString(R.string.sync_authority), Bundle().apply {
                putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                //putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
                putInt(SyncAdapter.ACTION, SyncAdapter.SYNC_LOCAL_CHANGES)
            })
        }

        storagePermissionRequestLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            when {
                isGranted -> {
                    // Explicitly request ACCESS_MEDIA_LOCATION permission
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) (registerForActivityResult(ActivityResultContracts.RequestPermission()) {}).launch(android.Manifest.permission.ACCESS_MEDIA_LOCATION)

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
                            bottomSheet.state = if (mediaPagerAdapter.itemCount > 1) BottomSheetBehavior.STATE_EXPANDED else BottomSheetBehavior.STATE_COLLAPSED
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

        //sideTouchAreaWidth = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 40f, resources.displayMetrics).roundToInt()
        quickScrollGridSpanCount = resources.getInteger(R.integer.cameraroll_grid_span_count)

        playerViewModel.setWindow(requireActivity().window)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? = inflater.inflate(R.layout.fragment_camera_roll, container, false)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewReCreated = true

        (requireActivity() as AppCompatActivity).supportActionBar!!.hide()

        postponeEnterTransition()

        view.setBackgroundColor(Color.BLACK)

        divider = view.findViewById(R.id.divider)
        dateTextView = view.findViewById(R.id.date)
        sizeTextView = view.findViewById(R.id.size)
        infoButton = view.findViewById(R.id.info_button)
        shareButton = view.findViewById(R.id.share_button)
        removeButton = view.findViewById(R.id.remove_button)
        lespasButton = view.findViewById(R.id.lespas_button)
        closeButton = view.findViewById(R.id.close_button)
        selectionText = view.findViewById(R.id.selection_text)

        infoButton.setOnClickListener {
            bottomSheet.state = BottomSheetBehavior.STATE_HIDDEN
            if (parentFragmentManager.findFragmentByTag(INFO_DIALOG) == null) MetaDataDialogFragment.newInstance(mediaPagerAdapter.getMediaAtPosition(getCurrentVisibleItemPosition())).show(parentFragmentManager, INFO_DIALOG)
        }
        shareButton.setOnClickListener {
            copyTargetUris()

            if (stripExif == getString(R.string.strip_ask_value)) {
                if (hasExifInSelection()) {
                    if (parentFragmentManager.findFragmentByTag(CONFIRM_DIALOG) == null) YesNoDialogFragment.newInstance(getString(R.string.strip_exif_msg, getString(R.string.strip_exif_title)), STRIP_REQUEST_KEY).show(parentFragmentManager, CONFIRM_DIALOG)
                } else shareOut(false)
            }
            else shareOut(stripExif == getString(R.string.strip_on_value))
        }
        lespasButton.setOnClickListener {
            copyTargetUris()

            if (parentFragmentManager.findFragmentByTag(TAG_DESTINATION_DIALOG) == null) DestinationDialogFragment.newInstance(lastSelection,true).show(parentFragmentManager, TAG_DESTINATION_DIALOG)
        }
        removeButton.setOnClickListener {
            copyTargetUris()

            // Get confirmation from user
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                deleteMediaLauncher.launch(IntentSenderRequest.Builder(MediaStore.createDeleteRequest(requireContext().contentResolver, lastSelection)).setFillInIntent(null).build())
            }
            else if (parentFragmentManager.findFragmentByTag(CONFIRM_DIALOG) == null) ConfirmDialogFragment.newInstance(getString(R.string.confirm_delete), getString(R.string.yes_delete), true, DELETE_REQUEST_KEY).show(parentFragmentManager, CONFIRM_DIALOG)
        }
        closeButton.setOnClickListener { if (selectionTracker.hasSelection()) selectionTracker.clearSelection() else bottomSheet.state = BottomSheetBehavior.STATE_HIDDEN }

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
                override fun canSetStateForKey(key: String, nextState: Boolean): Boolean = (key.isNotEmpty())
                override fun canSetStateAtPosition(position: Int, nextState: Boolean): Boolean = (position != 0)
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
                        } else {
                            removeButton.isEnabled = false
                            shareButton.isEnabled = false
                            lespasButton.isEnabled = false
                            closeButton.setImageResource(R.drawable.ic_baseline_arrow_downward_24)
                            selectionText.isVisible = false
                            selectionText.text = ""
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
            addOnItemTouchListener(object: RecyclerView.SimpleOnItemTouchListener() {
                override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean = gestureDetector.onTouchEvent(e)
            })

            addOnScrollListener(object: RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    super.onScrollStateChanged(recyclerView, newState)

                    when(newState) {
                        // Dismiss BottomSheet when user starts scrolling
                        RecyclerView.SCROLL_STATE_DRAGGING-> bottomSheet.state = BottomSheetBehavior.STATE_HIDDEN
                        // Update meta display textview after scrolled
                        RecyclerView.SCROLL_STATE_IDLE-> updateMetaDisplay()
                    }
                }
            })
        }

        // Since we are not in immersive mode, set correct photo display width so that right edge can be detected
        mediaPager.doOnLayout { mediaPagerAdapter.setDisplayWidth(mediaPager.width) }

        // TODO dirty hack to reduce mediaPager's scroll sensitivity to get smoother zoom experience
        (RecyclerView::class.java.getDeclaredField("mTouchSlop")).apply {
            isAccessible = true
            set(mediaPager, (get(mediaPager) as Int) * 4)
        }

        bottomSheet = BottomSheetBehavior.from(view.findViewById(R.id.bottom_sheet) as ConstraintLayout).apply {
            state = BottomSheetBehavior.STATE_HIDDEN
            saveFlags = BottomSheetBehavior.SAVE_ALL
            skipCollapsed = true

            addBottomSheetCallback(object: BottomSheetBehavior.BottomSheetCallback() {
                val primaryColor = ContextCompat.getColor(requireContext(), R.color.color_on_primary_invert)
                val backgroundColor = ContextCompat.getColor(requireContext(), R.color.color_background)

                @SuppressLint( "SwitchIntDef")
                override fun onStateChanged(view: View, newState: Int) {
                    when (newState) {
                        BottomSheetBehavior.STATE_EXPANDED -> {
                            if (mediaPagerAdapter.itemCount == 1) {
                                // If there is only 1 item in the list, user swipe up to reveal the expanded sheet, then we should update the text here
                                // TODO any better way to detect mediaPager's scroll to event?
                                updateMetaDisplay()
                            } else {
                                // If there are more than 1 media in the list, get ready to show BottomSheet expanded state
                                if (!closeButton.isVisible) {
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

                                    quickScroll.scrollToPosition(quickScrollAdapter.getPhotoPosition(mediaPagerAdapter.getMediaAtPosition(getCurrentVisibleItemPosition()).id))
                                    quickScroll.foreground = null
                                }
                            }
                        }
                        BottomSheetBehavior.STATE_HIDDEN -> {
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

                                closeButton.isEnabled = false
                                closeButton.isVisible = false
                                selectionText.isVisible = false
                            }
                        }
                        BottomSheetBehavior.STATE_COLLAPSED -> {
                            // Update media meta when in collapsed state, should do it here since when media list updated, like after deletion, list's addOnScrollListener callback won't be called
                            // TODO any better way to detect mediaPager's scroll to event?
                            updateMetaDisplay()
                        }
                    }
                }

                override fun onSlide(view: View, slideOffset: Float) {
                    if (mediaPagerAdapter.itemCount > 1 && slideOffset >= 0 && dateTextView.isEnabled) {
                        val alpha = 255 - (255 * slideOffset).toInt()

                        with(ColorUtils.setAlphaComponent(primaryColor, alpha)) {
                            dateTextView.setTextColor(this)
                            sizeTextView.setTextColor(this)
                        }

                        quickScroll.foreground = ColorDrawable(ColorUtils.setAlphaComponent(backgroundColor, alpha))
                        // TODO way to animate ImageButton from enabled to disabled status
                        //val iAlpha = (128 * slideOffset).toInt()
                        //removeButton.setColorFilter(Color.argb(iAlpha, 0, 0 , 0))
                        //shareButton.setColorFilter(Color.argb(iAlpha, 0, 0 , 0))
                        //lespasButton.setColorFilter(Color.argb(iAlpha, 0, 0 , 0))
                    }
                }
            })
        }

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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) (registerForActivityResult(ActivityResultContracts.RequestPermission()) {}).launch(android.Manifest.permission.ACCESS_MEDIA_LOCATION)

                observeCameraRoll()
            }
        }

        // Acquiring new medias
        destinationModel.getDestination().observe(viewLifecycleOwner, Observer { album ->
            album?.apply {
                // Acquire files
                if (parentFragmentManager.findFragmentByTag(TAG_ACQUIRING_DIALOG) == null)
                    AcquiringDialogFragment.newInstance(lastSelection, album, destinationModel.shouldRemoveOriginal()).show(parentFragmentManager, TAG_ACQUIRING_DIALOG)
            }
        })

        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(removeOriginalBroadcastReceiver, IntentFilter(AcquiringDialogFragment.BROADCAST_REMOVE_ORIGINAL))

        // Removing medias confirm result handler
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            deleteMediaLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
                //if (result.resultCode == Activity.RESULT_OK) camerarollModel.removeCurrentMedia()
                if (result.resultCode == Activity.RESULT_OK) camerarollModel.removeMedias(lastSelection)
            }
        }

        parentFragmentManager.setFragmentResultListener(ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY, viewLifecycleOwner) { _, bundle ->
            when(bundle.getString(ConfirmDialogFragment.INDIVIDUAL_REQUEST_KEY)) {
                //DELETE_REQUEST_KEY -> if (bundle.getBoolean(ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY, false)) camerarollModel.removeCurrentMedia()
                DELETE_REQUEST_KEY -> if (bundle.getBoolean(ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY, false)) camerarollModel.removeMedias(lastSelection)
                STRIP_REQUEST_KEY -> shareOut(bundle.getBoolean(ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY, false))
            }
        }

        savedInstanceState ?: run {
            if (showListFirst) {
                removeButton.isEnabled = false
                shareButton.isEnabled = false
                lespasButton.isEnabled = false
                closeButton.isEnabled = true
                closeButton.isVisible = true
                selectionText.isVisible = true
                quickScroll.foreground = null

                bottomSheet.state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
    }

    override fun onResume() {
        //Log.e(">>>>>", "onResume $videoStopPosition")
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
    }

    override fun onPause() {
        //Log.e(">>>>>", "onPause")
        viewReCreated = false

        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        if (!hasFocus) playerViewModel.pause(Uri.EMPTY)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SCROLL_TO, mediaPagerAdapter.getMediaAtPosition(getCurrentVisibleItemPosition()).id)
        outState.putParcelableArrayList(KEY_LAST_SELECTION, lastSelection)
    }

    override fun onDestroyView() {
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(removeOriginalBroadcastReceiver)

        super.onDestroyView()
    }

    override fun onDestroy() {
        (requireActivity() as AppCompatActivity).run {
            supportActionBar!!.show()
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

            // Set initial position if passed in arguments or restore from last session
            if (startWithThisMedia.isNotEmpty()) {
                camerarollModel.setStartPosition(it.indexOfFirst { media -> media.id == startWithThisMedia })
                startWithThisMedia = ""
                bottomSheet.state = BottomSheetBehavior.STATE_HIDDEN
            }

            // Populate list and scroll to correct position
            (mediaPager.adapter as MediaPagerAdapter).submitList(it)
            mediaPager.scrollToPosition(camerarollModel.getStartPosition())
            (quickScroll.adapter as QuickScrollAdapter).submitList(it)

            // Disable delete function if it's launched as media viewer on Android 11
            //if (camerarollModel.shouldDisableRemove()) removeButton.isEnabled = false
        })
    }

    @SuppressLint("SetTextI18n")
    private fun updateMetaDisplay() {
        with(mediaPagerAdapter.getMediaAtPosition(getCurrentVisibleItemPosition())) {
            dateTextView.text = "${dateTaken.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())}, ${dateTaken.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))}"
            sizeTextView.text = Tools.humanReadableByteCountSI(eTag.toLong())
        }
    }

    private fun getCurrentVisibleItemPosition(): Int {
        val pos = (mediaPager.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
        return if (pos == RecyclerView.NO_POSITION) 0 else pos
    }

    private fun copyTargetUris() {
        // Save target media item list
        lastSelection =
            if (selectionTracker.hasSelection()) {
                val uris = arrayListOf<Uri>()
                for (uri in selectionTracker.selection) uris.add(Uri.parse(uri))
                selectionTracker.clearSelection()
                uris
            } else arrayListOf(Uri.parse(mediaPagerAdapter.getMediaAtPosition(getCurrentVisibleItemPosition()).id))

        // Dismiss BottomSheet when it's in collapsed mode, which means it's working on the current item
        if (bottomSheet.state == BottomSheetBehavior.STATE_COLLAPSED) bottomSheet.state = BottomSheetBehavior.STATE_HIDDEN
    }

    private fun hasExifInSelection(): Boolean {
        for (photoId in lastSelection) {
            if (Tools.hasExif(mediaPagerAdapter.getPhotoBy(photoId.toString()).mimeType)) return true
        }

        return false
    }

    private fun prepareShares(strip: Boolean, job: Job?): ArrayList<Uri> {
        val uris = arrayListOf<Uri>()
        var destFile: File
        val cr = requireContext().contentResolver

        for (photoId in lastSelection) {
            // Quit asap when job cancelled
            job?.let { if (it.isCancelled) return arrayListOf() }

            mediaPagerAdapter.getPhotoBy(photoId.toString()).also {  photo->
                // This TEMP_CACHE_FOLDER is created by MainActivity
                destFile = File("${requireActivity().cacheDir}${MainActivity.TEMP_CACHE_FOLDER}", if (strip) "${UUID.randomUUID()}.${photo.name.substringAfterLast('.')}" else photo.name)

                // Copy the file from camera roll to cacheDir/name, strip EXIF base on setting
                if (strip && Tools.hasExif(photo.mimeType)) {
                    try {
                        // Strip EXIF, rotate picture if needed
                        BitmapFactory.decodeStream(cr.openInputStream(Uri.parse(photo.id)))?.let { bmp->
                            (if (photo.shareId != 0) Bitmap.createBitmap(bmp, 0, 0, photo.width, photo.height, Matrix().apply { preRotate(photo.shareId.toFloat()) }, true) else bmp)
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

                val uris = prepareShares(strip, shareOutJob)

                withContext(Dispatchers.Main) {
                    // Call system share chooser
                    if (uris.isNotEmpty()) {
                        val clipData = ClipData.newUri(requireActivity().contentResolver, "", uris[0])
                        for (i in 1 until uris.size)
                            if (isActive) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) clipData.addItem(requireActivity().contentResolver, ClipData.Item(uris[i]))
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
                            type = requireContext().contentResolver.getType(uris[0]) ?: "image/*"
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
    class CameraRollViewModelFactory(private val application: Application, private val fileUri: String?): ViewModelProvider.NewInstanceFactory() {
        override fun <T : ViewModel> create(modelClass: Class<T>): T = CameraRollViewModel(application, fileUri) as T
    }

    class CameraRollViewModel(application: Application, fileUri: String?): AndroidViewModel(application) {
        private val mediaList = MutableLiveData<MutableList<Photo>>()
        private var startPosition = 0
        private val cr = application.contentResolver
        private var shouldDisableRemove = false
        private var shouldDisableShare = false

        init {
            var medias = mutableListOf<Photo>()

            fileUri?.apply {
                Tools.getFolderFromUri(this, application.contentResolver)?.let { uri->
                    //Log.e(">>>>>", "${uri.first}   ${uri.second}")
                    medias = Tools.listMediaContent(uri.first, cr, imageOnly = false, strict = true)
                    setStartPosition(medias.indexOfFirst { it.id.substringAfterLast('/') == uri.second })
                } ?: run {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) shouldDisableRemove = true

                    val uri = Uri.parse(this)
                    val photo = Photo(this, ImageLoaderViewModel.FROM_CAMERA_ROLL, "", "0", LocalDateTime.now(), LocalDateTime.MIN, 0, 0, "", 0)

                    photo.mimeType = cr.getType(uri)?.let { Intent.normalizeMimeType(it) } ?: run {
                        MimeTypeMap.getSingleton().getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(fileUri).lowercase()) ?: "image/jpeg"
                    }
                    when (uri.scheme) {
                        "content" -> {
                            cr.query(uri, null, null, null, null)?.use { cursor ->
                                cursor.moveToFirst()
                                cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))?.let { photo.name = it }
                                // Store file size in property eTag
                                cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE))?.let { photo.eTag = it }
                            }
                        }
                        "file" -> {
                            uri.path?.let { photo.name = it.substringAfterLast('/') }
                            shouldDisableShare = true
                        }
                    }

                    if (photo.mimeType.startsWith("video/")) {
                        MediaMetadataRetriever().run {
                            setDataSource(application, uri)
                            photo.width = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 0
                            photo.height = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 0
                            photo.dateTaken = Tools.getVideoFileDate(this, photo.name)
                            release()
                        }
                    } else {
                        when (photo.mimeType.substringAfter("image/", "")) {
                            in Tools.FORMATS_WITH_EXIF-> {
                                val exif = ExifInterface(cr.openInputStream(uri)!!)

                                // Get date
                                photo.dateTaken = Tools.getImageFileDate(exif, photo.name)?.let {
                                    try {
                                        LocalDateTime.parse(it, DateTimeFormatter.ofPattern(Tools.DATE_FORMAT_PATTERN))
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                        LocalDateTime.now()
                                    }
                                } ?: LocalDateTime.now()

                                // Store orientation in property shareId
                                photo.shareId = exif.rotationDegrees
                            }
                        }

                        BitmapFactory.Options().run {
                            inJustDecodeBounds = true
                            BitmapFactory.decodeStream(cr.openInputStream(uri), null, this)
                            photo.width = outWidth
                            photo.height = outHeight
                        }
                    }

                    medias.add(photo)
                }

            } ?: run { medias = Tools.getCameraRoll(cr, false) }

            mediaList.postValue(medias)
        }

        fun setStartPosition(position: Int) { startPosition = position }
        fun getStartPosition(): Int = startPosition
        fun getMediaList(): LiveData<MutableList<Photo>> = mediaList
        fun removeMedias(removeList: List<Uri>) {
            // Remove from system if running on Android 10 or lower
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) removeList.forEach { media-> cr.delete(media, null, null) }

            // Remove from our list
            mediaList.value?.toMutableList()?.run {
                removeAll { removeList.contains(Uri.parse(it.id)) }
                mediaList.postValue(this)
            }
        }

        fun shouldDisableRemove(): Boolean = this.shouldDisableRemove
        fun shouldDisableShare(): Boolean = this.shouldDisableShare
    }

    class MediaPagerAdapter(displayWidth: Int, playerViewModel: VideoPlayerViewModel, val clickListener: (Boolean?) -> Unit, val imageLoader: (Photo, ImageView, String) -> Unit, cancelLoader: (View) -> Unit
    ): SeamlessMediaSliderAdapter<Photo>(displayWidth, PhotoDiffCallback(), playerViewModel, clickListener, imageLoader, cancelLoader) {
        override fun getVideoItem(position: Int): VideoItem = with(getItem(position) as Photo) {
            VideoItem(Uri.parse(id), mimeType, width, height, id.substringAfterLast('/'))
        }
        override fun getItemTransitionName(position: Int): String = (getItem(position) as Photo).id
        override fun getItemMimeType(position: Int): String = (getItem(position) as Photo).mimeType

        fun getMediaAtPosition(position: Int): Photo = currentList[position] as Photo
        fun findMediaPosition(photo: Photo): Int = (currentList as List<Photo>).indexOf(photo)
        fun getPhotoBy(photoId: String): Photo = currentList.last { it.id == photoId }
    }

    class QuickScrollAdapter(private val clickListener: (Photo) -> Unit, private val imageLoader: (Photo, ImageView, String) -> Unit
    ): ListAdapter<Photo, RecyclerView.ViewHolder>(PhotoDiffCallback()) {
        private lateinit var selectionTracker: SelectionTracker<String>
        private val selectedFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0.0f) })

        inner class MediaViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
            private val ivPhoto = itemView.findViewById<ImageView>(R.id.photo)
            private val ivSelectionMark = itemView.findViewById<ImageView>(R.id.selection_mark)
            private val ivPlayMark = itemView.findViewById<ImageView>(R.id.play_mark)

            fun bind(item: Photo, isActivated: Boolean) {
                itemView.let {
                    it.isActivated = isActivated

                    with(ivPhoto) {
                        imageLoader(item, this, ImageLoaderViewModel.TYPE_GRID)

                        if (this.isActivated) {
                            colorFilter = selectedFilter
                            ivSelectionMark.visibility = View.VISIBLE
                        } else {
                            clearColorFilter()
                            ivSelectionMark.visibility = View.GONE
                        }

                        setOnClickListener { if (!selectionTracker.hasSelection()) clickListener(item) }
                    }
                    ivPlayMark.visibility = if (Tools.isMediaPlayable(item.mimeType)) View.VISIBLE else View.GONE
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

        inner class HorizontalDateViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
            private val tvDate = itemView.findViewById<TextView>(R.id.date)

            @SuppressLint("SetTextI18n")
            fun bind(item: Photo) {
                with(item.dateTaken) {
                    //itemView.findViewById<TextView>(R.id.date).text = "${format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG))}, ${dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())}   |   ${String.format(itemView.context.getString(R.string.total_photo), item.shareId)}"
                    tvDate.text = "${format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG))}, ${dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())}"
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
/*
            if (viewType == MEDIA_TYPE) MediaViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_cameraroll, parent, false))
            else DateViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_cameraroll_date, parent, false))
*/
            if (viewType == MEDIA_TYPE) MediaViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_photo, parent, false))
            else HorizontalDateViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_cameraroll_date_horizontal, parent, false))

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is MediaViewHolder) holder.bind(currentList[position], selectionTracker.isSelected(currentList[position].id))
            //else if (holder is DateViewHolder) holder.bind(currentList[position])
            else if (holder is HorizontalDateViewHolder) holder.bind(currentList[position])
        }

        override fun submitList(list: MutableList<Photo>?) {
            list?.apply {
                // Group by date
                val listGroupedByDate = mutableListOf<Photo>()
                var currentDate = LocalDate.now().plusDays(1)
                for (media in this) {
                    if (media.dateTaken.toLocalDate() != currentDate) {
                        currentDate = media.dateTaken.toLocalDate()
                        listGroupedByDate.add(Photo("", ImageLoaderViewModel.FROM_CAMERA_ROLL, "", "", media.dateTaken, media.dateTaken, 0, 0, "", 0))
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

                super.submitList(listGroupedByDate)
            }
        }

        override fun getItemViewType(position: Int): Int = if (currentList[position].id.isEmpty()) DATE_TYPE else MEDIA_TYPE

        //fun findMediaPosition(photo: Photo): Int = currentList.indexOf(photo)

        internal fun setSelectionTracker(selectionTracker: SelectionTracker<String>) { this.selectionTracker = selectionTracker }
        internal fun getPhotoId(position: Int): String = currentList[position].id
        internal fun getPhotoPosition(photoId: String): Int = currentList.indexOfLast { it.id == photoId }
        class PhotoKeyProvider(private val adapter: QuickScrollAdapter): ItemKeyProvider<String>(SCOPE_CACHED) {
            override fun getKey(position: Int): String = adapter.getPhotoId(position)
            override fun getPosition(key: String): Int = with(adapter.getPhotoPosition(key)) { if (this >= 0) this else RecyclerView.NO_POSITION }
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
        private const val KEY_SCROLL_TO = "KEY_SCROLL_TO"
        private const val KEY_URI = "KEY_URI"
        private const val KEY_LAST_SELECTION = "KEY_LAST_SELECTION"

        const val TAG_DESTINATION_DIALOG = "CAMERAROLL_DESTINATION_DIALOG"
        const val TAG_ACQUIRING_DIALOG = "CAMERAROLL_ACQUIRING_DIALOG"
        private const val CONFIRM_DIALOG = "CONFIRM_DIALOG"
        private const val INFO_DIALOG = "INFO_DIALOG"
        private const val DELETE_REQUEST_KEY = "CAMERA_ROLL_DELETE_REQUEST_KEY"
        private const val STRIP_REQUEST_KEY = "CAMERA_ROLL_STRIP_REQUEST_KEY"

        @JvmStatic
        @JvmOverloads
        fun newInstance(scrollTo: String = "") = CameraRollFragment().apply { arguments = Bundle().apply { putString(KEY_SCROLL_TO, scrollTo) }}

        @JvmStatic
        fun newInstance(uri: Uri) = CameraRollFragment().apply { arguments = Bundle().apply { putString(KEY_URI, uri.toString()) }}
    }
}
