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

package site.leos.apps.lespas.story

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Animatable2
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.transition.TransitionManager
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.animation.doOnEnd
import androidx.core.content.ContextCompat
import androidx.core.transition.doOnEnd
import androidx.core.view.GestureDetectorCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.asLiveData
import androidx.media3.common.Player
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.ViewHolder
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.transition.platform.MaterialContainerTransform
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.Album
import site.leos.apps.lespas.album.AlbumViewModel
import site.leos.apps.lespas.album.BGMDialogFragment
import site.leos.apps.lespas.helper.SeamlessMediaSliderAdapter
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.helper.Tools.parcelable
import site.leos.apps.lespas.helper.VideoPlayerViewModel
import site.leos.apps.lespas.helper.VideoPlayerViewModelFactory
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.publication.NCShareViewModel
import java.io.File
import java.lang.Integer.min
import kotlin.math.abs

class StoryFragment : Fragment() {
    private lateinit var album: Album
    private var isRemote: Boolean = false
    private var isPublication: Boolean = false
    private lateinit var serverPath: String
    private lateinit var serverFullPath: String
    private lateinit var publicationPath: String
    private lateinit var rootPath: String
    private var total = 0
    private var startAt = 0
    private var animationState = STATE_UNKNOWN

    private lateinit var pAdapter: StoryAdapter
    private lateinit var slider: ViewPager2
    private lateinit var container: ConstraintLayout
    private lateinit var captionCrank: ScrollView
    private lateinit var captionTextView: TextView
    private lateinit var controlFAB: FloatingActionButton
    private lateinit var endSign: TextView
    private lateinit var pauseSign: ImageView

    private val animationHandler = Handler(Looper.getMainLooper())
    private val knobAnimationHandler = Handler(Looper.getMainLooper())
    private lateinit var slowSwipeAnimator: ValueAnimator
    private lateinit var captionCrankAnimator: ObjectAnimator
    private lateinit var dreamyAnimator: ObjectAnimator
    private lateinit var hideSettingRunnable: Runnable
    private val animatableCallback = AnimatedDrawableCallback { if (animationState == STATE_STARTED) advanceSlide() }

    private val albumModel: AlbumViewModel by activityViewModels()
    private val imageLoaderModel: NCShareViewModel by activityViewModels()
    private lateinit var playerViewModel: VideoPlayerViewModel
    private lateinit var bgmModel: BGMViewModel

    private var previousTitleBarDisplayOption = 0

    private lateinit var gestureDetector: GestureDetectorCompat
    private lateinit var volumeDrawable: Drawable
    private lateinit var brightnessDrawable: Drawable
    private var displayWidth = 0
    private lateinit var knobLayout: FrameLayout
    private lateinit var knobIcon: ImageView
    private lateinit var knobPosition: CircularProgressIndicator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        album = requireArguments().parcelable(ARGUMENT_ALBUM)!!
        isRemote = Tools.isRemoteAlbum(album)
        isPublication = album.eTag == Photo.ETAG_FAKE
        rootPath = Tools.getLocalRoot(requireContext())
        publicationPath = imageLoaderModel.getResourceRoot()
        serverPath = "${Tools.getRemoteHome(requireContext())}/${album.name}"
        serverFullPath = "${publicationPath}${serverPath}"

        startAt = savedInstanceState?.getInt(KEY_START_AT) ?: 0

        playerViewModel = ViewModelProvider(this, VideoPlayerViewModelFactory(requireActivity(), imageLoaderModel.getCallFactory(), imageLoaderModel.getPlayerCache(), slideshowMode = true))[VideoPlayerViewModel::class.java]
        // Advance to next slide after video playback end
        playerViewModel.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                super.onPlaybackStateChanged(playbackState)
                if (playbackState == Player.STATE_ENDED) {
                    if (animationState == STATE_STARTED) {
                        advanceSlide(300)
                        //if (slider.currentItem < total && !pAdapter.isSlideVideo(slider.currentItem + 1)) bgmModel.fadeInBGM()
                    }
                }
            }
        })

        displayWidth = Tools.getDisplayDimension(requireActivity()).first
        pAdapter = StoryAdapter(
            requireContext(),
            displayWidth,
            playerViewModel,
            { rp ->
                with(rp.photo) {
                    val uri = when {
                        isPublication -> Uri.parse("${publicationPath}${rp.remotePath}/${name}")
                        isRemote && eTag != Photo.ETAG_NOT_YET_UPLOADED -> Uri.parse("${serverFullPath}/${name}")
                        else -> {
                            var fileName = "${rootPath}/${id}"
                            if (!(File(fileName).exists())) fileName = "${rootPath}/${name}"
                            Uri.parse("file:///$fileName")
                        }
                    }
                    SeamlessMediaSliderAdapter.VideoItem(uri, mimeType, width, height, id)
                }
            },
            { photo, imageView, type ->
                // Usually slideshow triggered in slider's onPageScrollStateChanged callback. But during the initial launch, onPageScrollStateChanged won't be called, need to do it here
                // For photos and animatables, load the full size image before starting the show; for videos, video player will handle the loading and buffering, show can be started right away
                //if (type != NCShareViewModel.TYPE_NULL) imageLoaderModel.setImagePhoto(photo, imageView!!, type, animatableCallback) { fullSize -> if (needKickOff && fullSize) startFirstSlide() }
                if (type != NCShareViewModel.TYPE_NULL) imageLoaderModel.setImagePhoto(photo, imageView!!, type, animatableCallback) { if (needKickOff) startFirstSlide() }
                else if (needKickOff) startFirstSlide()
            },
            { view -> imageLoaderModel.cancelSetImagePhoto(view) },
        ).apply { stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY }

        // Prepare display
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) requireActivity().window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        // Wipe ActionBar
        (requireActivity() as AppCompatActivity).supportActionBar?.run {
            previousTitleBarDisplayOption = savedInstanceState?.run {
                // During fragment recreate, wipe actionbar to avoid flash
                wipeActionBar()

                getInt(KEY_DISPLAY_OPTION)
            } ?: displayOptions
        }
        Tools.goImmersive(requireActivity().window)
        @Suppress("DEPRECATION")
        requireActivity().window.decorView.setOnSystemUiVisibilityChangeListener { wipeActionBar() }

        // Prepare BGM playing
        bgmModel = ViewModelProvider(this, BGMViewModelFactory(requireActivity(), imageLoaderModel.getCallFactory(), if (isPublication) album.bgmId else "file://${Tools.getLocalRoot(requireContext())}/${album.id}${BGMDialogFragment.BGM_FILE_SUFFIX}"))[BGMViewModel::class.java]
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? = inflater.inflate(R.layout.fragment_story, container, false)
    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        slider = view.findViewById<ViewPager2>(R.id.pager).apply {
            adapter = pAdapter
            isUserInputEnabled = false
            setPageTransformer(ZoomInPageTransformer())
        }

        slider.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageScrollStateChanged(state: Int) {
                super.onPageScrollStateChanged(state)

                // Activate slide animation here, rather than in onPageSelected, because onPageSelected is called before page transformer animation ends
                // Start the show by setting the caption textview
                if (state == ViewPager2.SCROLL_STATE_IDLE) {
                    if (animationState == STATE_STARTED) captionTextView.text = pAdapter.getCaption(slider.currentItem)
                }
            }

            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)

                startAt = position
            }
        })

        container = view.findViewById(R.id.container)
        captionCrank = view.findViewById(R.id.caption_crank)
        captionTextView = view.findViewById(R.id.caption)
        // After caption is loaded, trigger the show of current slide, do it here will make sure caption textview is ready and avoid race condition
        captionTextView.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { showCurrentSlide() }
        })

        if (isPublication) imageLoaderModel.publicationContentMeta.asLiveData().observe(viewLifecycleOwner) { if (it.isNotEmpty()) loadSlideshow(it, startAt) }
        else albumModel.getAllPhotoInAlbum(album.id).observe(viewLifecycleOwner) { photos ->
            Tools.sortPhotos(photos, album.sortOrder).run {
                val rpList = mutableListOf<NCShareViewModel.RemotePhoto>()
                forEach { rpList.add(NCShareViewModel.RemotePhoto(it, if (isRemote && it.eTag != Photo.ETAG_NOT_YET_UPLOADED) serverPath else "")) }
                loadSlideshow(rpList, startAt)
            }
        }

        // Controls
        volumeDrawable = ContextCompat.getDrawable(requireContext(), R.drawable.ic_baseline_volume_on_24)!!
        brightnessDrawable = ContextCompat.getDrawable(requireContext(), R.drawable.ic_baseline_brightness_24)!!
        hideSettingRunnable = Runnable { knobLayout.isVisible = false }
        knobLayout = view.findViewById(R.id.knob)
        knobIcon = view.findViewById(R.id.knob_icon)
        knobPosition = view.findViewById(R.id.knob_position)
        gestureDetector = GestureDetectorCompat(requireContext(), object: GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = animationState == STATE_STARTED

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (animationState == STATE_STARTED) {
                    stopSlideshow(false)

                    TransitionManager.beginDelayedTransition(container, android.transition.Fade().apply {
                        duration = 300
                        doOnEnd {
                            animationHandler.postDelayed({
                                TransitionManager.beginDelayedTransition(container, MaterialContainerTransform().apply {
                                    duration = 500
                                    startView = pauseSign
                                    endView = controlFAB
                                    scrimColor = Color.TRANSPARENT
                                    this.fadeMode = MaterialContainerTransform.FADE_MODE_THROUGH
                                    addTarget(controlFAB)
                                })
                                pauseSign.isVisible = false
                                controlFAB.isVisible = true

                                pAdapter.getViewHolderByPosition(slider.currentItem)?.apply {
                                    // Restore photoview state, other view types are handled in function stopSlideshow(...)
                                    if (this is SeamlessMediaSliderAdapter<*>.PhotoViewHolder) {
                                        getPhotoView().apply {
                                            scaleX = 1.0f
                                            scaleY = 1.0f
                                            alpha = 1.0f
                                        }
                                    }
                                }

                                // Allow swiping
                                slider.isUserInputEnabled = true
                                slider.setPageTransformer(null)
                                slider.offscreenPageLimit = ViewPager2.OFFSCREEN_PAGE_LIMIT_DEFAULT
                            }, 500)
                        }
                    })
                    pauseSign.isVisible = true
                    controlFAB.isVisible = false
                    controlFAB.setImageResource(R.drawable.ic_baseline_play_arrow_24)

                    return true
                } else return false
            }

            override fun onScroll(e1: MotionEvent, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (animationState == STATE_STARTED) {
                    // Ignore flings
                    if (abs(distanceY) > 15) return false

                    if (abs(distanceX) < abs(distanceY)) {
                        knobLayout.isVisible = true
                        // Response to vertical scroll only, horizontal scroll reserved for viewpager sliding
                        if (e1.x > displayWidth / 2) {
                            knobIcon.setImageDrawable(volumeDrawable)
                            // Affect both video player and bgm player
                            playerViewModel.setVolume(distanceY / 300)
                            knobPosition.progress = (playerViewModel.getVolume() * 100).toInt()
                        } else {
                            knobIcon.setImageDrawable(brightnessDrawable)
                            // Affect both video player and bgm player
                            playerViewModel.setBrightness(distanceY / 300)
                            knobPosition.progress = (playerViewModel.getBrightness() * 100).toInt()
                        }

                        knobAnimationHandler.removeCallbacks(hideSettingRunnable)
                        knobAnimationHandler.postDelayed(hideSettingRunnable, 1000)
                    }

                    return true
                } else return false
            }
        })

        controlFAB = view.findViewById<FloatingActionButton?>(R.id.fab).apply {
            setOnClickListener {
                when(animationState) {
                    STATE_ENDED -> {
                        bgmModel.rewind()
                        checkSlide(0)
                        slider.setCurrentItem(0, false)
                        captionTextView.text = pAdapter.getCaption(0)

                        TransitionManager.beginDelayedTransition(container, android.transition.Fade().apply { duration = 800 })
                        controlFAB.isVisible = false
                        Tools.goImmersive(requireActivity().window)
                    }
                    STATE_PAUSED -> {
                        TransitionManager.beginDelayedTransition(container, android.transition.Fade().apply { duration = 800 })
                        controlFAB.isVisible = false
                        checkSlide(slider.currentItem)
                        captionTextView.text = pAdapter.getCaption(slider.currentItem)
                    }
                }
            }
        }
        endSign = view.findViewById(R.id.end_sign)
        pauseSign = view.findViewById(R.id.pause_sign)

        view.findViewById<View>(R.id.touch).run { setOnTouchListener { _, event -> gestureDetector.onTouchEvent(event) }}

        // Initialize animators
        slowSwipeAnimator = ValueAnimator()
        captionCrankAnimator = ObjectAnimator()
        dreamyAnimator = ObjectAnimator()
    }

    override fun onResume() {
        super.onResume()
        if (animationState == STATE_PAUSED) captionTextView.text = pAdapter.getCaption(slider.currentItem)
    }

    override fun onPause() {
        if (animationState == STATE_STARTED) stopSlideshow(endOfSlideshow = false)
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_DISPLAY_OPTION, previousTitleBarDisplayOption)
        outState.putInt(KEY_START_AT, startAt)
    }

    override fun onDestroyView() {
        // Make sure onViewDetachedFromWindow called in SeamlessMediaSliderAdapter
        slider.adapter = null

        super.onDestroyView()
    }

    override fun onDestroy() {
        animationHandler.removeCallbacksAndMessages(null)
        knobAnimationHandler.removeCallbacksAndMessages(null)
        Tools.quitImmersive(requireActivity().window)
        (requireActivity() as AppCompatActivity).run {
            supportActionBar?.run {
                displayOptions = previousTitleBarDisplayOption
                setBackgroundDrawable(ColorDrawable(Tools.getAttributeColor(requireContext(), android.R.attr.colorPrimary)))
            }
        }

        super.onDestroy()
    }

    private fun checkSlide(position: Int) {
        // fade out BGM if next slide is video, do it here to prevent audio mix up
        if (pAdapter.isSlideVideo(position)) bgmModel.fadeOutBGM() else bgmModel.fadeInBGM()

        // With offscreenPageLimit greater than 0, the next slide will be preloaded, however if two consecutive slides are both video, pre-fetch of the 2nd one will ruin the playback of the 1st one
        slider.offscreenPageLimit = if (position < total && pAdapter.isSlideVideo(position) && pAdapter.isSlideVideo(position + 1)) ViewPager2.OFFSCREEN_PAGE_LIMIT_DEFAULT else 1
    }

    private var needKickOff = true
    private fun startFirstSlide() {
        needKickOff = false
        captionTextView.text = pAdapter.getCaption(slider.currentItem)
        animationState = STATE_STARTED
    }

    private fun loadSlideshow(photos: List<NCShareViewModel.RemotePhoto>, startAt: Int) {
        total = photos.size - 1
        slider.endFakeDrag()
        pAdapter.setPhotos(photos) {
            checkSlide(startAt)
            slider.setCurrentItem(startAt, false)
        }
    }

    private fun stopSlideshow(endOfSlideshow: Boolean) {
        // Set status flag asap
        animationState = STATE_PAUSED

        // Stop animation both running or scheduled
        animationHandler.removeCallbacksAndMessages(null)
        // Immediately stop all animation listeners to cut off chained effect
        if (dreamyAnimator.isRunning) {
            dreamyAnimator.removeAllListeners()
            dreamyAnimator.cancel()
        }
        if (captionCrankAnimator.isRunning) {
            captionCrankAnimator.removeAllListeners()
            captionCrankAnimator.cancel()
        }
        if (slowSwipeAnimator.isRunning) {
            slowSwipeAnimator.removeAllUpdateListeners()
            slowSwipeAnimator.cancel()
        }
        slider.animation?.cancel()
        slider.clearAnimation()

        // Stop playing video and animatable
        pAdapter.getViewHolderByPosition(slider.currentItem)?.apply {
            when(this) {
                is SeamlessMediaSliderAdapter<*>.AnimatedViewHolder -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) getAnimatedDrawable()?.run {
                        clearAnimationCallbacks()
                        stop()
                    }
                }
                is SeamlessMediaSliderAdapter<*>.VideoViewHolder -> pause()
            }
        }

        captionCrank.apply {
            isVisible = false
            clearAnimation()
            scrollTo(0, 0)
        }

        // Stop BGM
        bgmModel.fadeOutBGM()

        // Stop at the end of the show and provide way to restart
        if (endOfSlideshow) {
            animationState = STATE_ENDED

            TransitionManager.beginDelayedTransition(container, android.transition.Fade().apply {
                duration = 300
                doOnEnd {
                    animationHandler.postDelayed({
                        TransitionManager.beginDelayedTransition(container, MaterialContainerTransform().apply {
                            duration = 500
                            startView = endSign
                            endView = controlFAB
                            scrimColor = Color.TRANSPARENT
                            this.fadeMode = MaterialContainerTransform.FADE_MODE_THROUGH
                            addTarget(controlFAB)
                        })
                        endSign.isVisible = false
                        controlFAB.isVisible = true

                        // Shows the system bars by removing all the flags except for the ones that make the content appear under the system bars.
                        @Suppress("DEPRECATION")
                        requireActivity().window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
                    }, 1200)
                }
            })
            endSign.isVisible = true
            controlFAB.isVisible = false
            controlFAB.setImageResource(R.drawable.ic_baseline_replay_24)

            // Continue playing BGM for 1.5 sec, then fade out
            animationHandler.postDelayed({ bgmModel.fadeOutBGM() }, 1500)
        }
    }

    private fun showCurrentSlide() {
        animationState = STATE_STARTED
        slider.isUserInputEnabled = false
        slider.setPageTransformer(ZoomInPageTransformer())
        controlFAB.isVisible = false
        captionCrank.scrollY = 0
        captionCrank.isVisible = captionTextView.text.isNotEmpty()

        if (captionCrank.isVisible) showCaption() else animateSlide()
    }

    private fun showCaption() {
        animationHandler.removeCallbacksAndMessages(null)

        // Make sure view has been layout so that view's height is available
        captionTextView.post {
            val pageHeight = (captionCrank.height - captionCrank.paddingTop - captionCrank.paddingBottom) * 4 / 5
            val maxScrollPosition = captionTextView.bottom - captionCrank.height + captionCrank.paddingBottom
            // Auto scroll caption
            val remain = maxScrollPosition - captionCrank.scrollY
            val delay = (
                CAPTION_PAGE_VIEWING_TIME *
                    when {
                        maxScrollPosition == 0 -> (captionTextView.lineCount * captionTextView.lineHeight) / captionCrank.height.toFloat()  // One page only
                        remain == 0 -> (maxScrollPosition % pageHeight) / captionCrank.height.toFloat()                                     // Next page will be the last one
                        else -> 1.0f                                                                                                        // There are still pages down below
                    }
            ).toLong()
            animationHandler.postDelayed({
                if (remain > 0) {
                    // Stop any existing animation
                    captionCrank.clearAnimation()
                    captionCrankAnimator = ObjectAnimator.ofInt(captionCrank, "scrollY", captionCrank.scrollY + min(pageHeight, remain)).setDuration(3000).apply {
                        doOnEnd { if (animationState == STATE_STARTED) showCaption() }
                    }
                    captionCrankAnimator.start()
                } else animateSlide()
            }, kotlin.math.max(delay, CAPTION_PAGE_MINIMUM_VIEWING_TIME))
        }
    }

    private fun animateSlide() {
        //Log.e(">>>>>>>>", "animateSlide: ${slider.currentItem}", )
        animationHandler.removeCallbacksAndMessages(null)

        // Hide caption
        TransitionManager.beginDelayedTransition(container, android.transition.Fade().apply { duration = 500 })
        captionCrank.isVisible = false

        // Delayed runnable is necessary because it takes a little bit of time for RecyclerView to return correct ViewHolder at the current position
        animationHandler.postDelayed({
            pAdapter.getViewHolderByPosition(slider.currentItem)?.apply {
                when (this) {
                    is SeamlessMediaSliderAdapter<*>.PhotoViewHolder -> {
                        bgmModel.fadeInBGM()
                        getPhotoView().let { photoView ->
                            // Stop any existing animation
                            photoView.animation?.cancel()
                            photoView.animation?.reset()
                            photoView.clearAnimation()

                            // Start a dreamy animation by scaling image a little by 5% in a long period of time of 5s
                            photoView.scaleX = 1.0f
                            photoView.scaleY = 1.0f
                            dreamyAnimator = ObjectAnimator.ofPropertyValuesHolder(photoView, PropertyValuesHolder.ofFloat(View.SCALE_X, DREAMY_SCALE_FACTOR), PropertyValuesHolder.ofFloat(View.SCALE_Y, DREAMY_SCALE_FACTOR)).setDuration(5000).apply {
                                // Advance to the next slide after dreamy animation ended
                                doOnEnd { if (animationState == STATE_STARTED) advanceSlide() }
                            }
                            dreamyAnimator.start()
                        }
                    }

                    is SeamlessMediaSliderAdapter<*>.AnimatedViewHolder -> {
                        // For animated image, auto advance is handled by passing a Animatable2.AnimationCallback implementation which call advanceSlide() when play back ended to NCShareViewModel.setImagePhoto(...)
                        // setImagePhoto(...) will register this callback after the animated drawable decoded
                        bgmModel.fadeInBGM()

                        // Need this to restart animatable playing after onPause(), which means animatable is stopped and callback had been removed
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) getAnimatedDrawable()?.apply {
                            registerAnimationCallback(animatableCallback)
                            start()
                        }
                    }

                    is SeamlessMediaSliderAdapter<*>.VideoViewHolder -> {
                        // For video item, auto advance to next slide is handled by player's listener
                        play()
                    }
                }
            }
        }, 300)
    }

    private fun advanceSlide(delay: Long = 0) {
        //Log.e(">>>>>>>>", "advanceSlide: ${slider.currentItem}", )
        animationHandler.removeCallbacksAndMessages(null)

        if (slider.currentItem < total) {
            checkSlide(slider.currentItem + 1)
            // Slow down the default page transformation speed
            slowSwipeAnimator = ValueAnimator.ofFloat(0f, slider.width.toFloat()).apply {
                var prevValue = 0f
                duration = 800
                //interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener {
                    (it.animatedValue as Float).run {
                        slider.fakeDragBy(prevValue - this)
                        prevValue = this
                    }
                }
                addListener(object : Animator.AnimatorListener {
                    override fun onAnimationStart(animation: Animator) { slider.beginFakeDrag() }
                    override fun onAnimationEnd(animation: Animator) {
                        slider.endFakeDrag()
                        if (pAdapter.isSlideVideo(slider.currentItem)) playerViewModel.rewind()
                    }
                    override fun onAnimationCancel(animation: Animator) { slider.endFakeDrag() }
                    override fun onAnimationRepeat(animation: Animator) {}
                })
         
                start()
            }
        }
        else stopSlideshow(endOfSlideshow = true)
    }

    private fun wipeActionBar() {
        (requireActivity() as AppCompatActivity).supportActionBar?.run {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            displayOptions = 0
        }
    }

    // Advance to next slide when animated image play back ended
    private class AnimatedDrawableCallback(private val doOnEnd: () -> Unit) : Animatable2.AnimationCallback() {
        override fun onAnimationEnd(drawable: Drawable?) {
            super.onAnimationEnd(drawable)
            doOnEnd()
        }
    }

    // ViewPager2 PageTransformer for zooming out current slide and zooming in the next
    class ZoomInPageTransformer: ViewPager2.PageTransformer {
        override fun transformPage(page: View, position: Float) {
            page.apply {
                when(page) {
                    is ConstraintLayout -> {
                        // Avoid scaling video item viewpager_item_exoplayer's first element is a ConstraintLayout
                        // So it means we can't use ConstraintLayout as the 1st element of viewpager_item_photo and viewpager_item_gif
                    }
                    else -> {
                        when {
                            position <= -1f -> { // [-Infinity, -1)
                                // This page is way off-screen to the left
                                translationX = 1.1f

                                // Reset scale and alpha to normal
                                alpha = 1f
                                scaleX = 1f
                                scaleY = 1f
                                translationZ = 1f
/*
                                alpha = 0f
                                scaleX = 1f + DREAMY_SCALE_FACTOR
                                scaleY = 1f + DREAMY_SCALE_FACTOR
*/
                            }
                            position < 0f -> { // [-1, 0)
                                // This page is moving off-screen

                                alpha = 1f + position
                                // Counteract the default slide transition
                                translationX = width * -position
                                // Move it above the page on the right, which is the one becoming the center piece
                                translationZ = 1f
                                (DREAMY_SCALE_FACTOR - position).run {
                                    scaleX = this
                                    scaleY = this
                                }
                            }
                            position == 0f -> {
                                alpha = 1f
                                translationX = 0f
                                translationZ = 0f
                                // keep scale factor intact, remove glitch between dreamy zoom and slide zoom out
                            }
                            position < 1f -> { // (0, 1)
                                // This page is moving into screen
                                alpha = 1f - position
                                // Counteract the default slide transition
                                translationX = width * -position
                                translationZ = 0f
                                //(0.5f * (1 - position) + 0.5f).run {
                                //(0.5f * (2f - position)).run {
                                (1f - position * 0.5f).run {
                                    scaleX = this
                                    scaleY = this
                                }
                            }
                            else -> { // [1, +Infinity]
                                // This page is way off-screen to the right
                                translationX = -1.1f
                                translationZ = 1f

                                // Scale and alpha should be normal
                                alpha = 1.0f
                                scaleX = 1.0f
                                scaleY = 1.0f
/*
                                alpha = 0f
                                translationZ = -1f
                                scaleX = 0.5f
                                scaleY = 0.5f
*/
                            }
                        }
                    }
                }
            }
        }
    }

    private class StoryAdapter(context: Context, displayWidth: Int, playerViewModel: VideoPlayerViewModel, private val videoItemLoader: (NCShareViewModel.RemotePhoto) -> VideoItem, imageLoader: (NCShareViewModel.RemotePhoto, ImageView?, String) -> Unit, cancelLoader: (View) -> Unit
    ): SeamlessMediaSliderAdapter<NCShareViewModel.RemotePhoto>(context, displayWidth, PhotoDiffCallback(), playerViewModel, null, imageLoader, cancelLoader) {
        override fun getVideoItem(position: Int): VideoItem = videoItemLoader(getItem(position))
        override fun getItemTransitionName(position: Int): String = getItem(position).photo.id
        override fun getItemMimeType(position: Int): String = getItem(position).photo.mimeType

        fun setPhotos(photos: List<NCShareViewModel.RemotePhoto>, callback: () -> Unit) { submitList(photos.toMutableList()) { callback() }}

        fun isSlideVideo(position: Int): Boolean = try { currentList[position].photo.mimeType.startsWith("video") } catch (_: Exception) { false }
        fun getCaption(position: Int): String = currentList[position].photo.caption

        // Maintaining a map between adapter position and it's ViewHolder
        private val vhMap = HashMap<ViewHolder, Int>()
        fun getViewHolderByPosition(position: Int): ViewHolder? {
            vhMap.entries.forEach { if (it.value == position) return it.key }
            return null
        }
        override fun onViewAttachedToWindow(holder: ViewHolder) {
            super.onViewAttachedToWindow(holder)
            vhMap[holder] = holder.bindingAdapterPosition
        }

        override fun onViewDetachedFromWindow(holder: ViewHolder) {
            vhMap.remove(holder)

            // Remove animatable's callback
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && holder is SeamlessMediaSliderAdapter<*>.AnimatedViewHolder) holder.getAnimatedDrawable()?.clearAnimationCallbacks()

            super.onViewDetachedFromWindow(holder)
        }

        override fun onViewRecycled(holder: ViewHolder) {
            vhMap.remove(holder)
            super.onViewRecycled(holder)
        }

        override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
            vhMap.clear()
            super.onDetachedFromRecyclerView(recyclerView)
        }
    }

    class PhotoDiffCallback: DiffUtil.ItemCallback<NCShareViewModel.RemotePhoto>() {
        override fun areItemsTheSame(oldItem: NCShareViewModel.RemotePhoto, newItem: NCShareViewModel.RemotePhoto): Boolean = oldItem.photo.id == newItem.photo.id
        override fun areContentsTheSame(oldItem: NCShareViewModel.RemotePhoto, newItem: NCShareViewModel.RemotePhoto): Boolean = oldItem.photo.id == newItem.photo.id
    }

    companion object {
        private const val STATE_UNKNOWN = -1
        private const val STATE_STARTED = 0
        private const val STATE_ENDED = 1
        private const val STATE_PAUSED = 2

        private const val CAPTION_PAGE_VIEWING_TIME = 8000
        private const val CAPTION_PAGE_MINIMUM_VIEWING_TIME = 4000L
        private const val DREAMY_SCALE_FACTOR = 1.05f
        private const val KEY_DISPLAY_OPTION = "KEY_DISPLAY_OPTION"
        private const val KEY_START_AT = "KEY_START_AT"

        private const val ARGUMENT_ALBUM = "ALBUM"

        @JvmStatic
        fun newInstance(album: Album) = StoryFragment().apply { arguments = Bundle().apply { putParcelable(ARGUMENT_ALBUM, album) }}
    }
}