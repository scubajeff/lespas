package site.leos.apps.lespas.photo

import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.view.*
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.github.chrisbanes.photoview.PhotoView
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.Album

class PhotoSlideFragment : Fragment() {
    private lateinit var album: Album
    private var startAt: Int = 0
    private lateinit var slider: ViewPager2
    private lateinit var pAdapter: PhotoSlideAdapter
    private lateinit var albumModel: PhotoViewModel     // TODO naming
    private lateinit var currentPhotoModel: CurrentPhotoViewModel
    private lateinit var uiModel: UIViewModel

    companion object {
        private const val ALBUM = "ALBUM"
        private const val POSITION = "POSITION"

        @JvmStatic
        fun newInstance(album: Album, position: Int) = PhotoSlideFragment().apply {
            arguments = Bundle().apply {
                putParcelable(ALBUM, album)
                putInt(POSITION, position)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        album = arguments?.getParcelable(ALBUM)!!
        startAt = savedInstanceState?.getInt(POSITION) ?: arguments?.getInt(POSITION)!!

        //sharedElementEnterTransition = ChangeBounds()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_photoslide, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        uiModel = ViewModelProvider(requireActivity()).get(UIViewModel::class.java)
        currentPhotoModel = ViewModelProvider(requireActivity()).get(CurrentPhotoViewModel::class.java).apply { setCurrentPhoto(null, startAt) }
        pAdapter = PhotoSlideAdapter(object : PhotoSlideAdapter.Listener {
            override fun onTouch(photo: Photo, position: Int) {
                uiModel.toggleOnOff()
                currentPhotoModel.setCurrentPhoto(photo, position)
            }
        })

        slider = view.findViewById<ViewPager2>(R.id.pager).apply {
            adapter = pAdapter
        }

        albumModel = ViewModelProvider(this, PhotoListFragment.ExtraParamsViewModelFactory(this.requireActivity().application, album.id)).get(PhotoViewModel::class.java)
        albumModel.allPhotoInAlbum.observe(viewLifecycleOwner, { photos->
            pAdapter.setPhotos(photos)
            slider.setCurrentItem(currentPhotoModel.getPosition(), false)
            currentPhotoModel.setCurrentPhoto(photos[currentPhotoModel.getPosition()])
        })

        // TODO: should be started when view loaded
        // Briefly show controls
        uiModel.hideUI()
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        (requireActivity() as AppCompatActivity).supportActionBar?.hide()
    }

    override fun onResume() {
        super.onResume()
        requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(POSITION, slider.currentItem)
    }

    override fun onPause() {
        super.onPause()

        requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
    }

    override fun onDestroy() {
        // BACK TO NORMAL UI
        (requireActivity() as AppCompatActivity).run {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
                window.decorView.setOnSystemUiVisibilityChangeListener(null)
            } else {
                window.insetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                window.decorView.setOnApplyWindowInsetsListener(null)
            }
            supportActionBar?.show()
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        }

        super.onDestroy()
    }

    class PhotoSlideAdapter(private val itemListener: Listener) : RecyclerView.Adapter<PhotoSlideAdapter.PagerViewHolder>() {
        private var photos = emptyList<Photo>()

        interface Listener {
            fun onTouch(photo: Photo, position: Int)
        }

        inner class PagerViewHolder(private val itemView: View) : RecyclerView.ViewHolder(itemView) {
            fun bindViewItems(photo: Photo, itemListener: Listener) {
                itemView.findViewById<PhotoView>(R.id.media).apply {
                    setImageResource(R.drawable.ic_footprint)
                    setOnPhotoTapListener { _, _, _ -> itemListener.onTouch(photo, adapterPosition) }
                    setOnOutsidePhotoTapListener { itemListener.onTouch(photo, adapterPosition) }
                    maximumScale = 5.0f
                    mediumScale = 2.5f
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

        fun setPhotos(collection: List<Photo>) {
            photos = collection
            notifyDataSetChanged()
        }
    }

    // Share current photo within this fragment and BottomControlsFragment and CropCoverFragement
    class CurrentPhotoViewModel : ViewModel() {
        private val photo = MutableLiveData<Photo>()
        private var position = 0

        fun getCurrentPhoto(): LiveData<Photo> { return photo }
        fun setCurrentPhoto(newPhoto: Photo) { photo.value = newPhoto }
        fun setCurrentPhoto(newPhoto: Photo?, position: Int) {
            photo.value = newPhoto
            this.position = position
        }
        fun getPosition(): Int = position
    }

    // Share system ui visibility status with BottomControlsFragment
    class UIViewModel : ViewModel() {
        private val showUI = MutableLiveData<Boolean>()

        fun hideUI() { showUI.value = false }
        fun toggleOnOff() { showUI.value = !showUI.value!! }
        fun status(): LiveData<Boolean> { return showUI }
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