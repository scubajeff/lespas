package site.leos.apps.lespas.photo

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.transition.Slide
import android.transition.TransitionManager
import android.view.*
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.google.android.material.snackbar.Snackbar
import kotlinx.android.synthetic.main.fragment_info_dialog.*
import site.leos.apps.lespas.MainActivity
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.DialogShapeDrawable
import java.io.File
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

class BottomControlsFragment : Fragment(), MainActivity.OnWindowFocusChangedListener {
    private lateinit var albumId: String
    private lateinit var window: Window
    private lateinit var controls: LinearLayout
    private lateinit var setCoverButton: ImageButton
    private lateinit var shareButton: ImageButton
    private lateinit var infoButton: ImageButton
    private val currentPhoto: PhotoSlideFragment.CurrentPhotoViewModel by activityViewModels()
    private val uiToggle: PhotoSlideFragment.UIViewModel by activityViewModels()
    private var ignore = true

    companion object {
        private const val AUTO_HIDE_DELAY_MILLIS = 3000L // The number of milliseconds to wait after user interaction before hiding the system UI.

        private const val ALBUM_ID = "ALBUM_ID"
        private const val INFO_DIALOG = "INFO_DIALOG"

        fun newInstance(albumId: String) = BottomControlsFragment().apply { arguments = Bundle().apply{ putString(ALBUM_ID, albumId) }}
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        albumId = arguments?.getString(ALBUM_ID)!!

        // Listener for our UI controls to show/hide with System UI
        this.window = requireActivity().window

        // TODO: Nasty exception handling here, but Android doesn't provide method to unregister System UI/Insets changes listener
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            window.decorView.setOnSystemUiVisibilityChangeListener { visibility ->
                try {
                    if (visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) {
                        TransitionManager.beginDelayedTransition(controls, Slide(Gravity.BOTTOM).apply { duration = 80 })
                        controls.visibility = View.VISIBLE
                        visible = true
                    } else {
                        TransitionManager.beginDelayedTransition(controls, Slide(Gravity.BOTTOM).apply { duration = 80 })
                        controls.visibility = View.GONE
                        visible = false
                    }
                } catch (e: UninitializedPropertyAccessException) {}
            }
        } else {
            window.decorView.setOnApplyWindowInsetsListener { _, insets ->
                try {
                    TransitionManager.beginDelayedTransition(controls, Slide(Gravity.BOTTOM).apply { duration = 80 })
                    if (insets.isVisible(WindowInsets.Type.statusBars())) {
                        controls.visibility = View.VISIBLE
                        visible = true
                    } else {
                        controls.visibility = View.GONE
                        visible = false
                    }
                } catch (e: UninitializedPropertyAccessException) {}

                insets
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_bottomcontrols, container, false)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        uiToggle.status().observe(viewLifecycleOwner, {
            if (ignore) {
                hideHandler.postDelayed(hideSystemUI, AUTO_HIDE_DELAY_MILLIS)
                ignore = false
            } else toggle()
        })

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
                parentFragmentManager.beginTransaction()
                    .setReorderingAllowed(true)
                    .replace(R.id.container_bottom_toolbar, CoverSettingFragment.newInstance(albumId))
                    .addToBackStack(CoverSettingFragment::class.simpleName)
                    .commit()
            }
        }
        shareButton.run {
            setOnTouchListener(delayHideTouchListener)
            setOnClickListener {
                with(currentPhoto.getCurrentPhoto().value!!) {
                    File("${requireActivity().filesDir}${getString(R.string.lespas_base_folder_name)}", id).copyTo(File(requireActivity().cacheDir, name), true, 4096)
                    startActivity(
                        Intent.createChooser(
                            Intent().apply {
                                action = Intent.ACTION_SEND
                                type = "image/*"
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(requireContext(), getString(R.string.file_authority), File(requireActivity().cacheDir, name)))
                            }, null
                        )
                    )
                }
            }
        }
        infoButton.apply {
            setOnTouchListener(delayHideTouchListener)
            setOnClickListener {
                hideHandler.post(hideSystemUI)
                if (parentFragmentManager.findFragmentByTag(INFO_DIALOG) == null) {
                    currentPhoto.getCurrentPhoto().value!!.run {
                        InfoDialogFragment.newInstance(id, name, dateTaken.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT, FormatStyle.SHORT)), width.toString(), height.toString()
                        ).show(parentFragmentManager, INFO_DIALOG)
                    }
                }
            }
        }

        // Breifly show SystemUI at start
        //hideHandler.postDelayed(hideSystemUI, AUTO_HIDE_DELAY_MILLIS)
        //hideHandler.post(showSystemUI)
        currentPhoto.getCoverAppliedStatus().observe(viewLifecycleOwner, { appliedStatus ->
            if (currentPhoto.forReal())
                Snackbar
                    .make(controls, getString(if (appliedStatus) R.string.toast_cover_applied else R.string.toast_cover_set_canceled), Snackbar.LENGTH_SHORT)
                    .setAnimationMode(Snackbar.ANIMATION_MODE_FADE)
                    .setAnchorView(controls)
                    .setBackgroundTint(resources.getColor(R.color.color_primary, null))
                    .setTextColor(resources.getColor(R.color.color_text_light))
                    .show()
        })
    }

    @Suppress("DEPRECATION")
    override fun onDestroy() {
        // BACK TO NORMAL UI
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

    // TODO: what is the usage scenario of this
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        if (hasFocus) hideHandler.postDelayed(hideSystemUI, AUTO_HIDE_DELAY_MILLIS)
        else hideHandler.removeCallbacks(hideSystemUI)
    }

    // Hide/Show controls, status bar, navigation bar
    private var visible: Boolean = false
    private val hideHandler = Handler(Looper.getMainLooper())

    private fun toggle() {
        hideHandler.removeCallbacks(if (visible) showSystemUI else hideSystemUI)
        hideHandler.post(if (visible) hideSystemUI else showSystemUI)
    }

    @Suppress("DEPRECATION")
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

    @Suppress("DEPRECATION")
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

    class InfoDialogFragment : DialogFragment() {
        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
            return inflater.inflate(R.layout.fragment_info_dialog, container, false)
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            background.background = DialogShapeDrawable.newInstance(requireContext(), resources.getColor(R.color.color_primary_variant, null))
            ok_button.setOnClickListener { dismiss() }
            info_filename.text = arguments?.getString(NAME)
            info_shotat.text = arguments?.getString(DATE)
            val exif = ExifInterface("${requireActivity().filesDir}${resources.getString(R.string.lespas_base_folder_name)}/${arguments?.getString(ID)}")
            info_camera_mfg.text = exif.getAttribute(ExifInterface.TAG_MAKE)?.substringBefore(" ") ?: ""
            info_camera_model.text = String.format("%s%s", exif.getAttribute(ExifInterface.TAG_MODEL)?.trim() ?: "", exif.getAttribute(ExifInterface.TAG_LENS_MODEL)?.let{ "\n${it.trim()}" } ?: "")
            info_parameter.text = String.format("%s  %s  %s  %s",
                exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)?.let { "${it.substringBefore("/").toInt() / it.substringAfter("/").toInt()}mm" } ?: "",
                exif.getAttribute(ExifInterface.TAG_F_NUMBER)?.let{ "f$it" } ?: "",
                exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.let{ "1/${(1 / it.toFloat()).roundToInt()}s" } ?: "",
                exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.let { "ISO$it" } ?: ""
            )
            info_size.text = String.format("%s  %s",
                String.format("%.1fM", File("${requireActivity().filesDir}${resources.getString(R.string.lespas_base_folder_name)}", arguments?.getString(ID)!!).length().toFloat() / 1048576),
                String.format("%sw × %sh", arguments?.getString(WIDTH), arguments?.getString(HEIGHT)))
        }

        override fun onStart() {
            super.onStart()

            dialog!!.window!!.apply {
                // Set dialog width to a fixed ration of screen width
                val width = (resources.displayMetrics.widthPixels * resources.getInteger(R.integer.dialog_width_ratio) / 100)
                setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
                attributes.apply {
                    dimAmount = 0.6f
                    flags or WindowManager.LayoutParams.FLAG_DIM_BEHIND
                }

                setBackgroundDrawable(DialogShapeDrawable.newInstance(context, DialogShapeDrawable.NO_STROKE))
                setWindowAnimations(R.style.Theme_LesPas_Dialog_Animation)
            }
        }

        companion object {
            const val ID = "ID"
            const val NAME = "NAME"
            const val DATE = "DATE"
            const val WIDTH = "WIDTH"
            const val HEIGHT = "HEIGHT"

            fun newInstance(photoId: String, photoName: String, photoDate: String, photoWidth: String, photoHeight: String) = InfoDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ID, photoId)
                    putString(NAME, photoName)
                    putString(DATE, photoDate)
                    putString(WIDTH, photoWidth)
                    putString(HEIGHT, photoHeight)
            }}
        }
    }
}