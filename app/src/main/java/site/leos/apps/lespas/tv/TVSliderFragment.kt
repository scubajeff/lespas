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

package site.leos.apps.lespas.tv

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.ColorMatrixColorFilter
import android.icu.text.BreakIterator
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TableRow
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.animation.doOnCancel
import androidx.core.animation.doOnEnd
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.exifinterface.media.ExifInterface
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.setFragmentResult
import androidx.leanback.widget.BaseGridView
import androidx.leanback.widget.HorizontalGridView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.Slide
import androidx.transition.Transition
import androidx.transition.TransitionManager
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.snackbar.Snackbar
import com.panoramagl.PLManager
import com.panoramagl.PLSphericalPanorama
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.bonuspack.location.GeocoderNominatim
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import site.leos.apps.lespas.BuildConfig
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.Album
import site.leos.apps.lespas.album.AlbumViewModel
import site.leos.apps.lespas.album.BGMDialogFragment
import site.leos.apps.lespas.helper.MetaDataDialogFragment.PhotoMeta
import site.leos.apps.lespas.helper.SeamlessMediaSliderAdapter
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.helper.Tools.parcelable
import site.leos.apps.lespas.helper.VideoPlayerViewModel
import site.leos.apps.lespas.helper.VideoPlayerViewModelFactory
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.photo.PhotoRepository
import site.leos.apps.lespas.publication.NCShareViewModel
import site.leos.apps.lespas.story.BGMViewModel
import site.leos.apps.lespas.story.BGMViewModelFactory
import site.leos.apps.lespas.sync.SyncAdapter
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class TVSliderFragment: Fragment() {
    private lateinit var mediaAdapter: MediaAdapter
    private lateinit var slider: ViewPager2

    private lateinit var exitSnackbar: Snackbar

    private lateinit var captionPage: ConstraintLayout
    private lateinit var tvCaption: TextView
    private lateinit var captionHint: ImageView

    private lateinit var fastScroller: HorizontalGridView
    private lateinit var fastScrollAdapter: FastScrollAdapter

    private lateinit var mapView: MapView
    private lateinit var tvLocality: TextView

    private lateinit var metaPage: ConstraintLayout
    private lateinit var tvName: TextView
    private lateinit var tvDate: TextView
    private lateinit var tvSize: TextView
    private lateinit var tvMfg: TextView
    private lateinit var tvModel: TextView
    private lateinit var tvParam: TextView
    private lateinit var tvArtist: TextView
    private lateinit var trSize: TableRow
    private lateinit var trMfg: TableRow
    private lateinit var trModel: TableRow
    private lateinit var trParam: TableRow
    private lateinit var trArtist: TableRow

    private val albumModel: AlbumViewModel by activityViewModels()
    private val imageLoaderModel: NCShareViewModel by activityViewModels()
    private lateinit var playerViewModel: VideoPlayerViewModel
    private var bgmModel: BGMViewModel? = null

    private val handler = Handler(Looper.getMainLooper())
    private val metaDisplayThread = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
    private var captionAnimationJob: Job? = null
    private val wordIterator: BreakIterator = BreakIterator.getWordInstance(Locale.getDefault())
    private var captionHintingAnimation = AnimatorSet()
    private var isSortedByDate = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        playerViewModel = ViewModelProvider(this, VideoPlayerViewModelFactory(requireActivity(), imageLoaderModel.getCallFactory(), imageLoaderModel.getPlayerCache(), imageLoaderModel.getSessionVolumePercentage()))[VideoPlayerViewModel::class.java]

        mediaAdapter = MediaAdapter(
            requireContext(),
            Tools.getDisplayDimension(requireActivity()).first,
            imageLoaderModel.getResourceRoot(),
            playerViewModel,
            { on -> },
            { media, imageView, type -> if (type != NCShareViewModel.TYPE_NULL) imageLoaderModel.setImagePhoto(media, imageView!!, NCShareViewModel.TYPE_FULL) },
            { media, imageView, plManager, panorama -> imageLoaderModel.setImagePhoto(media, imageView!!, NCShareViewModel.TYPE_PANORAMA, plManager, panorama) },
            { view -> imageLoaderModel.cancelSetImagePhoto(view) },
            { delta ->
                if (!metaPage.isVisible) {
                    hideCaptionPage()
                    // Use fake drag to move back and forth
                    slider.beginFakeDrag()
                    slider.fakeDragBy(delta * slider.width)
                    slider.endFakeDrag()
                }
            },
            { media -> toggleMeta(media, metaPage.isVisible) },
            { state -> toggleCaption(state) },
            { showFastScroller() },
            {},
        )

        fastScrollAdapter = FastScrollAdapter(
            { remotePhoto, view -> imageLoaderModel.setImagePhoto(remotePhoto, view, NCShareViewModel.TYPE_GRID)},
            { view -> imageLoaderModel.cancelSetImagePhoto(view) }
        )

        var isShared = false
        var sharedPath = ""
        requireArguments().parcelable<NCShareViewModel.ShareWithMe>(KEY_SHARED)?.let { shared ->
             sharedPath = shared.sharePath
            lifecycleScope.launch(Dispatchers.IO) { imageLoaderModel.getRemotePhotoList(shared, true) }
            isShared = true
        }
        setFragmentResult(RESULT_REQUEST_KEY, bundleOf(KEY_SHARED to isShared))

        requireActivity().onBackPressedDispatcher.addCallback(this, object: OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                when {
                    metaPage.isVisible -> toggleMeta(NCShareViewModel.RemotePhoto(Photo(dateTaken = LocalDateTime.MIN, lastModified = LocalDateTime.MIN)), true)
                    captionPage.isVisible -> hideCaptionPage()
                    fastScroller.isVisible -> hideFastScroller()
                    exitSnackbar.isShown -> {
                        exitSnackbar.dismiss()
                        parentFragmentManager.popBackStack()
                    }
                    else -> {
                        exitSnackbar.view.run {
                            layoutParams = (layoutParams as FrameLayout.LayoutParams).apply {
                                gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
                                bottomMargin = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, resources.displayMetrics).roundToInt()
                                width = FrameLayout.LayoutParams.WRAP_CONTENT
                            }
                        }
                        exitSnackbar.show()
                    }
                }
            }
        }.apply { isEnabled = true })

        lifecycleScope.launch(Dispatchers.IO) {
            (if (isShared) imageLoaderModel.isExisted("${imageLoaderModel.getResourceRoot()}${sharedPath}/${SyncAdapter.BGM_FILENAME_ON_SERVER}")
            else File("${Tools.getLocalRoot(requireContext())}/${requireArguments().parcelable<Album>(KEY_ALBUM)!!.id}${BGMDialogFragment.BGM_FILE_SUFFIX}").exists()).let { exist ->
                if (exist) withContext(Dispatchers.Main) {
                    bgmModel = ViewModelProvider(
                        this@TVSliderFragment,
                        BGMViewModelFactory(
                            requireActivity(), imageLoaderModel.getCallFactory(),
                            if (isShared) "${imageLoaderModel.getResourceRoot()}${sharedPath}/${SyncAdapter.BGM_FILENAME_ON_SERVER}"
                            else "file://${Tools.getLocalRoot(requireContext())}/${requireArguments().parcelable<Album>(KEY_ALBUM)!!.id}${BGMDialogFragment.BGM_FILE_SUFFIX}"
                            )
                    )[BGMViewModel::class.java]
                }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        (activity as AppCompatActivity).supportActionBar?.hide()
        Tools.setImmersive(requireActivity().window, true)

        return inflater.inflate(R.layout.fragment_tv_slider, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        metaPage = view.findViewById(R.id.info_page)
        tvName = view.findViewById(R.id.info_filename)
        tvDate = view.findViewById(R.id.info_shotat)
        tvSize = view.findViewById(R.id.info_size)
        tvMfg = view.findViewById(R.id.info_camera_mfg)
        tvModel = view.findViewById(R.id.info_camera_model)
        tvParam = view.findViewById(R.id.info_parameter)
        tvArtist = view.findViewById(R.id.info_artist)
        trSize = view.findViewById(R.id.size_row)
        trMfg = view.findViewById(R.id.mfg_row)
        trModel = view.findViewById(R.id.model_row)
        trParam = view.findViewById(R.id.param_row)
        trArtist = view.findViewById(R.id.artist_row)

        mapView = view.findViewById<MapView>(R.id.map).apply {
            setMultiTouchControls(false)
            setUseDataConnection(true)
            setTileSource(TileSourceFactory.MAPNIK)
            isFlingEnabled = false
            overlays.add(CopyrightOverlay(requireContext()))
            org.osmdroid.config.Configuration.getInstance().userAgentValue = BuildConfig.APPLICATION_ID
        }
        tvLocality = view.findViewById(R.id.locality)

        captionPage = view.findViewById(R.id.caption_page)
        tvCaption = view.findViewById(R.id.caption)
        captionHint = view.findViewById(R.id.caption_hint)

        fastScroller = view.findViewById<HorizontalGridView>(R.id.fast_scroller).apply {
            var ignoreKeyPress = true
            windowAlignmentOffsetPercent = 8f
            itemAnimator = null
            adapter = fastScrollAdapter

            onUnhandledKeyListener = BaseGridView.OnUnhandledKeyListener { event ->
                if (event.action == KeyEvent.ACTION_UP) {
                    when(event.keyCode) {
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_A -> {
                            fastScroller.findContainingViewHolder(fastScroller.focusedChild)?.bindingAdapterPosition?.let { pos ->
                                slider.setCurrentItem(pos, false)
                                hideFastScroller()
                            }
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                            changeDate(true)
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                            changeDate(false)
                            true
                        }
                        else -> false
                    }
                } else false
            }

            // When fast scroller appears, ignore the long keypress which is still firing
            viewTreeObserver.addOnGlobalLayoutListener { if (this.visibility != View.VISIBLE) ignoreKeyPress = true }
            setOnKeyInterceptListener(object : BaseGridView.OnKeyInterceptListener {
                override fun onInterceptKeyEvent(event: KeyEvent): Boolean {
                    if (ignoreKeyPress) {
                        if (event.action == KeyEvent.ACTION_UP) {
                            ignoreKeyPress = false
                            return true
                        }
                    }

                    return ignoreKeyPress
                }
            })
        }

        slider = view.findViewById<ViewPager2>(R.id.slider).apply {
            adapter = mediaAdapter
            requestFocus()
            
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    super.onPageSelected(position)

                    if (mediaAdapter.isSlideVideo(position)) {
                        bgmModel?.fadeOutBGM()
                    } else {
                        bgmModel?.fadeInBGM()
                        mediaAdapter.getCaption(position).let { if (it.isNotEmpty()) { startShowingCaption(it) }}
                    }
                }
            })
        }

        exitSnackbar = Snackbar.make(slider, getString(R.string.tv_slider_exit_toast), 1000)

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                requireArguments().parcelable<Album>(KEY_ALBUM)?.let { album -> launch {
                    isSortedByDate = album.sortOrder in Album.BY_DATE_TAKEN_ASC..Album.BY_DATE_MODIFIED_DESC || album.sortOrder in Album.BY_DATE_TAKEN_ASC_WIDE..Album.BY_DATE_MODIFIED_DESC_WIDE
                    albumModel.getAllPhotoInAlbum(album.id).collect { photos ->
                        val serverPath = if (album.isRemote()) "${Tools.getRemoteHome(requireContext())}/${album.name}" else ""

                        // Panorama photo need focus to play with, filter them now
                        Tools.sortPhotos(photos.filter { it.mimeType != Tools.PANORAMA_MIMETYPE }, album.sortOrder).map { NCShareViewModel.RemotePhoto(it, serverPath) }.run {
                            mediaAdapter.submitList(this) { bgmModel?.fadeInBGM() }
                            fastScrollAdapter.submitList(this)
                        }
                    }
                }}
                requireArguments().parcelable<NCShareViewModel.ShareWithMe>(KEY_SHARED)?.let { shared -> launch {
                    isSortedByDate = shared.sortOrder in Album.BY_DATE_TAKEN_ASC..Album.BY_DATE_MODIFIED_DESC || shared.sortOrder in Album.BY_DATE_TAKEN_ASC_WIDE..Album.BY_DATE_MODIFIED_DESC_WIDE
                    imageLoaderModel.publicationContentMeta.collect { photos ->
                        mediaAdapter.submitList(photos) { bgmModel?.fadeInBGM() }
                        fastScrollAdapter.submitList(photos)
                    }
                }}
            }
        }

        setupCaptionHintingAnimation()
    }

    override fun onResume() {
        super.onResume()
        mediaAdapter.setPauseVideo(true)
    }

    override fun onStop() {
        try { if (mediaAdapter.getPhotoAt(slider.currentItem).mimeType.startsWith("video")) handler.postDelayed({ playerViewModel.pause(Uri.EMPTY) }, 300) } catch (_: IndexOutOfBoundsException) {}
        bgmModel?.fadeOutBGM()

        super.onStop()
    }

    override fun onDestroy() {
        captionHintingAnimation.cancel()
        captionAnimationJob?.cancel()
        metaDisplayThread.cancel()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun setupCaptionHintingAnimation() {
        captionHintingAnimation.playSequentially(
            ObjectAnimator.ofFloat(captionHint, View.ALPHA, 0.0f, 1.0f).setDuration(650),
            ObjectAnimator.ofFloat(captionHint, View.ALPHA, 1.0f, 0.0f).setDuration(650),
            ObjectAnimator.ofFloat(captionHint, View.ALPHA, 0.0f, 1.0f).setDuration(650),
            ObjectAnimator.ofFloat(captionHint, View.ALPHA, 1.0f, 0.0f).setDuration(650),
            ObjectAnimator.ofFloat(captionHint, View.ALPHA, 0.0f, 1.0f).setDuration(650),
            ObjectAnimator.ofFloat(captionHint, View.ALPHA, 1.0f, 0.0f).setDuration(650),
        )
        captionHintingAnimation.doOnCancel { captionHint.isVisible = false }
        captionHintingAnimation.doOnEnd { showCaption(mediaAdapter.getCaption(slider.currentItem)) }
    }

    private fun startShowingCaption(caption: String) {
        captionHint.isVisible = true
        captionHintingAnimation.start()

        wordIterator.setText(caption)
    }

    private fun showCaption(caption: String) {
        captionAnimationJob = viewLifecycleOwner.lifecycleScope.launch {
            captionHint.isVisible = false

            tvCaption.text = ""
            TransitionManager.beginDelayedTransition(captionPage, Slide(Gravity.START).setDuration(800))
            captionPage.isVisible = true

            delay(500)
            var i = wordIterator.next()
            while(i != BreakIterator.DONE) {
                ensureActive()
                tvCaption.text = caption.subSequence(0, i)
                i = wordIterator.next()
                delay(80)
            }
        }
    }

    private fun hideCaptionPage() {
        captionHintingAnimation.cancel()

        if (captionPage.isVisible) {
            captionAnimationJob?.run {
                cancel()
                captionAnimationJob = null
            }
            TransitionManager.beginDelayedTransition(captionPage, Slide(Gravity.BOTTOM).setDuration(150))
            captionPage.isVisible = false
        }
    }

    private fun hideFastScroller() {
        if (fastScroller.isVisible) {
            TransitionManager.beginDelayedTransition(fastScroller, Slide(Gravity.BOTTOM).setDuration(500))
            fastScroller.isVisible = false
            slider.requestFocus()
        }
    }

    private fun showFastScroller() {
        //captionHintingAnimation.cancel()
        hideCaptionPage()
        mediaAdapter.getPhotoAt(slider.currentItem).let { currentPhoto ->
            fastScrollAdapter.currentList.indexOfFirst { it.photo.id == currentPhoto.id }.let { pos ->
                TransitionManager.beginDelayedTransition(fastScroller, Slide(Gravity.BOTTOM).setDuration(500))
                fastScroller.isVisible = true
                if (pos >= 0) fastScroller.smoothScrollToPosition(pos)
                fastScroller.requestFocus()
            }
        }
    }

    private fun changeDate(forward: Boolean) {
        if (isSortedByDate) fastScroller.focusedChild?.let { currentFocused ->
            fastScroller.findContainingViewHolder(currentFocused)?.bindingAdapterPosition?.let { pos -> fastScroller.smoothScrollToPosition(mediaAdapter.findAdjacentDate(pos, forward)) }
        }
    }

    private fun toggleCaption(state: Boolean) {
        // Activate fast scroller when user press Down key while caption page is not visible
        //if (!state && !captionPage.isVisible) showFastScroller()
        //else mediaAdapter.getCaption(slider.currentItem).let { caption ->
        mediaAdapter.getCaption(slider.currentItem).let { caption ->
            if (caption.isNotEmpty()) {
                if (state) {
                    captionHintingAnimation.cancel()
                    captionAnimationJob?.cancel()
                    captionAnimationJob = null

                    tvCaption.text = caption
                    if (!captionPage.isVisible) {
                        TransitionManager.beginDelayedTransition(captionPage, Slide(Gravity.BOTTOM).setDuration(500))
                        captionPage.isVisible = true
                    }
                } else hideCaptionPage()
            }
        }
    }

    private fun toggleMeta(rPhoto: NCShareViewModel.RemotePhoto, off: Boolean) {
        if (off) {
            metaDisplayThread.cancel(null)

            TransitionManager.beginDelayedTransition(metaPage, Slide(Gravity.END).apply {
                duration = 200
                addListener(object : Transition.TransitionListener {
                    override fun onTransitionCancel(transition: Transition) {}
                    override fun onTransitionPause(transition: Transition) {}
                    override fun onTransitionResume(transition: Transition) {}
                    override fun onTransitionStart(transition: Transition) {}
                    override fun onTransitionEnd(transition: Transition) {
                        tvName.text = ""
                        tvDate.text = ""
                        tvSize.text = ""
                        tvMfg.text = ""
                        tvModel.text = ""
                        tvParam.text = ""
                        tvArtist.text = ""
                        tvLocality.text = ""

                        trSize.isVisible = false
                        trMfg.isVisible = false
                        trModel.isVisible = false
                        trParam.isVisible = false
                        trArtist.isVisible = false
                        mapView.isVisible = false
                        tvLocality.isVisible = false
                    }
                })
            })
            metaPage.isVisible = false
        } else {
            TransitionManager.beginDelayedTransition(metaPage, Slide(Gravity.END).apply { duration = 200 })
            metaPage.isVisible = true
            tvName.text = rPhoto.photo.name
            tvDate.text = String.format("%s %s", rPhoto.photo.dateTaken.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()), rPhoto.photo.dateTaken.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.MEDIUM)))

            viewLifecycleOwner.lifecycleScope.launch(metaDisplayThread) {
                val pm = PhotoMeta(rPhoto.photo)
                var exif: ExifInterface? = null

                try {
                    imageLoaderModel.getMediaExif(rPhoto).let { result ->
                        exif = result.first
                        pm.size = if (result.second > 0) result.second else imageLoaderModel.getMediaSize(rPhoto)
                    }

                    exif?.run {
                        pm.mfg = getAttribute(ExifInterface.TAG_MAKE)?.substringBefore(" ") ?: ""
                        pm.model = (getAttribute(ExifInterface.TAG_MODEL)?.trim() ?: "") + (getAttribute(ExifInterface.TAG_LENS_MODEL)?.let { "\n${it.trim()}" } ?: "")
                        pm.params = ((getAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM) ?: getAttribute(ExifInterface.TAG_FOCAL_LENGTH))?.let { "${it.substringBefore("/").toInt() / it.substringAfter("/", "1").toInt()}mm  " } ?: "") +
                                (getAttribute(ExifInterface.TAG_F_NUMBER)?.let { "f$it  " } ?: "") +
                                (getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.let {
                                    val exp = it.toFloat()
                                    if (exp < 1) "1/${(1 / it.toFloat()).roundToInt()}s  " else "${exp.roundToInt()}s  "
                                } ?: "") +
                                (getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.let { "ISO$it" } ?: "")
                        pm.artist = getAttribute((ExifInterface.TAG_ARTIST)) ?: ""

                        latLong?.let {
                            pm.photo!!.latitude = it[0]
                            pm.photo.longitude = it[1]
                        }
                        pm.date = Tools.getImageTakenDate(this)

                        pm.photo?.width = getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
                        pm.photo?.height = getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
                    }
                } catch (_: Exception) {}

                withContext(Dispatchers.Main) {
                    pm.photo?.run {
                        // Size row
                        val pWidth: Int
                        val pHeight: Int
                        if (orientation == 90 || orientation == 270) {
                            pWidth = height
                            pHeight = width
                        } else {
                            pWidth = width
                            pHeight = height
                        }
                        tvSize.text = if (pm.size == 0L) String.format("%sw × %sh", "$pWidth", "$pHeight") else String.format("%s, %s", Tools.humanReadableByteCountSI(pm.size), String.format("%sw × %sh", "$pWidth", "$pHeight"))
                        trSize.isVisible = true

                        if (pm.mfg.isNotEmpty()) {
                            trMfg.isVisible = true
                            tvMfg.text = pm.mfg
                        }
                        if (pm.model.isNotEmpty()) {
                            trModel.isVisible = true
                            tvModel.text = pm.model
                        }
                        if (pm.params.trim().isNotEmpty()) {
                            trParam.isVisible = true
                            tvParam.text = pm.params
                        }
                        if (pm.artist.isNotEmpty()) {
                            trArtist.isVisible = true
                            tvArtist.text = pm.artist
                        }

                        if (latitude != Photo.NO_GPS_DATA) showMap(this)
                    }
                }
            }
        }
    }

    private fun showMap(photo: Photo) {
        viewLifecycleOwner.lifecycleScope.launch(metaDisplayThread) {
            try {
                with(mapView) {
                    val poi = GeoPoint(photo.latitude, photo.longitude)
                    controller.setZoom(17.5)
                    controller.setCenter(poi)
                    overlayManager.tilesOverlay.setColorFilter(
                        ColorMatrixColorFilter(
                            floatArrayOf(
                                1.05f, 0f, 0f, 0f, -72f,  // red, reduce brightness about 1/4, increase contrast by 5%
                                0f, 1.05f, 0f, 0f, -72f,  // green, reduce brightness about 1/4, reduced contrast by 5%
                                0f, 0f, 1.05f, 0f, -72f,  // blue, reduce brightness about 1/4, reduced contrast by 5%
                                0f, 0f, 0f, 1f, 0f,
                            )
                        )
                    )

                    (if (overlays.last() is Marker) overlays.last() as Marker else Marker(this)).let {
                        it.position = poi
                        it.icon = ContextCompat.getDrawable(this.context, R.drawable.ic_baseline_location_marker_24)
                        this.overlays.add(it)
                    }

                    ensureActive()
                    withContext(Dispatchers.Main) {
                        invalidate()
                        mapView.isVisible = true
                    }
                }

                if (photo.locality.isNotEmpty() && photo.country.isNotEmpty()) {
                    // TODO use map text overlay instead
                    withContext(Dispatchers.Main) {
                        tvLocality.run {
                            text = String.format("%s, %s", photo.locality, photo.country)
                            isVisible = true
                        }
                    }
                } else try {
                    ensureActive()
                    GeocoderNominatim(Locale.getDefault(), BuildConfig.APPLICATION_ID).getFromLocation(photo.latitude, photo.longitude, 1)
                } catch (_: IOException) { null }?.let { result ->
                    if (result.isNotEmpty()) {
                        result[0]?.let { address ->
                            if (address.countryName != null) {
                                val locality = address.locality ?: address.adminArea ?: Photo.NO_ADDRESS

                                try { PhotoRepository(requireActivity().application).updateAddress(photo.id, locality, address.countryName, address.countryCode ?: Photo.NO_ADDRESS) } catch (_: IllegalStateException) {}

                                withContext(Dispatchers.Main) {
                                    tvLocality.run {
                                        text = String.format("%s, %s", locality, address.countryName)
                                        isVisible = true
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    class MediaAdapter(context: Context, displayWidth: Int, private val basePath: String, playerViewModel: VideoPlayerViewModel,
       clickListener: ((Boolean?) -> Unit), imageLoader: (NCShareViewModel.RemotePhoto, ImageView?, type: String) -> Unit, panoLoader: (NCShareViewModel.RemotePhoto, ImageView?, PLManager, PLSphericalPanorama) -> Unit, cancelLoader: (View) -> Unit,
       private val scrollListener: (Float) -> Unit, private val iListener: (NCShareViewModel.RemotePhoto) -> Unit, private val cListener: (Boolean) -> Unit, private val fsLauncher: () -> Unit, private val cmLauncher: () -> Unit
    ): SeamlessMediaSliderAdapter<NCShareViewModel.RemotePhoto>(context, displayWidth, PhotoDiffCallback(), playerViewModel, clickListener, imageLoader, panoLoader, cancelLoader) {
        fun getPhotoAt(position: Int): Photo = currentList[position].photo
        fun isSlideVideo(position: Int): Boolean = try { currentList[position].photo.mimeType.startsWith("video") } catch (_: Exception) { false }
        fun getCaption(position: Int): String = currentList[position].photo.caption
        fun findAdjacentDate(current: Int, forward: Boolean): Int {
            val currentDate = currentList[current].photo.dateTaken.toLocalDate()
            var i = current
            while(i in 0 until currentList.size) {
                if (currentList[i].photo.dateTaken.toLocalDate() != currentDate) break
                if (forward) i++ else i--
            }
            if (i < 0) i = 0
            if (i >= currentList.size) i = currentList.size - 1

            // Move to the 1st picture in this date if moving backward
            if (!forward) {
                val newDate = currentList[i].photo.dateTaken.toLocalDate()
                while(i in 0 until currentList.size) {
                    if (currentList[i].photo.dateTaken.toLocalDate() != newDate) break
                    i--
                }
                i++
                if (i < 0) i = 0
                if (i >= currentList.size) i = currentList.size - 1
            }

            return i
        }

        override fun getVideoItem(position: Int): VideoItem = with(getItem(position)) { VideoItem("$basePath$remotePath/${photo.name}".toUri(), photo.mimeType, photo.width, photo.height, photo.id) }
        override fun getItemTransitionName(position: Int): String = getItem(position).photo.id
        override fun getItemMimeType(position: Int): String = getItem(position).photo.mimeType
        override fun isMotionPhoto(position: Int): Boolean = false

        override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
            super.onAttachedToRecyclerView(recyclerView)

            recyclerView.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            recyclerView.itemAnimator = null
            recyclerView.setOnKeyListener(object : View.OnKeyListener {
                val lm = recyclerView.layoutManager as LinearLayoutManager

                override fun onKey(v: View?, keyCode: Int, event: KeyEvent?): Boolean {
                    when(event?.action) {
                        KeyEvent.ACTION_UP -> {
                            when(keyCode) {
                                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_BUTTON_R1 -> {
                                    scrollListener(if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_BUTTON_L1) 1f else -1f)
                                    return true
                                }
                                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_A -> {
                                    recyclerView.findViewHolderForLayoutPosition(lm.findFirstVisibleItemPosition())?.let { viewHolder ->
                                        if (viewHolder is SeamlessMediaSliderAdapter<*>.VideoViewHolder) { viewHolder.playOrPauseOnTV() }
                                        iListener(getItem(viewHolder.bindingAdapterPosition))
                                        return true
                                    }
                                }
                                KeyEvent.KEYCODE_DPAD_UP -> {
                                    cListener(true)
                                    return true
                                }
                                KeyEvent.KEYCODE_DPAD_DOWN -> {
                                    cListener(false)
                                    return true
                                }
                            }
                        }
                        KeyEvent.ACTION_DOWN -> {
                            if (event.repeatCount == 5) {
                                when (keyCode) {
                                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_A -> cmLauncher()
                                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_BUTTON_R1 -> fsLauncher()
                                }
                            }

                            return event.repeatCount >= 5
                        }
                    }
                    
                    return false
                }
            })
        }

        override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
            recyclerView.setOnKeyListener(null)
            super.onDetachedFromRecyclerView(recyclerView)
        }
    }

    class FastScrollAdapter(private val imageLoader: (NCShareViewModel.RemotePhoto, ImageView) -> Unit, private val cancelLoader: (View) -> Unit
    ): ListAdapter<NCShareViewModel.RemotePhoto, FastScrollAdapter.MediaViewHolder>(PhotoDiffCallback()) {
        inner class  MediaViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
            val ivMedia: AppCompatImageView = itemView.findViewById(R.id.photo)
            val ivPlayMark: AppCompatImageView = itemView.findViewById(R.id.play_mark)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaViewHolder = MediaViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_tv_slider_fast_scroller, parent, false))
        override fun onBindViewHolder(holder: MediaViewHolder, position: Int) {
            getItem(position).let { remotePhoto ->
                imageLoader(remotePhoto, holder.ivMedia)
                holder.ivPlayMark.isVisible = Tools.isMediaPlayable(remotePhoto.photo.mimeType)
            }
        }
        override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
            recyclerView.setOnKeyListener(null)
            for (i in 0 until currentList.size) { recyclerView.findViewHolderForAdapterPosition(i)?.let { holder -> cancelLoader((holder as MediaViewHolder).ivMedia) }}
            super.onDetachedFromRecyclerView(recyclerView)
        }
    }

    class PhotoDiffCallback(): DiffUtil.ItemCallback<NCShareViewModel.RemotePhoto>() {
        override fun areItemsTheSame(oldItem: NCShareViewModel.RemotePhoto, newItem: NCShareViewModel.RemotePhoto): Boolean = oldItem.photo.id == newItem.photo.id
        override fun areContentsTheSame(oldItem: NCShareViewModel.RemotePhoto, newItem: NCShareViewModel.RemotePhoto): Boolean = oldItem.photo.eTag == newItem.photo.eTag
    }

    companion object {
        const val RESULT_REQUEST_KEY = "RESULT_REQUEST_KEY"

        private const val KEY_ALBUM = "KEY_ALBUM"
        const val KEY_SHARED = "KEY_SHARED"

        @JvmStatic
        fun newInstance(album: Album?, shared: NCShareViewModel.ShareWithMe?) = TVSliderFragment().apply {
            arguments = Bundle().apply {
                putParcelable(KEY_ALBUM, album)
                putParcelable(KEY_SHARED, shared)
            }
        }
    }
}