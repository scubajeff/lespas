package site.leos.apps.lespas.photo

import android.annotation.SuppressLint
import android.content.*
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.*
import android.widget.ImageButton
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.app.SharedElementCallback
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.get
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.workDataOf
import com.github.chrisbanes.photoview.PhotoView
import com.google.android.material.transition.MaterialContainerTransform
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.Album
import site.leos.apps.lespas.album.AlbumViewModel
import site.leos.apps.lespas.helper.ImageLoaderViewModel
import site.leos.apps.lespas.helper.SnapseedResultWorker
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.helper.VolumeControlVideoView
import site.leos.apps.lespas.sync.ActionViewModel
import java.io.File
import java.time.LocalDateTime

class PhotoSlideFragment : Fragment() {
    private lateinit var album: Album
    private lateinit var slider: ViewPager2
    private lateinit var pAdapter: PhotoSlideAdapter
    private val albumModel: AlbumViewModel by activityViewModels()
    private val actionModel: ActionViewModel by viewModels()
    private val imageLoaderModel: ImageLoaderViewModel by activityViewModels()
    private val currentPhotoModel: CurrentPhotoViewModel by activityViewModels()
    private val uiModel: UIViewModel by activityViewModels()
    private var autoRotate = false
    private var previousNavBarColor = 0

    private lateinit var snapseedCatcher: BroadcastReceiver
    private lateinit var snapseedOutputObserver: ContentObserver

    private var videoStopPosition = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        album = arguments?.getParcelable(KEY_ALBUM)!!

        sharedElementEnterTransition = MaterialContainerTransform().apply {
            duration = resources.getInteger(android.R.integer.config_mediumAnimTime).toLong()
            scrimColor = Color.TRANSPARENT
        }

        // Adjusting the shared element mapping
        setEnterSharedElementCallback(object : SharedElementCallback() {
            override fun onMapSharedElements(names: MutableList<String>?, sharedElements: MutableMap<String, View>?) {
                sharedElements?.put(names?.get(0)!!, slider[0].findViewById(R.id.media))}
        })

        autoRotate = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(context?.getString(R.string.auto_rotate_perf_key), false)

        // Broadcast receiver listening on share destination
        snapseedCatcher = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent!!.getParcelableExtra<ComponentName>(Intent.EXTRA_CHOSEN_COMPONENT)?.packageName!!.substringAfterLast('.') == "snapseed") {
                    // Shared to Snapseed. Register content observer if we have storage permission and integration with snapseed option is on
                    if (ContextCompat.checkSelfPermission(context!!, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(getString(R.string.snapseed_pref_key), false)) {
                        context.contentResolver.apply {
                            unregisterContentObserver(snapseedOutputObserver)
                            registerContentObserver(
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                true,
                                snapseedOutputObserver
                            )
                        }
                    }
                }
            }
        }
        context?.registerReceiver(snapseedCatcher, IntentFilter(CHOOSER_SPY_ACTION))

        // Content observer looking for Snapseed output
        snapseedOutputObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            private var lastId = ""
            private lateinit var snapseedWorker: WorkRequest

            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)

                // ContentObserver got called twice, once for itself, once for it's descendant, all with same last path segment
                if (uri?.lastPathSegment!! != lastId) {
                    lastId = uri.lastPathSegment!!

                    snapseedWorker = OneTimeWorkRequestBuilder<SnapseedResultWorker>().setInputData(
                        workDataOf(SnapseedResultWorker.KEY_IMAGE_URI to uri.toString(), SnapseedResultWorker.KEY_SHARED_PHOTO to pAdapter.getPhotoAt(slider.currentItem).id, SnapseedResultWorker.KEY_ALBUM to album.id)).build()
                    WorkManager.getInstance(requireContext()).enqueue(snapseedWorker)

                    WorkManager.getInstance(requireContext()).getWorkInfoByIdLiveData(snapseedWorker.id).observe(parentFragmentManager.findFragmentById(R.id.container_root)!!, { workInfo->
                        if (workInfo != null) {
                            //if (workInfo.progress.getBoolean(SnapseedResultWorker.KEY_INVALID_OLD_PHOTO_CACHE, false)) imageLoaderModel.invalid(pAdapter.getPhotoAt(slider.currentItem))
                            workInfo.progress.getString(SnapseedResultWorker.KEY_NEW_PHOTO_NAME)?.apply {
                                if (PreferenceManager.getDefaultSharedPreferences(context).getBoolean(getString(R.string.snapseed_replace_pref_key), false)) {
                                    pAdapter.getPhotoAt(slider.currentItem).let {
                                        //it.eTag = ""
                                        //it.name = this
                                        //imageLoaderModel.reloadPhoto(it)
                                        imageLoaderModel.invalid(it.id)
                                        //slider[0].findViewById<PhotoView>(R.id.media)?.invalidate()
                                        pAdapter.refreshPhoto(it)
                                    }
                                }
                                currentPhotoModel.setCurrentPhotoName(this)
                            }
                        }
                        /*
                        if (workInfo != null && workInfo.state.isFinished) {
                            if (workInfo.outputData.getBoolean(SnapseedResultWorker.KEY_INVALID_OLD_PHOTO_CACHE, false)) {
                                with(pAdapter.getPhotoAt(slider.currentItem)) {
                                    // Invalid cache, notify adapter change, and update current photo model value to show new photo
                                    imageLoaderModel.invalid(this)
                                    pAdapter.refreshPhoto(this)
                                    // TODO what if the adapter is not updated yet, pAdapter.getPhotoAt will return old information
                                    currentPhotoModel.setCurrentPhoto(this, null)
                                }
                            }
                        }
                         */
                    })
                }

                requireContext().contentResolver.unregisterContentObserver(this)
            }
        }

        pAdapter = PhotoSlideAdapter(
            "${requireContext().filesDir}${resources.getString(R.string.lespas_base_folder_name)}",
            { uiModel.toggleOnOff() },
            { newPosition->
                if (newPosition > 0) videoStopPosition = newPosition
                videoStopPosition
            },
        ) { photo, imageView, type ->
            if (Tools.isMediaPlayable(photo.mimeType)) startPostponedEnterTransition()
            else imageLoaderModel.loadPhoto(photo, imageView as ImageView, type) { startPostponedEnterTransition() }}

        savedInstanceState?.apply { videoStopPosition = getInt(STOP_POSITION) }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_photoslide, container, false)

        postponeEnterTransition()

        slider = view.findViewById<ViewPager2>(R.id.pager).apply {
            adapter = pAdapter

            // Use reflection to reduce Viewpager2 slide sensitivity, so that PhotoView inside can zoom presently
            val recyclerView = (ViewPager2::class.java.getDeclaredField("mRecyclerView").apply{ isAccessible = true }).get(this) as RecyclerView
            (RecyclerView::class.java.getDeclaredField("mTouchSlop")).apply {
                isAccessible = true
                set(recyclerView, (get(recyclerView) as Int) * 4)
            }

            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    super.onPageSelected(position)

                    pAdapter.getPhotoAt(position).run {
                        currentPhotoModel.setCurrentPhoto(this, position + 1)

                        if (autoRotate) activity?.requestedOrientation =
                            if (this.width > this.height) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    }
                }
            })
        }

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        albumModel.getAllPhotoInAlbum(album.id).observe(viewLifecycleOwner, { photos->
            pAdapter.setPhotos(photos, album.sortOrder)
            //slider.setCurrentItem(pAdapter.findPhotoPosition(currentPhotoModel.getCurrentPhoto().value!!), false)
            currentPhotoModel.getCurrentPhotoId()?.let {
                //imageLoaderModel.invalid(it)
                slider.setCurrentItem(pAdapter.findPhotoPosition(it), false)
            }
        })

        currentPhotoModel.getRemoveItem().observe(viewLifecycleOwner, {
            it?.run {
                pAdapter.getNextAvailablePhoto(it)?.let { nextPhoto->
                    currentPhotoModel.setCurrentPhoto(nextPhoto, null)
                    actionModel.deletePhotos(listOf(it), album.name)
                }
            }
        })
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        (requireActivity() as AppCompatActivity).supportActionBar!!.hide()
        requireActivity().window.run {
            previousNavBarColor = navigationBarColor
            navigationBarColor = Color.BLACK
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insetsController?.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_BARS_BY_SWIPE
                statusBarColor = Color.TRANSPARENT
                setDecorFitsSystemWindows(false)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
    }

    override fun onPause() {
        super.onPause()
        requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        with(slider[0].findViewById<View>(R.id.media)) {
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
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        requireActivity().window.navigationBarColor = previousNavBarColor

        requireContext().apply {
            unregisterReceiver(snapseedCatcher)
            contentResolver.unregisterContentObserver(snapseedOutputObserver)
        }

        currentPhotoModel.clearRemoveItem()
    }

    class PhotoSlideAdapter(private val rootPath: String, private val itemListener: OnTouchListener, private val stopPositionHolder: StopPositionHolder, private val imageLoader: OnLoadImage
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var photos = emptyList<Photo>()

        fun interface OnTouchListener { fun onTouch() }
        fun interface StopPositionHolder { fun setAndGet(newStopPosition: Int): Int }
        fun interface OnLoadImage { fun loadImage(photo: Photo, view: View, type: String) }

        inner class PhotoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            fun bindViewItems(photo: Photo) {
                itemView.findViewById<PhotoView>(R.id.media).apply {
                    imageLoader.loadImage(photo, this, ImageLoaderViewModel.TYPE_FULL)
                    setOnPhotoTapListener { _, _, _ -> itemListener.onTouch() }
                    setOnOutsidePhotoTapListener { itemListener.onTouch() }
                    maximumScale = 5.0f
                    mediumScale = 2.5f
                    ViewCompat.setTransitionName(this, photo.id)
                }
            }
        }

        inner class AnimatedViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            @SuppressLint("ClickableViewAccessibility")
            fun bindViewItems(photo: Photo) {
                itemView.findViewById<ImageView>(R.id.media).apply {
                    // Even thought we don't load animated image with ImageLoader, we still need to call it here so that postponed enter transition can be started
                    imageLoader.loadImage(photo, this, ImageLoaderViewModel.TYPE_FULL)

                    var fileName = "$rootPath/${photo.id}"
                    if (!(File(fileName).exists())) fileName = "$rootPath/${photo.name}"
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        setImageDrawable(ImageDecoder.decodeDrawable(ImageDecoder.createSource(File(fileName))).apply { if (this is AnimatedImageDrawable) this.start() })
                    } else {
                        setImageBitmap(BitmapFactory.decodeFile(fileName))
                    }
                    setOnTouchListener { _, event ->
                        if (event.action == MotionEvent.ACTION_DOWN) {
                            itemListener.onTouch()
                            true
                        } else false
                    }
                    ViewCompat.setTransitionName(this, photo.id)
                }
            }
        }

        inner class VideoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private lateinit var videoView: VolumeControlVideoView
            private lateinit var muteButton: ImageButton
            private lateinit var replayButton: ImageButton
            private lateinit var fileName: String

            @SuppressLint("ClickableViewAccessibility")
            fun bindViewItems(video: Photo) {
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
                    imageLoader.loadImage(video, this, ImageLoaderViewModel.TYPE_FULL)

                    fileName = "$rootPath/${video.id}"
                    if (!(File(fileName).exists())) fileName = "$rootPath/${video.name}"
                    setVideoPath(fileName)
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

                    setOnTouchListener { _, event ->
                        if (event.action == MotionEvent.ACTION_DOWN) {
                            itemListener.onTouch()
                            true
                        } else false
                    }

                    ViewCompat.setTransitionName(this, video.id)
                }

                muteButton.setOnClickListener { setMute(!videoView.isMute()) }
                replayButton.setOnClickListener {
                    it.visibility = View.GONE
                    videoView.setVideoPath(fileName)
                    videoView.start()
                }

                // If user touch outside VideoView
                itemView.findViewById<ConstraintLayout>(R.id.videoview_container).setOnTouchListener { _, event ->
                    if (event.action == MotionEvent.ACTION_DOWN) {
                        itemListener.onTouch()
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
                TYPE_VIDEO-> VideoViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.viewpager_item_video, parent, false))
                TYPE_ANIMATED-> AnimatedViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.viewpager_item_gif, parent, false))
                else-> PhotoViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.viewpager_item_photo, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when(holder) {
                is VideoViewHolder-> holder.bindViewItems(photos[position])
                is AnimatedViewHolder-> holder.bindViewItems(photos[position])
                else-> (holder as PhotoViewHolder).bindViewItems(photos[position])
            }
        }

        fun findPhotoPosition(photoId: String): Int {
            photos.forEachIndexed { i, p ->
                // If photo synced back from server, the id property will be changed from filename to fileId
                if ((p.id == photoId) || (p.name == photoId)) return i
            }
            return -1
        }

        fun getPhotoAt(position: Int): Photo = photos[position]

        fun getNextAvailablePhoto(photo: Photo): Photo? {
            with(photos.indexOf(photo)) {
                return when(this) {
                    -1-> null
                    photos.size - 1-> photos[this - 1]
                    else-> photos[this + 1]
                }
            }
        }

        fun refreshPhoto(photo: Photo) {
            notifyItemChanged(findPhotoPosition(photo.id))
        }

        fun setPhotos(collection: List<Photo>, sortOrder: Int) {
            //val oldPhotos = photos
            photos = when(sortOrder) {
                Album.BY_DATE_TAKEN_ASC-> collection.sortedWith(compareBy { it.dateTaken })
                Album.BY_DATE_TAKEN_DESC-> collection.sortedWith(compareByDescending { it.dateTaken })
                Album.BY_DATE_MODIFIED_ASC-> collection.sortedWith(compareBy { it.lastModified })
                Album.BY_DATE_MODIFIED_DESC-> collection.sortedWith(compareByDescending { it.lastModified })
                Album.BY_NAME_ASC-> collection.sortedWith(compareBy { it.name })
                Album.BY_NAME_DESC-> collection.sortedWith(compareByDescending { it.name })
                else-> collection
            }
            /*
            DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = oldPhotos.size
                override fun getNewListSize(): Int = photos.size
                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean = oldPhotos[oldItemPosition].name == photos[newItemPosition].name
                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean = oldPhotos[oldItemPosition] == photos[newItemPosition]
            }).dispatchUpdatesTo(this)
             */
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = photos.size

        override fun getItemViewType(position: Int): Int {
            with(getPhotoAt(position).mimeType) {
                return when {
                    this == "image/agif" || this == "image/awebp" -> TYPE_ANIMATED
                    this.startsWith("video/") -> TYPE_VIDEO
                    else -> TYPE_PHOTO
                }
            }
        }

        override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
            super.onViewAttachedToWindow(holder)
            if (holder is VideoViewHolder) {
                // Restore playback position when View got recreated, like screen rotated
                holder.itemView.findViewById<VolumeControlVideoView>(R.id.media).apply {
                    // If view's seeWhenPrepare property value is 0, means new view created, then need to set last stop position (saved by saveInstanceState()) as seekWhenPrepare
                    if (getSeekOnPrepare() == 0) setSeekOnPrepare(stopPositionHolder.setAndGet(-1))
                }
            }
        }

        override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
            super.onViewDetachedFromWindow(holder)
            if (holder is VideoViewHolder) {
                holder.itemView.findViewById<VolumeControlVideoView>(R.id.media).apply {
                    // Save playback position when being swiped, when swap between recent apps, onViewDetachedFromWindow might be called with wrong currentPosition as 0, so test it's value first
                    if (currentPosition > 0) {
                        setSeekOnPrepare(currentPosition)
                        stopPositionHolder.setAndGet(currentPosition)
                    }
                    stopPlayback()
                }
            }
        }

        companion object {
            private const val TYPE_PHOTO = 0
            private const val TYPE_ANIMATED = 1
            private const val TYPE_VIDEO = 2
        }
    }

    // Share current photo within this fragment and BottomControlsFragment and CropCoverFragment
    class CurrentPhotoViewModel : ViewModel() {
        // AlbumDetail fragment grid item positions, this is for AlbumDetailFragment, nothing to do with other fragments
        private var currentPosition = 0
        private var firstPosition = 0
        private var lastPosition = 1
        fun getCurrentPosition(): Int = currentPosition
        fun setCurrentPosition(position: Int) { currentPosition = position }
        fun setFirstPosition(position: Int) { firstPosition = position }
        fun getFirstPosition(): Int = firstPosition
        fun setLastPosition(position: Int) { lastPosition = position }
        fun getLastPosition(): Int = lastPosition

        // Current photo shared with CoverSetting and BottomControl fragments by PhotoSlider
        private val photo = MutableLiveData<Photo>()
        private val coverApplyStatus = MutableLiveData<Boolean>()
        private var forReal = false     // TODO Dirty hack, should be SingleLiveEvent
        fun getCurrentPhoto(): LiveData<Photo> { return photo }
        fun getCurrentPhotoId(): String? = photo.value?.id
        fun setCurrentPhotoName(newName: String) {
            photo.value?.name = newName
            photo.value?.eTag = ""
        }
        fun setCurrentPhoto(newPhoto: Photo, position: Int?) {
            //photo.postValue(newPhoto)
            photo.value = newPhoto
            position?.let { currentPosition = it }
        }
        fun coverApplied(applied: Boolean) {
            coverApplyStatus.value = applied
            forReal = true
        }
        fun getCoverAppliedStatus(): LiveData<Boolean> { return coverApplyStatus }
        fun forReal(): Boolean {
            val r = forReal
            forReal = false
            return r
        }

        // For removing photo
        private val removeItem = MutableLiveData<Photo>()
        fun removePhoto() { removeItem.value = photo.value }
        fun getRemoveItem(): LiveData<Photo> { return removeItem }
        fun clearRemoveItem() { removeItem.value = null }
    }

    // Share system ui visibility status with BottomControlsFragment
    class UIViewModel : ViewModel() {
        private val showUI = MutableLiveData(true)

        @Suppress("unused")
        fun hideUI() { showUI.value = false }
        fun toggleOnOff() { showUI.value = !showUI.value!! }
        fun status(): LiveData<Boolean> { return showUI }
    }

    companion object {
        private const val STOP_POSITION = "STOP_POSITION"

        const val CHOOSER_SPY_ACTION = "site.leos.apps.lespas.CHOOSER_PHOTOSLIDER"

        const val KEY_ALBUM = "ALBUM"

        fun newInstance(album: Album) = PhotoSlideFragment().apply { arguments = Bundle().apply { putParcelable(KEY_ALBUM, album) }}
    }
}