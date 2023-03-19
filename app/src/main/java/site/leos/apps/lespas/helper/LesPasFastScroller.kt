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

package site.leos.apps.lespas.helper

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.animation.ValueAnimator.AnimatorUpdateListener
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import android.view.MotionEvent
import androidx.annotation.IntDef
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.ItemDecoration
import androidx.recyclerview.widget.RecyclerView.OnItemTouchListener
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// Cloned from Android source. Fixed thumb size
class LesPasFastScroller(recyclerView: RecyclerView, val mVerticalThumbDrawable: StateListDrawable, val verticalTrackDrawable: Drawable, private val mHorizontalThumbDrawable: StateListDrawable, val horizontalTrackDrawable: Drawable, defaultWidth: Int, scrollbarMinimumRange: Int, margin: Int, thumbSize: Int
) : ItemDecoration(), OnItemTouchListener {
    @IntDef(STATE_HIDDEN, STATE_VISIBLE, STATE_DRAGGING)
    private annotation class State

    @IntDef(DRAG_X, DRAG_Y, DRAG_NONE)
    private annotation class DragState

    @IntDef(ANIMATION_STATE_OUT, ANIMATION_STATE_FADING_IN, ANIMATION_STATE_IN, ANIMATION_STATE_FADING_OUT)
    private annotation class AnimationState

    private val mScrollbarMinimumRange: Int
    private val mMargin: Int

    private val mVerticalThumbWidth: Int
    private val mVerticalTrackWidth: Int

    private val mHorizontalThumbHeight: Int
    private val mHorizontalTrackHeight: Int

    // Dynamic values for the vertical scroll bar
    private val mVerticalThumbHeight: Int
    var mVerticalThumbCenterY = 0
    var mVerticalDragY = 0f

    // Dynamic values for the horizontal scroll bar
    private val mHorizontalThumbWidth: Int
    var mHorizontalThumbCenterX = 0
    var mHorizontalDragX = 0f

    private var mRecyclerViewWidth = 0
    private var mRecyclerViewHeight = 0
    private var mRecyclerView: RecyclerView? = null

    /**
     * Whether the document is long/wide enough to require scrolling. If not, we don't show the
     * relevant scroller.
     */
    private var mNeedVerticalScrollbar = false
    private var mNeedHorizontalScrollbar = false

    @State
    private var mState = STATE_HIDDEN

    @DragState
    private var mDragState = DRAG_NONE

    private val mVerticalRange = IntArray(2)
    private val mHorizontalRange = IntArray(2)
    val mShowHideAnimator: ValueAnimator = ValueAnimator.ofFloat(0f, 1f)

    var mAnimationState = ANIMATION_STATE_OUT
    private val mHideRunnable = Runnable { hide(HIDE_DURATION_MS) }
    private val mOnScrollListener: RecyclerView.OnScrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            updateScrollPosition(
                recyclerView.computeHorizontalScrollOffset(),
                recyclerView.computeVerticalScrollOffset()
            )
        }
    }

    private fun attachToRecyclerView(recyclerView: RecyclerView) {
        if (mRecyclerView == recyclerView) return  // nothing to do
        if (mRecyclerView != null) destroyCallbacks()

        mRecyclerView = recyclerView
        if (mRecyclerView != null) setupCallbacks()
    }

    private fun setupCallbacks() {
        mRecyclerView!!.addItemDecoration(this)
        mRecyclerView!!.addOnItemTouchListener(this)
        mRecyclerView!!.addOnScrollListener(mOnScrollListener)
    }

    private fun destroyCallbacks() {
        mRecyclerView!!.removeItemDecoration(this)
        mRecyclerView!!.removeOnItemTouchListener(this)
        mRecyclerView!!.removeOnScrollListener(mOnScrollListener)
        cancelHide()
    }

    fun requestRedraw() { mRecyclerView!!.invalidate() }

    fun setState(@State state: Int) {
        if (state == STATE_DRAGGING && mState != STATE_DRAGGING) {
            mVerticalThumbDrawable.state = PRESSED_STATE_SET
            cancelHide()
        }

        if (state == STATE_HIDDEN) requestRedraw()
        else show()

        if (mState == STATE_DRAGGING && state != STATE_DRAGGING) {
            mVerticalThumbDrawable.state = EMPTY_STATE_SET
            resetHideDelay(HIDE_DELAY_AFTER_DRAGGING_MS)
        } else if (state == STATE_VISIBLE) resetHideDelay(HIDE_DELAY_AFTER_VISIBLE_MS)

        mState = state
    }

    private val isLayoutRTL: Boolean = mRecyclerView?.layoutManager?.layoutDirection == ViewCompat.LAYOUT_DIRECTION_RTL
    val isDragging: Boolean = mState == STATE_DRAGGING
    val isVisible: Boolean = mState == STATE_VISIBLE

    fun show() {
        when (mAnimationState) {
            ANIMATION_STATE_FADING_OUT -> {
                mShowHideAnimator.cancel()
                mAnimationState = ANIMATION_STATE_FADING_IN
                mShowHideAnimator.setFloatValues(mShowHideAnimator.animatedValue as Float, 1f)
                mShowHideAnimator.duration = SHOW_DURATION_MS.toLong()
                mShowHideAnimator.startDelay = 0
                mShowHideAnimator.start()
            }
            ANIMATION_STATE_OUT -> {
                mAnimationState = ANIMATION_STATE_FADING_IN
                mShowHideAnimator.setFloatValues(mShowHideAnimator.animatedValue as Float, 1f)
                mShowHideAnimator.duration = SHOW_DURATION_MS.toLong()
                mShowHideAnimator.startDelay = 0
                mShowHideAnimator.start()
            }
        }
    }

    fun hide(duration: Int) {
        when (mAnimationState) {
            ANIMATION_STATE_FADING_IN -> {
                mShowHideAnimator.cancel()
                mAnimationState = ANIMATION_STATE_FADING_OUT
                mShowHideAnimator.setFloatValues(mShowHideAnimator.animatedValue as Float, 0f)
                mShowHideAnimator.duration = duration.toLong()
                mShowHideAnimator.start()
            }
            ANIMATION_STATE_IN -> {
                mAnimationState = ANIMATION_STATE_FADING_OUT
                mShowHideAnimator.setFloatValues(mShowHideAnimator.animatedValue as Float, 0f)
                mShowHideAnimator.duration = duration.toLong()
                mShowHideAnimator.start()
            }
        }
    }

    private fun cancelHide() { mRecyclerView!!.removeCallbacks(mHideRunnable) }

    private fun resetHideDelay(delay: Int) {
        cancelHide()
        mRecyclerView!!.postDelayed(mHideRunnable, delay.toLong())
    }

    override fun onDrawOver(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        if (mRecyclerViewWidth != mRecyclerView!!.width || mRecyclerViewHeight != mRecyclerView!!.height) {
            mRecyclerViewWidth = mRecyclerView!!.width
            mRecyclerViewHeight = mRecyclerView!!.height
            // This is due to the different events ordering when keyboard is opened or
            // retracted vs rotate. Hence to avoid corner cases we just disable the
            // scroller when size changed, and wait until the scroll position is recomputed
            // before showing it back.
            setState(STATE_HIDDEN)
            return
        }
        if (mAnimationState != ANIMATION_STATE_OUT) {
            if (mNeedVerticalScrollbar) drawVerticalScrollbar(canvas)
            if (mNeedHorizontalScrollbar) drawHorizontalScrollbar(canvas)
        }
    }

    private fun drawVerticalScrollbar(canvas: Canvas) {
        val viewWidth = mRecyclerViewWidth
        val left = (viewWidth - mVerticalThumbWidth).toFloat()
        val top = (mVerticalThumbCenterY - mVerticalThumbHeight / 2).toFloat()
        mVerticalThumbDrawable.setBounds(0, 0, mVerticalThumbWidth, mVerticalThumbHeight)
        verticalTrackDrawable.setBounds(0, 0, mVerticalTrackWidth, mRecyclerViewHeight)
        if (isLayoutRTL) {
            verticalTrackDrawable.draw(canvas)
            canvas.translate(mVerticalThumbWidth.toFloat(), top)
            canvas.scale(-1f, 1f)
            mVerticalThumbDrawable.draw(canvas)
            canvas.scale(-1f, 1f)
            canvas.translate((-mVerticalThumbWidth).toFloat(), -top)
        } else {
            canvas.translate(left, 0f)
            verticalTrackDrawable.draw(canvas)
            canvas.translate(0f, top)
            mVerticalThumbDrawable.draw(canvas)
            canvas.translate(-left, -top)
        }
    }

    private fun drawHorizontalScrollbar(canvas: Canvas) {
        val viewHeight = mRecyclerViewHeight
        val top = (viewHeight - mHorizontalThumbHeight).toFloat()
        val left = (mHorizontalThumbCenterX - mHorizontalThumbWidth / 2).toFloat()
        mHorizontalThumbDrawable.setBounds(0, 0, mHorizontalThumbWidth, mHorizontalThumbHeight)
        horizontalTrackDrawable.setBounds(0, 0, mRecyclerViewWidth, mHorizontalTrackHeight)
        canvas.translate(0f, top)
        horizontalTrackDrawable.draw(canvas)
        canvas.translate(left, 0f)
        mHorizontalThumbDrawable.draw(canvas)
        canvas.translate(-left, -top)
    }

    /**
     * Notify the scroller of external change of the scroll, e.g. through dragging or flinging on
     * the view itself.
     *
     * @param offsetX The new scroll X offset.
     * @param offsetY The new scroll Y offset.
     */
    fun updateScrollPosition(offsetX: Int, offsetY: Int) {
        val verticalContentLength = mRecyclerView!!.computeVerticalScrollRange().toFloat()
        val verticalVisibleLength = mRecyclerViewHeight.toFloat()
        mNeedVerticalScrollbar = (verticalContentLength - verticalVisibleLength > 0 && mRecyclerViewHeight >= mScrollbarMinimumRange)
        val horizontalContentLength = mRecyclerView!!.computeHorizontalScrollRange().toFloat()
        val horizontalVisibleLength = mRecyclerViewWidth.toFloat()
        mNeedHorizontalScrollbar = (horizontalContentLength - horizontalVisibleLength > 0 && mRecyclerViewWidth >= mScrollbarMinimumRange)
        if (!mNeedVerticalScrollbar && !mNeedHorizontalScrollbar) {
            if (mState != STATE_HIDDEN) { setState(STATE_HIDDEN) }
            return
        }
        if (mNeedVerticalScrollbar) {
/*
            val middleScreenPos = offsetY + verticalVisibleLength / 2.0f
            mVerticalThumbCenterY = (verticalVisibleLength * middleScreenPos / verticalContentLength).toInt()
            mVerticalThumbHeight = min(verticalVisibleLength,verticalVisibleLength * verticalVisibleLength / verticalContentLength)
*/
            mVerticalThumbCenterY = min((offsetY * (verticalVisibleLength / (verticalContentLength - verticalVisibleLength))).toInt() + mVerticalThumbHeight / 2, verticalVisibleLength.toInt() - mVerticalThumbHeight / 2)
        }
        if (mNeedHorizontalScrollbar) {
/*
            val middleScreenPos = offsetX + horizontalVisibleLength / 2.0f
            mHorizontalThumbCenterX = (horizontalVisibleLength * middleScreenPos / horizontalContentLength).toInt()
            mHorizontalThumbWidth = min(horizontalVisibleLength, horizontalVisibleLength * horizontalVisibleLength / horizontalContentLength)
*/
            mHorizontalThumbCenterX = min((offsetX * (horizontalVisibleLength / (horizontalContentLength - horizontalVisibleLength))).toInt() + mHorizontalThumbWidth / 2, horizontalVisibleLength.toInt() - mHorizontalThumbWidth / 2)
        }
        if (mState == STATE_HIDDEN || mState == STATE_VISIBLE) setState(STATE_VISIBLE)
    }

    override fun onInterceptTouchEvent(recyclerView: RecyclerView, ev: MotionEvent): Boolean {
        val handled: Boolean
        if (mState == STATE_VISIBLE) {
            val insideVerticalThumb = isPointInsideVerticalThumb(ev.x, ev.y)
            val insideHorizontalThumb = isPointInsideHorizontalThumb(ev.x, ev.y)
            if (ev.action == MotionEvent.ACTION_DOWN && (insideVerticalThumb || insideHorizontalThumb)) {
                if (insideHorizontalThumb) {
                    mDragState = DRAG_X
                    mHorizontalDragX = ev.x.toInt().toFloat()
                } else if (insideVerticalThumb) {
                    mDragState = DRAG_Y
                    mVerticalDragY = ev.y.toInt().toFloat()
                }
                setState(STATE_DRAGGING)
                handled = true
            } else handled = false
        } else handled = mState == STATE_DRAGGING
        return handled
    }

    override fun onTouchEvent(recyclerView: RecyclerView, me: MotionEvent) {
        if (mState == STATE_HIDDEN) {
            return
        }
        if (me.action == MotionEvent.ACTION_DOWN) {
            val insideVerticalThumb = isPointInsideVerticalThumb(me.x, me.y)
            val insideHorizontalThumb = isPointInsideHorizontalThumb(me.x, me.y)
            if (insideVerticalThumb || insideHorizontalThumb) {
                if (insideHorizontalThumb) {
                    mDragState = DRAG_X
                    mHorizontalDragX = me.x.toInt().toFloat()
                } else if (insideVerticalThumb) {
                    mDragState = DRAG_Y
                    mVerticalDragY = me.y.toInt().toFloat()
                }
                setState(STATE_DRAGGING)
            }
        } else if (me.action == MotionEvent.ACTION_UP && mState == STATE_DRAGGING) {
            mVerticalDragY = 0f
            mHorizontalDragX = 0f
            setState(STATE_VISIBLE)
            mDragState = DRAG_NONE
        } else if (me.action == MotionEvent.ACTION_MOVE && mState == STATE_DRAGGING) {
            show()
            if (mDragState == DRAG_X) horizontalScrollTo(me.x)
            if (mDragState == DRAG_Y) verticalScrollTo(me.y)
        }
    }

    override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
    private fun verticalScrollTo(yPos: Float) {
        var y = yPos
        val scrollbarRange = verticalRange
        y = max(scrollbarRange[0].toFloat(), min(scrollbarRange[1].toFloat(), y))
        if (abs(mVerticalThumbCenterY - y) < 2) return
        val scrollingBy = scrollTo(
            mVerticalDragY, y, scrollbarRange,
            mRecyclerView!!.computeVerticalScrollRange(),
            mRecyclerView!!.computeVerticalScrollOffset(), mRecyclerViewHeight
        )
        if (scrollingBy != 0) mRecyclerView!!.scrollBy(0, scrollingBy)
        mVerticalDragY = y
    }

    private fun horizontalScrollTo(xPos: Float) {
        var x = xPos
        val scrollbarRange = horizontalRange
        x = max(scrollbarRange[0].toFloat(), min(scrollbarRange[1].toFloat(), x))
        if (abs(mHorizontalThumbCenterX - x) < 2) return
        val scrollingBy = scrollTo(
            mHorizontalDragX, x, scrollbarRange,
            mRecyclerView!!.computeHorizontalScrollRange(),
            mRecyclerView!!.computeHorizontalScrollOffset(), mRecyclerViewWidth
        )
        if (scrollingBy != 0) mRecyclerView!!.scrollBy(scrollingBy, 0)
        mHorizontalDragX = x
    }

    private fun scrollTo(oldDragPos: Float, newDragPos: Float, scrollbarRange: IntArray, scrollRange: Int, scrollOffset: Int, viewLength: Int): Int {
        val scrollbarLength = scrollbarRange[1] - scrollbarRange[0]
        if (scrollbarLength == 0) return 0

        val percentage = (newDragPos - oldDragPos) / scrollbarLength.toFloat()
        val totalPossibleOffset = scrollRange - viewLength
        val scrollingBy = (percentage * totalPossibleOffset).toInt()
        val absoluteOffset = scrollOffset + scrollingBy

        return if (absoluteOffset in 0 until totalPossibleOffset) scrollingBy else 0
    }

    private fun isPointInsideVerticalThumb(x: Float, y: Float): Boolean =
        (if (isLayoutRTL) x <= mVerticalThumbWidth else x >= mRecyclerViewWidth - 2 * mVerticalThumbWidth) && y >= mVerticalThumbCenterY - mVerticalThumbHeight / 2 && y <= mVerticalThumbCenterY + mVerticalThumbHeight / 2

    private fun isPointInsideHorizontalThumb(x: Float, y: Float): Boolean =
        y >= mRecyclerViewHeight - 4 * mHorizontalThumbHeight && x >= mHorizontalThumbCenterX - mHorizontalThumbWidth / 2 && x <= mHorizontalThumbCenterX + mHorizontalThumbWidth / 2

    /**
     * Gets the (min, max) vertical positions of the vertical scroll bar.
     */
    private val verticalRange: IntArray
        get() {
            mVerticalRange[0] = mMargin
            mVerticalRange[1] = mRecyclerViewHeight - mMargin
            return mVerticalRange
        }

    /**
     * Gets the (min, max) horizontal positions of the horizontal scroll bar.
     */
    private val horizontalRange: IntArray
        get() {
            mHorizontalRange[0] = mMargin
            mHorizontalRange[1] = mRecyclerViewWidth - mMargin
            return mHorizontalRange
        }

    private inner class AnimatorListener : AnimatorListenerAdapter() {
        private var mCanceled = false
        override fun onAnimationEnd(animation: Animator) {
            // Cancel is always followed by a new directive, so don't update state.
            if (mCanceled) {
                mCanceled = false
                return
            }
            if (mShowHideAnimator.animatedValue as Float == 0f) {
                mAnimationState = ANIMATION_STATE_OUT
                setState(STATE_HIDDEN)
            } else {
                mAnimationState = ANIMATION_STATE_IN
                requestRedraw()
            }
        }

        override fun onAnimationCancel(animation: Animator) {
            mCanceled = true
        }
    }

    private inner class AnimatorUpdater: AnimatorUpdateListener {
        override fun onAnimationUpdate(valueAnimator: ValueAnimator) {
            val alpha = (SCROLLBAR_FULL_OPAQUE * valueAnimator.animatedValue as Float).toInt()
            mVerticalThumbDrawable.alpha = alpha
            verticalTrackDrawable.alpha = alpha
            requestRedraw()
        }
    }

    companion object {
        // Scroll thumb not showing
        private const val STATE_HIDDEN = 0
        // Scroll thumb visible and moving along with the scrollbar
        private const val STATE_VISIBLE = 1
        // Scroll thumb being dragged by user
        private const val STATE_DRAGGING = 2

        private const val DRAG_NONE = 0
        private const val DRAG_X = 1
        private const val DRAG_Y = 2

        private const val ANIMATION_STATE_OUT = 0
        private const val ANIMATION_STATE_FADING_IN = 1
        private const val ANIMATION_STATE_IN = 2
        private const val ANIMATION_STATE_FADING_OUT = 3

        private const val SHOW_DURATION_MS = 500
        private const val HIDE_DELAY_AFTER_VISIBLE_MS = 500
        private const val HIDE_DELAY_AFTER_DRAGGING_MS = 1000
        private const val HIDE_DURATION_MS = 500

        private const val SCROLLBAR_FULL_OPAQUE = 255

        private val PRESSED_STATE_SET = intArrayOf(android.R.attr.state_pressed)
        private val EMPTY_STATE_SET = intArrayOf()
    }

    init {
        mVerticalThumbWidth = max(defaultWidth, mVerticalThumbDrawable.intrinsicWidth)
        mVerticalTrackWidth = max(defaultWidth, verticalTrackDrawable.intrinsicWidth)
        mHorizontalThumbHeight = max(defaultWidth, mHorizontalThumbDrawable.intrinsicWidth)
        mHorizontalTrackHeight = max(defaultWidth, horizontalTrackDrawable.intrinsicWidth)
        mScrollbarMinimumRange = scrollbarMinimumRange
        mMargin = 0
        mVerticalThumbHeight = thumbSize
        mHorizontalThumbWidth = thumbSize
        mVerticalThumbDrawable.alpha = SCROLLBAR_FULL_OPAQUE
        verticalTrackDrawable.alpha = SCROLLBAR_FULL_OPAQUE
        mShowHideAnimator.addListener(AnimatorListener())
        mShowHideAnimator.addUpdateListener(AnimatorUpdater())
        attachToRecyclerView(recyclerView)
    }
}