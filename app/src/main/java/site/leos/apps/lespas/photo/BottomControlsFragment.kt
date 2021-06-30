package site.leos.apps.lespas.photo

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ClipData
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.transition.Slide
import android.transition.TransitionManager
import android.view.*
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.core.view.updatePadding
import androidx.exifinterface.media.ExifInterface
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.transition.Fade
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import site.leos.apps.lespas.MainActivity
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.Album
import site.leos.apps.lespas.helper.ConfirmDialogFragment
import site.leos.apps.lespas.helper.LesPasDialogFragment
import site.leos.apps.lespas.helper.RemoveOriginalBroadcastReceiver
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.sync.AcquiringDialogFragment
import site.leos.apps.lespas.sync.ShareReceiverActivity
import java.io.File
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

class BottomControlsFragment : Fragment(), MainActivity.OnWindowFocusChangedListener, ConfirmDialogFragment.OnResultListener {
    private lateinit var album: Album
    private lateinit var window: Window
    private lateinit var controlsContainer: LinearLayout
    private lateinit var moreControls: LinearLayout
    private lateinit var removeButton: ImageButton
    private lateinit var coverButton: ImageButton
    private lateinit var setAsButton: ImageButton
    private val currentPhotoModel: PhotoSlideFragment.CurrentPhotoViewModel by activityViewModels()
    private val uiToggle: PhotoSlideFragment.UIViewModel by activityViewModels()
    private var ignore = true

    private lateinit var removeOriginalBroadcastReceiver: RemoveOriginalBroadcastReceiver

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        album = arguments?.getParcelable(ALBUM)!!

        // Listener for our UI controls to show/hide with System UI
        this.window = requireActivity().window

        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            window.decorView.setOnSystemUiVisibilityChangeListener { visibility ->
                followSystemBar(visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0, window.decorView.rootWindowInsets.stableInsetBottom)
            }
        } else {
            window.decorView.setOnApplyWindowInsetsListener { _, insets ->
                followSystemBar(insets.isVisible(WindowInsets.Type.navigationBars()), insets.getInsets(WindowInsets.Type.navigationBars()).bottom)
                insets
            }
        }

        removeOriginalBroadcastReceiver = RemoveOriginalBroadcastReceiver { if (it && currentPhotoModel.getCurrentPhotoId() != album.cover) currentPhotoModel.removePhoto() }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_bottomcontrols, container, false)
    }

    @SuppressLint("ClickableViewAccessibility", "ShowToast")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ignore = true

        uiToggle.status().observe(viewLifecycleOwner, {
            if (ignore) {
                //hideHandler.postDelayed(hideSystemUI, INITIAL_AUTO_HIDE_DELAY_MILLIS)
                ignore = false
                visible = false
            } else toggle()
        })
        currentPhotoModel.getCurrentPhoto().observe(viewLifecycleOwner, {
            coverButton.isEnabled = !(Tools.isMediaPlayable(it.mimeType))
            setAsButton.isEnabled = !(Tools.isMediaPlayable(it.mimeType))
            removeButton.isEnabled = it.id != album.cover
        })

        // Controls
        controlsContainer = view.findViewById(R.id.bottom_controls_container)
        moreControls = view.findViewById(R.id.more_controls)
        removeButton = view.findViewById(R.id.remove_button)
        coverButton = view.findViewById(R.id.cover_button)
        setAsButton = view.findViewById(R.id.set_as_button)

        coverButton.run {
            setOnTouchListener(delayHideTouchListener)
            setOnClickListener {
                hideHandler.post(hideSystemUI)
                exitTransition = Fade().apply { duration = 80 }
                parentFragmentManager.beginTransaction()
                    .setReorderingAllowed(true)
                    .replace(R.id.container_bottom_toolbar, CoverSettingFragment.newInstance(album.id), CoverSettingFragment::class.java.canonicalName)
                    .addToBackStack(null)
                    .commit()
            }
        }
        view.findViewById<ImageButton>(R.id.share_button).run {
            setOnTouchListener(delayHideTouchListener)
            setOnClickListener {
                hideHandler.post(hideSystemUI)
                with(currentPhotoModel.getCurrentPhoto().value!!) {
                    try {
                        // Synced file is named after id, not yet synced file is named after file's name
                        File(Tools.getLocalRoot(context), if (eTag.isNotEmpty()) id else name).copyTo(File(context.cacheDir, name), true, 4096)
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
                                    putExtra(ShareReceiverActivity.KEY_SHOW_REMOVE_OPTION, true)
                                }, null, PendingIntent.getBroadcast(context, 0, Intent(PhotoSlideFragment.CHOOSER_SPY_ACTION), PendingIntent.FLAG_UPDATE_CURRENT).intentSender
                            )
                        )
                    } catch(e: Exception) { e.printStackTrace() }
                }
            }
        }
        setAsButton.run {
            setOnTouchListener(delayHideTouchListener)
            setOnClickListener {
                hideHandler.post(hideSystemUI)
                with(currentPhotoModel.getCurrentPhoto().value!!) {
                    try {
                        File(Tools.getLocalRoot(context), if (eTag.isNotEmpty()) id else name).copyTo(File(context.cacheDir, name), true, 4096)
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
        view.findViewById<ImageButton>(R.id.info_button).run {
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
        view.findViewById<ImageButton>(R.id.more_button).run {
            setOnTouchListener(delayHideTouchListener)
            setOnClickListener {
                moreControls.visibility = View.VISIBLE
                delayHideTouchListener
            }
        }
        removeButton.run {
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
                Snackbar.make(window.decorView.rootView, getString(if (appliedStatus) R.string.toast_cover_applied else R.string.toast_cover_set_canceled), Snackbar.LENGTH_SHORT)
                    .setAnimationMode(Snackbar.ANIMATION_MODE_FADE)
                    //.setAnchorView(window.decorView.rootView)
                    .setBackgroundTint(resources.getColor(R.color.color_primary, null))
                    .setTextColor(resources.getColor(R.color.color_text_light, null))
                    .addCallback(object: Snackbar.Callback() {
                        override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                            super.onDismissed(transientBottomBar, event)
                            hideHandler.post(showSystemUI)
                        }
                    })
                    .show()

                // Clear transition when coming back from CoverSettingFragment
                exitTransition = null
            }
        })

        LocalBroadcastManager.getInstance(requireContext().applicationContext).registerReceiver(removeOriginalBroadcastReceiver, IntentFilter(AcquiringDialogFragment.BROADCAST_REMOVE_ORIGINAL))
    }

    override fun onDestroyView() {
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(removeOriginalBroadcastReceiver)

        super.onDestroyView()
    }

    @Suppress("DEPRECATION")
    override fun onDestroy() {
        // BACK TO NORMAL UI
        hideHandler.removeCallbacksAndMessages(null)

        requireActivity().window.run {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
                decorView.setOnSystemUiVisibilityChangeListener(null)
            } else {
                insetsController?.apply {
                    show(WindowInsets.Type.systemBars())
                    systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_BARS_BY_TOUCH
                }
                statusBarColor = resources.getColor(R.color.color_primary)
                setDecorFitsSystemWindows(true)
                decorView.setOnApplyWindowInsetsListener(null)
            }
        }

        super.onDestroy()
    }

    // TODO: what is the usage scenario of this
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        if (hasFocus) hideHandler.postDelayed(hideSystemUI, AUTO_HIDE_DELAY_MILLIS)
        else hideHandler.removeCallbacks(hideSystemUI)
    }

    // Remove current photo after confirmation
    override fun onResult(positive: Boolean, requestCode: Int) {
        if (positive) currentPhotoModel.removePhoto()
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
            @Suppress("DEPRECATION")
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

    private fun followSystemBar(show: Boolean, bottomPadding: Int) {
        // TODO: Nasty exception handling here, but Android doesn't provide method to unregister System UI/Insets changes listener
        try {
            TransitionManager.beginDelayedTransition(controlsContainer, Slide(Gravity.BOTTOM).apply { duration = 80 })
            if (show) {
                controlsContainer.updatePadding(bottom = bottomPadding)
                controlsContainer.visibility = View.VISIBLE
                visible = true
            } else {
                controlsContainer.visibility = View.GONE
                visible = false
            }
            moreControls.visibility = View.GONE
        } catch (e: UninitializedPropertyAccessException) { e.printStackTrace() }
    }

    class InfoDialogFragment : LesPasDialogFragment(R.layout.fragment_info_dialog) {
        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            view.findViewById<MaterialButton>(R.id.ok_button).setOnClickListener { dismiss() }

            try {
                val fileName: String
                val appRootFolder = Tools.getLocalRoot(requireContext())
                fileName = "${appRootFolder}/${arguments?.getString(if (arguments?.getString(ETAG)?.isNotEmpty()!!) ID else NAME)}"
                view.findViewById<TextView>(R.id.info_filename).text = arguments?.getString(NAME)
                view.findViewById<TextView>(R.id.info_shotat).text = arguments?.getString(DATE)
                view.findViewById<TextView>(R.id.info_size).text = String.format(
                    "%s, %s",
                    Tools.humanReadableByteCountSI(File(fileName).length()),
                    String.format("%sw × %sh", arguments?.getString(WIDTH), arguments?.getString(HEIGHT))
                )

                val exif = ExifInterface(fileName)
                var t = exif.getAttribute(ExifInterface.TAG_MAKE)?.substringBefore(" ") ?: ""
                if (t.isEmpty()) view.findViewById<TableRow>(R.id.mfg_row).visibility = View.GONE else view.findViewById<TextView>(R.id.info_camera_mfg).text = t
                t = (exif.getAttribute(ExifInterface.TAG_MODEL)?.trim() ?: "") + (exif.getAttribute(ExifInterface.TAG_LENS_MODEL)?.let { "\n${it.trim()}" } ?: "")
                if (t.isEmpty()) view.findViewById<TableRow>(R.id.model_row).visibility = View.GONE else view.findViewById<TextView>(R.id.info_camera_model).text = t
                t = (exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)?.let { "${it.substringBefore("/").toInt() / it.substringAfter("/").toInt()}mm  " } ?: "") +
                        (exif.getAttribute(ExifInterface.TAG_F_NUMBER)?.let { "f$it  " } ?: "") +
                        (exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.let {
                            val exp = it.toFloat()
                            if (exp < 1) "1/${(1 / it.toFloat()).roundToInt()}s  " else "${exp.roundToInt()}s  "
                        } ?: "") +
                        (exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.let { "ISO$it" } ?: "")
                if (t.trim().isEmpty()) view.findViewById<TableRow>(R.id.param_row).visibility = View.GONE else view.findViewById<TextView>(R.id.info_parameter).text = t
                t = exif.getAttribute((ExifInterface.TAG_ARTIST)) ?: ""
                if (t.isEmpty()) view.findViewById<TableRow>(R.id.artist_row).visibility = View.GONE else view.findViewById<TextView>(R.id.info_artist).text = t
            } catch (e:Exception) { e.printStackTrace() }
        }

        companion object {
            const val ID = "ID"
            const val NAME = "NAME"
            const val DATE = "DATE"
            const val WIDTH = "WIDTH"
            const val HEIGHT = "HEIGHT"
            const val ETAG = "ETAG"

            @JvmStatic
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
        private const val INITIAL_AUTO_HIDE_DELAY_MILLIS = 1600L

        private const val ALBUM = "ALBUM"
        private const val INFO_DIALOG = "INFO_DIALOG"
        private const val REMOVE_DIALOG = "REMOVE_DIALOG"

        @JvmStatic
        fun newInstance(album: Album) = BottomControlsFragment().apply { arguments = Bundle().apply{ putParcelable(ALBUM, album) }}
    }
}