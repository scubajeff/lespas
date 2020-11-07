package site.leos.apps.lespas.photo

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.transition.Slide
import android.transition.TransitionManager
import android.view.*
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import site.leos.apps.lespas.MainActivity
import site.leos.apps.lespas.R

class BottomControlsFragment : Fragment(), MainActivity.OnWindowFocusChangedListener {
    private lateinit var window: Window
    private lateinit var controls: LinearLayout
    private lateinit var setCoverButton: ImageButton
    private lateinit var shareButton: ImageButton
    private lateinit var infoButton: ImageButton
    private lateinit var currentPhoto: PhotoDisplayFragment.CurrentPhotoViewModel
    private lateinit var uiToggle: PhotoDisplayFragment.UIViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Listener for our UI controls to show/hide with System UI
        this.window = requireActivity().window
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            window.decorView.setOnSystemUiVisibilityChangeListener { visibility ->
                if (visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) {
                    TransitionManager.beginDelayedTransition(controls, Slide(Gravity.BOTTOM).apply { duration = 80 })
                    controls.visibility = View.VISIBLE
                    visible = true
                    hideHandler.postDelayed(hideSystemUI, AUTO_HIDE_DELAY_MILLIS)
                } else {
                    TransitionManager.beginDelayedTransition(controls, Slide(Gravity.BOTTOM).apply { duration = 80 })
                    controls.visibility = View.GONE
                    visible = false
                }
            }
        } else {
            window.decorView.setOnApplyWindowInsetsListener { v, insets ->
                TransitionManager.beginDelayedTransition(controls, Slide(Gravity.BOTTOM).apply { duration = 80 })
                if (insets.isVisible(WindowInsets.Type.statusBars())) {
                    controls.visibility = View.VISIBLE
                    hideHandler.postDelayed(hideSystemUI, AUTO_HIDE_DELAY_MILLIS)
                    visible = true
                } else {
                    controls.visibility = View.GONE
                    visible = false
                }
                insets
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        currentPhoto = ViewModelProvider(requireActivity()).get(PhotoDisplayFragment.CurrentPhotoViewModel::class.java)
        uiToggle = ViewModelProvider(requireActivity()).get(PhotoDisplayFragment.UIViewModel::class.java)
        uiToggle.status().observe(viewLifecycleOwner, { value-> toggle() })

        return inflater.inflate(R.layout.fragment_bottom_controls, container, false)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Controls
        controls = view.findViewById(R.id.controls)
        setCoverButton = view.findViewById(R.id.cover_button)
        shareButton = view.findViewById(R.id.share_button)
        infoButton = view.findViewById(R.id.info_button)

        // Upon interacting with UI controls, delay any scheduled hide() operations to prevent the jarring behavior of controls going away while interacting with the UI.
        setCoverButton.run {
            setOnTouchListener(delayHideTouchListener)
            setOnClickListener {
                TransitionManager.beginDelayedTransition(controls, Slide(Gravity.BOTTOM).apply { duration = 80 })
                controls.visibility = View.GONE
                hideHandler.post(hideSystemUI)
                parentFragmentManager.beginTransaction().setReorderingAllowed(true).replace(R.id.container_bottom_toolbar, CropCoverFragment()).addToBackStack(CropCoverFragment.javaClass.name).commit()
            }
        }
        shareButton.run {
            setOnTouchListener(delayHideTouchListener)
            setOnClickListener {
                startActivity(Intent.createChooser(Intent().setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, currentPhoto.getCurrentPhoto().value!!.name), null))
            }
        }
        infoButton.apply {
            setOnTouchListener(delayHideTouchListener)
            setOnClickListener {
                hideHandler.post(hideSystemUI)
                InfoDialogFragment(currentPhoto.getCurrentPhoto().value!!.name).show(parentFragmentManager, "")
            }
        }

        // Breifly show SystemUI at start
        //hideHandler.postDelayed(hideSystemUI, AUTO_HIDE_DELAY_MILLIS)
        //hideHandler.post(showSystemUI)
    }

    // TODO: what is the usage scenario of this
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        if (hasFocus) hideHandler.postDelayed(hideSystemUI, AUTO_HIDE_DELAY_MILLIS)
        else hideHandler.removeCallbacks(hideSystemUI)
    }

    override fun onDestroy() {
        super.onDestroy()

        // Clean up
        hideHandler.removeCallbacksAndMessages(null)
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
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE
                    // Set the content to appear under the system bars so that the
                    // content doesn't resize when the system bars hide and show.
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    // Hide the nav bar and status bar
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN)

        } else {
            window.insetsController?.run {
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

        // auto hide
        hideHandler.postDelayed(hideSystemUI, AUTO_HIDE_DELAY_MILLIS)
    }

    // Delay hiding the system UI while interacting with controls, preventing the jarring behavior of controls going away
    @SuppressLint("ClickableViewAccessibility")
    private val delayHideTouchListener = View.OnTouchListener { _, _ ->
        hideHandler.removeCallbacks(hideSystemUI)
        hideHandler.postDelayed(hideSystemUI, AUTO_HIDE_DELAY_MILLIS)
        false
    }

    class InfoDialogFragment(private val message: String) : DialogFragment() {
        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            return AlertDialog.Builder(requireActivity())
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, i_ -> }
                .create()
        }
    }

    companion object {
        private const val AUTO_HIDE_DELAY_MILLIS = 3000L // The number of milliseconds to wait after user interaction before hiding the system UI.

    }
}