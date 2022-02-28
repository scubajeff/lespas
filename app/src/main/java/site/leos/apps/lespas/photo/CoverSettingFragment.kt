package site.leos.apps.lespas.photo

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.util.DisplayMetrics
import android.view.*
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.GestureDetectorCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.ViewModelProvider
import androidx.transition.Fade
import androidx.transition.Slide
import androidx.transition.Transition
import com.google.android.material.floatingactionbutton.FloatingActionButton
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.Cover
import site.leos.apps.lespas.sync.ActionViewModel
import kotlin.math.roundToInt

class CoverSettingFragment : Fragment() {
    private lateinit var albumId: String
    private lateinit var currentPhoto: Photo

    private lateinit var root: ConstraintLayout
    private lateinit var applyButton: FloatingActionButton
    private lateinit var cropArea: ViewGroup
    private lateinit var cropFrameGestureDetector: GestureDetectorCompat
    private lateinit var layoutParams: ConstraintLayout.LayoutParams
    private val currentPhotoModel: PhotoSlideFragment.CurrentPhotoViewModel by activityViewModels()

    private var constraintSet = ConstraintSet()
    private var newBias = 0.5f
    private var scrollTop = 0f
    private var scrollBottom = 1f
    private var screenHeight = 0f
    private var drawableHeight = 0f
    private var frameHeight = 0
    private var upperGap = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        albumId = requireArguments().getString(KEY_ALBUM_ID)!!
        currentPhoto = requireArguments().getParcelable(KEY_PHOTO)!!
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_coversetting, container, false)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        //postponeEnterTransition()
        //view.doOnPreDraw { startPostponedEnterTransition() }

        root = view.findViewById(R.id.root)
        applyButton = view.findViewById(R.id.apply)
        cropArea = view.findViewById(R.id.croparea)
        layoutParams = cropArea.layoutParams as ConstraintLayout.LayoutParams
        constraintSet.clone(root)

        DisplayMetrics().run {
            val screenWidth: Float
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                @Suppress("DEPRECATION")
                requireActivity().windowManager.defaultDisplay.getRealMetrics(this)
                screenWidth = widthPixels.toFloat()
                screenHeight = heightPixels.toFloat()
            } else {
                with(requireActivity().windowManager.currentWindowMetrics.bounds) {
                    screenWidth = width().toFloat()
                    screenHeight = height().toFloat()
                }
            }

            val drawableWidth: Float

            if (screenHeight/currentPhoto.height > screenWidth / currentPhoto.width) {
                drawableHeight = currentPhoto.height * (screenWidth / currentPhoto.width)
                drawableWidth = screenWidth
            } else {
                drawableHeight = screenHeight
                drawableWidth = currentPhoto.width * (screenHeight / currentPhoto.height)
            }
            frameHeight = (drawableWidth * 9 / 21).roundToInt()

            upperGap = ((screenHeight - drawableHeight) / 2).roundToInt()
            scrollTop = upperGap / (screenHeight - frameHeight)
            if (scrollTop < 0f) scrollTop = 0f
            scrollBottom = 1 - scrollTop

            constraintSet.setDimensionRatio(R.id.croparea, "H,${screenWidth.toInt()}:$frameHeight")
        }

        savedInstanceState?.let {
            val oldBias = it.getFloat(BIAS)
            val oldSH = it.getFloat(SH)
            val oldDH = it.getFloat(DH)
            val oldFH = it.getInt(FH)

            newBias = (((drawableHeight / oldDH) * ((oldSH - oldFH) * oldBias - oldSH / 2)) + screenHeight / 2) / (screenHeight - frameHeight)

            constraintSet.setVerticalBias(R.id.croparea, newBias)
        }
        constraintSet.applyTo(root)

        cropFrameGestureDetector = GestureDetectorCompat(activity, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent?): Boolean {
                //Handler(requireContext().mainLooper).post { Snackbar.make(requireActivity().window.decorView, getString(R.string.toast_cover_set_canceled), Snackbar.LENGTH_SHORT).show() }
                currentPhotoModel.coverApplied(false)
                parentFragmentManager.popBackStack()
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent?, distanceX: Float, distanceY: Float): Boolean {
                newBias -= distanceY / screenHeight
                if (newBias < scrollTop) newBias = scrollTop
                if (newBias > scrollBottom) newBias = scrollBottom

                //layoutParams.verticalBias = newBias
                //coverarea.layoutParams = layoutParams
                constraintSet.run {
                    setVerticalBias(R.id.croparea, newBias)
                    applyTo(root)
                }
                return true
            }
        })

        with(root) {
            setOnTouchListener { _, event ->
                cropFrameGestureDetector.onTouchEvent(event)

                if (event.action == MotionEvent.ACTION_UP) {
                    applyButton.run {
                        alpha = 0f
                        animate().alpha(1f).setDuration(300).setListener(object: AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator?) {
                                super.onAnimationEnd(animation)
                                applyButton.visibility = View.VISIBLE
                            }
                        })
                    }
                }
                if (event.action == MotionEvent.ACTION_DOWN) {
                    applyButton.run {
                        alpha = 1f
                        animate().alpha(0f).setDuration(150).setListener(object: AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator?) {
                                super.onAnimationEnd(animation)
                                applyButton.visibility = View.GONE
                            }
                        })
                    }
                }
                false
            }
        }

        applyButton.setOnClickListener {
            currentPhoto.run {
                var baseLine = ((height / drawableHeight) * (((screenHeight - frameHeight) * newBias) - upperGap)).roundToInt()
                if (baseLine < 0) baseLine = 0
                ViewModelProvider(requireActivity())[ActionViewModel::class.java].updateCover(albumId, Cover(id, baseLine, width, height, name, mimeType, orientation))
            }
            currentPhotoModel.coverApplied(true)

            parentFragmentManager.popBackStack()
        }

        enterTransition = Slide().apply {
            //duration = resources.getInteger(android.R.integer.config_shortAnimTime).toLong()
            duration = 0
            //interpolator = AccelerateInterpolator()
            //addTarget(root)
            //excludeTarget(R.id.upper, true)
            //excludeTarget(R.id.lower, true)
            addListener(object : Transition.TransitionListener{
                override fun onTransitionStart(transition: Transition) { applyButton.visibility = View.GONE }
                override fun onTransitionCancel(transition: Transition) {}
                override fun onTransitionPause(transition: Transition) {}
                override fun onTransitionResume(transition: Transition) {}
                override fun onTransitionEnd(transition: Transition) {
                    // TODO: Better way to chain transitions??
                    // Animation crop area, hinting user that it can be moved
                    ConstraintSet().apply {
                        var i = 1
                        val t = AutoTransition()
                        t.interpolator = AccelerateDecelerateInterpolator()
                        t.duration = 500
                        t.addListener(object : android.transition.Transition.TransitionListener {
                            override fun onTransitionStart(transition: android.transition.Transition?) {}
                            override fun onTransitionEnd(transition: android.transition.Transition?) {
                                if (i < 2) {
                                    t.duration = 400
                                    clone(root)
                                    setVerticalBias(R.id.croparea, 0.58f)
                                    TransitionManager.beginDelayedTransition(root, t)
                                    applyTo(root)
                                    i += 1
                                } else if (i < 3) {
                                    t.duration = 200
                                    //t.interpolator = FastOutSlowInInterpolator()
                                    clone(root)
                                    setVerticalBias(R.id.croparea, 0.5f)
                                    TransitionManager.beginDelayedTransition(root, t)
                                    applyTo(root)
                                    applyButton.visibility = View.VISIBLE
                                    i += 1
                                }
                            }

                            override fun onTransitionCancel(transition: android.transition.Transition?) {}
                            override fun onTransitionPause(transition: android.transition.Transition?) {}
                            override fun onTransitionResume(transition: android.transition.Transition?) {}
                        })
                        clone(root)
                        setVerticalBias(R.id.croparea, 0.38f)
                        TransitionManager.beginDelayedTransition(root, t)
                        applyTo(root)
                    }
                }
            })
        }
        returnTransition = Fade().apply {
            duration = resources.getInteger(android.R.integer.config_mediumAnimTime).toLong()
            addTarget(root)
        }

        // Hide system UI
/*
        requireActivity().window.apply {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
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

            } else {
                insetsController?.apply {
                    systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsets.Type.systemBars())
                }
            }
        }
*/
/*
        @Suppress("DEPRECATION")
        requireActivity().window.decorView.systemUiVisibility = (
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
*/
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putFloat(BIAS, newBias)
        outState.putFloat(SH, screenHeight)
        outState.putFloat(DH, drawableHeight)
        outState.putInt(FH, frameHeight)
    }


    companion object {
        private const val BIAS = "BIAS"
        private const val SH = "SH"
        private const val FH = "FH"
        private const val DH = "DH"

        private const val KEY_ALBUM_ID = "KEY_ALBUM_ID"
        private const val KEY_PHOTO = "KEY_PHOTO"

        @JvmStatic
        fun newInstance(albumId: String, photo: Photo) = CoverSettingFragment().apply {
            arguments = Bundle().apply{
                putString(KEY_ALBUM_ID, albumId)
                putParcelable(KEY_PHOTO, photo)
            }
        }
    }
}