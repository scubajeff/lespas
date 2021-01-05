package site.leos.apps.lespas.photo

import android.annotation.SuppressLint
import android.content.*
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.*
import android.widget.ImageView
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.app.SharedElementCallback
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
import com.github.chrisbanes.photoview.PhotoView
import com.google.android.material.transition.MaterialContainerTransform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.Album
import site.leos.apps.lespas.album.AlbumViewModel
import site.leos.apps.lespas.helper.ImageLoaderViewModel
import site.leos.apps.lespas.helper.ShareChooserBroadcastReceiver
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.sync.Action
import site.leos.apps.lespas.sync.ActionViewModel
import java.io.File
import java.lang.Thread.sleep
import java.util.*

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
    private lateinit var sp: SharedPreferences
    //private var originalItem: Photo? = null
    private val snapseedCatcher = ShareChooserBroadcastReceiver()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        album = arguments?.getParcelable<Album>(ALBUM)!!

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

        sp = PreferenceManager.getDefaultSharedPreferences(requireContext())

        context?.registerReceiver(snapseedCatcher, IntentFilter(CHOOSER_SPY_ACTION))
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_photoslide, container, false)

        postponeEnterTransition()

        pAdapter = PhotoSlideAdapter(
            "${requireContext().filesDir}${resources.getString(R.string.lespas_base_folder_name)}",
            { uiModel.toggleOnOff() }
        ) { photo, imageView, type ->
            if (Tools.isMediaPlayable(photo.mimeType)) startPostponedEnterTransition()
            else imageLoaderModel.loadPhoto(photo, imageView as ImageView, type) { startPostponedEnterTransition() }}

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
            /*
            // TODO stupid hack to test if new photo added by snapseed, since observer get called twice, must be sth. to do with miss fired
            val c1 = pAdapter.itemCount
            pAdapter.setPhotos(photos, arguments?.getString(SORT_ORDER)!!.toInt())
            val c2 = pAdapter.itemCount
            if (originalItem != null && c1 != c2) {
                // Scroll to original after new snapseed output added
                val oldPosition = currentPhotoModel.getCurrentPosition()
                val newPosition = pAdapter.findPhotoPosition(originalItem!!) + 1
                if (newPosition != oldPosition) {
                    currentPhotoModel.setCurrentPosition(newPosition)
                    currentPhotoModel.setFirstPosition(currentPhotoModel.getFirstPosition() + newPosition - oldPosition)
                    currentPhotoModel.setLastPosition(currentPhotoModel.getLastPosition() + newPosition - oldPosition)
                }
                originalItem = null
            }
            slider.setCurrentItem(currentPhotoModel.getCurrentPosition() - 1, false)
             */
            pAdapter.setPhotos(photos, arguments?.getString(SORT_ORDER)!!.toInt())
            slider.setCurrentItem(pAdapter.findPhotoPosition(currentPhotoModel.getCurrentPhoto().value!!), false)
        })

        currentPhotoModel.getRemoveItem().observe(viewLifecycleOwner, {
            it?.run {
                CoroutineScope(Dispatchers.Default).launch(Dispatchers.IO) {
                    val nextPhoto = pAdapter.getNextAvailablePhoto(it)
                    nextPhoto?.run {
                        withContext(Dispatchers.Main) { currentPhotoModel.setCurrentPhoto(nextPhoto, null) }
                        albumModel.removePhoto(it)
                        actionModel.addAction(Action(null, Action.ACTION_DELETE_FILES_ON_SERVER, album.id, album.name, it.id, it.name, System.currentTimeMillis(), 1))
                    }
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
        if (sp.getBoolean(getString(R.string.snapseed_pref_key), false) && snapseedCatcher.getDest() == "snapseed") checkSnapseed()
    }

    override fun onPause() {
        super.onPause()
        requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
    }

    override fun onDestroy() {
        super.onDestroy()
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        requireActivity().window.navigationBarColor = previousNavBarColor

        context?.unregisterReceiver(snapseedCatcher)
        currentPhotoModel.clearRemoveItem()
    }

    private fun checkSnapseed() {
        CoroutineScope(Dispatchers.Default).launch(Dispatchers.IO) {
            val photo = pAdapter.getPhotoAt(slider.currentItem)
            val snapseedFile = File("${Environment.getExternalStorageDirectory().absolutePath}/Snapseed/${photo.name.substringBeforeLast('.')}-01.jpeg")
            val appRootFolder = "${requireActivity().filesDir}${getString(R.string.lespas_base_folder_name)}"

            // Clear flag
            snapseedCatcher.clearFlag()

            // Wait at most 500ms for Snapseed output file
            val t = System.currentTimeMillis()
            while(!snapseedFile.exists()) {
                sleep(100)
                if (System.currentTimeMillis() - t > 500) break
            }

            if (snapseedFile.exists()) {
                //Log.e(">>>>>>", "file ${snapseedFile.absolutePath} exist")

                /*
                if (sp.getBoolean(getString(R.string.snapseed_replace_pref_key), false)) {
                    // Replace the original

                    val lastModified = Tools.dateToLocalDateTime(Date(snapseedFile.lastModified()))
                    // Compare file size to to make sure it's a new edition
                    if (snapseedFile.length() != File(appRootFolder, photo.id).length()) {
                        //Log.e(">>>>>>>", "file ${snapseedFile.absolutePath} is a different edition")

                        val actions = mutableListOf<Action>()
                        // Snapseed use JPEG format for output
                        var newName = photo.name
                        if (photo.mimeType != JPEG) {
                            newName = photo.name.substringBeforeLast('.') + ".jpeg"
                            //Log.e(">>>>>>", "old file ${photo.name} will be deleted, new file $newName will be created on both side")
                        }

                        try {
                            snapseedFile.inputStream().use { input ->
                                File(appRootFolder, newName).outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                            return@launch
                        }

                        //Log.e(">>>>", "${snapseedFile.absolutePath} replaced $appRootFolder/$newName")

                        // Invalid image cache
                        imageLoaderModel.invalid(photo)

                        // Get image width and height, in case Snapseed crop it
                        val options = BitmapFactory.Options().apply {
                            inJustDecodeBounds = true
                            BitmapFactory.decodeFile("$appRootFolder/$newName", this)
                        }

                        // Replace photo id with photo name, and empty eTag, make it like it's a newly acquired photo and follow that process to sync with server
                        albumModel.updatePhoto(photo.id, newName, lastModified, options.outWidth, options.outHeight, JPEG)
                        // Fix album cover Id if required
                        // TODO cover baseline, width, height might need to change if user crop the photo in Snapseed
                        if (album.cover == photo.id) albumModel.fixCoverId(album.id, newName)

                        // Upload changes to server, mimetype passed in fileId property
                        actions.add(Action(null, Action.ACTION_ADD_FILES_ON_SERVER, album.id, album.name, JPEG, newName, System.currentTimeMillis(), 1))
                        // If the original photo is not JPEG, we need to delete the original on server side. e.g. new edition will be a new file rather than update
                        if (photo.mimeType != JPEG)
                            actions.add(Action(null, Action.ACTION_DELETE_FILES_ON_SERVER, album.id, album.name, photo.id, photo.name, System.currentTimeMillis(), 1))
                        actionModel.addActions(actions)

                        // Fix currentPhotoModel data, since viewpager2 won't scroll when setting current item to the same item as before
                        currentPhotoModel.setCurrentPhoto(
                            photo.copy(id = newName, width = options.outWidth, height = options.outHeight, lastModified = lastModified, mimeType = JPEG), null)

                        // New file with new fileId will be sync from server, remove old file on local
                        if (photo.mimeType != JPEG)
                            try {
                                File(appRootFolder, photo.id).delete()
                            } catch (e: Exception) { e.printStackTrace() }
                    }
                */
                if (sp.getBoolean(getString(R.string.snapseed_replace_pref_key), false)) {
                    // Replace the original

                    // Compare file size, make sure it's a different edition
                    if (snapseedFile.length() != File(appRootFolder, photo.id).length()) {
                        try {
                            snapseedFile.inputStream().use { input->
                                // Name new photo filename after Snapseed's output name
                                File(appRootFolder, snapseedFile.name).outputStream().use { output->
                                    input.copyTo(output)
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                            // Quit when exception happens during file copy
                            return@launch
                        }

                        // Add newPhoto, delete old photo locally
                        val newPhoto = with(snapseedFile.name) { Tools.getPhotoParams("$appRootFolder/$this", JPEG, this).copy(id = this, albumId = album.id, name = this) }
                        //originalItem = newPhoto

                        albumModel.replacePhoto(photo, newPhoto)
                        // Fix currentPhotoModel data, since viewpager2 won't scroll when setting current item to the same item as before
                        withContext(Dispatchers.Main) {currentPhotoModel.setCurrentPhoto(newPhoto, null)}
                        // Fix album cover Id if required
                        if (album.cover == photo.id)
                            albumModel.replaceCover(album.id, newPhoto.id, newPhoto.width, newPhoto.height, (album.coverBaseline.toFloat() * newPhoto.height / album.coverHeight).toInt())
                        // Clear image cache for old photo
                        imageLoaderModel.invalid(photo)
                        // Delete old image file, TODO: the file might be using by some other process, like uploading to server
                        try {
                            File(appRootFolder, photo.id).delete()
                        } catch (e: Exception) { e.printStackTrace() }


                        // Add newPhoto, delete old photo remotely
                        with(mutableListOf<Action>()) {
                            // Pass photo mimeType in Action's folderId property
                            add(Action(null, Action.ACTION_ADD_FILES_ON_SERVER, newPhoto.mimeType, album.name, newPhoto.id, newPhoto.name, System.currentTimeMillis(), 1))
                            add(Action(null, Action.ACTION_DELETE_FILES_ON_SERVER, album.id, album.name, photo.id, photo.name, System.currentTimeMillis(), 1))
                            actionModel.addActions(this)
                        }
                    }
                } else {
                    // Copy Snapseed output

                    // Append timestamp suffix to make a unique filename
                    val fileName = "${snapseedFile.name.substringBeforeLast('.')}_${System.currentTimeMillis()}.${snapseedFile.name.substringAfterLast('.')}"

                    try {
                        snapseedFile.inputStream().use { input ->
                            File(appRootFolder, fileName).outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        return@launch
                    }

                    // Tell observer to relocate the original photo
                    //originalItem = photo

                    // Create new photo
                    albumModel.addPhoto(Tools.getPhotoParams("$appRootFolder/$fileName", JPEG, fileName).copy(id = fileName, albumId = album.id, name = fileName))

                    // Upload changes to server, mimetype passed in folderId property
                    actionModel.addAction(Action(null, Action.ACTION_ADD_FILES_ON_SERVER, JPEG, album.name, fileName, fileName, System.currentTimeMillis(), 1))
                }

                // Repeat editing of same source will generate multiple files with sequential suffix, remove Snapseed output to avoid tedious filename parsing
                try {
                    snapseedFile.delete()
                } catch (e: Exception) { e.printStackTrace() }
            }

            // Remove cache copy too
            try {
                File(requireContext().cacheDir, photo.name).delete()
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    class PhotoSlideAdapter(private val rootPath: String, private val itemListener: OnTouchListener, private val imageLoader: OnLoadImage,) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var photos = emptyList<Photo>()

        fun interface OnTouchListener { fun onTouch() }
        fun interface OnLoadImage { fun loadImage(photo: Photo, view: View, type: String) }

        inner class PhotoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            fun bindViewItems(photo: Photo, itemListener: OnTouchListener) {
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
            fun bindViewItems(photo: Photo, itemListener: OnTouchListener) {
                itemView.findViewById<ImageView>(R.id.media).apply {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        // Even thought we don't load animated image with ImageLoader, we still need to call it here so that postponed enter transition can be started
                        imageLoader.loadImage(photo, this, ImageLoaderViewModel.TYPE_FULL)

                        var fileName = "$rootPath/${photo.id}"
                        if (!(File(fileName).exists())) fileName = "$rootPath/${photo.name}"
                        setImageDrawable(ImageDecoder.decodeDrawable(ImageDecoder.createSource(File(fileName))).apply { if (this is AnimatedImageDrawable) this.start() })
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
            @SuppressLint("ClickableViewAccessibility")
            fun bindViewItems(photo: Photo, itemListener: OnTouchListener) {
                val root = itemView.findViewById<ConstraintLayout>(R.id.videoview_container)

                with(itemView.findViewById<VideoView>(R.id.media)) {
                    if (photo.height != 0) with(ConstraintSet()) {
                        clone(root)
                        setDimensionRatio(R.id.media, "${photo.width}:${photo.height}")
                        applyTo(root)
                    }
                    // Even thought we don't load animated image with ImageLoader, we still need to call it here so that postponed enter transition can be started
                    imageLoader.loadImage(photo, this, ImageLoaderViewModel.TYPE_FULL)

                    var fileName = "$rootPath/${photo.id}"
                    if (!(File(fileName).exists())) fileName = "$rootPath/${photo.name}"
                    setVideoPath(fileName)

                    setOnTouchListener { _, event ->
                        if (event.action == MotionEvent.ACTION_DOWN) {
                            itemListener.onTouch()
                            true
                        } else false
                    }

                    ViewCompat.setTransitionName(this, photo.id)
                }

                // If user touch outside VideoView
                itemView.findViewById<ConstraintLayout>(R.id.videoview_container).setOnTouchListener { _, event ->
                    if (event.action == MotionEvent.ACTION_DOWN) {
                        itemListener.onTouch()
                        true
                    } else false
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
                is VideoViewHolder-> holder.bindViewItems(photos[position], itemListener)
                is AnimatedViewHolder-> holder.bindViewItems(photos[position], itemListener)
                else-> (holder as PhotoViewHolder).bindViewItems(photos[position], itemListener)
            }
        }

        fun findPhotoPosition(photo: Photo): Int {
            photos.forEachIndexed { i, p ->
                // If photo synced back from server, the id property will be changed from filename to fileId
                if ((p.id == photo.id) || (p.name == photo.id)) return i
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

        fun setPhotos(collection: List<Photo>, sortOrder: Int) {
            photos = when(sortOrder) {
                Album.BY_DATE_TAKEN_ASC-> collection.sortedWith(compareBy { it.dateTaken })
                Album.BY_DATE_TAKEN_DESC-> collection.sortedWith(compareByDescending { it.dateTaken })
                Album.BY_DATE_MODIFIED_ASC-> collection.sortedWith(compareBy { it.lastModified })
                Album.BY_DATE_MODIFIED_DESC-> collection.sortedWith(compareByDescending { it.lastModified })
                Album.BY_NAME_ASC-> collection.sortedWith(compareBy { it.name })
                Album.BY_NAME_DESC-> collection.sortedWith(compareByDescending { it.name })
                else-> collection
            }
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
            if (holder is VideoViewHolder) holder.itemView.findViewById<VideoView>(R.id.media).start()
        }

        override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
            super.onViewDetachedFromWindow(holder)
            if (holder is VideoViewHolder) holder.itemView.findViewById<VideoView>(R.id.media).stopPlayback()
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
        private val showUI = MutableLiveData<Boolean>(true)

        fun hideUI() { showUI.value = false }
        fun toggleOnOff() { showUI.value = !showUI.value!! }
        fun status(): LiveData<Boolean> { return showUI }
    }

    companion object {
        private const val ALBUM = "ALBUM"
        private const val SORT_ORDER = "SORT_ORDER"
        const val JPEG = "image/jpeg"

        const val CHOOSER_SPY_ACTION = "site.leos.apps.lespas.CHOOSER_PHOTOSLIDER"

        fun newInstance(album: Album, sortOrder: Int) = PhotoSlideFragment().apply {
            arguments = Bundle().apply {
                putParcelable(ALBUM, album)
                putString(SORT_ORDER, sortOrder.toString())
        }}
    }
}

/*
    class MyPhotoImageView @JvmOverloads constructor(context: Context, attributeSet: AttributeSet? = null, defStyle: Int = 0
    ) : AppCompatImageView(context, attributeSet, defStyle) {
        init {
            super.setClickable(true)
            super.setOnTouchListener { v, event ->
                mScaleDetector.onTouchEvent(event)
                true
            }
        }
        private var mScaleFactor = 1f
        private val scaleListener = object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                mScaleFactor *= detector.scaleFactor
                mScaleFactor = Math.max(0.1f, Math.min(mScaleFactor, 5.0f))
                scaleX = mScaleFactor
                scaleY = mScaleFactor
                invalidate()
                return true
            }
        }
        private val mScaleDetector = ScaleGestureDetector(context, scaleListener)

        override fun onDraw(canvas: Canvas?) {
            super.onDraw(canvas)

            canvas?.apply {
                save()
                scale(mScaleFactor, mScaleFactor)
                restore()
            }
        }
    }
*/