package site.leos.apps.lespas.photo

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.transition.Slide
import android.transition.TransitionManager
import android.util.Log
import android.view.*
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.github.chrisbanes.photoview.PhotoView
import site.leos.apps.lespas.MainActivity
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.CoverViewModel

class PhotoDisplayFragment : Fragment(), MainActivity.OnWindowFocusChangedListener {
    private lateinit var photo: Photo
    private lateinit var window: Window
    private lateinit var setCoverButton: ImageButton
    private lateinit var shareButton: ImageButton
    private lateinit var infoButton: ImageButton
    private lateinit var media: PhotoView
    private lateinit var controls: LinearLayout
    private lateinit var coverModel: CoverViewModel
    private var isSettingCover = false

    companion object {
        private const val AUTO_HIDE_DELAY_MILLIS = 3000L // The number of milliseconds to wait after user interaction before hiding the system UI.

        private const val PHOTO = "PHOTO"
        private const val CONTROL_VISIBILITY_STATE = "CONTROL_VISIBILITY_STATE"

        @JvmStatic
        fun newInstance(photo: Photo) = PhotoDisplayFragment().apply { arguments = Bundle().apply { putParcelable(PhotoDisplayFragment.PHOTO, photo) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        photo = arguments?.getParcelable(PHOTO)!!
        this.window = requireActivity().window

        // Listener for our UI controls to show/hide with System UI
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            window.decorView.setOnSystemUiVisibilityChangeListener { visibility ->
                if (visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) {
                    if (!isSettingCover) {
                        TransitionManager.beginDelayedTransition(controls, Slide(Gravity.BOTTOM).apply { duration = 80 })
                        controls.visibility = View.VISIBLE
                        visible = true
                    }
                    hideHandler.postDelayed(hideSystemUI, AUTO_HIDE_DELAY_MILLIS)
                } else {
                    if (!isSettingCover) {
                        TransitionManager.beginDelayedTransition(controls, Slide(Gravity.BOTTOM).apply { duration = 80 })
                        controls.visibility = View.INVISIBLE
                        visible = false
                    }
                }
        }
        } else {
            window.decorView.setOnApplyWindowInsetsListener { v, insets ->
                TransitionManager.beginDelayedTransition(controls, Slide(Gravity.BOTTOM).apply { duration = 80 })
                if (insets.isVisible(WindowInsets.Type.statusBars())) {
                    controls.visibility = View.VISIBLE
                    hideHandler.postDelayed(hideSystemUI, AUTO_HIDE_DELAY_MILLIS)
                } else controls.visibility = View.GONE
                insets
            }
        }

        //sharedElementEnterTransition = ChangeBounds()

        if (savedInstanceState != null) visible = savedInstanceState.getInt(CONTROL_VISIBILITY_STATE) == 1
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        coverModel = ViewModelProvider(requireActivity()).get(CoverViewModel::class.java)
        coverModel.isSettingCover().observe(viewLifecycleOwner, { status->
            isSettingCover = status
            // If coming back from CropCover fragment, show controls UI
            if (!isSettingCover) hideHandler.post(showSystemUI)
        })

        return inflater.inflate(R.layout.fragment_photodisplay, container, false)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (requireActivity() as AppCompatActivity).supportActionBar?.hide()

        media = view.findViewById<PhotoView>(R.id.media).apply {
            setImageResource(R.drawable.ic_baseline_broken_image_24)
            setOnPhotoTapListener { view, x, y -> toggle() }
            setOnOutsidePhotoTapListener { toggle() }
            //setOnMatrixChangeListener { Log.e("====", "Matrix changed") }
            //setOnScaleChangeListener { scaleFactor, focusX, focusY -> Log.e("===", "scale changed $scaleFactor") }
            setOnSingleFlingListener { e1, e2, velocityX, velocityY ->
                Log.e("====", "single fling")
                true
            }
            maximumScale = 5.0f
            mediumScale = 2.5f
        }

        // Controls
        controls = view.findViewById(R.id.controls)
        setCoverButton = view.findViewById(R.id.set_cover_button)
        shareButton = view.findViewById(R.id.share_button)
        infoButton = view.findViewById(R.id.info_button)

        // Upon interacting with UI controls, delay any scheduled hide() operations to prevent the jarring behavior of controls going away while interacting with the UI.
        setCoverButton.run {
            setOnTouchListener(delayHideTouchListener)
            setOnClickListener {
                TransitionManager.beginDelayedTransition(controls, Slide(Gravity.BOTTOM).apply { duration = 80 })
                controls.visibility = View.GONE
                hideHandler.post(hideSystemUI)
                parentFragmentManager.beginTransaction().setReorderingAllowed(true).add(R.id.container_root, CropCoverFragment.newInstance(photo)).addToBackStack(null).commit()
            }
        }
        shareButton.run {
            setOnTouchListener(delayHideTouchListener)
            setOnClickListener {
                startActivity(Intent.createChooser(Intent().setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, photo.name), null))
            }
        }
        infoButton.apply {
            setOnTouchListener(delayHideTouchListener)
            setOnClickListener {
                hideHandler.post(hideSystemUI)
                InfoDialogFragment().show(parentFragmentManager, "")
            }
        }

        // Breifly show SystemUI at start
        hideHandler.postDelayed(hideSystemUI, AUTO_HIDE_DELAY_MILLIS)


    }

    override fun onResume() {
        super.onResume()

        if (!visible) controls.visibility = View.GONE
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        if (hasFocus) hideHandler.postDelayed(hideSystemUI, AUTO_HIDE_DELAY_MILLIS)
        else hideHandler.removeCallbacks(hideSystemUI)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(CONTROL_VISIBILITY_STATE, if (visible) 1 else 0)
    }

    override fun onPause() {
        super.onPause()

        window.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
    }

    override fun onDestroy() {
        // Clean up
        hideHandler.removeCallbacksAndMessages(null)

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

    // Hide/Show controls, status bar, navigation bar
    private var visible: Boolean = true
    private val hideHandler = Handler(Looper.getMainLooper())

    private fun toggle() {
        hideHandler.removeCallbacks(if (visible) showSystemUI else hideSystemUI)
        hideHandler.post(if (visible) hideSystemUI else showSystemUI)
    }

    private val hideSystemUI = Runnable {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            requireActivity().window?.decorView?.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE
                    // Set the content to appear under the system bars so that the
                    // content doesn't resize when the system bars hide and show.
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    // Hide the nav bar and status bar
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN)

        } else {
            requireActivity().window?.insetsController?.run {
                hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_BARS_BY_TOUCH
            }
        }
        visible = false
    }

    private val showSystemUI = Runnable {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R)
        // Shows the system bars by removing all the flags except for the ones that make the content appear under the system bars.
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        else window.insetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
        visible = true
        hideHandler.postDelayed(hideSystemUI, AUTO_HIDE_DELAY_MILLIS)
    }

    // Delay hiding the system UI while interacting with controls, preventing the jarring behavior of controls going away
    @SuppressLint("ClickableViewAccessibility")
    private val delayHideTouchListener = View.OnTouchListener { _, _ ->
        hideHandler.removeCallbacks(hideSystemUI)
        hideHandler.postDelayed(hideSystemUI, AUTO_HIDE_DELAY_MILLIS)
        false
    }

    class InfoDialogFragment : DialogFragment() {
        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            return AlertDialog.Builder(requireActivity())
                .setPositiveButton(android.R.string.ok) { _, i_ -> }
                .create()
        }
    }
}

/*
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) media?.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        else media?.windowInsetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())

    // Gesture handling
    override fun onDown(e: MotionEvent?): Boolean = true
    override fun onShowPress(e: MotionEvent?) {}
    override fun onSingleTapUp(e: MotionEvent?): Boolean {
        toggle()
        return false
    }
    override fun onScroll(e1: MotionEvent?, e2: MotionEvent?, distanceX: Float, distanceY: Float): Boolean = false
    override fun onLongPress(e: MotionEvent?) {}
    override fun onFling(e1: MotionEvent?, e2: MotionEvent?, velocityX: Float, velocityY: Float): Boolean = false

    @SuppressLint("ClickableViewAccessibility")
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