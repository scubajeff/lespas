package site.leos.apps.lespas.cameraroll

import android.accounts.AccountManager
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.ContentResolver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.transition.Slide
import android.transition.TransitionManager
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import androidx.core.app.SharedElementCallback
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.*
import androidx.lifecycle.Observer
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.*
import com.google.android.material.transition.MaterialContainerTransform
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

class CameraRollFragment : Fragment() {
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

    private lateinit var storagePermissionRequestLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Create adapter here so that it won't leak
        mediaPagerAdapter = MediaPagerAdapter(
            { _-> toggleControlView(controlViewGroup.visibility == View.GONE) },    // TODO what's the proper way to toggle quickscroll
            { photo, imageView, type-> imageLoaderModel.loadPhoto(photo, imageView, type) { startPostponedEnterTransition() }},
            { view-> imageLoaderModel.cancelLoading(view as ImageView) }
        ).apply { stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY }

        quickScrollAdapter = QuickScrollAdapter(
            { photo ->
                mediaPager.scrollToPosition(mediaPagerAdapter.findMediaPosition(photo))
                toggleControlView(false)
            },
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

        savedInstanceState?.getParcelable<MediaSliderAdapter.PlayerState>(PLAYER_STATE)?.apply { mediaPagerAdapter.setPlayerState(this) }

        startWithThisMedia = arguments?.getString(KEY_SCROLL_TO) ?: ""

        sharedElementEnterTransition = MaterialContainerTransform().apply {
            duration = resources.getInteger(android.R.integer.config_shortAnimTime).toLong()
            scrimColor = Color.TRANSPARENT
        }

        // Adjusting the shared element mapping
        setEnterSharedElementCallback(object : SharedElementCallback() {
            override fun onMapSharedElements(names: MutableList<String>?, sharedElements: MutableMap<String, View>?) {
                try {
                    sharedElements?.put(names?.get(0)!!, mediaPager.findViewHolderForAdapterPosition(camerarollModel.getCurrentMediaIndex())?.itemView?.findViewById(R.id.media)!!)
                } catch (e: IndexOutOfBoundsException) { e.printStackTrace() }
            }
        })

        storagePermissionRequestLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            when {
                isGranted -> observeCameraRoll()
                requireActivity() is MainActivity -> parentFragmentManager.popBackStack()
                else -> requireActivity().finish()
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.fragment_camera_roll, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        (requireActivity() as AppCompatActivity).supportActionBar!!.hide()

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
                deleteMediaLauncher.launch(IntentSenderRequest.Builder(MediaStore.createDeleteRequest(requireContext().contentResolver, mutableListOf(camerarollModel.getCurrentMediaUri()))).setFillInIntent(null).build())
            }
            else if (parentFragmentManager.findFragmentByTag(CONFIRM_DIALOG) == null) ConfirmDialogFragment.newInstance(getString(R.string.confirm_delete), getString(R.string.yes_delete)).show(parentFragmentManager, CONFIRM_DIALOG)
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
            if (ContextCompat.checkSelfPermission(requireContext(), permission) != PackageManager.PERMISSION_GRANTED) storagePermissionRequestLauncher.launch(permission)
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

        // Removing medias confirm result handler
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            deleteMediaLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK) camerarollModel.removeCurrentMedia()
            }
        } else {
            parentFragmentManager.setFragmentResultListener(ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY, viewLifecycleOwner) { key, bundle ->
                if (key == ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY && bundle.getBoolean(ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY, false)) camerarollModel.removeCurrentMedia()
            }
        }
    }


    override fun onStart() {
        super.onStart()
        mediaPagerAdapter.initializePlayer(requireContext())
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
            if (this is MediaSliderAdapter<*>.VideoViewHolder) this.resume()
        }
    }

    override fun onPause() {
        //Log.e(">>>>>", "onPause")
        with(mediaPager.findViewHolderForAdapterPosition((mediaPager.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition())) {
            if (this is MediaSliderAdapter<*>.VideoViewHolder) this.pause()
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

    class MediaPagerAdapter(val clickListener: (Boolean?) -> Unit, val imageLoader: (Photo, ImageView, String) -> Unit, val cancelLoader: (View) -> Unit
    ): MediaSliderAdapter<Photo>(PhotoDiffCallback(), clickListener, imageLoader, cancelLoader) {
        override fun getVideoItem(position: Int): VideoItem = with(getItem(position) as Photo) {
            VideoItem(Uri.parse(id), mimeType, width, height, id.substringAfterLast('/'))
        }
        override fun getItemTransitionName(position: Int): String = (getItem(position) as Photo).id.substringAfterLast('/')
        override fun getItemMimeType(position: Int): String = (getItem(position) as Photo).mimeType

        fun getMediaAtPosition(position: Int): Photo = currentList[position] as Photo
        fun findMediaPosition(photo: Photo): Int = (currentList as List<Photo>).indexOf(photo)
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

        private const val PLAYER_STATE = "PLAYER_STATE"

        @JvmStatic
        fun newInstance(scrollTo: String) = CameraRollFragment().apply { arguments = Bundle().apply { putString(KEY_SCROLL_TO, scrollTo) }}

        @JvmStatic
        fun newInstance(uri: Uri) = CameraRollFragment().apply { arguments = Bundle().apply { putString(KEY_URI, uri.toString()) }}
    }
}
