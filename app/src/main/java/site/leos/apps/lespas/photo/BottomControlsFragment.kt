package site.leos.apps.lespas.photo

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.transition.Slide
import android.transition.TransitionManager
import android.view.*
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.core.content.FileProvider
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.ViewModelProvider
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import androidx.transition.Fade
import com.google.android.material.snackbar.Snackbar
import site.leos.apps.lespas.MainActivity
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.Album
import site.leos.apps.lespas.album.AlbumViewModel
import site.leos.apps.lespas.album.Cover
import site.leos.apps.lespas.helper.ConfirmDialogFragment
import site.leos.apps.lespas.helper.MetaDataDialogFragment
import site.leos.apps.lespas.helper.RemoveOriginalBroadcastReceiver
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.settings.SettingsFragment
import site.leos.apps.lespas.sync.AcquiringDialogFragment
import site.leos.apps.lespas.sync.ActionViewModel
import site.leos.apps.lespas.sync.ShareReceiverActivity
import java.io.File

class BottomControlsFragment : Fragment(), MainActivity.OnWindowFocusChangedListener {
    private lateinit var album: Album
    private lateinit var window: Window
    private lateinit var controlsContainer: LinearLayout
    private lateinit var removeButton: ImageButton
    private lateinit var coverButton: ImageButton
    private lateinit var setAsButton: ImageButton
    private val currentPhotoModel: PhotoSlideFragment.CurrentPhotoViewModel by activityViewModels()
    private val uiToggle: PhotoSlideFragment.UIViewModel by activityViewModels()

    private lateinit var removeOriginalBroadcastReceiver: RemoveOriginalBroadcastReceiver

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        album = arguments?.getParcelable(ALBUM)!!

        // Listener for our UI controls to show/hide with System UI
        this.window = requireActivity().window

        @Suppress("DEPRECATION")
/*
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
*/
        window.decorView.setOnSystemUiVisibilityChangeListener { visibility ->
            followSystemBar(visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0, window.decorView.rootWindowInsets.stableInsetBottom)
        }

        removeOriginalBroadcastReceiver = RemoveOriginalBroadcastReceiver { if (it && currentPhotoModel.getCurrentPhotoId() != album.cover) currentPhotoModel.removePhoto() }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_bottomcontrols, container, false)
    }

    @SuppressLint("ClickableViewAccessibility", "ShowToast")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        uiToggle.status().observe(viewLifecycleOwner, { toggle(it) })
        currentPhotoModel.getCurrentPhoto().observe(viewLifecycleOwner, {
            // Can't set video as avatar
            setAsButton.isEnabled = !(Tools.isMediaPlayable(it.mimeType))
            // Can't delete cover
            removeButton.isEnabled = it.id != album.cover
        })

        // Controls
        controlsContainer = view.findViewById(R.id.bottom_controls_container)
        removeButton = view.findViewById(R.id.remove_button)
        coverButton = view.findViewById(R.id.cover_button)
        setAsButton = view.findViewById(R.id.set_as_button)

        coverButton.run {
            setOnTouchListener(delayHideTouchListener)
            setOnClickListener {
                hideHandler.post(hideSystemUI)
                val currentMedia = currentPhotoModel.getCurrentPhoto().value!!
                if (Tools.isMediaPlayable(currentMedia.mimeType)) {
                    ViewModelProvider(requireActivity()).get(AlbumViewModel::class.java).setCover(album.id, Cover(currentMedia.id, 0, currentMedia.width, currentMedia.height))
                    // If album has not been uploaded yet, update the cover id in action table too
                    ViewModelProvider(requireActivity()).get(ActionViewModel::class.java).updateCover(album.id, currentMedia.id)
                    showCoverAppliedStatus(true)
                } else {
                    exitTransition = Fade().apply { duration = 80 }
                    parentFragmentManager.beginTransaction().setReorderingAllowed(true).replace(R.id.container_bottom_toolbar, CoverSettingFragment.newInstance(album.id), CoverSettingFragment::class.java.canonicalName).addToBackStack(null).commit()
                }
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
                    currentPhotoModel.getCurrentPhoto().value!!.run { MetaDataDialogFragment.newInstance(this).show(parentFragmentManager, INFO_DIALOG) }
                }
            }
        }
        view.findViewById<ImageButton>(R.id.snapseed_button).run {
            setOnTouchListener(delayHideTouchListener)
            setOnClickListener {
                with(currentPhotoModel.getCurrentPhoto().value!!) {
                    try {
                        // Synced file is named after id, not yet synced file is named after file's name
                        File(Tools.getLocalRoot(context), if (eTag.isNotEmpty()) id else name).copyTo(File(context.cacheDir, name), true, 4096)
                        val uri = FileProvider.getUriForFile(context, getString(R.string.file_authority), File(context.cacheDir, name))
                        val mimeType = this.mimeType

                        startActivity(Intent().apply {
                            action = Intent.ACTION_SEND
                            data = uri
                            type = mimeType
                            putExtra(Intent.EXTRA_STREAM, uri)
                            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                            setClassName(SettingsFragment.SNAPSEED_PACKAGE_NAME, SettingsFragment.SNAPSEED_MAIN_ACTIVITY_CLASS_NAME)
                        })
                    } catch (e: Exception) { e.printStackTrace() }
                }

                // Send broadcast just like system share does when user chooses Snapseed, so that PhotoSliderFragment can catch editing result
                it.context.sendBroadcast(Intent().apply {
                    action = PhotoSlideFragment.CHOOSER_SPY_ACTION
                    putExtra(Intent.EXTRA_CHOSEN_COMPONENT, ComponentName(SettingsFragment.SNAPSEED_PACKAGE_NAME, SettingsFragment.SNAPSEED_MAIN_ACTIVITY_CLASS_NAME))
                })
            }

            this.isEnabled = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(getString(R.string.snapseed_pref_key), false)
        }
        removeButton.run {
            setOnTouchListener(delayHideTouchListener)
            setOnClickListener {
                hideHandler.post(hideSystemUI)
                if (parentFragmentManager.findFragmentByTag(REMOVE_DIALOG) == null)
                    ConfirmDialogFragment.newInstance(getString(R.string.confirm_delete), getString(R.string.yes_delete)).show(parentFragmentManager, REMOVE_DIALOG)
            }
        }

        currentPhotoModel.getCoverAppliedStatus().observe(viewLifecycleOwner, { appliedStatus ->
            if (currentPhotoModel.forReal()) {
                showCoverAppliedStatus(appliedStatus == true)

                // Clear transition when coming back from CoverSettingFragment
                exitTransition = null
            }
        })

        LocalBroadcastManager.getInstance(requireContext().applicationContext).registerReceiver(removeOriginalBroadcastReceiver, IntentFilter(AcquiringDialogFragment.BROADCAST_REMOVE_ORIGINAL))

        // Remove photo confirm dialog result handler
        parentFragmentManager.setFragmentResultListener(ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY, viewLifecycleOwner) { key, bundle ->
            if (key == ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY && bundle.getBoolean(ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY, false)) currentPhotoModel.removePhoto()
        }
    }

    override fun onDestroyView() {
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(removeOriginalBroadcastReceiver)

        super.onDestroyView()
    }

    override fun onDestroy() {
        // BACK TO NORMAL UI
        hideHandler.removeCallbacksAndMessages(null)

        @Suppress("DEPRECATION")
        requireActivity().window.run {
/*
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
*/
            decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            decorView.setOnSystemUiVisibilityChangeListener(null)
            statusBarColor = resources.getColor(R.color.color_primary)
        }

        super.onDestroy()
    }

    private fun showCoverAppliedStatus(appliedStatus: Boolean) {
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
    }

    // TODO: what is the usage scenario of this
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        if (hasFocus) hideHandler.postDelayed(hideSystemUI, AUTO_HIDE_DELAY_MILLIS)
        else hideHandler.removeCallbacks(hideSystemUI)
    }

    // Hide/Show controls, status bar, navigation bar
    private val hideHandler = Handler(Looper.getMainLooper())

    private fun toggle(state: Boolean) {
        hideHandler.removeCallbacksAndMessages(null)
        hideHandler.post(if (state) showSystemUI else hideSystemUI)
    }

    private val hideSystemUI = Runnable {
/*
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
*/
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE
            // Set the content to appear under the system bars so that the
            // content doesn't resize when the system bars hide and show.
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            // Hide the nav bar and status bar
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
        )
        uiToggle.toggleOnOff(false)
    }

    private val showSystemUI = Runnable {
/*
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R)
        // Shows the system bars by removing all the flags except for the ones that make the content appear under the system bars.
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        else window.insetsController?.show(WindowInsets.Type.systemBars())
*/
        // Shows the system bars by removing all the flags except for the ones that make the content appear under the system bars.
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
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
            } else {
                controlsContainer.visibility = View.GONE
            }
        } catch (e: UninitializedPropertyAccessException) { e.printStackTrace() }

        // auto hide
        if (show) hideHandler.postDelayed(hideSystemUI, AUTO_HIDE_DELAY_MILLIS)
    }

    companion object {
        private const val AUTO_HIDE_DELAY_MILLIS = 3000L // The number of milliseconds to wait after user interaction before hiding the system UI.

        private const val ALBUM = "ALBUM"
        private const val INFO_DIALOG = "INFO_DIALOG"
        private const val REMOVE_DIALOG = "REMOVE_DIALOG"

        @JvmStatic
        fun newInstance(album: Album) = BottomControlsFragment().apply { arguments = Bundle().apply{ putParcelable(ALBUM, album) }}
    }
}