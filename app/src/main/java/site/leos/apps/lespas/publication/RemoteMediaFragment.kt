/*
 *   Copyright 2019 Jeffrey Liu (scubajeffrey@protonmail.ch)
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package site.leos.apps.lespas.publication

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.transition.Slide
import android.transition.TransitionManager
import android.view.*
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.core.app.SharedElementCallback
import androidx.core.content.ContextCompat
import androidx.core.view.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.transition.MaterialContainerTransform
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.Album
import site.leos.apps.lespas.helper.*
import site.leos.apps.lespas.helper.Tools.parcelableArray
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.sync.Action
import site.leos.apps.lespas.sync.ActionViewModel
import site.leos.apps.lespas.sync.DestinationDialogFragment
import java.time.ZoneId
import kotlin.math.min

class RemoteMediaFragment: Fragment() {
    private lateinit var window: Window
    private lateinit var controlsContainer: LinearLayoutCompat
    private lateinit var slider: ViewPager2
    private lateinit var pAdapter: RemoteMediaAdapter
    private lateinit var captionTextView: TextView
    private lateinit var dividerView: View

    private val shareModel: NCShareViewModel by activityViewModels()
    private val currentPositionModel: PublicationDetailFragment.CurrentPublicationViewModel by activityViewModels()
    private val destinationModel: DestinationDialogFragment.DestinationViewModel by activityViewModels()
    private val playerViewModel: VideoPlayerViewModel by viewModels { VideoPlayerViewModelFactory(requireActivity(), shareModel.getCallFactory(), shareModel.getPlayerCache()) }

    private var autoRotate = false
    private var previousOrientationSetting = 0
    private var previousTitleBarDisplayOption = 0
    private val handler = Handler(Looper.getMainLooper())

    private var albumId = ""

    private lateinit var storagePermissionRequestLauncher: ActivityResultLauncher<String>
    private lateinit var accessMediaLocationPermissionRequestLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        albumId = requireArguments().getString(KEY_ALBUM_ID) ?: ""

        pAdapter = RemoteMediaAdapter(
            requireContext(),
            Tools.getDisplayDimension(requireActivity()).first,
            shareModel.getResourceRoot(),
            playerViewModel,
            { state-> toggleSystemUI(state) },
            { media, imageView, type-> if (type == NCShareViewModel.TYPE_NULL) startPostponedEnterTransition() else shareModel.setImagePhoto(media, imageView!!, type) { startPostponedEnterTransition() }},
            { view-> shareModel.cancelSetImagePhoto(view) }
        ).apply {
            stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        }

        // Adjusting the shared element mapping
        setEnterSharedElementCallback(object : SharedElementCallback() {
            override fun onMapSharedElements(names: MutableList<String>?, sharedElements: MutableMap<String, View>?) {
                if (names?.isNotEmpty() == true) slider.getChildAt(0)?.findViewById<View>(R.id.media)?.apply { sharedElements?.put(names[0], this) }
            }
        })

        @Suppress("DEPRECATION")
        requireActivity().window.decorView.setOnSystemUiVisibilityChangeListener { visibility -> followSystemBar(visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0) }

        accessMediaLocationPermissionRequestLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
        storagePermissionRequestLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted->
            requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

            if (isGranted) {
                // Explicitly request ACCESS_MEDIA_LOCATION permission
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) accessMediaLocationPermissionRequestLauncher.launch(android.Manifest.permission.ACCESS_MEDIA_LOCATION)

                saveMedia()
            }
        }

        this.window = requireActivity().window
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        postponeEnterTransition()

        // Wipe ActionBar
        (requireActivity() as AppCompatActivity).supportActionBar?.run {
            previousTitleBarDisplayOption = savedInstanceState?.run {
                // During fragment recreate, wipe actionbar to avoid flash
                wipeActionBar()

                getInt(KEY_DISPLAY_OPTION)
            } ?: displayOptions
        }

        previousOrientationSetting = requireActivity().requestedOrientation
        autoRotate = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(requireContext().getString(R.string.auto_rotate_perf_key), false)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_remote_media, container, false)

        captionTextView = view.findViewById<TextView>(R.id.caption).apply {
            movementMethod =  ScrollingMovementMethod()
            doOnEachNextLayout {
                // Hide bottom control with delay adapted to caption's length
                (it as TextView).let { caption ->
                    handler.removeCallbacks(hideSystemUI)
                    handler.postDelayed(hideSystemUI, AUTO_HIDE_DELAY_MILLIS + if (caption.text.isNotEmpty()) min(caption.lineCount, caption.maxLines) * 800 else 0)
                }
            }
        }
        dividerView = view.findViewById(R.id.divider)

        slider = view.findViewById<ViewPager2>(R.id.pager).apply {
            adapter = pAdapter

            // Use reflection to reduce Viewpager2 slide sensitivity, so that PhotoView inside can zoom presently
            val recyclerView = (ViewPager2::class.java.getDeclaredField("mRecyclerView").apply { isAccessible = true }).get(this) as RecyclerView
            (RecyclerView::class.java.getDeclaredField("mTouchSlop")).apply {
                isAccessible = true
                set(recyclerView, (get(recyclerView) as Int) * 4)
            }

            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageScrollStateChanged(state: Int) {
                    super.onPageScrollStateChanged(state)
                    if (state == ViewPager2.SCROLL_STATE_SETTLING) handler.post(hideSystemUI)
                }

                override fun onPageSelected(position: Int) {
                    super.onPageSelected(position)
                    currentPositionModel.setCurrentPosition(position)
                    captionTextView.text = pAdapter.getCaption(position)
                    if (autoRotate) requireActivity().requestedOrientation = if (pAdapter.isLandscape(position)) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
            })
        }
        slider.doOnLayout {
            // Get into immersive mode
            Tools.goImmersive(window, savedInstanceState == null)
        }

        controlsContainer = view.findViewById<LinearLayoutCompat>(R.id.bottom_controls_container).apply {
            ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets->
                @Suppress("DEPRECATION")
                v.updatePadding(bottom = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) window.decorView.rootWindowInsets.stableInsetBottom else with(window.windowManager.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars())) { bottom - top })
                insets
            }
        }
        view.findViewById<Button>(R.id.download_button).run {
            setOnTouchListener(delayHideTouchListener)
            setOnClickListener {
                handler.post(hideSystemUI)
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED

                    storagePermissionRequestLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
                else saveMedia()
            }
        }
        view.findViewById<Button>(R.id.lespas_button).run {
            setOnTouchListener(delayHideTouchListener)
            setOnClickListener {
                handler.post(hideSystemUI)
                if (parentFragmentManager.findFragmentByTag(TAG_DESTINATION_DIALOG) == null)
                    DestinationDialogFragment.newInstance(arrayListOf(pAdapter.currentList[currentPositionModel.getCurrentPositionValue()]), albumId, false).show(parentFragmentManager, TAG_DESTINATION_DIALOG)
            }
        }
        view.findViewById<Button>(R.id.info_button).run {
            setOnTouchListener(delayHideTouchListener)
            setOnClickListener {
                handler.post(hideSystemUI)
                if (parentFragmentManager.findFragmentByTag(TAG_INFO_DIALOG) == null) {
                    MetaDataDialogFragment.newInstance(pAdapter.currentList[currentPositionModel.getCurrentPositionValue()]).show(parentFragmentManager, TAG_INFO_DIALOG)
                }
            }
        }

        sharedElementEnterTransition = MaterialContainerTransform().apply {
            duration = resources.getInteger(android.R.integer.config_mediumAnimTime).toLong()
            scrimColor = Color.TRANSPARENT
            fadeMode = MaterialContainerTransform.FADE_MODE_CROSS
        }.apply {
            addListener(MediaSliderTransitionListener(slider))
        }

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        (requireArguments().parcelableArray<NCShareViewModel.RemotePhoto>(KEY_REMOTE_MEDIA)!!).run {
            pAdapter.submitList(toMutableList()) {
                requireArguments().getInt(KEY_SCROLL_TO).let { jumpTo ->
                    savedInstanceState ?: run { slider.setCurrentItem(jumpTo, false) }
                    currentPositionModel.setCurrentPosition(jumpTo)
                    captionTextView.text = pAdapter.getCaption(jumpTo)
                }
            }
        }

        // When DestinationDialog returns
        destinationModel.getDestination().observe(viewLifecycleOwner) {
            it?.let { targetAlbum ->
                destinationModel.getRemotePhotos()[0].let { remotePhoto ->
                    ViewModelProvider(requireActivity())[ActionViewModel::class.java].addActions(mutableListOf<Action>().apply {
                        //val metaString = remotePhoto.photo.let { photo -> "${targetAlbum.eTag}|${photo.dateTaken.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()}|${photo.mimeType}|${photo.width}|${photo.height}|${photo.orientation}|${photo.caption}|${photo.latitude}|${photo.longitude}|${photo.altitude}|${photo.bearing}" }
                        val metaString = remotePhoto.photo.let { photo -> "${targetAlbum.eTag}|${photo.dateTaken.atZone(ZoneId.of("Z")).toInstant().toEpochMilli()}|${photo.mimeType}|${photo.width}|${photo.height}|${photo.orientation}|${photo.caption}|${photo.latitude}|${photo.longitude}|${photo.altitude}|${photo.bearing}" }
                        if (targetAlbum.id == Album.JOINT_ALBUM_ID) {
                            targetAlbum.coverFileName.substringBeforeLast('/').let { targetFolder ->
                                add(Action(null, Action.ACTION_COPY_ON_SERVER, remotePhoto.remotePath,
                                    targetFolder,
                                    metaString,
                                    "${remotePhoto.photo.name}|true",
                                    System.currentTimeMillis(), 1
                                ))

                                // Target album is Joint Album, update it's content metadata file
                                add(Action(null, Action.ACTION_UPDATE_JOINT_ALBUM_PHOTO_META, targetAlbum.eTag, targetFolder, "", "", System.currentTimeMillis(), 1))
                            }
                        } else {
                            if (targetAlbum.id.isEmpty()) {
                                // Create new album first, since this whole operations will be carried out on server, we don't have to worry about cover here, SyncAdapter will handle all the rest during next sync
                                add(Action(null, Action.ACTION_ADD_DIRECTORY_ON_SERVER, "", targetAlbum.name, "", "", System.currentTimeMillis(), 1))
                            }

                            add(Action(null, Action.ACTION_COPY_ON_SERVER, remotePhoto.remotePath,
                                "${Tools.getRemoteHome(requireContext())}/${targetAlbum.name}",
                                metaString,
                                "${remotePhoto.photo.name}|false",
                                System.currentTimeMillis(), 1
                            ))
                        }
                    })
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        pAdapter.setPauseVideo(true)
    }

    override fun onStop() {
        super.onStop()
        if (pAdapter.getPhotoAt(slider.currentItem).mimeType.startsWith("video")) handler.postDelayed({
            playerViewModel.pause(Uri.EMPTY)
        }, 300)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_DISPLAY_OPTION, previousTitleBarDisplayOption)

        pAdapter.setPauseVideo(false)
    }

    override fun onDestroyView() {
        handler.removeCallbacksAndMessages(null)
        slider.adapter = null

        super.onDestroyView()
    }

    override fun onDestroy() {
        // BACK TO NORMAL UI
        Tools.quitImmersive(window)

        (requireActivity() as AppCompatActivity).run {
            supportActionBar?.apply {
                displayOptions = previousTitleBarDisplayOption
                setBackgroundDrawable(ColorDrawable(Tools.getAttributeColor(requireContext(), android.R.attr.colorPrimary)))
            }
            requestedOrientation = previousOrientationSetting
        }

        super.onDestroy()
    }

    private fun toggleSystemUI(state: Boolean?) {
        handler.removeCallbacksAndMessages(null)
        handler.post(if (state ?: !controlsContainer.isVisible) showSystemUI else hideSystemUI)
    }

    private val hideSystemUI = Runnable { Tools.goImmersive(window) }
    @Suppress("DEPRECATION")
    private val showSystemUI = Runnable {
        window.run {
/*
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
            else insetsController?.show(WindowInsets.Type.systemBars())
*/
            decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }

        captionTextView.text.isNotEmpty().let {
            // Use View.INVISIBLE so that caption's lines can be count even if it's empty
            captionTextView.visibility = if (it) View.VISIBLE else View.INVISIBLE
            dividerView.isVisible = it
        }

        // auto hide, now triggered by caption view layout adapting to caption's length
        //hideHandler.postDelayed(hideSystemUI, AUTO_HIDE_DELAY_MILLIS)
    }

    // Delay hiding the system UI while interacting with controls, preventing the jarring behavior of controls going away
    @SuppressLint("ClickableViewAccessibility")
    private val delayHideTouchListener = View.OnTouchListener { _, _ ->
        handler.removeCallbacks(hideSystemUI)
        handler.postDelayed(hideSystemUI, AUTO_HIDE_DELAY_MILLIS)
        false
    }

    private fun followSystemBar(show: Boolean) {
        // Wipe ActionBar
        (requireActivity() as AppCompatActivity).supportActionBar?.run {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            displayOptions = 0
        }

        // TODO: Nasty exception handling here, but Android doesn't provide method to unregister System UI/Insets changes listener
        try {
            TransitionManager.beginDelayedTransition(controlsContainer, if (Build.VERSION.SDK_INT > Build.VERSION_CODES.R) android.transition.Fade() else Slide(Gravity.BOTTOM).apply { duration = 50 })
            controlsContainer.visibility = if (show) View.VISIBLE else View.GONE
        } catch (e: UninitializedPropertyAccessException) { e.printStackTrace() }

        // auto hide, now triggered by caption view layout adapting to caption's length
        //if (show) hideHandler.postDelayed(hideSystemUI, AUTO_HIDE_DELAY_MILLIS)

        // Although it seems like repeating this everytime when showing system UI, wiping actionbar here rather than when fragment creating will prevent action bar flashing
        wipeActionBar()
    }

    private fun wipeActionBar() {
        (requireActivity() as AppCompatActivity).supportActionBar?.run {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            displayOptions = 0
        }
    }

    private fun saveMedia() {
        pAdapter.currentList[currentPositionModel.getCurrentPositionValue()].apply {
            shareModel.savePhoto(requireContext(), this)
            handler.postDelayed({ Snackbar.make(window.decorView.rootView, getString(R.string.downloading_message, this.remotePath.substringAfterLast('/')), Snackbar.LENGTH_LONG).show() }, 400L)
        }
    }

    class RemoteMediaAdapter(context: Context, displayWidth: Int, private val basePath: String, playerViewModel: VideoPlayerViewModel, val clickListener: (Boolean?) -> Unit, val imageLoader: (NCShareViewModel.RemotePhoto, ImageView?, type: String) -> Unit, cancelLoader: (View) -> Unit
    ): SeamlessMediaSliderAdapter<NCShareViewModel.RemotePhoto>(context, displayWidth, PhotoDiffCallback(), playerViewModel, clickListener, imageLoader, cancelLoader) {
        override fun getVideoItem(position: Int): VideoItem = with(getItem(position) as NCShareViewModel.RemotePhoto) { VideoItem(Uri.parse("$basePath$remotePath/${photo.name}"), photo.mimeType, photo.width, photo.height, photo.id) }
        override fun getItemTransitionName(position: Int): String  = (getItem(position) as NCShareViewModel.RemotePhoto).photo.id
        override fun getItemMimeType(position: Int): String = (getItem(position) as NCShareViewModel.RemotePhoto).photo.mimeType
        fun getCaption(position: Int): String = try { currentList[position].photo.caption } catch (_: Exception) { "" }
        fun isLandscape(position: Int): Boolean = try { currentList[position].photo.width >= currentList[position].photo.height } catch (_: Exception) { false }

        fun getPhotoAt(position: Int): Photo = currentList[position].photo
    }

    class PhotoDiffCallback: DiffUtil.ItemCallback<NCShareViewModel.RemotePhoto>() {
        override fun areItemsTheSame(oldItem: NCShareViewModel.RemotePhoto, newItem: NCShareViewModel.RemotePhoto): Boolean = oldItem.photo.id == newItem.photo.id
        override fun areContentsTheSame(oldItem: NCShareViewModel.RemotePhoto, newItem: NCShareViewModel.RemotePhoto): Boolean = oldItem.photo.id == newItem.photo.id
    }

    companion object {
        private const val KEY_REMOTE_MEDIA = "REMOTE_MEDIA"
        private const val KEY_SCROLL_TO = "SCROLL_TO"
        private const val KEY_ALBUM_ID = "KEY_ALBUM_ID"
        private const val AUTO_HIDE_DELAY_MILLIS = 3000L // The number of milliseconds to wait after user interaction before hiding the system UI.

        private const val KEY_DISPLAY_OPTION = "KEY_DISPLAY_OPTION"

        private const val TAG_DESTINATION_DIALOG = "REMOTEMEDIA_DESTINATION_DIALOG"
        private const val TAG_INFO_DIALOG = "REMOTEMEDIA_INFO_DIALOG"

        @JvmStatic
        fun newInstance(media: List<NCShareViewModel.RemotePhoto>, position: Int, albumId: String) = RemoteMediaFragment().apply {
            arguments = Bundle().apply {
                putParcelableArray(KEY_REMOTE_MEDIA, media.toTypedArray())
                putInt(KEY_SCROLL_TO, position)
                putString(KEY_ALBUM_ID, albumId)
            }
        }
    }
}