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
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.GestureDetectorCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.floatingactionbutton.FloatingActionButton
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.AlbumViewModel
import site.leos.apps.lespas.album.Cover
import kotlin.math.roundToInt

class CoverSettingFragment : Fragment() {
    private lateinit var albumId: String
    private lateinit var root: ConstraintLayout
    private lateinit var applyButton: FloatingActionButton
    private lateinit var cropArea: ViewGroup
    private lateinit var cropFrameGestureDetector: GestureDetectorCompat
    private lateinit var layoutParams: ConstraintLayout.LayoutParams
    private val currentPhoto: PhotoSlideFragment.CurrentPhotoViewModel by activityViewModels()

    private var constraintSet = ConstraintSet()
    private var newBias = 0.5f
    //private var scrollDistance = 0f
    private var scrollTop = 0f
    private var scrollBottom = 1f
    private var screenHeight = 0f
    private var drawableHeight = 0f
    private var frameHeight = 0
    private var upperGap = 0

    companion object {
        private const val BIAS = "BIAS"
        private const val ALBUM_ID = "ALBUM_ID"

        fun newInstance(albumId: String) = CoverSettingFragment().apply { arguments = Bundle().apply{ putString(ALBUM_ID, albumId) }}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        albumId = arguments?.getString(ALBUM_ID)!!

        if (savedInstanceState != null) newBias = savedInstanceState.getFloat(BIAS)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_coversetting, container, false)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        root = view.findViewById(R.id.root)
        applyButton = view.findViewById(R.id.apply)
        cropArea = view.findViewById(R.id.croparea)
        layoutParams = cropArea.layoutParams as ConstraintLayout.LayoutParams
        constraintSet.clone(root)

        DisplayMetrics().run {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                requireActivity().windowManager.defaultDisplay.getRealMetrics(this)
            } else {
                requireActivity().display?.getRealMetrics(this)
            }

            val d = currentPhoto.getCurrentPhoto().value!!
            val screenWidth = widthPixels.toFloat()
            screenHeight = heightPixels.toFloat()
            val drawableWidth: Float

            if (screenHeight/d.height > screenWidth / d.width) {
                drawableHeight = d.height * (screenWidth / d.width)
                drawableWidth = screenWidth
            } else {
                drawableHeight = screenHeight
                drawableWidth = d.width * (screenHeight / d.height)
            }
            frameHeight = (drawableWidth * 9 / 21).roundToInt()

            upperGap = ((screenHeight - drawableHeight) / 2).roundToInt()
            scrollTop = upperGap / (screenHeight - frameHeight)
            if (scrollTop < 0f) scrollTop = 0f
            scrollBottom = 1 - scrollTop

            constraintSet.setDimensionRatio(R.id.croparea, "H,$widthPixels:$frameHeight")
        }

        constraintSet.setVerticalBias(R.id.croparea, newBias)
        constraintSet.applyTo(root)

        cropFrameGestureDetector = GestureDetectorCompat(activity, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent?): Boolean {
                //Handler(requireContext().mainLooper).post { Snackbar.make(requireActivity().window.decorView, getString(R.string.toast_cover_set_canceled), Snackbar.LENGTH_SHORT).show() }
                currentPhoto.coverApplied(false)
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

        root.run {
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

        // Animation crop area, hinting user that it can be moved
        if (newBias == 0.5f) {
            val duration = resources.getInteger(android.R.integer.config_shortAnimTime).toLong()
            applyButton.alpha = 0f
            applyButton.visibility = View.GONE
            applyButton.animate().alpha(1f).setDuration(duration).setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator?) {
                    super.onAnimationEnd(animation)

                    // TODO: Better way to chain transitions??
                    ConstraintSet().apply {
                        var i = 1
                        val t = AutoTransition()
                        t.duration = duration
                        t.addListener(object : android.transition.Transition.TransitionListener {
                            override fun onTransitionStart(transition: android.transition.Transition?) {}
                            override fun onTransitionEnd(transition: android.transition.Transition?) {
                                if (i < 2) {
                                    t.duration = duration * 2
                                    clone(root)
                                    setVerticalBias(R.id.croparea, 0.55f)
                                    TransitionManager.beginDelayedTransition(root, t)
                                    applyTo(root)
                                    i += 1
                                } else if (i < 3) {
                                    t.duration = duration
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
                        setVerticalBias(R.id.croparea, 0.45f)
                        TransitionManager.beginDelayedTransition(root, t)
                        applyTo(root)
                    }
                }
            })
        }

        applyButton.setOnClickListener {
            currentPhoto.getCurrentPhoto().value!!.run {
                val baseLine = ((height / drawableHeight) * (((screenHeight - frameHeight) * newBias) - upperGap)).roundToInt()
                ViewModelProvider(requireActivity()).get(AlbumViewModel::class.java).setCover(albumId, Cover(id, baseLine, width, height))
            }
            //Snackbar.make(requireActivity().window.decorView, getString(R.string.toast_cover_applied), Snackbar.LENGTH_SHORT).show()
            currentPhoto.coverApplied(true)

            parentFragmentManager.popBackStack()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putFloat(BIAS, newBias)    // TODO: saving bias is not enough
    }
}