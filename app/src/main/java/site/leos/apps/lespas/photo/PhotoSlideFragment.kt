package site.leos.apps.lespas.photo

import android.annotation.SuppressLint
import android.app.Activity
import android.content.*
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.*
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
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import androidx.work.*
import com.github.chrisbanes.photoview.PhotoView
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.SimpleExoPlayer
import com.google.android.exoplayer2.ui.PlayerView
import com.google.android.material.transition.MaterialContainerTransform
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.Album
import site.leos.apps.lespas.album.AlbumViewModel
import site.leos.apps.lespas.helper.ImageLoaderViewModel
import site.leos.apps.lespas.helper.SnapseedResultWorker
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.sync.ActionViewModel
import java.io.File
import java.time.LocalDateTime

class PhotoSlideFragment : Fragment() {
    private lateinit var album: Album
    private lateinit var slider: ViewPager2
    private lateinit var pAdapter: PhotoSlideAdapter
    private val albumModel: AlbumViewModel by activityViewModels()
    private val actionModel: ActionViewModel by viewModels()
    //private val publishModel: NCShareViewModel by activityViewModels()
    private val imageLoaderModel: ImageLoaderViewModel by activityViewModels()
    private val currentPhotoModel: CurrentPhotoViewModel by activityViewModels()
    private val uiModel: UIViewModel by activityViewModels()
    private var autoRotate = false
    private var previousNavBarColor = 0
    private var previousOrientationSetting = 0

    private lateinit var snapseedCatcher: BroadcastReceiver
    private lateinit var snapseedOutputObserver: ContentObserver

    private var videoStopPosition = 0L

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
                sharedElements?.put(names?.get(0)!!, slider.getChildAt(0).findViewById(R.id.media))}
        })

        autoRotate = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(context?.getString(R.string.auto_rotate_perf_key), false)

        // Broadcast receiver listening on share destination
        snapseedCatcher = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent!!.getParcelableExtra<ComponentName>(Intent.EXTRA_CHOSEN_COMPONENT)?.packageName!!.substringAfterLast('.') == "snapseed") {
                    // Shared to Snapseed. Register content observer if we have storage permission and integration with snapseed option is on
                    if (ContextCompat.checkSelfPermission(context!!, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) android.Manifest.permission.READ_EXTERNAL_STORAGE else android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(getString(R.string.snapseed_pref_key), false)) {
                        context.contentResolver.apply {
                            unregisterContentObserver(snapseedOutputObserver)
                            registerContentObserver(
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
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
            private val workerName = "${PhotoSlideFragment::class.java.canonicalName}.SNAPSEED_WORKER"
            private var lastId = ""
            private lateinit var snapseedWorker: OneTimeWorkRequest

            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)

                // ContentObserver got called twice, once for itself, once for it's descendant, all with same last path segment
                if (uri?.lastPathSegment!! != lastId) {
                    lastId = uri.lastPathSegment!!

                    snapseedWorker = OneTimeWorkRequestBuilder<SnapseedResultWorker>().setInputData(
                        // TODO publish status is not persistent locally
                        //workDataOf(SnapseedResultWorker.KEY_IMAGE_URI to uri.toString(), SnapseedResultWorker.KEY_SHARED_PHOTO to pAdapter.getPhotoAt(slider.currentItem).id, SnapseedResultWorker.KEY_ALBUM to album.id, SnapseedResultWorker.KEY_PUBLISHED to publishModel.isShared(album.id))).build()
                        workDataOf(SnapseedResultWorker.KEY_IMAGE_URI to uri.toString(), SnapseedResultWorker.KEY_SHARED_PHOTO to pAdapter.getPhotoAt(slider.currentItem).id, SnapseedResultWorker.KEY_ALBUM to album.id)).build()
                    WorkManager.getInstance(requireContext()).enqueueUniqueWork(workerName, ExistingWorkPolicy.KEEP, snapseedWorker)

                    WorkManager.getInstance(requireContext()).getWorkInfosForUniqueWorkLiveData(workerName).observe(parentFragmentManager.findFragmentById(R.id.container_root)!!, { workInfo->
                        if (workInfo != null) {
                            //if (workInfo.progress.getBoolean(SnapseedResultWorker.KEY_INVALID_OLD_PHOTO_CACHE, false)) imageLoaderModel.invalid(pAdapter.getPhotoAt(slider.currentItem))
                            workInfo[0]?.progress?.getString(SnapseedResultWorker.KEY_NEW_PHOTO_NAME)?.apply {
                                pAdapter.getPhotoAt(slider.currentItem).let {
                                    //it.eTag = ""
                                    //it.name = this
                                    //imageLoaderModel.reloadPhoto(it)
                                    imageLoaderModel.invalid(it.id)
                                    //slider[0].findViewById<PhotoView>(R.id.media)?.invalidate()
                                    pAdapter.refreshPhoto(it)
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
            requireContext(),
            Tools.getLocalRoot(requireContext()),
            { uiModel.toggleOnOff() },
        ) { photo, imageView, type ->
            if (Tools.isMediaPlayable(photo.mimeType)) startPostponedEnterTransition()
            else imageLoaderModel.loadPhoto(photo, imageView as ImageView, type) { startPostponedEnterTransition() }}

        savedInstanceState?.getLong(STOP_POSITION)?.apply {
            pAdapter.setSavedStopPosition(this)
            videoStopPosition = this
        }

        previousOrientationSetting = requireActivity().requestedOrientation
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

                        if (autoRotate) requireActivity().requestedOrientation =
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

        currentPhotoModel.getRemoveItem().observe(viewLifecycleOwner, { deleteItem->
            deleteItem?.run {
                pAdapter.getNextAvailablePhoto(deleteItem).apply {
                    this.first?.let { photo->
                        currentPhotoModel.setCurrentPhoto(photo, this.second)
                        // TODO publish status is not persistent locally
                        //actionModel.deletePhotos(listOf(deleteItem), album.name, publishModel.isShared(album.id))
                        actionModel.deletePhotos(listOf(deleteItem), album.name)
                        slider.beginFakeDrag()
                        slider.fakeDragBy(-1f)
                        slider.endFakeDrag()
                    }
                    ?: run {
                        // TODO this seems never happen since user can't delete cover, so there is at least 1 photo in an album
                        parentFragmentManager.popBackStack()
                    }
                }
            }
        })
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        // Setup basic UI here because BottomControlsFragment might be replaced by CoverSettingFragment
        (requireActivity() as AppCompatActivity).supportActionBar!!.hide()
        requireActivity().window.run {
            previousNavBarColor = navigationBarColor
            navigationBarColor = Color.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insetsController?.let {
                    it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    it.hide(WindowInsets.Type.systemBars())
                }
                statusBarColor = Color.TRANSPARENT
                setDecorFitsSystemWindows(false)
            } else {
                @Suppress("DEPRECATION")
                decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE
                        // Set the content to appear under the system bars so that the
                        // content doesn't resize when the system bars hide and show.
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        // Hide the nav bar and status bar
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        pAdapter.initializePlayer()
    }

    override fun onResume() {
        super.onResume()
        requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        (slider.getChildAt(0) as RecyclerView).findViewHolderForAdapterPosition(slider.currentItem).apply {
            if (this is PhotoSlideAdapter.VideoViewHolder) this.resume()
        }
    }

    override fun onPause() {
        super.onPause()
        requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        (slider.getChildAt(0) as RecyclerView).findViewHolderForAdapterPosition(slider.currentItem).apply {
            if (this is PhotoSlideAdapter.VideoViewHolder) videoStopPosition = this.pause()
        }

    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(STOP_POSITION, videoStopPosition)
    }

    override fun onStop() {
        pAdapter.cleanUp()

        super.onStop()
    }

    override fun onDestroy() {
        requireActivity().window.navigationBarColor = previousNavBarColor

        (requireActivity() as AppCompatActivity).run {
            supportActionBar!!.show()
            requestedOrientation = previousOrientationSetting
        }

        requireContext().apply {
            unregisterReceiver(snapseedCatcher)
            contentResolver.unregisterContentObserver(snapseedOutputObserver)
        }

        currentPhotoModel.clearRemoveItem()

        super.onDestroy()
    }

    class PhotoSlideAdapter(private val ctx: Context, private val rootPath: String, private val itemListener: OnTouchListener, private val imageLoader: OnLoadImage
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var photos = emptyList<Photo>()

        private lateinit var exoPlayer: SimpleExoPlayer
        private var currentVolume = 0f
        private var oldVideoViewHolder: VideoViewHolder? = null
        private var savedStopPosition = FAKE_POSITION


        fun interface OnTouchListener { fun onTouch() }
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
            private lateinit var videoView: PlayerView
            private lateinit var thumbnailView: ImageView
            private lateinit var muteButton: ImageButton
            private lateinit var videoUri: Uri
            private var videoMimeType = ""
            private var stopPosition = 0L

            @SuppressLint("ClickableViewAccessibility")
            fun bindViewItems(video: Photo) {
                if (savedStopPosition != FAKE_POSITION) {
                    stopPosition = savedStopPosition
                    savedStopPosition = FAKE_POSITION
                }
                muteButton = itemView.findViewById(R.id.exo_mute)
                videoView = itemView.findViewById<PlayerView>(R.id.player_view).apply {
                    controllerShowTimeoutMs = 3000
                    setOnClickListener { itemListener.onTouch() }
                }

                var fileName = "$rootPath/${video.id}"
                if (!(File(fileName).exists())) fileName = "$rootPath/${video.name}"
                videoUri = Uri.fromFile(File(fileName))
                videoMimeType = video.mimeType

                itemView.findViewById<ConstraintLayout>(R.id.videoview_container).let {
                    // Fix view aspect ratio
                    if (video.height != 0) with(ConstraintSet()) {
                        clone(it)
                        setDimensionRatio(R.id.media, "${video.width}:${video.height}")
                        applyTo(it)
                    }

                    it.setOnClickListener { itemListener.onTouch() }
                }

                thumbnailView = itemView.findViewById<ImageView>(R.id.media).apply {
                    // Even thought we don't load animated image with ImageLoader, we still need to call it here so that postponed enter transition can be started
                    imageLoader.loadImage(video, this, ImageLoaderViewModel.TYPE_FULL)
                    ViewCompat.setTransitionName(this, video.id)
                }

                muteButton.setOnClickListener { toggleMute() }
            }

            // Need to call this so that exit transition can happen
            fun showThumbnail() { thumbnailView.visibility = View.INVISIBLE }

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
                                showThumbnail()
                            }
                        }
                    }
                    playWhenReady = true
                    setMediaItem(MediaItem.Builder().setUri(videoUri).setMimeType(videoMimeType).build(), stopPosition)
                    //setMediaItem(MediaItem.fromUri(videoUri), stopPosition)
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

            fun pause(): Long {
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

                return stopPosition
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
                TYPE_VIDEO-> VideoViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.viewpager_item_exoplayer, parent, false))
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

        fun getNextAvailablePhoto(photo: Photo): Pair<Photo?, Int> {
            with(photos.indexOf(photo)) {
                return when(this) {
                    -1-> Pair(null, -1)
                    photos.size - 1-> if (this > 0) Pair(photos[this - 1], this - 1) else Pair(null, -1)
                    else-> Pair(photos[this + 1], this)
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
            if (holder is VideoViewHolder) holder.resume()
        }

        override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
            super.onViewDetachedFromWindow(holder)
            if (holder is VideoViewHolder) holder.pause()
        }

        fun setSavedStopPosition(position: Long) { savedStopPosition = position }

        fun initializePlayer() {
            //private var exoPlayer = SimpleExoPlayer.Builder(ctx, { _, _, _, _, _ -> arrayOf(MediaCodecVideoRenderer(ctx, MediaCodecSelector.DEFAULT)) }) { arrayOf(Mp4Extractor()) }.build()
            exoPlayer = SimpleExoPlayer.Builder(ctx).build()
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
                    if (isPlaying) oldVideoViewHolder?.run {
                        showThumbnail()
                        hideControllers()
                    }
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

            private const val FAKE_POSITION = -1L
        }
    }

    // Share current photo within this fragment and BottomControlsFragment and CropCoverFragment
    class CurrentPhotoViewModel : ViewModel() {
        // AlbumDetail fragment grid item positions, this is for AlbumDetailFragment, nothing to do with other fragments
        private var currentPosition = 0
        private var lastPosition = 0
        fun getCurrentPosition(): Int = currentPosition
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
        fun setCurrentPhoto(newPhoto: Photo, position: Int) {
            //photo.postValue(newPhoto)
            photo.value = newPhoto
            currentPosition = position
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
        private val removeItem = MutableLiveData<Photo?>()
        fun removePhoto() { removeItem.value = photo.value }
        fun getRemoveItem(): LiveData<Photo?> { return removeItem }
        fun clearRemoveItem() { removeItem.value = null }
    }

    // Share system ui visibility status with BottomControlsFragment
    class UIViewModel : ViewModel() {
        private val showUI = MutableLiveData(true)

        fun toggleOnOff() { showUI.value = !showUI.value!! }
        fun status(): LiveData<Boolean> { return showUI }
    }

    companion object {
        private const val STOP_POSITION = "STOP_POSITION"

        const val CHOOSER_SPY_ACTION = "site.leos.apps.lespas.CHOOSER_PHOTOSLIDER"

        const val KEY_ALBUM = "ALBUM"

        @JvmStatic
        fun newInstance(album: Album) = PhotoSlideFragment().apply { arguments = Bundle().apply { putParcelable(KEY_ALBUM, album) }}
    }
}