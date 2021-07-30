package site.leos.apps.lespas.cameraroll

import android.accounts.AccountManager
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.*
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.transition.Slide
import android.transition.TransitionManager
import android.view.*
import android.webkit.MimeTypeMap
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.app.SharedElementCallback
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.exifinterface.media.ExifInterface
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.*
import androidx.lifecycle.Observer
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.*
import com.github.chrisbanes.photoview.PhotoView
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.ui.PlayerView
import com.google.android.material.transition.MaterialContainerTransform
import kotlinx.parcelize.Parcelize
import site.leos.apps.lespas.MainActivity
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.*
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.sync.AcquiringDialogFragment
import site.leos.apps.lespas.sync.DestinationDialogFragment
import site.leos.apps.lespas.sync.ShareReceiverActivity
import site.leos.apps.lespas.sync.SyncAdapter
import java.lang.Integer.min
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.*
import kotlin.math.roundToInt

class CameraRollFragment : Fragment(), ConfirmDialogFragment.OnResultListener {
    private lateinit var controlViewGroup: ConstraintLayout
    private lateinit var mediaPager: RecyclerView
    private lateinit var quickScroll: RecyclerView
    private lateinit var divider: View
    private lateinit var nameTextView: TextView
    private lateinit var sizeTextView: TextView
    private lateinit var shareButton: ImageButton
    private lateinit var removeButton: ImageButton
    private var savedStatusBarColor = 0
    private var savedNavigationBarColor = 0
    private var savedNavigationBarDividerColor = 0

    private val imageLoaderModel: ImageLoaderViewModel by activityViewModels()
    private val destinationModel: DestinationDialogFragment.DestinationViewModel by activityViewModels()
    private val camerarollModel: CameraRollViewModel by viewModels { CameraRollViewModelFactory(requireActivity().application, arguments?.getString(KEY_URI)) }

    private lateinit var mediaPagerAdapter: MediaPagerAdapter
    private lateinit var quickScrollAdapter: QuickScrollAdapter

    private lateinit var startWithThisMedia: String

    private lateinit var removeOriginalBroadcastReceiver: RemoveOriginalBroadcastReceiver

    private lateinit var deleteMediaLauncher: ActivityResultLauncher<IntentSenderRequest>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Create adapter here so that it won't leak
        mediaPagerAdapter = MediaPagerAdapter(
            requireContext(),
            { toggleControlView(controlViewGroup.visibility == View.GONE) },
            { videoControlVisible-> toggleControlView(videoControlVisible) },
            {photo, imageView, type-> imageLoaderModel.loadPhoto(photo, imageView, type) { startPostponedEnterTransition() }},
        ).apply {
            stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        }

        quickScrollAdapter = QuickScrollAdapter(
            { photo -> mediaPager.scrollToPosition(mediaPagerAdapter.findMediaPosition(photo))},
            { photo, imageView, type -> imageLoaderModel.loadPhoto(photo, imageView, type) }
        ).apply {
            stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        }

        removeOriginalBroadcastReceiver = RemoveOriginalBroadcastReceiver {
            if (it) camerarollModel.removeCurrentMedia()

            // Immediately sync with server after adding photo to local album
            ContentResolver.requestSync(AccountManager.get(requireContext()).getAccountsByType(getString(R.string.account_type_nc))[0], getString(R.string.sync_authority), Bundle().apply {
                putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                //putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
                putInt(SyncAdapter.ACTION, SyncAdapter.SYNC_LOCAL_CHANGES)
            })
        }

        savedInstanceState?.getParcelable<MediaPagerAdapter.PlayerState>(PLAYER_STATE)?.apply { mediaPagerAdapter.setPlayerState(this) }

        startWithThisMedia = arguments?.getString(KEY_SCROLL_TO) ?: ""

        sharedElementEnterTransition = MaterialContainerTransform().apply {
            duration = resources.getInteger(android.R.integer.config_shortAnimTime).toLong()
            scrimColor = Color.TRANSPARENT
        }

        // Adjusting the shared element mapping
        setEnterSharedElementCallback(object : SharedElementCallback() {
            override fun onMapSharedElements(names: MutableList<String>?, sharedElements: MutableMap<String, View>?) {
                sharedElements?.put(names?.get(0)!!, mediaPager.findViewHolderForAdapterPosition(camerarollModel.getCurrentMediaIndex())?.itemView?.findViewById(R.id.media)!!)}
        })

        // On Android 11 and above, result handler of media deletion request
        deleteMediaLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result->
            if (result.resultCode == Activity.RESULT_OK) camerarollModel.removeCurrentMedia()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.fragment_camera_roll, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        postponeEnterTransition()

        view.setBackgroundColor(Color.BLACK)

        controlViewGroup = view.findViewById<ConstraintLayout>(R.id.control_container).apply {
            // Prevent touch event passing to media pager underneath this
            setOnTouchListener { _, _ ->
                this.performClick()
                true
            }
        }
        nameTextView = view.findViewById(R.id.name)
        sizeTextView = view.findViewById(R.id.size)
        shareButton = view.findViewById(R.id.share_button)
        removeButton = view.findViewById(R.id.remove_button)
        divider = view.findViewById(R.id.divider)

        shareButton.setOnClickListener {
            toggleControlView(false)

            val mediaToShare = mediaPagerAdapter.getMediaAtPosition(camerarollModel.getCurrentMediaIndex())
            startActivity(Intent.createChooser(Intent().apply {
                action = Intent.ACTION_SEND
                type = mediaToShare.mimeType
                putExtra(Intent.EXTRA_STREAM, Uri.parse(mediaToShare.id))
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                putExtra(ShareReceiverActivity.KEY_SHOW_REMOVE_OPTION, true)
            }, null))
        }
        view.findViewById<ImageButton>(R.id.lespas_button).setOnClickListener {
            toggleControlView(false)

            if (parentFragmentManager.findFragmentByTag(TAG_DESTINATION_DIALOG) == null)
                DestinationDialogFragment.newInstance(arrayListOf(Uri.parse(mediaPagerAdapter.getMediaAtPosition(camerarollModel.getCurrentMediaIndex()).id)!!), true).show(parentFragmentManager, TAG_DESTINATION_DIALOG)
        }
        removeButton.setOnClickListener {
            toggleControlView(false)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                deleteMediaLauncher.launch(IntentSenderRequest.Builder(MediaStore.createDeleteRequest(requireContext().contentResolver, mutableListOf(camerarollModel.getCurrentMediaUri()))).setFillInIntent(null).setFlags(0, 0).build())
            }
            else if (parentFragmentManager.findFragmentByTag(CONFIRM_DIALOG) == null) ConfirmDialogFragment.newInstance(getString(R.string.confirm_delete), getString(R.string.yes_delete)).let{
                it.setTargetFragment(this, DELETE_MEDIA_REQUEST_CODE)
                it.show(parentFragmentManager, CONFIRM_DIALOG)
            }
        }

        quickScroll = view.findViewById<RecyclerView>(R.id.quick_scroll).apply {
            adapter = quickScrollAdapter

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
        }

        mediaPager = view.findViewById<RecyclerView>(R.id.media_pager).apply {
            adapter = mediaPagerAdapter

            // Snap like a ViewPager
            PagerSnapHelper().attachToRecyclerView(this)

            addOnScrollListener(object: RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)

                    // scrollToPosition called
                    if (dx == 0 && dy == 0) newPositionSet()
                }
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    super.onScrollStateChanged(recyclerView, newState)

                    when(newState) {
                        RecyclerView.SCROLL_STATE_IDLE-> { newPositionSet() }
                        RecyclerView.SCROLL_STATE_DRAGGING-> { toggleControlView(false) }
                    }
                }
            })
        }

        // TODO dirty hack to reduce mediaPager's scroll sensitivity to get smoother zoom experience
        (RecyclerView::class.java.getDeclaredField("mTouchSlop")).apply {
            isAccessible = true
            set(mediaPager, (get(mediaPager) as Int) * 4)
        }

        savedInstanceState?.let {
            observeCameraRoll()
        } ?: run {
            val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) android.Manifest.permission.READ_EXTERNAL_STORAGE else android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(requireContext(), permission) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(permission), STORAGE_PERMISSION_REQUEST)
            }
            else observeCameraRoll()
        }

        // Acquiring new medias
        destinationModel.getDestination().observe(viewLifecycleOwner, Observer { album ->
            album?.apply {
                // Acquire files
                if (parentFragmentManager.findFragmentByTag(TAG_ACQUIRING_DIALOG) == null)
                    AcquiringDialogFragment.newInstance(arrayListOf(Uri.parse(mediaPagerAdapter.getMediaAtPosition(camerarollModel.getCurrentMediaIndex()).id)!!), album, destinationModel.shouldRemoveOriginal()).show(parentFragmentManager, TAG_ACQUIRING_DIALOG)
            }
        })

        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(removeOriginalBroadcastReceiver, IntentFilter(AcquiringDialogFragment.BROADCAST_REMOVE_ORIGINAL))
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        (requireActivity() as AppCompatActivity).supportActionBar!!.hide()
    }

    override fun onStart() {
        super.onStart()
        mediaPagerAdapter.initializePlayer()
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

        with(mediaPager.findViewHolderForAdapterPosition((mediaPager.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition())) {
            if (this is MediaPagerAdapter.VideoViewHolder) {
                this.resume()
            }
        }
    }

    override fun onPause() {
        //Log.e(">>>>>", "onPause")
        with(mediaPager.findViewHolderForAdapterPosition((mediaPager.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition())) {
            if (this is MediaPagerAdapter.VideoViewHolder) this.pause()
        }

        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putParcelable(PLAYER_STATE, mediaPagerAdapter.getPlayerState())
    }

    override fun onStop() {
        mediaPagerAdapter.cleanUp()
        super.onStop()
    }

    override fun onDestroyView() {
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(removeOriginalBroadcastReceiver)

        super.onDestroyView()
    }

    override fun onDestroy() {
        (requireActivity() as AppCompatActivity).run {
            supportActionBar!!.show()
            window.statusBarColor = savedStatusBarColor
            window.navigationBarColor = savedNavigationBarColor
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) window.navigationBarDividerColor = savedNavigationBarDividerColor
        }

        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == STORAGE_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) observeCameraRoll()
            else if (requireActivity() is MainActivity) parentFragmentManager.popBackStack() else requireActivity().finish()
        }
    }

    // From ConfirmDialogFragment for media deletion
    override fun onResult(positive: Boolean, requestCode: Int) {
        if (positive) camerarollModel.removeCurrentMedia()
    }

    private fun observeCameraRoll() {
        // Observing media list update
        camerarollModel.getMediaList().observe(viewLifecycleOwner, Observer {
            if (it.size == 0) {
                Toast.makeText(requireContext(), getString(R.string.empty_camera_roll), Toast.LENGTH_SHORT).show()
                if (requireActivity() is MainActivity) parentFragmentManager.popBackStack() else requireActivity().finish()
            }

            // Set initial position if passed in arguments
            if (startWithThisMedia.isNotEmpty()) {
                camerarollModel.setCurrentMediaIndex(it.indexOfFirst { it.id == startWithThisMedia })
                startWithThisMedia = ""
            }

            // Populate list and scroll to correct position
            (mediaPager.adapter as MediaPagerAdapter).submitList(it)
            mediaPager.scrollToPosition(camerarollModel.getCurrentMediaIndex())
            (quickScroll.adapter as QuickScrollAdapter).submitList(it)

            // Disable delete function if it's launched as media viewer on Android 11
            if (camerarollModel.shouldDisableRemove()) removeButton.isEnabled = false

        })
    }

    private fun toggleControlView(show: Boolean) {
        TransitionManager.beginDelayedTransition(controlViewGroup, Slide(Gravity.BOTTOM).apply { duration = resources.getInteger(android.R.integer.config_shortAnimTime).toLong() })
        controlViewGroup.visibility = if (show) View.VISIBLE else View.GONE

        if (mediaPagerAdapter.itemCount == 1) {
            // Disable quick scroll if there is only one media
            quickScroll.visibility = View.GONE
            divider.visibility = View.GONE
            // Disable share function if scheme of the uri shared with us is "file", this only happened when viewing a single file
            if (mediaPagerAdapter.getMediaAtPosition(0).id.startsWith("file")) shareButton.isEnabled = false
        }
    }

    @SuppressLint("SetTextI18n")
    private fun newPositionSet() {
        (mediaPager.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition().apply {
            camerarollModel.setCurrentMediaIndex(this)

            if (this >= 0) {
                with(mediaPagerAdapter.getMediaAtPosition(this)) {
                    nameTextView.text = name
                    sizeTextView.text = "${dateTaken.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())}, ${dateTaken.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))}   |   ${Tools.humanReadableByteCountSI(eTag.toLong())}"

                    var pos = quickScrollAdapter.findMediaPosition(this)
                    if (pos == 1) pos = 0   // Show date separator for first item
                    quickScroll.scrollToPosition(pos)
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    class CameraRollViewModelFactory(private val application: Application, private val fileUri: String?): ViewModelProvider.NewInstanceFactory() {
        override fun <T : ViewModel?> create(modelClass: Class<T>): T = CameraRollViewModel(application, fileUri) as T
    }

    class CameraRollViewModel(application: Application, fileUri: String?): AndroidViewModel(application) {
        private val mediaList = MutableLiveData<MutableList<Photo>>()
        private var currentMediaIndex = 0
        private val cr = application.contentResolver
        private var shouldDisableRemove = false

        init {
            var medias = mutableListOf<Photo>()

            fileUri?.apply {
                Tools.getFolderFromUri(this, application.contentResolver)?.let { uri->
                    //Log.e(">>>>>", "${uri.first}   ${uri.second}")
                    medias = Tools.listMediaContent(uri.first, cr, false, true)
                    setCurrentMediaIndex(medias.indexOfFirst { it.id.substringAfterLast('/') == uri.second })
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
                                cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME))?.let { photo.name = it }
                                // Store file size in property eTag
                                cursor.getString(cursor.getColumnIndex(OpenableColumns.SIZE))?.let { photo.eTag = it }
                            }
                        }
                        "file" -> uri.path?.let { photo.name = it.substringAfterLast('/') }
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
                        when (photo.mimeType) {
                            "image/jpeg", "image/tiff" -> {
                                val exif = ExifInterface(cr.openInputStream(uri)!!)

                                // Get date
                                photo.dateTaken = Tools.getImageFileDate(exif, photo.name)?.let {
                                    try {
                                        LocalDateTime.parse(it, DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss"))
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                        LocalDateTime.now()
                                    }
                                } ?: LocalDateTime.now()

                                // Store orientation in property shareId
                                photo.shareId = exif.rotationDegrees
                            }
                            "image/gif" -> {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                    // Set my own image/agif mimetype for animated GIF
                                    if (ImageDecoder.decodeDrawable(ImageDecoder.createSource(cr, uri)) is AnimatedImageDrawable) photo.mimeType = "image/agif"
                                }
                            }
                            "image/webp" -> {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                    // Set my own image/agif mimetype for animated GIF
                                    if (ImageDecoder.decodeDrawable(ImageDecoder.createSource(cr, uri)) is AnimatedImageDrawable) photo.mimeType = "image/awebp"
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

                    medias.add(photo)
                }

            } ?: run { medias = Tools.getCameraRoll(cr, false) }

            mediaList.postValue(medias)
        }

        fun setCurrentMediaIndex(position: Int) { currentMediaIndex = position }
        fun getCurrentMediaIndex(): Int = currentMediaIndex
        //fun setCurrentMedia(media: Photo) { currentMediaIndex = mediaList.value!!.indexOf(media) }
        //fun setCurrentMedia(id: String) { currentMediaIndex = mediaList.value!!.indexOfFirst { it.id == id }}
        fun getMediaList(): LiveData<MutableList<Photo>> = mediaList
        //fun getMediaListSize(): Int = mediaList.value!!.size

        fun getCurrentMediaUri(): Uri = Uri.parse(mediaList.value?.get(currentMediaIndex)!!.id)

        fun removeCurrentMedia() {
            val newList = mediaList.value?.toMutableList()

            newList?.run {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) cr.delete(Uri.parse(this[currentMediaIndex].id), null, null)
                removeAt(currentMediaIndex)

                // Move index to the end of the new list if item to removed is at the end of the list
                currentMediaIndex = if (size > 0) min(currentMediaIndex, size-1) else 0

                mediaList.postValue(this)
            }
        }

        fun shouldDisableRemove(): Boolean = this.shouldDisableRemove
    }

    class MediaPagerAdapter(private val ctx: Context, private val photoClickListener: (Photo) -> Unit, private val videoClickListener: (Boolean) -> Unit, private val imageLoader: (Photo, ImageView, String) -> Unit
    ): ListAdapter<Photo, RecyclerView.ViewHolder>(PhotoDiffCallback()) {
        private lateinit var exoPlayer: SimpleExoPlayer
        private var currentVolume = 0f
        private var oldVideoViewHolder: VideoViewHolder? = null
        private var savedPlayerState = PlayerState(isMuted = false, stopPosition = FAKE_POSITION)

        @Parcelize
        data class PlayerState(
            var isMuted: Boolean,
            var stopPosition: Long,
        ): Parcelable {
            fun setState(isMuted: Boolean, stopPosition: Long) {
                this.isMuted = isMuted
                this.stopPosition = stopPosition
            }
        }

        inner class PhotoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            fun bind(photo: Photo) {
                itemView.findViewById<PhotoView>(R.id.media).apply {
                    imageLoader(photo, this, ImageLoaderViewModel.TYPE_FULL)

                    setOnPhotoTapListener { _, _, _ ->  photoClickListener(photo) }
                    setOnOutsidePhotoTapListener { photoClickListener(photo) }

                    maximumScale = 5.0f
                    mediumScale = 2.5f

                    ViewCompat.setTransitionName(this, photo.id)
                }
            }
        }

        inner class AnimatedViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            @SuppressLint("ClickableViewAccessibility")
            fun bind(photo: Photo) {
                itemView.findViewById<ImageView>(R.id.media).apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        setImageDrawable(ImageDecoder.decodeDrawable(ImageDecoder.createSource(this.context.contentResolver, Uri.parse(photo.id))).apply { if (this is AnimatedImageDrawable) start() })
                    } else {
                        //setImageBitmap(BitmapFactory.decodeStream(this.context.contentResolver.openInputStream(Uri.parse(photo.id))))
                        imageLoader(photo, this, ImageLoaderViewModel.TYPE_FULL)
                    }
                    setOnClickListener { photoClickListener(photo) }
                    ViewCompat.setTransitionName(this, photo.id)
                }
            }
        }

        inner class VideoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private lateinit var videoView: PlayerView
            private lateinit var muteButton: ImageButton
            private var videoId = ""
            private var stopPosition = 0L

            @SuppressLint("ClickableViewAccessibility")
            fun bind(video: Photo) {
                if (savedPlayerState.stopPosition != FAKE_POSITION) {
                    stopPosition = savedPlayerState.stopPosition
                    savedPlayerState.stopPosition = FAKE_POSITION

                    if (savedPlayerState.isMuted) exoPlayer.volume = 0f
                }
                muteButton = itemView.findViewById(R.id.exo_mute)
                videoView = itemView.findViewById(R.id.player_view)
                videoId = video.id

                itemView.findViewById<ConstraintLayout>(R.id.videoview_container).let {
                    // Fix view aspect ratio
                    if (video.height != 0) with(ConstraintSet()) {
                        clone(it)
                        setDimensionRatio(R.id.media, "${video.width}:${video.height}")
                        applyTo(it)
                    }

                    // TODO If user touch outside VideoView, how to sync video player control view
                    //it.setOnClickListener { clickListener(video) }
                }

                with(videoView) {
                    setControllerVisibilityListener { videoClickListener(it == View.VISIBLE) }
                    //setOnClickListener { videoClickListener(muteButton.visibility == View.VISIBLE) }
                    //ViewCompat.setTransitionName(this, video.id)
                }

                muteButton.setOnClickListener { toggleMute() }
            }

            fun hideControllers() { videoView.hideController() }
            fun setStopPosition(position: Long) {
                //Log.e(">>>","set stop position $position")
                stopPosition = position }

            // This step is important to reset the SurfaceView that ExoPlayer attached to, avoiding video playing with a black screen
            fun resetVideoViewPlayer() { videoView.player = null }

            fun resume() {
                //Log.e(">>>>", "resume playback at $stopPosition")
                exoPlayer.apply {
                    // Stop playing old video if swipe from it. The childDetachedFrom event of old VideoView always fired later than childAttachedTo event of new VideoView
                    if (isPlaying) {
                        playWhenReady = false
                        stop()
                        oldVideoViewHolder?.apply {
                            if (this != this@VideoViewHolder) {
                                setStopPosition(currentPosition)
                            }
                        }
                    }
                    playWhenReady = true
                    setMediaItem(MediaItem.fromUri(videoId), stopPosition)
                    prepare()
                    oldVideoViewHolder?.resetVideoViewPlayer()
                    videoView.player = exoPlayer
                    oldVideoViewHolder = this@VideoViewHolder

                    // Maintain mute status indicator
                    muteButton.setImageResource(if (exoPlayer.volume == 0f) R.drawable.ic_baseline_volume_off_24 else R.drawable.ic_baseline_volume_on_24)

                    // Keep screen on
                    (videoView.context as Activity).window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            fun pause() {
                //Log.e(">>>>", "pause playback")
                // If swipe out to a new VideoView, then no need to perform stop procedure. The childDetachedFrom event of old VideoView always fired later than childAttachedTo event of new VideoView
                if (oldVideoViewHolder == this) {
                    exoPlayer.apply {
                        playWhenReady = false
                        stop()
                        setStopPosition(currentPosition)
                    }
                    hideControllers()
                }

                // Resume auto screen off
                (videoView.context as Activity).window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

                savedPlayerState.setState(exoPlayer.volume == 0f, stopPosition)
            }

            private fun mute() {
                currentVolume = exoPlayer.volume
                exoPlayer.volume = 0f
                muteButton.setImageResource(R.drawable.ic_baseline_volume_off_24)
            }

            private fun toggleMute() {
                exoPlayer.apply {
                    if (volume == 0f) {
                        volume = currentVolume
                        muteButton.setImageResource(R.drawable.ic_baseline_volume_on_24)
                    }
                    else mute()
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return when(viewType) {
                TYPE_PHOTO-> PhotoViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.viewpager_item_photo, parent, false))
                TYPE_ANIMATED -> AnimatedViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.viewpager_item_gif, parent, false))
                else-> VideoViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.viewpager_item_exoplayer, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when(holder) {
                is PhotoViewHolder-> holder.bind(getItem(position))
                is AnimatedViewHolder -> holder.bind(getItem(position))
                else-> (holder as VideoViewHolder).bind(getItem(position))
            }
        }

        override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
            super.onViewAttachedToWindow(holder)
            //Log.e(">>>>>", "onViewAttachedToWindow $holder")
            if (holder is VideoViewHolder) {
                holder.resume()
            }
        }

        override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
            super.onViewDetachedFromWindow(holder)
            //Log.e(">>>>>", "onViewDetachedFromWindow $holder")
            if (holder is VideoViewHolder) {
                holder.pause()
            }
        }

        override fun submitList(list: MutableList<Photo>?) {
            super.submitList(list?.toMutableList())
        }

        override fun getItemViewType(position: Int): Int {
            with(currentList[position].mimeType) {
                return when {
                    this == "image/gif" || this == "image/webp" -> TYPE_ANIMATED    // Let viewholder decide how to handle animation
                    this.startsWith("video/") -> TYPE_VIDEO
                    else -> TYPE_PHOTO
                }
            }
        }

        fun getMediaAtPosition(position: Int): Photo = currentList[position]
        fun findMediaPosition(photo: Photo): Int = currentList.indexOf(photo)
        fun setPlayerState(state: PlayerState) { savedPlayerState = state }
        fun getPlayerState(): PlayerState = savedPlayerState

        fun initializePlayer() {
            //private var exoPlayer = SimpleExoPlayer.Builder(ctx, { _, _, _, _, _ -> arrayOf(MediaCodecVideoRenderer(ctx, MediaCodecSelector.DEFAULT)) }) { arrayOf(Mp4Extractor()) }.build()
            exoPlayer = SimpleExoPlayer.Builder(ctx).build()
            currentVolume = exoPlayer.volume
            exoPlayer.addListener(object: Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    super.onPlaybackStateChanged(state)

                    if (state == Player.STATE_ENDED) {
                        exoPlayer.playWhenReady = false
                        exoPlayer.seekTo(0L)
                        oldVideoViewHolder?.setStopPosition(0L)

                        // Resume auto screen off
                        (oldVideoViewHolder?.itemView?.context as Activity).window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    super.onIsPlayingChanged(isPlaying)
                    if (isPlaying) oldVideoViewHolder?.hideControllers()
                }
            })

            // Default mute the video playback during late night
            with(LocalDateTime.now().hour) {
                if (this >= 22 || this < 7) {
                    currentVolume = exoPlayer.volume
                    exoPlayer.volume = 0f
                }
            }
        }

        fun cleanUp() { exoPlayer.release() }

        companion object {
            private const val TYPE_PHOTO = 0
            private const val TYPE_ANIMATED = 1
            private const val TYPE_VIDEO = 2

            const val FAKE_POSITION = -1L
        }
    }

    class QuickScrollAdapter(private val clickListener: (Photo) -> Unit, private val imageLoader: (Photo, ImageView, String) -> Unit
    ): ListAdapter<Photo, RecyclerView.ViewHolder>(PhotoDiffCallback()) {

        inner class MediaViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
            fun bind(item: Photo) {
                with(itemView.findViewById<ImageView>(R.id.photo)) {
                    imageLoader(item, this, ImageLoaderViewModel.TYPE_GRID)
                    setOnClickListener { clickListener(item) }
                }
                itemView.findViewById<ImageView>(R.id.play_mark).visibility = if (Tools.isMediaPlayable(item.mimeType)) View.VISIBLE else View.GONE
            }
        }

        inner class DateViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
            fun bind(item: Photo) {
                with(item.dateTaken) {
                    itemView.findViewById<TextView>(R.id.month).text = this.monthValue.toString()
                    itemView.findViewById<TextView>(R.id.day).text = this.dayOfMonth.toString()
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            if (viewType == MEDIA_TYPE) MediaViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_cameraroll, parent, false))
            else DateViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_cameraroll_date, parent, false))

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is MediaViewHolder) holder.bind(currentList[position])
            else if (holder is DateViewHolder) holder.bind(currentList[position])
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

                super.submitList(listGroupedByDate)
            }
        }

        override fun getItemViewType(position: Int): Int = if (currentList[position].id.isEmpty()) DATE_TYPE else MEDIA_TYPE

        fun findMediaPosition(photo: Photo): Int = currentList.indexOf(photo)

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

        const val TAG_DESTINATION_DIALOG = "CAMERAROLL_DESTINATION_DIALOG"
        const val TAG_ACQUIRING_DIALOG = "CAMERAROLL_ACQUIRING_DIALOG"
        private const val CONFIRM_DIALOG = "CONFIRM_DIALOG"
        private const val DELETE_MEDIA_REQUEST_CODE = 3399

        private const val STORAGE_PERMISSION_REQUEST = 6464

        private const val PLAYER_STATE = "PLAYER_STATE"

        @JvmStatic
        fun newInstance(scrollTo: String) = CameraRollFragment().apply { arguments = Bundle().apply { putString(KEY_SCROLL_TO, scrollTo) }}

        @JvmStatic
        fun newInstance(uri: Uri) = CameraRollFragment().apply { arguments = Bundle().apply { putString(KEY_URI, uri.toString()) }}
    }
}
