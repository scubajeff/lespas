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

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.core.app.SharedElementCallback
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.Slide
import androidx.transition.TransitionManager
import androidx.viewpager2.widget.ViewPager2
import com.github.chrisbanes.photoview.PhotoView
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.transition.MaterialContainerTransform
import com.panoramagl.PLManager
import com.panoramagl.PLSphericalPanorama
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.Album
import site.leos.apps.lespas.helper.MediaSliderTransitionListener
import site.leos.apps.lespas.helper.MetaDataDialogFragment
import site.leos.apps.lespas.helper.SeamlessMediaSliderAdapter
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.helper.Tools.parcelable
import site.leos.apps.lespas.helper.Tools.parcelableArray
import site.leos.apps.lespas.helper.Tools.parcelableArrayList
import site.leos.apps.lespas.helper.VideoPlayerViewModel
import site.leos.apps.lespas.helper.VideoPlayerViewModelFactory
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.sync.Action
import site.leos.apps.lespas.sync.ActionViewModel
import site.leos.apps.lespas.sync.DestinationDialogFragment
import java.time.LocalDateTime
import java.time.ZoneOffset

class RemoteMediaFragment: Fragment() {
    private lateinit var window: Window
    private lateinit var controlsContainer: LinearLayoutCompat
    private lateinit var slider: ViewPager2
    private lateinit var pAdapter: RemoteMediaAdapter
    private lateinit var captionTextView: TextView
    private lateinit var dividerView: View

    private val shareModel: NCShareViewModel by activityViewModels()
    private val currentPositionModel: PublicationDetailFragment.CurrentPublicationViewModel by activityViewModels()
    private val playerViewModel: VideoPlayerViewModel by viewModels { VideoPlayerViewModelFactory(requireActivity(), shareModel.getCallFactory(), shareModel.getPlayerCache(), shareModel.getSavedSystemVolume(), shareModel.getSessionVolumePercentage()) }

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
            { state-> toggleBottomControls(state) },
            { media, imageView, type -> if (type == NCShareViewModel.TYPE_NULL) startPostponedEnterTransition() else shareModel.setImagePhoto(media, imageView!!, type) { startPostponedEnterTransition() }},
            { media, imageView, plManager, panorama -> shareModel.setImagePhoto(media, imageView!!, NCShareViewModel.TYPE_PANORAMA, plManager, panorama) { startPostponedEnterTransition() }},
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

        accessMediaLocationPermissionRequestLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
        storagePermissionRequestLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted->
            requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

            if (isGranted) {
                // Explicitly request ACCESS_MEDIA_LOCATION permission
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) accessMediaLocationPermissionRequestLauncher.launch(android.Manifest.permission.ACCESS_MEDIA_LOCATION)

                saveMedia()
            }
        }

        previousOrientationSetting = requireActivity().requestedOrientation
        autoRotate = PreferenceManager.getDefaultSharedPreferences(requireContext()).getBoolean(requireContext().getString(R.string.auto_rotate_perf_key), false)

        this.window = requireActivity().window
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        (activity as AppCompatActivity).supportActionBar?.hide()
        Tools.setImmersive(window, true)

        return inflater.inflate(R.layout.fragment_remote_media, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        postponeEnterTransition()

        captionTextView = view.findViewById<TextView>(R.id.caption).apply { movementMethod =  ScrollingMovementMethod() }
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
                    if (state == ViewPager2.SCROLL_STATE_SETTLING) handler.post(hideBottomControls)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && state == ViewPager2.SCROLL_STATE_IDLE) slider.getChildAt(0)?.findViewById<View>(R.id.media)?.apply {
                        window.colorMode = if (this is PhotoView && getTag(R.id.HDR_TAG) as Boolean? == true) ActivityInfo.COLOR_MODE_HDR else ActivityInfo.COLOR_MODE_DEFAULT
                    }
                }

                override fun onPageSelected(position: Int) {
                    super.onPageSelected(position)
                    currentPositionModel.setCurrentPosition(position)
                    captionTextView.text = pAdapter.getCaption(position)
                    if (autoRotate) requireActivity().requestedOrientation = if (pAdapter.isLandscape(position)) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
            })
        }

        controlsContainer = view.findViewById(R.id.bottom_controls_container)
        ViewCompat.setOnApplyWindowInsetsListener(controlsContainer) { v, insets->
            @Suppress("DEPRECATION")
            if (insets.isVisible(WindowInsetsCompat.Type.navigationBars()) || window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION == 0) {
                val systemBar = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                val displayCutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
                v.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    bottomMargin = systemBar.bottom
                    rightMargin = systemBar.right + displayCutout.right
                    leftMargin = systemBar.left + displayCutout.left
                }
            }
            insets
        }

        view.findViewById<Button>(R.id.download_button).run {
            //setOnTouchListener(delayHideTouchListener)
            setOnClickListener {
                handler.post(hideBottomControls)
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    requireActivity().requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED

                    storagePermissionRequestLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
                else saveMedia()
            }
        }
        view.findViewById<Button>(R.id.lespas_button).run {
            //setOnTouchListener(delayHideTouchListener)
            setOnClickListener {
                handler.post(hideBottomControls)
                if (parentFragmentManager.findFragmentByTag(TAG_DESTINATION_DIALOG) == null)
                    DestinationDialogFragment.newInstance(DESTINATION_DIALOG_REQUEST_KEY, arrayListOf(pAdapter.currentList[currentPositionModel.currentPosition.value]), albumId, false).show(parentFragmentManager, TAG_DESTINATION_DIALOG)
            }
        }
        view.findViewById<Button>(R.id.info_button).run {
            //setOnTouchListener(delayHideTouchListener)
            setOnClickListener {
                handler.post(hideBottomControls)
                if (parentFragmentManager.findFragmentByTag(TAG_INFO_DIALOG) == null) {
                    MetaDataDialogFragment.newInstance(pAdapter.currentList[currentPositionModel.currentPosition.value]).show(parentFragmentManager, TAG_INFO_DIALOG)
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

        (requireArguments().parcelableArray<NCShareViewModel.RemotePhoto>(KEY_REMOTE_MEDIA)!!).run {
            pAdapter.submitList(toMutableList()) {
                requireArguments().getInt(KEY_SCROLL_TO).let { jumpTo ->
                    savedInstanceState ?: run { slider.setCurrentItem(jumpTo, false) }
                    currentPositionModel.setCurrentPosition(jumpTo)
                    captionTextView.text = pAdapter.getCaption(jumpTo)
                }
            }
        }

        // Destination dialog result handler
        parentFragmentManager.setFragmentResultListener(DESTINATION_DIALOG_REQUEST_KEY, viewLifecycleOwner) { _, result ->
            result.parcelable<Album>(DestinationDialogFragment.KEY_TARGET_ALBUM)?.let { targetAlbum ->
                val targetIsRemoteAlbum = Tools.isRemoteAlbum(targetAlbum)
                result.parcelableArrayList<NCShareViewModel.RemotePhoto>(DestinationDialogFragment.KEY_REMOTE_PHOTOS)?.get(0)?.let { remotePhoto ->
                    val actionModel = ViewModelProvider(requireActivity())[ActionViewModel::class.java]
                    val actions = mutableListOf<Action>()
                    val metaString = remotePhoto.photo.let { photo -> "${targetAlbum.eTag}|${photo.dateTaken.toInstant(ZoneOffset.UTC).toEpochMilli()}|${photo.mimeType}|${photo.width}|${photo.height}|${photo.orientation}|${photo.caption}|${photo.latitude}|${photo.longitude}|${photo.altitude}|${photo.bearing}" }
                    if (targetAlbum.id == Album.JOINT_ALBUM_ID) {
                        targetAlbum.coverFileName.substringBeforeLast('/').let { targetFolder ->
                            actions.add(Action(null, Action.ACTION_COPY_ON_SERVER, remotePhoto.remotePath,
                                targetFolder,
                                metaString,
                                "${remotePhoto.photo.name}|true|true",
                                System.currentTimeMillis(), 1
                            ))

                            // Target album is Joint Album, update it's content metadata file
                            actions.add(Action(null, Action.ACTION_UPDATE_JOINT_ALBUM_PHOTO_META, targetAlbum.eTag, targetFolder, "", "", System.currentTimeMillis(), 1))
                        }
                    } else {
                        val targetAlbumId = targetAlbum.id.ifEmpty { System.currentTimeMillis().toString() }

                        // Target album is own album, create new records in local DB now, ACTION_COPY_ON_SERVER taken in SyncAdapter will fill in the correct fileId and eTag later
                        actionModel.addPhotosAtLocal(mutableListOf<Photo>().apply { add(remotePhoto.photo.copy(id = remotePhoto.photo.name, albumId = targetAlbumId, eTag = Photo.ETAG_NOT_YET_UPLOADED, lastModified = LocalDateTime.now())) })

                        if (targetAlbum.id.isEmpty()) {
                            // Current system time as fake ID for new album
                            with(remotePhoto.photo) {
                                targetAlbum.coverBaseline = if (mimeType == "image/jpeg" || mimeType == "image/png") (height - (width * 9 / 21)) / 2 else Album.SPECIAL_COVER_BASELINE
                                targetAlbum.coverWidth = width
                                targetAlbum.coverHeight = height
                                targetAlbum.cover = name
                                targetAlbum.coverFileName = name
                                targetAlbum.coverMimeType = mimeType
                                targetAlbum.coverOrientation = orientation
                                targetAlbum.sortOrder = PreferenceManager.getDefaultSharedPreferences(requireContext()).getString(getString(R.string.default_sort_order_pref_key), "0")?.toInt() ?: Album.BY_DATE_TAKEN_ASC
                                targetAlbum.startDate = dateTaken
                                targetAlbum.endDate = dateTaken
                                targetAlbum.id = targetAlbumId
                            }
                            // Create new album first, since this whole operations will be carried out on server, we don't have to worry about cover here, SyncAdapter will handle all the rest during next sync
                            actions.add(Action(null, Action.ACTION_ADD_DIRECTORY_ON_SERVER, targetAlbumId, targetAlbum.name, "", remotePhoto.photo.name, System.currentTimeMillis(), 1))
                            // Create album record in local DB now
                            actionModel.addAlbumAtLocal(targetAlbum)
                        }

                        actions.add(Action(null, Action.ACTION_COPY_ON_SERVER, remotePhoto.remotePath,
                            "${Tools.getRemoteHome(requireContext())}/${targetAlbum.name}",
                            metaString,
                            "${remotePhoto.photo.name}|false|${targetIsRemoteAlbum}",
                            System.currentTimeMillis(), 1
                        ))

                        actions.add(Action(null, Action.ACTION_UPDATE_THIS_CONTENT_META, "", targetAlbum.name, "", "", System.currentTimeMillis(), 1))
                        actions.add(Action(null, Action.ACTION_UPDATE_THIS_ALBUM_META, "", targetAlbum.name, "", "", System.currentTimeMillis(), 1))
                    }

                    actionModel.addActions(actions)
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
        try {
            if (pAdapter.getPhotoAt(slider.currentItem).mimeType.startsWith("video")) handler.postDelayed({ playerViewModel.pause(Uri.EMPTY) }, 300)
        } catch (_: IndexOutOfBoundsException) {}
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_DISPLAY_OPTION, previousTitleBarDisplayOption)

        pAdapter.setPauseVideo(false)
    }

    override fun onDestroyView() {
        shareModel.saveSessionVolumePercentage(playerViewModel.getVolume())

        handler.removeCallbacksAndMessages(null)
        slider.adapter = null

        // Quit immersive
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            @Suppress("DEPRECATION")
            window.decorView.setOnSystemUiVisibilityChangeListener(null)
        }
        Tools.setImmersive(window, false)
        (requireActivity() as AppCompatActivity).apply {
            requestedOrientation = previousOrientationSetting
            supportActionBar?.show()
        }

        super.onDestroyView()
    }

    private fun toggleBottomControls(state: Boolean?) {
        handler.removeCallbacksAndMessages(null)
        handler.post(if (state ?: !controlsContainer.isVisible) showBottomControls else hideBottomControls)
    }

    private val hideBottomControls = Runnable {
        WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())

        TransitionManager.beginDelayedTransition(controlsContainer, Slide(Gravity.BOTTOM).apply { duration = 200 })
        controlsContainer.isVisible = false
        handler.removeCallbacksAndMessages(null)
    }
    private val showBottomControls = Runnable {
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())

        TransitionManager.beginDelayedTransition(controlsContainer, Slide(Gravity.BOTTOM).apply { duration = 200 })
        controlsContainer.isVisible = true
        captionTextView.text.isNotEmpty().let { hasCaption ->
            captionTextView.isVisible = hasCaption
            dividerView.isVisible = hasCaption

            // Trigger auto hide only if there is no caption
            if (!hasCaption) handler.postDelayed(hideBottomControls, AUTO_HIDE_DELAY_MILLIS)
        }
    }

/*
    // Delay hiding the system UI while interacting with controls, preventing the jarring behavior of controls going away
    @SuppressLint("ClickableViewAccessibility")
    private val delayHideTouchListener = View.OnTouchListener { _, _ ->
        handler.removeCallbacks(hideSystemUI)
        handler.postDelayed(hideSystemUI, AUTO_HIDE_DELAY_MILLIS)
        false
    }
*/

    private fun saveMedia() {
        pAdapter.currentList[currentPositionModel.currentPosition.value].apply {
            shareModel.savePhoto(requireContext(), listOf(this))
            Snackbar.make(requireView(), getString(R.string.msg_saved_location), Snackbar.LENGTH_LONG).show()
        }
    }

    class RemoteMediaAdapter(context: Context, displayWidth: Int, private val basePath: String, playerViewModel: VideoPlayerViewModel,
         clickListener: (Boolean?) -> Unit, imageLoader: (NCShareViewModel.RemotePhoto, ImageView?, type: String) -> Unit, panoLoader: (NCShareViewModel.RemotePhoto, ImageView?, PLManager, PLSphericalPanorama) -> Unit, cancelLoader: (View) -> Unit
    ): SeamlessMediaSliderAdapter<NCShareViewModel.RemotePhoto>(context, displayWidth, PhotoDiffCallback(), playerViewModel, clickListener, imageLoader, panoLoader, cancelLoader) {
        override fun getVideoItem(position: Int): VideoItem = with(getItem(position)) { VideoItem("$basePath$remotePath/${photo.name}".toUri(), photo.mimeType, photo.width, photo.height, photo.id) }
        override fun getItemTransitionName(position: Int): String = getItem(position).photo.id
        override fun getItemMimeType(position: Int): String = getItem(position).photo.mimeType
        override fun isMotionPhoto(position: Int): Boolean = Tools.isMotionPhoto(getItem(position).photo.shareId)

        fun getPhotoAt(position: Int): Photo = currentList[position].photo
        fun getCaption(position: Int): String = try { getItem(position).photo.caption } catch (_: Exception) { "" }
        fun isLandscape(position: Int): Boolean = try { getItem(position).photo.width >= getItem(position).photo.height } catch (_: Exception) { false }
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

        private const val TAG_DESTINATION_DIALOG = "REMOTE_MEDIA_DESTINATION_DIALOG"
        private const val TAG_INFO_DIALOG = "REMOTE_MEDIA_INFO_DIALOG"
        private const val DESTINATION_DIALOG_REQUEST_KEY = "REMOTE_MEDIA_FRAGMENT_DESTINATION_DIALOG_REQUEST_KEY"

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