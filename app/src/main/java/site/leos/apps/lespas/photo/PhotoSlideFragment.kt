package site.leos.apps.lespas.photo

import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.app.SharedElementCallback
import androidx.core.view.ViewCompat
import androidx.core.view.get
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.github.chrisbanes.photoview.PhotoView
import com.google.android.material.transition.MaterialContainerTransform
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.AlbumViewModel
import site.leos.apps.lespas.helper.ImageLoaderViewModel

class PhotoSlideFragment : Fragment() {
    private lateinit var albumId: String
    private lateinit var slider: ViewPager2
    private lateinit var pAdapter: PhotoSlideAdapter
    private val albumModel: AlbumViewModel by activityViewModels()
    private val imageLoaderModel: ImageLoaderViewModel by activityViewModels()
    private val currentPhotoModel: CurrentPhotoViewModel by activityViewModels()
    private val uiModel: UIViewModel by activityViewModels()
    private var autoRotate = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        albumId = arguments?.getString(ALBUM_ID)!!

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
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_photoslide, container, false)

        postponeEnterTransition()

        pAdapter = PhotoSlideAdapter(
            { uiModel.toggleOnOff() }
        ) { photo, imageView, type ->
            imageLoaderModel.loadPhoto(photo, imageView, type) { startPostponedEnterTransition() }}

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
                    currentPhotoModel.setCurrentPhoto(pAdapter.getPhotoAt(position), position + 1)

                    val photo = pAdapter.getPhotoAt(position)
                    if (autoRotate) activity?.requestedOrientation = if (photo.width > photo.height) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
            })
        }

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        albumModel.getAllPhotoInAlbum(albumId).observe(viewLifecycleOwner, { photos->
            pAdapter.setPhotos(photos)
            slider.setCurrentItem(currentPhotoModel.getCurrentPosition() - 1, false)
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
    }

    class PhotoSlideAdapter(private val itemListener: OnTouchListener, private val imageLoader: OnLoadImage) : RecyclerView.Adapter<PhotoSlideAdapter.PagerViewHolder>() {
        private var photos = emptyList<Photo>()

        fun interface OnTouchListener {
            fun onTouch()
        }

        fun interface OnLoadImage {
            fun loadImage(photo: Photo, view: ImageView, type: String)
        }

        inner class PagerViewHolder(private val itemView: View) : RecyclerView.ViewHolder(itemView) {
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

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoSlideAdapter.PagerViewHolder {
            return PagerViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.viewpager_item_photo, parent, false))
        }

        override fun onBindViewHolder(holder: PhotoSlideAdapter.PagerViewHolder, position: Int) {
            holder.bindViewItems(photos[position], itemListener)
        }

        override fun getItemCount(): Int {
            return photos.size
        }

        internal fun getPhotoAt(position: Int): Photo {
            return photos[position]
        }

        fun setPhotos(collection: List<Photo>) {
            photos = collection
            notifyDataSetChanged()
        }
    }

    // Share current photo within this fragment and BottomControlsFragment and CropCoverFragment
    class CurrentPhotoViewModel : ViewModel() {
        // AlbumDetail fragment grid item positions
        private var currentPosition = 0
        private var firstPosition = 0
        private var lastPosition = 1
        fun setCurrentPosition(position: Int) { currentPosition = position }
        fun getCurrentPosition(): Int = currentPosition
        fun setFirstPosition(position: Int) { firstPosition = position }
        fun getFirstPosition(): Int = firstPosition
        fun setLastPosition(position: Int) { lastPosition = position }
        fun getLastPosition(): Int = lastPosition

        // Current photo shared with CoverSetting and BottomControl fragments
        private val photo = MutableLiveData<Photo>()
        private val coverApplyStatus = MutableLiveData<Boolean>()
        private var forReal = false     // TODO Dirty hack, should be SingleLiveEvent

        fun getCurrentPhoto(): LiveData<Photo> { return photo }
        fun setCurrentPhoto(newPhoto: Photo, position: Int) {
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
    }

    // Share system ui visibility status with BottomControlsFragment
    class UIViewModel : ViewModel() {
        private val showUI = MutableLiveData<Boolean>(true)

        fun hideUI() { showUI.value = false }
        fun toggleOnOff() { showUI.value = !showUI.value!! }
        fun status(): LiveData<Boolean> { return showUI }
    }

    companion object {
        private const val ALBUM_ID = "ALBUM_ID"

        fun newInstance(albumId: String) = PhotoSlideFragment().apply { arguments = Bundle().apply { putString(ALBUM_ID, albumId) }}
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