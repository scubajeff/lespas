package site.leos.apps.lespas.photo

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.ImageButton
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import site.leos.apps.lespas.MainActivity
import site.leos.apps.lespas.R

class PhotoDisplayFragment : Fragment(), MainActivity.OnWindowFocusChangedListener, GestureDetector.OnGestureListener {
    private lateinit var photo: Photo
    private lateinit var deleteButton: ImageButton
    private lateinit var shareButton: ImageButton
    private lateinit var infoButton: ImageButton
    private lateinit var media: ImageView
    private lateinit var controls: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        photo = arguments?.getParcelable<Photo>(PHOTO)!!

        activity?.window?.decorView?.run {
        }
        //sharedElementEnterTransition = ChangeBounds()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_photodisplay, container, false)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Briefly show controls
        visible = true

        media = view.findViewById<ImageView>(R.id.media).apply {
            setImageResource(R.drawable.ic_footprint)
            setOnClickListener { toggle() }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                setOnSystemUiVisibilityChangeListener {visibility ->
                    controls.visibility = if (visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) View.VISIBLE else View.GONE
                }
            }
            else {
                setOnApplyWindowInsetsListener { v, insets ->
                    controls.visibility = if (insets.isVisible(WindowInsets.Type.statusBars())) View.VISIBLE else View.GONE
                    insets
                }
            }
        }
        controls = view.findViewById(R.id.controls)
        deleteButton = view.findViewById(R.id.delete_button)
        shareButton = view.findViewById(R.id.share_button)
        infoButton = view.findViewById(R.id.info_button)

        // Upon interacting with UI controls, delay any scheduled hide() operations to prevent the jarring behavior of controls going away while interacting with the UI.
        deleteButton.setOnTouchListener(delayHideTouchListener)
        shareButton.setOnTouchListener(delayHideTouchListener)
        infoButton.setOnTouchListener(delayHideTouchListener)

        hideHandler.postDelayed(hideSystemUI, AUTO_HIDE_DELAY_MILLIS)
    }

    override fun onResume() {
        super.onResume()
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        (activity as? AppCompatActivity)?.supportActionBar?.hide()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        if (hasFocus) hideHandler.postDelayed(hideSystemUI, AUTO_HIDE_DELAY_MILLIS)
        else hideHandler.removeCallbacks(hideSystemUI)
    }

    override fun onPause() {
        super.onPause()
        activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
    }

    override fun onDestroy() {
        (activity as? AppCompatActivity)?.supportActionBar?.show()

        super.onDestroy()
    }

    // Hide/Show controls, status bar, navigation bar
    private var visible: Boolean = false
    private val hideHandler = Handler(Looper.getMainLooper())

    private fun toggle() {
        hideHandler.removeCallbacks(if (visible) showSystemUI else hideSystemUI)
        hideHandler.post(if (visible) hideSystemUI else showSystemUI)
    }

    private val hideSystemUI = Runnable {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            activity?.window?.decorView?.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    // Set the content to appear under the system bars so that the
                    // content doesn't resize when the system bars hide and show.
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    // Hide the nav bar and status bar
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN)

        }
        else {
            activity?.window?.insetsController?.run {
                hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_BARS_BY_TOUCH
            }
        }
        visible = false
    }

    private val showSystemUI = Runnable {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R)
            // Shows the system bars by removing all the flags except for the ones that make the content appear under the system bars.
            activity?.window?.decorView?.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        else activity?.window?.insetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
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

    override fun onDown(e: MotionEvent?): Boolean = true

    override fun onShowPress(e: MotionEvent?) {}

    override fun onSingleTapUp(e: MotionEvent?): Boolean {
        toggle()
        return true
    }

    override fun onScroll(e1: MotionEvent?, e2: MotionEvent?, distanceX: Float, distanceY: Float): Boolean = false

    override fun onLongPress(e: MotionEvent?) {}

    override fun onFling(e1: MotionEvent?, e2: MotionEvent?, velocityX: Float, velocityY: Float): Boolean {
        TODO("Not yet implemented")
    }

    companion object {
        private const val AUTO_HIDE_DELAY_MILLIS = 3000L // The number of milliseconds to wait after user interaction before hiding the system UI.

        private const val PHOTO = "PHOTO"
        @JvmStatic
        fun newInstance(photo: Photo) = PhotoDisplayFragment().apply { arguments = Bundle().apply { putParcelable(PhotoDisplayFragment.PHOTO, photo) }}
    }
}

/*
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) media?.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        else media?.windowInsetsController?.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())

 */