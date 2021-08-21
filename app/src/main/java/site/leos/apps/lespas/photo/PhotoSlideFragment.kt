package site.leos.apps.lespas.photo

import android.content.*
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.SharedElementCallback
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import androidx.work.*
import com.google.android.material.transition.MaterialContainerTransform
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.Album
import site.leos.apps.lespas.album.AlbumViewModel
import site.leos.apps.lespas.helper.*
import site.leos.apps.lespas.sync.ActionViewModel
import java.io.File

class PhotoSlideFragment : Fragment() {
    private lateinit var album: Album
    private lateinit var slider: ViewPager2
    private lateinit var pAdapter: PhotoSlideAdapter
    private val albumModel: AlbumViewModel by activityViewModels()
    private val actionModel: ActionViewModel by viewModels()
    private val imageLoaderModel: ImageLoaderViewModel by activityViewModels()
    private val currentPhotoModel: CurrentPhotoViewModel by activityViewModels()
    private val uiModel: UIViewModel by activityViewModels()
    private var autoRotate = false
    private var previousNavBarColor = 0
    private var previousOrientationSetting = 0
    private var viewReCreated = false

    private lateinit var snapseedCatcher: BroadcastReceiver
    private lateinit var snapseedOutputObserver: ContentObserver

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        album = arguments?.getParcelable(KEY_ALBUM)!!

        pAdapter = PhotoSlideAdapter(
            Tools.getLocalRoot(requireContext()),
            { state-> uiModel.toggleOnOff(state) },
            { photo, imageView, type -> imageLoaderModel.loadPhoto(photo, imageView, type) { startPostponedEnterTransition() }},
            { view -> imageLoaderModel.cancelLoading(view as ImageView) }
        ).apply { stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY }

        // Adjusting the shared element mapping
        setEnterSharedElementCallback(object : SharedElementCallback() {
            override fun onMapSharedElements(names: MutableList<String>?, sharedElements: MutableMap<String, View>?) {
                try {
                    sharedElements?.put(names?.get(0)!!, slider.getChildAt(0).findViewById(R.id.media))
                } catch (e: IndexOutOfBoundsException) { e.printStackTrace() }
            }
        })

        previousOrientationSetting = requireActivity().requestedOrientation
        autoRotate = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(context?.getString(R.string.auto_rotate_perf_key), false)

        savedInstanceState?.getParcelable<MediaSliderAdapter.PlayerState>(PLAYER_STATE)?.apply {
            pAdapter.setPlayerState(this)
            pAdapter.setAutoStart(true)
        }

        // Broadcast receiver listening on share destination
        snapseedCatcher = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent!!.getParcelableExtra<ComponentName>(Intent.EXTRA_CHOSEN_COMPONENT)?.packageName!!.substringAfterLast('.') == "snapseed") {
                    // Shared to Snapseed. Register content observer if we have storage permission and integration with snapseed option is on
                    if (ContextCompat.checkSelfPermission(context!!, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) android.Manifest.permission.READ_EXTERNAL_STORAGE else android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(getString(R.string.snapseed_pref_key), false)) {
                        context.contentResolver.apply {
                            unregisterContentObserver(snapseedOutputObserver)
                            registerContentObserver(
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                true,
                                snapseedOutputObserver
                            )
                        }
                    }
                }
            }
        }
        context?.registerReceiver(snapseedCatcher, IntentFilter(CHOOSER_SPY_ACTION))

        // Content observer looking for Snapseed output
        snapseedOutputObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            private val workerName = "${PhotoSlideFragment::class.java.canonicalName}.SNAPSEED_WORKER"
            private var lastId = ""
            private lateinit var snapseedWorker: OneTimeWorkRequest

            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)

                // ContentObserver got called twice, once for itself, once for it's descendant, all with same last path segment
                if (uri?.lastPathSegment!! != lastId) {
                    lastId = uri.lastPathSegment!!

                    snapseedWorker = OneTimeWorkRequestBuilder<SnapseedResultWorker>().setInputData(
                        // TODO publish status is not persistent locally
                        //workDataOf(SnapseedResultWorker.KEY_IMAGE_URI to uri.toString(), SnapseedResultWorker.KEY_SHARED_PHOTO to pAdapter.getPhotoAt(slider.currentItem).id, SnapseedResultWorker.KEY_ALBUM to album.id, SnapseedResultWorker.KEY_PUBLISHED to publishModel.isShared(album.id))).build()
                        workDataOf(SnapseedResultWorker.KEY_IMAGE_URI to uri.toString(), SnapseedResultWorker.KEY_SHARED_PHOTO to pAdapter.getPhotoAt(slider.currentItem).id, SnapseedResultWorker.KEY_ALBUM to album.id)).build()
                    WorkManager.getInstance(requireContext()).enqueueUniqueWork(workerName, ExistingWorkPolicy.KEEP, snapseedWorker)

                    WorkManager.getInstance(requireContext()).getWorkInfosForUniqueWorkLiveData(workerName).observe(parentFragmentManager.findFragmentById(R.id.container_root)!!, { workInfo->
                        if (workInfo != null) {
                            workInfo[0]?.progress?.getString(SnapseedResultWorker.KEY_NEW_PHOTO_NAME)?.apply {
                                if (PreferenceManager.getDefaultSharedPreferences(requireContext()).getBoolean(requireContext().getString(R.string.snapseed_replace_pref_key), false)) {
                                    pAdapter.getPhotoAt(slider.currentItem).let {
                                        imageLoaderModel.invalid(it.id)
                                        pAdapter.refreshPhoto(it)
                                    }
                                }
                                currentPhotoModel.setCurrentPhotoName(this)
                            }
                        }
                    })
                }

                requireContext().contentResolver.unregisterContentObserver(this)
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.fragment_photoslide, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewReCreated = true

        postponeEnterTransition()

        slider = view.findViewById<ViewPager2>(R.id.pager).apply {
            adapter = pAdapter

            // Use reflection to reduce Viewpager2 slide sensitivity, so that PhotoView inside can zoom presently
            val recyclerView = (ViewPager2::class.java.getDeclaredField("mRecyclerView").apply{ isAccessible = true }).get(this) as RecyclerView
            (RecyclerView::class.java.getDeclaredField("mTouchSlop")).apply {
                isAccessible = true
                set(recyclerView, (get(recyclerView) as Int) * 4)
            }

            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    super.onPageSelected(position)

                    pAdapter.getPhotoAt(position).run {
                        currentPhotoModel.setCurrentPhoto(this, position + 1)
                        if (autoRotate) requireActivity().requestedOrientation = if (this.width > this.height) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    }
                }
            })
        }

        sharedElementEnterTransition = MaterialContainerTransform().apply {
            duration = resources.getInteger(android.R.integer.config_mediumAnimTime).toLong()
            scrimColor = Color.TRANSPARENT
            fadeMode = MaterialContainerTransform.FADE_MODE_CROSS
        }.also {
            // Prevent ViewPager from showing content before transition finished, without this, Android 11 will show it right at the beginning
            // Also we can transit to video thumbnail before player start playing
            it.addListener(MediaSliderTransitionListener(slider))
        }

        albumModel.getAllPhotoInAlbum(album.id).observe(viewLifecycleOwner, { photos->
            pAdapter.setPhotos(photos, album.sortOrder)
            currentPhotoModel.getCurrentPhotoId()?.let {
                //imageLoaderModel.invalid(it)
                slider.setCurrentItem(pAdapter.findPhotoPosition(it), false)
            }
        })

        currentPhotoModel.getRemoveItem().observe(viewLifecycleOwner, { deleteItem->
            deleteItem?.run {
                pAdapter.getNextAvailablePhoto(deleteItem).apply {
                    this.first?.let { photo->
                        currentPhotoModel.setCurrentPhoto(photo, this.second)
                        // TODO publish status is not persistent locally
                        //actionModel.deletePhotos(listOf(deleteItem), album.name, publishModel.isShared(album.id))
                        actionModel.deletePhotos(listOf(deleteItem), album.name)
                        slider.beginFakeDrag()
                        slider.fakeDragBy(-1f)
                        slider.endFakeDrag()
                    }
                    ?: run {
                        // TODO this seems never happen since user can't delete cover, so there is at least 1 photo in an album
                        parentFragmentManager.popBackStack()
                    }
                }
            }
        })

        // Setup basic UI here because BottomControlsFragment might be replaced by CoverSettingFragment
        (requireActivity() as AppCompatActivity).supportActionBar!!.hide()
        requireActivity().window.run {
            previousNavBarColor = navigationBarColor
            navigationBarColor = Color.TRANSPARENT
/*
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insetsController?.let {
                    it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    it.hide(WindowInsets.Type.systemBars())
                }
                statusBarColor = Color.TRANSPARENT
                setDecorFitsSystemWindows(false)
            } else {
                @Suppress("DEPRECATION")
                decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE
                        // Set the content to appear under the system bars so that the
                        // content doesn't resize when the system bars hide and show.
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        // Hide the nav bar and status bar
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN)
            }
*/
            statusBarColor = Color.TRANSPARENT
            @Suppress("DEPRECATION")
            decorView.systemUiVisibility = (
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
        }
    }

    override fun onStart() {
        super.onStart()
        pAdapter.initializePlayer(requireContext(), null)
    }

    override fun onResume() {
        super.onResume()
        requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        (slider.getChildAt(0) as RecyclerView).findViewHolderForAdapterPosition(slider.currentItem)?.apply {
            if (!viewReCreated && pAdapter.currentList[slider.currentItem].mimeType.startsWith("video")) {
                (this as MediaSliderAdapter<*>.VideoViewHolder).apply {
                    pAdapter.setAutoStart(true)
                    resume()
                }
            }
        }
    }

    override fun onPause() {
        requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)

        (slider.getChildAt(0) as RecyclerView).findViewHolderForAdapterPosition(slider.currentItem).apply {
            if (this is MediaSliderAdapter<*>.VideoViewHolder) this.pause()
        }

        viewReCreated = false

        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putParcelable(PLAYER_STATE, pAdapter.getPlayerState())
    }

    override fun onStop() {
        pAdapter.cleanUp()
        super.onStop()
    }

    override fun onDestroy() {
        requireActivity().window.navigationBarColor = previousNavBarColor

        (requireActivity() as AppCompatActivity).run {
            supportActionBar!!.show()
            requestedOrientation = previousOrientationSetting
        }

        requireContext().apply {
            unregisterReceiver(snapseedCatcher)
            contentResolver.unregisterContentObserver(snapseedOutputObserver)
        }

        currentPhotoModel.clearRemoveItem()
        uiModel.toggleOnOff(false)

        super.onDestroy()
    }

    class PhotoSlideAdapter(private val rootPath: String, val clickListener: (Boolean?) -> Unit, val imageLoader: (Photo, ImageView, String) -> Unit, val cancelLoader: (View) -> Unit
    ): MediaSliderAdapter<Photo>(PhotoDiffCallback(), clickListener, imageLoader, cancelLoader) {
        override fun getVideoItem(position: Int): VideoItem = with(getItem(position) as Photo) {
            var fileName = "$rootPath/${id}"
            if (!(File(fileName).exists())) fileName = "$rootPath/${name}"
            VideoItem(Uri.fromFile(File(fileName)), mimeType, width, height, id)
        }
        override fun getItemTransitionName(position: Int): String = (getItem(position) as Photo).id
        override fun getItemMimeType(position: Int): String = (getItem(position) as Photo).mimeType

        fun setPhotos(collection: List<Photo>, sortOrder: Int) {
            val photos = when(sortOrder) {
                Album.BY_DATE_TAKEN_ASC-> collection.sortedWith(compareBy { it.dateTaken })
                Album.BY_DATE_TAKEN_DESC-> collection.sortedWith(compareByDescending { it.dateTaken })
                Album.BY_DATE_MODIFIED_ASC-> collection.sortedWith(compareBy { it.lastModified })
                Album.BY_DATE_MODIFIED_DESC-> collection.sortedWith(compareByDescending { it.lastModified })
                Album.BY_NAME_ASC-> collection.sortedWith(compareBy { it.name })
                Album.BY_NAME_DESC-> collection.sortedWith(compareByDescending { it.name })
                else-> collection
            }

            submitList(photos.toMutableList())
        }
        fun refreshPhoto(photo: Photo) { notifyItemChanged(findPhotoPosition(photo.id)) }
        fun findPhotoPosition(photoId: String): Int {
            with(currentList as List<Photo>) {
                // If photo synced back from server, the id property will be changed from filename to fileId
                forEachIndexed { index, photo -> if (photo.id == photoId || photo.name == photoId) return index }
            }
            return -1
        }
        fun getPhotoAt(position: Int): Photo = currentList[position] as Photo
        fun getNextAvailablePhoto(photo: Photo): Pair<Photo?, Int> {
            with((currentList as List<Photo>).indexOf(photo)) {
                return when(this) {
                    -1-> Pair(null, -1)
                    currentList.size - 1-> if (this > 0) Pair(currentList[this - 1] as Photo, this - 1) else Pair(null, -1)
                    else-> Pair(currentList[this + 1] as Photo, this)
                }
            }
        }
    }

    class PhotoDiffCallback: DiffUtil.ItemCallback<Photo>() {
        override fun areItemsTheSame(oldItem: Photo, newItem: Photo): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Photo, newItem: Photo): Boolean = oldItem.lastModified == newItem.lastModified
    }

    // Share current photo within this fragment and BottomControlsFragment and CropCoverFragment
    class CurrentPhotoViewModel : ViewModel() {
        // AlbumDetail fragment grid item positions, this is for AlbumDetailFragment, nothing to do with other fragments
        private var currentPosition = 0
        private var lastPosition = 0
        fun getCurrentPosition(): Int = currentPosition
        fun setLastPosition(position: Int) { lastPosition = position }
        fun getLastPosition(): Int = lastPosition

        // Current photo shared with CoverSetting and BottomControl fragments by PhotoSlider
        private val photo = MutableLiveData<Photo>()
        private val coverApplyStatus = MutableLiveData<Boolean>()
        private var forReal = false     // TODO Dirty hack, should be SingleLiveEvent
        fun getCurrentPhoto(): LiveData<Photo> { return photo }
        fun getCurrentPhotoId(): String? = photo.value?.id
        fun setCurrentPhotoName(newName: String) {
            photo.value?.name = newName
            photo.value?.eTag = ""
        }
        fun setCurrentPhoto(newPhoto: Photo, position: Int) {
            //photo.postValue(newPhoto)
            photo.value = newPhoto
            currentPosition = position
        }
        fun coverApplied(applied: Boolean) {
            coverApplyStatus.value = applied
            forReal = true
        }
        fun getCoverAppliedStatus(): LiveData<Boolean> { return coverApplyStatus }
        fun forReal(): Boolean {
            val r = forReal
            forReal = false
            return r
        }

        // For removing photo
        private val removeItem = MutableLiveData<Photo?>()
        fun removePhoto() { removeItem.value = photo.value }
        fun getRemoveItem(): LiveData<Photo?> { return removeItem }
        fun clearRemoveItem() { removeItem.value = null }
    }

    // Share system ui visibility status with BottomControlsFragment
    class UIViewModel : ViewModel() {
        private val showUI = MutableLiveData(false)

        fun toggleOnOff(state: Boolean?) { state?.let { if (state != showUI.value) showUI.value = state } ?: run { showUI.value = !showUI.value!! }}
        fun status(): LiveData<Boolean> { return showUI }
    }

    companion object {
        private const val PLAYER_STATE = "PLAYER_STATE"
        const val CHOOSER_SPY_ACTION = "site.leos.apps.lespas.CHOOSER_PHOTOSLIDER"
        const val KEY_ALBUM = "ALBUM"

        @JvmStatic
        fun newInstance(album: Album) = PhotoSlideFragment().apply { arguments = Bundle().apply { putParcelable(KEY_ALBUM, album) }}
    }
}
