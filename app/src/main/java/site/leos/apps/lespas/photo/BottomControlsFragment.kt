package site.leos.apps.lespas.photo

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ClipData
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
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
import androidx.transition.Fade
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar
import kotlinx.android.synthetic.main.fragment_bottomcontrols.*
import kotlinx.android.synthetic.main.fragment_info_dialog.*
import site.leos.apps.lespas.MainActivity
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.Album
import site.leos.apps.lespas.helper.ConfirmDialogFragment
import site.leos.apps.lespas.helper.DialogShapeDrawable
import site.leos.apps.lespas.helper.Tools
import java.io.File
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

class BottomControlsFragment : Fragment(), MainActivity.OnWindowFocusChangedListener, ConfirmDialogFragment.OnResultListener {
    private lateinit var album: Album
    private lateinit var window: Window
    private lateinit var controls: LinearLayout
    private lateinit var more_controls: LinearLayout
    private lateinit var remove_button: ImageButton
    private val currentPhotoModel: PhotoSlideFragment.CurrentPhotoViewModel by activityViewModels()
    private val uiToggle: PhotoSlideFragment.UIViewModel by activityViewModels()
    private var ignore = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        album = arguments?.getParcelable<Album>(ALBUM)!!

        // Listener for our UI controls to show/hide with System UI
        this.window = requireActivity().window

        // TODO: Nasty exception handling here, but Android doesn't provide method to unregister System UI/Insets changes listener
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            window.decorView.setOnSystemUiVisibilityChangeListener { visibility ->
                try {
                    TransitionManager.beginDelayedTransition(controls, Slide(Gravity.BOTTOM).apply { duration = 80 })
                    if (visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) {
                        more_controls.visibility = View.GONE
                        remove_button.isEnabled = currentPhotoModel.getCurrentPhoto().value!!.id != album.cover
                        controls.visibility = View.VISIBLE
                        visible = true
                    } else {
                        controls.visibility = View.GONE
                        more_controls.visibility = View.GONE
                        visible = false
                    }
                } catch (e: UninitializedPropertyAccessException) {}
            }
        } else {
            window.decorView.setOnApplyWindowInsetsListener { _, insets ->
                try {
                    TransitionManager.beginDelayedTransition(controls, Slide(Gravity.BOTTOM).apply { duration = 80 })
                    if (insets.isVisible(WindowInsets.Type.navigationBars())) {
                        more_controls.visibility = View.GONE
                        remove_button.isEnabled = currentPhotoModel.getCurrentPhoto().value!!.id != album.cover
                        controls.visibility = View.VISIBLE
                        visible = true
                    } else {
                        controls.visibility = View.GONE
                        more_controls.visibility = View.GONE
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
        currentPhotoModel.getCurrentPhoto().observe(viewLifecycleOwner, {
            cover_button.isEnabled = !(Tools.isMediaPlayable(it.mimeType))
            set_as_button.isEnabled = !(Tools.isMediaPlayable(it.mimeType))
        })

        // Controls
        controls = view.findViewById(R.id.base_controls)
        more_controls = view.findViewById(R.id.more_controls)
        remove_button = view.findViewById(R.id.remove_button)

        cover_button.run {
            setOnTouchListener(delayHideTouchListener)
            setOnClickListener {
                hideHandler.post(hideSystemUI)
                exitTransition = Fade().apply { duration = 80 }
                parentFragmentManager.beginTransaction()
                    .setReorderingAllowed(true)
                    .replace(R.id.container_bottom_toolbar, CoverSettingFragment.newInstance(album.id))
                    .addToBackStack(CoverSettingFragment::class.simpleName)
                    .commit()
            }
        }
        share_button.run {
            setOnTouchListener(delayHideTouchListener)
            setOnClickListener {
                hideHandler.post(hideSystemUI)
                with(currentPhotoModel.getCurrentPhoto().value!!) {
                    try {
                        // Synced file is named after id, not yet synced file is named after file's name
                        File("${context.filesDir}${getString(R.string.lespas_base_folder_name)}", if (eTag.isNotEmpty()) id else name).copyTo(File(context.cacheDir, name), true, 4096)
                        val uri = FileProvider.getUriForFile(context, getString(R.string.file_authority), File(context.cacheDir, name))
                        val mimeType = this.mimeType

                        startActivity(
                            Intent.createChooser(
                                Intent().apply {
                                    action = Intent.ACTION_SEND
                                    type = mimeType
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    clipData = ClipData.newUri(context.contentResolver, "", uri)
                                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                                }, null, PendingIntent.getBroadcast(context, 0, Intent(PhotoSlideFragment.CHOOSER_SPY_ACTION), PendingIntent.FLAG_UPDATE_CURRENT).intentSender
                            )
                        )
                    } catch(e: Exception) { e.printStackTrace() }
                }
            }
        }
        set_as_button.run {
            setOnTouchListener(delayHideTouchListener)
            setOnClickListener {
                hideHandler.post(hideSystemUI)
                with(currentPhotoModel.getCurrentPhoto().value!!) {
                    try {
                        File("${context.filesDir}${getString(R.string.lespas_base_folder_name)}", if (eTag.isNotEmpty()) id else name).copyTo(File(context.cacheDir, name), true, 4096)
                        val uri = FileProvider.getUriForFile(context, getString(R.string.file_authority), File(context.cacheDir, name))
                        val mimeType = this.mimeType

                        startActivity(
                            Intent.createChooser(
                                Intent().apply {
                                    action = Intent.ACTION_ATTACH_DATA
                                    setDataAndType(uri, mimeType)
                                    putExtra("mimeType", mimeType)
                                    //clipData = ClipData.newUri(requireActivity().contentResolver, "", uri)
                                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                                }, null
                            )
                        )
                    } catch(e: Exception) { e.printStackTrace() }
                }
            }
        }
        info_button.run {
            setOnTouchListener(delayHideTouchListener)
            setOnClickListener {
                hideHandler.post(hideSystemUI)
                if (parentFragmentManager.findFragmentByTag(INFO_DIALOG) == null) {
                    currentPhotoModel.getCurrentPhoto().value!!.run {
                        InfoDialogFragment.newInstance(id, name, dateTaken.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT, FormatStyle.SHORT)), width.toString(), height.toString(), eTag
                        ).show(parentFragmentManager, INFO_DIALOG)
                    }
                }
            }
        }
        more_button.run {
            setOnTouchListener(delayHideTouchListener)
            setOnClickListener {
                more_controls.visibility = View.VISIBLE
                delayHideTouchListener
            }
        }
        remove_button.run {
            setOnTouchListener(delayHideTouchListener)
            setOnClickListener {
                hideHandler.post(hideSystemUI)
                if (parentFragmentManager.findFragmentByTag(REMOVE_DIALOG) == null)
                    ConfirmDialogFragment.newInstance(getString(R.string.confirm_delete), getString(R.string.yes_delete)).let {
                        it.setTargetFragment(parentFragmentManager.findFragmentById(R.id.container_bottom_toolbar), 0)
                        it.show(parentFragmentManager, REMOVE_DIALOG)
                    }
            }
        }

        currentPhotoModel.getCoverAppliedStatus().observe(viewLifecycleOwner, { appliedStatus ->
            if (currentPhotoModel.forReal()) {
                Snackbar
                    .make(window.decorView.rootView, getString(if (appliedStatus) R.string.toast_cover_applied else R.string.toast_cover_set_canceled), Snackbar.LENGTH_SHORT)
                    .setAnimationMode(Snackbar.ANIMATION_MODE_FADE)
                    //.setAnchorView(window.decorView.rootView)
                    .setBackgroundTint(resources.getColor(R.color.color_primary, null))
                    .setTextColor(resources.getColor(R.color.color_text_light, null))
                    .show()

                // Clear transition when coming back from CoverSettingFragment
                exitTransition = null
            }
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
                window.insetsController?.apply {
                    show(WindowInsets.Type.systemBars())
                    systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_BARS_BY_TOUCH
                }
                window.statusBarColor = resources.getColor(R.color.color_primary)
                window.setDecorFitsSystemWindows(true)
                window.decorView.setOnApplyWindowInsetsListener(null)
            }
            supportActionBar?.show()
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
            window.insetsController?.apply {
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsets.Type.systemBars())
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
        else window.insetsController?.show(WindowInsets.Type.systemBars())
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

    // Remove current photo after confirmation
    override fun onResult(positive: Boolean, requestCode: Int) {
        if (positive) currentPhotoModel.removePhoto()
    }

    class InfoDialogFragment : DialogFragment() {
        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
            inflater.inflate(R.layout.fragment_info_dialog, container, false)

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            shape_background.background = DialogShapeDrawable.newInstance(requireContext(), DialogShapeDrawable.NO_STROKE)
            //background.background = DialogShapeDrawable.newInstance(requireContext(), resources.getColor(R.color.color_primary_variant, null))
            background.background = DialogShapeDrawable.newInstance(requireContext(), MaterialColors.getColor(view, R.attr.colorPrimaryVariant))

            ok_button.setOnClickListener { dismiss() }

            try {
                val fileName: String
                val appRootFolder = "${requireActivity().filesDir}${resources.getString(R.string.lespas_base_folder_name)}"
                fileName = "${appRootFolder}/${arguments?.getString(if (arguments?.getString(ETAG)?.isNotEmpty()!!) ID else NAME)}"
                info_filename.text = arguments?.getString(NAME)
                info_shotat.text = arguments?.getString(DATE)
                info_size.text = String.format(
                    "%s, %s",
                    Tools.humanReadableByteCountSI(File(fileName).length()),
                    String.format("%sw × %sh", arguments?.getString(WIDTH), arguments?.getString(HEIGHT))
                )

                val exif = ExifInterface(fileName)
                var t = exif.getAttribute(ExifInterface.TAG_MAKE)?.substringBefore(" ") ?: ""
                if (t.isEmpty()) mfg_row.visibility = View.GONE else info_camera_mfg.text = t
                t = (exif.getAttribute(ExifInterface.TAG_MODEL)?.trim() ?: "") + (exif.getAttribute(ExifInterface.TAG_LENS_MODEL)?.let { "\n${it.trim()}" } ?: "")
                if (t.isEmpty()) model_row.visibility = View.GONE else info_camera_model.text = t
                t = (exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)?.let { "${it.substringBefore("/").toInt() / it.substringAfter("/").toInt()}mm  " } ?: "") +
                        (exif.getAttribute(ExifInterface.TAG_F_NUMBER)?.let { "f$it  " } ?: "") +
                        (exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.let {
                            val exp = it.toFloat()
                            if (exp < 1) "1/${(1 / it.toFloat()).roundToInt()}s  " else "${exp.roundToInt()}s  "
                        } ?: "") +
                        (exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.let { "ISO$it" } ?: "")
                if (t.trim().isEmpty()) param_row.visibility = View.GONE else info_parameter.text = t
                t = exif.getAttribute((ExifInterface.TAG_ARTIST)) ?: ""
                if (t.isEmpty()) artist_row.visibility = View.GONE else info_artist.text = t
            } catch (e:Exception) { e.printStackTrace() }
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

                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setWindowAnimations(R.style.Theme_LesPas_Dialog_Animation)
            }
        }

        companion object {
            const val ID = "ID"
            const val NAME = "NAME"
            const val DATE = "DATE"
            const val WIDTH = "WIDTH"
            const val HEIGHT = "HEIGHT"
            const val ETAG = "ETAG"

            fun newInstance(photoId: String, photoName: String, photoDate: String, photoWidth: String, photoHeight: String, eTag: String) = InfoDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ID, photoId)
                    putString(NAME, photoName)
                    putString(DATE, photoDate)
                    putString(WIDTH, photoWidth)
                    putString(HEIGHT, photoHeight)
                    putString(ETAG, eTag)
            }}
        }
    }

    companion object {
        private const val AUTO_HIDE_DELAY_MILLIS = 3000L // The number of milliseconds to wait after user interaction before hiding the system UI.

        private const val ALBUM = "ALBUM"
        private const val INFO_DIALOG = "INFO_DIALOG"
        private const val REMOVE_DIALOG = "REMOVE_DIALOG"

        fun newInstance(album: Album) = BottomControlsFragment().apply { arguments = Bundle().apply{ putParcelable(ALBUM, album) }}
    }
}