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
import com.github.chrisbanes.photoview.PhotoView
import site.leos.apps.lespas.R

class PhotoDisplayFragment : Fragment() {
    private lateinit var photo: Photo
    private lateinit var media: PhotoView
    private lateinit var currentPhotoModel: CurrentPhotoViewModel
    private lateinit var uiModel: UIViewModel

    companion object {
        private const val PHOTO = "PHOTO"

        @JvmStatic
        fun newInstance(photo: Photo) = PhotoDisplayFragment().apply { arguments = Bundle().apply { putParcelable(PHOTO, photo) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        photo = arguments?.getParcelable(PHOTO)!!

        //sharedElementEnterTransition = ChangeBounds()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        currentPhotoModel = ViewModelProvider(requireActivity()).get(CurrentPhotoViewModel::class.java)
        currentPhotoModel.setCurrentPhoto(photo)
        uiModel = ViewModelProvider(requireActivity()).get(UIViewModel::class.java)

        return inflater.inflate(R.layout.fragment_photodisplay, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        media = view.findViewById<PhotoView>(R.id.media).apply {
            setImageResource(R.drawable.ic_footprint)
            setOnPhotoTapListener { _, _, _ -> uiModel.toggleOnOff() }
            setOnOutsidePhotoTapListener { uiModel.toggleOnOff() }
            maximumScale = 5.0f
            mediumScale = 2.5f
        }

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

    // Share current photo within this fragment and BottomControlsFragment and CropCoverFragement
    class CurrentPhotoViewModel : ViewModel() {
        private val photo = MutableLiveData<Photo>()

        fun getCurrentPhoto(): LiveData<Photo> { return photo }
        fun setCurrentPhoto(newCover: Photo) { photo.value = newCover }
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