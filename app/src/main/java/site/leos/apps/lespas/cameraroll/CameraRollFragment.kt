package site.leos.apps.lespas.cameraroll

import android.annotation.SuppressLint
import android.app.Application
import android.content.ContentUris
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.transition.Slide
import android.transition.TransitionManager
import android.view.*
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.ViewCompat
import androidx.core.view.get
import androidx.exifinterface.media.ExifInterface
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.*
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.github.chrisbanes.photoview.PhotoView
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.*
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.photo.PhotoSlideFragment
import site.leos.apps.lespas.sync.AcquiringDialogFragment
import site.leos.apps.lespas.sync.DestinationDialogFragment
import java.lang.Integer.min
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.roundToInt

class CameraRollFragment : Fragment(), ConfirmDialogFragment.OnResultListener {
    private lateinit var controlViewGroup: ConstraintLayout
    private lateinit var mediaPager: ViewPager2
    private lateinit var quickScroll: RecyclerView
    private lateinit var nameTextView: TextView
    private lateinit var sizeTextView: TextView

    private val imageLoaderModel: ImageLoaderViewModel by activityViewModels()
    private val destinationModel: DestinationDialogFragment.DestinationViewModel by activityViewModels()
    private val camerarollModel: CamerarollViewModel by viewModels { CamerarollViewModelFactory(requireActivity().application) }

    private lateinit var mediaPagerAdapter: MediaPagerAdapter
    private lateinit var quickScrollAdapter: QuickScrollAdapter

    private var initialPosition = arguments?.getString(KEY_SCROLL_TO) ?: ""
    private var videoStopPosition = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Create adapter here so that it won't leak
        mediaPagerAdapter = MediaPagerAdapter(
            { toggleControlView(controlViewGroup.visibility == View.GONE) },
            {photo, imageView, type-> imageLoaderModel.loadPhoto(photo, imageView, type) },
            { newPosition->
                if (newPosition > 0) videoStopPosition = newPosition
                videoStopPosition
            }
        )

        quickScrollAdapter = QuickScrollAdapter(
            { photo ->  mediaPager.setCurrentItem(mediaPagerAdapter.findMediaPosition(photo), false) },
            { photo, imageView, type -> imageLoaderModel.loadPhoto(photo, imageView, type) }
        )

        savedInstanceState?.apply { videoStopPosition = getInt(STOP_POSITION) }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.fragment_camera_roll, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        controlViewGroup = view.findViewById(R.id.control_container)
        nameTextView = view.findViewById(R.id.name)
        sizeTextView = view.findViewById(R.id.size)

        view.findViewById<ImageButton>(R.id.share_button).setOnClickListener {
            toggleControlView(false)

            val mediaToShare = mediaPagerAdapter.getMediaAtPosition(camerarollModel.getCurrentMediaIndex())
            startActivity(Intent.createChooser(Intent().apply {
                action = Intent.ACTION_SEND
                type = mediaToShare.mimeType
                putExtra(Intent.EXTRA_STREAM, Uri.parse(mediaToShare.id))
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            }, null))
        }
        view.findViewById<ImageButton>(R.id.lespas_button).setOnClickListener {
            toggleControlView(false)

            if (parentFragmentManager.findFragmentByTag(TAG_DESTINATION_DIALOG) == null)
                DestinationDialogFragment.newInstance().show(parentFragmentManager, TAG_DESTINATION_DIALOG)
        }
        view.findViewById<ImageButton>(R.id.remove_button).setOnClickListener {
            toggleControlView(false)

            if (parentFragmentManager.findFragmentByTag(CONFIRM_DIALOG) == null) ConfirmDialogFragment.newInstance(getString(R.string.confirm_delete), getString(R.string.yes_delete)).let{
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

        mediaPager = view.findViewById<ViewPager2>(R.id.media_pager).apply {
            adapter = mediaPagerAdapter

            registerOnPageChangeCallback(object: ViewPager2.OnPageChangeCallback() {
                override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                    super.onPageScrolled(position, positionOffset, positionOffsetPixels)
                    toggleControlView(false)
                }

                override fun onPageSelected(position: Int) {
                    super.onPageSelected(position)

                    camerarollModel.setCurrentMediaIndex(position)
                    with(mediaPagerAdapter.getMediaAtPosition(position)) {
                        nameTextView.text = name
                        sizeTextView.text = eTag

                        var pos = quickScrollAdapter.findMediaPosition(this)
                        if (pos == 1) pos = 0
                        quickScroll.scrollToPosition(pos)
                    }
                }
            })
        }

        camerarollModel.getMediaList().observe(viewLifecycleOwner, Observer {
            if (it.size == 0) {
                Toast.makeText(requireContext(), getString(R.string.empty_camera_roll), Toast.LENGTH_SHORT).show()
                parentFragmentManager.popBackStack()
            }

            // Set initial position if passed in arguments
            if (initialPosition.isNotEmpty()) {
                camerarollModel.setCurrentMedia(initialPosition)
                initialPosition = ""
            }

            // Populate list and scroll to correct position
            (mediaPager.adapter as MediaPagerAdapter).submitList(it)
            mediaPager.setCurrentItem(camerarollModel.getCurrentMediaIndex(), false)
            (quickScroll.adapter as QuickScrollAdapter).submitList(it)
        })

        destinationModel.getDestination().observe(viewLifecycleOwner, Observer { album ->
            // Acquire files
            if (parentFragmentManager.findFragmentByTag(TAG_ACQUIRING_DIALOG) == null)
                AcquiringDialogFragment.newInstance(arrayListOf(Uri.parse(mediaPagerAdapter.getMediaAtPosition(camerarollModel.getCurrentMediaIndex()).id)!!), album).show(parentFragmentManager, TAG_ACQUIRING_DIALOG)
        })

    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        (requireActivity() as AppCompatActivity).supportActionBar!!.hide()
    }

    override fun onPause() {
        super.onPause()
        with(mediaPager[0].findViewById<View>(R.id.media)) {
            if (this is VolumeControlVideoView) {
                // Save stop position to VideoView's seekWhenPrepare property and local property for later use in onSaveInstanceState
                videoStopPosition = currentPosition
                this.setSeekOnPrepare(currentPosition)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STOP_POSITION, videoStopPosition)
    }

    override fun onDestroy() {
        super.onDestroy()
        (requireActivity() as AppCompatActivity).supportActionBar!!.show()
    }

    override fun onResult(positive: Boolean, requestCode: Int) {
        if (positive) camerarollModel.removeCurrentMedia()
    }

    private fun toggleControlView(show: Boolean) {
        TransitionManager.beginDelayedTransition(controlViewGroup, Slide(Gravity.BOTTOM).apply { duration = 80 })
        controlViewGroup.visibility = if (show) View.VISIBLE else View.GONE
    }

    @Suppress("UNCHECKED_CAST")
    class CamerarollViewModelFactory(private val application: Application): ViewModelProvider.NewInstanceFactory() {
        override fun <T : ViewModel?> create(modelClass: Class<T>): T = CamerarollViewModel(application) as T
    }

    class CamerarollViewModel(application: Application): AndroidViewModel(application) {
        private val mediaList = MutableLiveData<MutableList<Photo>>()
        private var currentMediaIndex = 0
        private val cr = application.contentResolver

        init {
            val medias = mutableListOf<Photo>()
            val externalStorageUri = MediaStore.Files.getContentUri("external")

            @Suppress("DEPRECATION")
            val pathSelection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Files.FileColumns.RELATIVE_PATH else MediaStore.Files.FileColumns.DATA
            val dateSelection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.MediaColumns.DATE_TAKEN else MediaStore.Files.FileColumns.DATE_ADDED
            //val dateSelection = MediaStore.Files.FileColumns.DATE_ADDED
            val projection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                pathSelection,
                dateSelection,
                MediaStore.Files.FileColumns.MEDIA_TYPE,
                MediaStore.Files.FileColumns.MIME_TYPE,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.SIZE,
            )
            val selection =
                "(${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}) AND ($pathSelection LIKE '%DCIM%')"

            cr.query(externalStorageUri, projection, selection, null, "$dateSelection DESC"
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                //val pathColumn = cursor.getColumnIndexOrThrow(pathSelection)
                val dateColumn = cursor.getColumnIndexOrThrow(dateSelection)
                val typeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val defaultZone = ZoneId.systemDefault()
                var date: LocalDateTime
                var mediaUri: Uri
                val sizeOption = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                    inSampleSize = 1
                }
                var mimeType: String
                var orientation = 0
                var width = 0
                var height = 0

                while (cursor.moveToNext()) {
                    // DATE_TAKEN in Q and above has nano adjustment
                    date = LocalDateTime.ofInstant(Instant.ofEpochSecond(cursor.getLong(dateColumn).let { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) it / 1000 else it}), defaultZone)
                    mediaUri = ContentUris.withAppendedId(externalStorageUri, cursor.getString(idColumn).toLong())
                    mimeType = cursor.getString(typeColumn)

                    // Get media dimension and orientation
                    if (mimeType.startsWith("image/", true)) {
                        BitmapFactory.decodeStream(cr.openInputStream(mediaUri), null, sizeOption)
                        width = sizeOption.outWidth
                        height = sizeOption.outHeight
                        orientation = if (mimeType == "image/jpeg" || mimeType == "image/tiff") ExifInterface(cr.openInputStream(mediaUri)!!).rotationDegrees else 0
                    } else if (mimeType.startsWith("video/", true)) {
                        with(MediaMetadataRetriever()) {
                            setDataSource(application, mediaUri)
                            width = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 0
                            height = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 0
                            // Swap width and height if rotate 90 or 270 degree
                            orientation = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt().apply {
                                if (this == 90 || this == 270) {
                                    height = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 0
                                    width = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 0
                                }
                            } ?: 0
                        }
                    }

                    // Insert media
                    medias.add(Photo(mediaUri.toString(), ImageLoaderViewModel.FROM_CAMERA_ROLL, cursor.getString(nameColumn), Tools.humanReadableByteCountSI(cursor.getLong(sizeColumn)), date, date, width, height, mimeType, orientation))
                }
            }

            mediaList.postValue(medias)
        }

        fun setCurrentMediaIndex(position: Int) { currentMediaIndex = position }
        fun getCurrentMediaIndex(): Int = currentMediaIndex
        fun setCurrentMedia(media: Photo) { currentMediaIndex = mediaList.value!!.indexOf(media) }
        fun setCurrentMedia(id: String) { currentMediaIndex = mediaList.value!!.indexOfFirst { it.id == id }}
        fun getMediaList(): LiveData<MutableList<Photo>> = mediaList

        fun removeCurrentMedia() {
            val newList = mediaList.value?.toMutableList()

            newList?.apply {
                cr.delete(Uri.parse(this[currentMediaIndex].id), null, null)
                removeAt(currentMediaIndex)

                // Move index to the end of the new list if item to removed is at the end of the list
                currentMediaIndex = min(currentMediaIndex, size-1)

                mediaList.postValue(newList)
            }
        }
        /*
        fun removeCurrentMedia() {
            val newList = mediaList.value?.toMutableList()

            newList?.apply {
                //val itemToDelete = this.indexOf(currentMedia!!)
                val itemToDelete = currentMediaIndex
                val itemToShow: Int
                var last1inDate = false

                if (itemToDelete < this.lastIndex) {
                    // Not the last 1 in list, find next 1 to the right
                    if (this[itemToDelete + 1].id.isNotEmpty())
                        // Next 1 in list is a photo
                        itemToShow = itemToDelete + 1
                    else {
                        // Next 1 in list is date separator, get next to next 1
                        itemToShow = itemToDelete + 2
                        // If previous 1 and next 1 are all date separators, this one is the only one left in this date, should also remove it's date separator
                        last1inDate = (this[itemToDelete - 1].id.isEmpty())
                    }
                } else {
                    // Last 1 in list, should find previous 1
                    if (this[itemToDelete - 1].id.isNotEmpty())
                        // Previous 1 in list is a photo
                        itemToShow = itemToDelete - 1
                    else {
                        // Previous 1 in list is date separator
                        itemToShow = itemToDelete - 2
                        // Since this is the last 1, that means it's the only 1 in this date
                        last1inDate = true
                    }
                }

                removeAt(itemToDelete)
                if (last1inDate) removeAt(itemToDelete - 1)

                // itemToShow will be -1 in case of deleting last photo in list

                ///if (itemToShow > 0) currentMedia.postValue(newList[itemToShow])
                mediaList.postValue(newList)
            }
        }

         */
    }

    class MediaPagerAdapter(private val clickListener: (Photo) -> Unit, private val imageLoader: (Photo, ImageView, String) -> Unit, private val stopPosition: (Int) -> Int
    ): ListAdapter<Photo, RecyclerView.ViewHolder>(PhotoDiffCallback()) {

        inner class PhotoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            fun bind(photo: Photo) {
                itemView.findViewById<PhotoView>(R.id.media).apply {
                    imageLoader(photo, this, ImageLoaderViewModel.TYPE_FULL)

                    setOnPhotoTapListener { _, _, _ ->  clickListener(photo) }
                    setOnOutsidePhotoTapListener { clickListener(photo) }

                    maximumScale = 5.0f
                    mediumScale = 2.5f

                    ViewCompat.setTransitionName(this, photo.id)
                }
            }
        }

        inner class VideoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private lateinit var videoView: VolumeControlVideoView
            private lateinit var muteButton: ImageButton
            private lateinit var replayButton: ImageButton

            @SuppressLint("ClickableViewAccessibility")
            fun bind(video: Photo) {
                val root = itemView.findViewById<ConstraintLayout>(R.id.videoview_container)
                videoView = itemView.findViewById(R.id.media)
                muteButton = itemView.findViewById(R.id.mute_button)
                replayButton = itemView.findViewById(R.id.replay_button)

                with(videoView) {
                    if (video.height != 0) with(ConstraintSet()) {
                        clone(root)
                        setDimensionRatio(R.id.media, "${video.width}:${video.height}")
                        applyTo(root)
                    }
                    // Even thought we don't load animated image with ImageLoader, we still need to call it here so that postponed enter transition can be started
                    //imageLoader(video, this, ImageLoaderViewModel.TYPE_FULL)

                    setVideoURI(Uri.parse(video.id))
                    setOnCompletionListener {
                        replayButton.visibility = View.VISIBLE
                        this.stopPlayback()
                        setSeekOnPrepare(0)
                    }
                    setOnPreparedListener {
                        // Call parent onPrepared!!
                        this.onPrepared(it)

                        // Default mute the video playback during late night
                        with(LocalDateTime.now().hour) { if (this >= 22 || this < 7) setMute(true) }
                        // Restart playing after seek to last stop position
                        it.setOnSeekCompleteListener { mp-> mp.start() }
                    }

                    setOnClickListener {
                        clickListener(video)
                    }

                    ViewCompat.setTransitionName(this, video.id)
                }

                muteButton.setOnClickListener { setMute(!videoView.isMute()) }
                replayButton.setOnClickListener {
                    it.visibility = View.GONE
                    videoView.setVideoURI(Uri.parse(video.id))
                    videoView.start()
                }

                // If user touch outside VideoView
                itemView.findViewById<ConstraintLayout>(R.id.videoview_container).setOnTouchListener { _, event ->
                    if (event.action == MotionEvent.ACTION_DOWN) {
                        clickListener(video)
                        true
                    } else false
                }
            }

            private fun setMute(mute: Boolean) {
                if (mute) {
                    videoView.mute()
                    muteButton.setImageResource(R.drawable.ic_baseline_volume_off_24)
                } else {
                    videoView.unMute()
                    muteButton.setImageResource(R.drawable.ic_baseline_volume_on_24)
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return when(viewType) {
                TYPE_PHOTO-> PhotoViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.viewpager_item_photo, parent, false))
                else-> VideoViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.viewpager_item_video, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when(holder) {
                is PhotoViewHolder-> holder.bind(currentList[position])
                else-> (holder as VideoViewHolder).bind(currentList[position])
            }
        }

        override fun submitList(list: MutableList<Photo>?) {
            super.submitList(list?.toMutableList())
        }

        override fun getItemViewType(position: Int): Int {
            with(currentList[position].mimeType) {
                return when {
                    this.startsWith("video/") -> TYPE_VIDEO
                    else -> TYPE_PHOTO
                }
            }
        }

        fun getMediaAtPosition(position: Int): Photo = currentList[position]
        fun findMediaPosition(photo: Photo): Int = currentList.indexOf(photo)

        override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
            super.onViewAttachedToWindow(holder)
            if (holder is PhotoSlideFragment.PhotoSlideAdapter.VideoViewHolder) {
                // Restore playback position when View got recreated, like screen rotated
                holder.itemView.findViewById<VolumeControlVideoView>(R.id.media).apply {
                    // If view's seeWhenPrepare property value is 0, means new view created, then need to set last stop position (saved by saveInstanceState()) as seekWhenPrepare
                    if (getSeekOnPrepare() == 0) setSeekOnPrepare(stopPosition(-1))
                }
            }
        }

        override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
            super.onViewDetachedFromWindow(holder)
            if (holder is PhotoSlideFragment.PhotoSlideAdapter.VideoViewHolder) {
                holder.itemView.findViewById<VolumeControlVideoView>(R.id.media).apply {
                    // Save playback position when being swiped, when swap between recent apps, onViewDetachedFromWindow might be called with wrong currentPosition as 0, so test it's value first
                    if (currentPosition > 0) {
                        setSeekOnPrepare(currentPosition)
                        stopPosition(currentPosition)
                    }
                    stopPlayback()
                }
            }
        }

        companion object {
            private const val TYPE_PHOTO = 0
            private const val TYPE_VIDEO = 2
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

        const val TAG_DESTINATION_DIALOG = "CAMERAROLL_DESTINATION_DIALOG"
        const val TAG_ACQUIRING_DIALOG = "CAMERAROLL_ACQUIRING_DIALOG"

        private const val CONFIRM_DIALOG = "CONFIRM_DIALOG"
        private const val DELETE_MEDIA_REQUEST_CODE = 3399

        private const val STOP_POSITION = "STOP_POSITION"

        @JvmStatic
        fun newInstance(scrollTo: String) = CameraRollFragment().apply { arguments = Bundle().apply { putString(KEY_SCROLL_TO, scrollTo) }}
    }
}