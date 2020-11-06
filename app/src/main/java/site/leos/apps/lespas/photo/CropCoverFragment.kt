package site.leos.apps.lespas.photo

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.util.DisplayMetrics
import android.view.*
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.GestureDetectorCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.floatingactionbutton.FloatingActionButton
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.Cover
import site.leos.apps.lespas.album.CoverViewModel

class CropCoverFragment : Fragment() {
    private lateinit var photo: Photo
    private lateinit var root: ConstraintLayout
    private lateinit var applyButton: FloatingActionButton
    private lateinit var cropArea: ViewGroup
    private lateinit var cropFrameGestureDetector: GestureDetectorCompat
    private lateinit var layoutParams: ConstraintLayout.LayoutParams
    private lateinit var coverModel: CoverViewModel
    private var constraintSet = ConstraintSet()
    private var newBias = 0.5f
    private var scrollDistance = 0f
    private var scrollTop = 0f
    private var scrollBottom = 1f

    companion object {
        private const val PHOTO = "PHOTO"
        private const val BIAS = "BIAS"

        @JvmStatic
        fun newInstance(photo: Photo) = CropCoverFragment().apply { arguments = Bundle().apply { putParcelable(PHOTO, photo) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        photo = arguments?.getParcelable(PHOTO)!!

        if (savedInstanceState != null) newBias = savedInstanceState.getFloat(BIAS)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        coverModel = ViewModelProvider(requireActivity()).get(CoverViewModel::class.java)
        coverModel.settingCover()

        return inflater.inflate(R.layout.fragment_cropcover, container, false)
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

            val d = resources.getDrawable(R.drawable.ic_baseline_broken_image_24)
            val sw = widthPixels.toFloat()
            val sh = heightPixels.toFloat()
            var dh: Float
            var fh: Int

            if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
                dh = sw * d.intrinsicHeight/d.intrinsicWidth
                fh = (sw * 9 / 21).toInt()
            } else {
                dh = sh
                fh = (sh * (d.intrinsicWidth / d.intrinsicHeight) * 9 / 21).toInt()
            }

            val ug = (sh - dh) / 2
            val lg = sh - ug
            scrollTop = ug / (sh - fh)
            scrollBottom = (sh - ug - fh) / (sh - fh)
            if (scrollTop < 0f) scrollTop = 0f
            if (scrollBottom >  1f) scrollTop = 1f

            scrollDistance = sh * 12 / 21   // 21 - 9 = 12

            constraintSet.setDimensionRatio(R.id.croparea, "H,$widthPixels:$fh")
        }

        constraintSet.setVerticalBias(R.id.croparea, newBias)
        constraintSet.applyTo(root)

        cropFrameGestureDetector = GestureDetectorCompat(activity, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent?): Boolean {
                Handler(requireContext().mainLooper).post {
                    Toast.makeText(requireContext(), R.string.toast_cover_set_canceled, Toast.LENGTH_SHORT).show()
                }
                parentFragmentManager.popBackStack()
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent?, distanceX: Float, distanceY: Float): Boolean {
                newBias -= distanceY / scrollDistance
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
            setOnTouchListener { v, event ->
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
            // Animation crop area, hinting user that is can be moved
            // TODO: Better way to tom chain transitions??
            if (newBias == 0.5f) {
                post { ConstraintSet().apply {
                    var i = 1
                    val t = AutoTransition()
                    t.duration = 300
                    t.addListener(object: android.transition.Transition.TransitionListener {
                        override fun onTransitionStart(transition: android.transition.Transition?) {}
                        override fun onTransitionEnd(transition: android.transition.Transition?) {
                            if (i < 2) {
                                t.duration = 600
                                clone(root)
                                setVerticalBias(R.id.croparea, 0.55f)
                                TransitionManager.beginDelayedTransition(root, t)
                                applyTo(root)
                                i += 1
                            } else if (i < 3){
                                t.duration = 300
                                clone(root)
                                setVerticalBias(R.id.croparea, 0.5f)
                                TransitionManager.beginDelayedTransition(root, t)
                                applyTo(root)
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
                }}
            }
        }

        applyButton.setOnClickListener {
            coverModel.setCover(Cover(photo.name, (newBias * 100).toInt()))     // TODO: should translate to actual moving distance in pixel
            Handler(requireContext().mainLooper).post {
                Toast.makeText(requireContext(), R.string.toast_cover_applied, Toast.LENGTH_SHORT).show()
            }

            parentFragmentManager.popBackStack()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putFloat(BIAS, newBias)    // TODO: saving bias is not enough
    }

    override fun onDestroyView() {
        coverModel.notSettingCover()
        super.onDestroyView()
    }
}