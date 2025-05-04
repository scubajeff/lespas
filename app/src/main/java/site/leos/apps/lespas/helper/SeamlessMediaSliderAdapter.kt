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

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Parcelable
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.DiffUtil.ItemCallback
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.chrisbanes.photoview.PhotoView
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.parcelize.Parcelize
import site.leos.apps.lespas.R
import site.leos.apps.lespas.publication.NCShareViewModel
import kotlin.math.abs

@androidx.annotation.OptIn(UnstableApi::class)
abstract class SeamlessMediaSliderAdapter<T>(
    private val context: Context,
    private var displayWidth: Int,
    diffCallback: ItemCallback<T>,
    private val playerViewModel: VideoPlayerViewModel?,
    private val clickListener: ((Boolean?) -> Unit)?, private val imageLoader: (T, ImageView?, String) -> Unit, private val cancelLoader: (View) -> Unit
): ListAdapter<T, RecyclerView.ViewHolder>(diffCallback) {
    private val volumeDrawable = ContextCompat.getDrawable(context, R.drawable.ic_baseline_volume_on_24)
    private val brightnessDrawable = ContextCompat.getDrawable(context, R.drawable.ic_baseline_brightness_24)

    private var currentVideoView: PlayerView? = null
    private var knobLayout: FrameLayout? = null
    private var knobIcon: ImageView? = null
    private var knobPosition: CircularProgressIndicator? = null
    private var forwardMessage: TextView? = null
    private var rewindMessage: TextView? = null

    private val handler = Handler(context.mainLooper)
    private val hideSettingCallback = Runnable { knobLayout?.isVisible = false }
    private val hideProgressCallback = Runnable { currentVideoView?.hideController() }
    private val hideForwardMessageCallback = Runnable { forwardMessage?.isVisible = false }
    private val hideRewindMessageCallback = Runnable { rewindMessage?.isVisible = false }

    private var gestureDetector: GestureDetector? = null

    private var shouldPauseVideo = true

    init {
        clickListener?.let { cl ->
            gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (currentVideoView?.isControllerFullyVisible == false) {
                        currentVideoView?.showController()
                        cl(true)
                        handler.removeCallbacks(hideProgressCallback)
                        handler.postDelayed(hideProgressCallback, 3000)
                        return true
                    }
                    return false
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    if (e.x < displayWidth / 2) {
                        playerViewModel?.skip(-5)
                        rewindMessage?.isVisible = true
                        forwardMessage?.isVisible = false
                        handler.removeCallbacks(hideRewindMessageCallback)
                        handler.postDelayed(hideRewindMessageCallback, 1000)
                    } else {
                        playerViewModel?.skip(5)
                        forwardMessage?.isVisible = true
                        rewindMessage?.isVisible = false
                        handler.removeCallbacks(hideForwardMessageCallback)
                        handler.postDelayed(hideForwardMessageCallback, 1000)
                    }
                    return true
                }

                override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                    if (e1 != null) {
                        // Ignore flings
                        if (abs(distanceY) > 15) return false

                        if (abs(distanceX) < abs(distanceY)) {
                            knobLayout?.isVisible = true
                            // Response to vertical scroll only, horizontal scroll reserved for viewpager sliding
                            if (e1.x > displayWidth / 2) {
                                knobIcon?.setImageDrawable(volumeDrawable)
                                playerViewModel?.setVolume(distanceY / 300)
                                knobPosition?.progress = (playerViewModel!!.getVolume() * 100).toInt()
                            } else {
                                knobIcon?.setImageDrawable(brightnessDrawable)
                                playerViewModel?.setBrightness(distanceY / 300)
                                knobPosition?.progress = (playerViewModel!!.getBrightness() * 100).toInt()
                            }

                            handler.removeCallbacks(hideSettingCallback)
                            handler.postDelayed(hideSettingCallback, 1000)
                        }
                        return true
                    } else return false
                }
            })
        }
    }

    abstract fun getVideoItem(position: Int): VideoItem
    abstract fun getItemTransitionName(position: Int): String
    abstract fun getItemMimeType(position: Int): String

    override fun getItemViewType(position: Int): Int {
        with(getItemMimeType(position)) {
            return when {
                this == "image/agif" || this == "image/awebp" -> TYPE_ANIMATED
                this == "image/panorama" -> TYPE_PANORAMA
                this.startsWith("video/") -> TYPE_VIDEO
                else -> TYPE_PHOTO
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when(viewType) {
            TYPE_PHOTO -> PhotoViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.viewpager_item_photo, parent, false), displayWidth)
            TYPE_ANIMATED -> AnimatedViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.viewpager_item_gif, parent, false))
            TYPE_PANORAMA -> PanoramaViewHolder(LayoutInflater.from(context).inflate(R.layout.viewpager_item_panorama, parent, false))
            else-> VideoViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.viewpager_item_exoplayer, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when(holder) {
            is SeamlessMediaSliderAdapter<*>.VideoViewHolder -> holder.bind(getItem(position), getVideoItem(position), imageLoader)
            is SeamlessMediaSliderAdapter<*>.AnimatedViewHolder -> holder.bind(getItem(position), getItemTransitionName(position), imageLoader)
            is SeamlessMediaSliderAdapter<*>.PanoramaViewHolder -> holder.bind(getItem(position), getItemTransitionName(position), imageLoader)
            else-> (holder as SeamlessMediaSliderAdapter<*>.PhotoViewHolder).bind(getItem(position), getItemTransitionName(position), imageLoader)
        }
    }
    
    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        super.onViewAttachedToWindow(holder)
        if (holder is SeamlessMediaSliderAdapter<*>.VideoViewHolder) {
            playerViewModel?.resume(holder.videoView, holder.videoUri)
            currentVideoView = holder.videoView
            clickListener?.let {
                knobLayout = holder.knobLayout
                knobIcon = holder.knobIcon
                knobPosition = holder.knobPosition
                forwardMessage = holder.forwardMessage
                rewindMessage = holder.rewindMessage
            }

            handler.removeCallbacksAndMessages(null)
            //clickListener(false)
        }
    }

    override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
        if (holder is SeamlessMediaSliderAdapter<*>.VideoViewHolder) {
            if (shouldPauseVideo) playerViewModel?.pause(holder.videoUri)
            playerViewModel?.resetBrightness()
            holder.videoView.player = null
            clickListener?.let {
                holder.knobLayout.isVisible = false
                holder.forwardMessage.isVisible = false
                holder.rewindMessage.isVisible = false
            }
        }

        super.onViewDetachedFromWindow(holder)
    }

/*
    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        // Cancel loading only works for image
        holder.itemView.findViewById<View>(R.id.media)?.let { if (it is ImageView) cancelLoader(it) }
        super.onViewRecycled(holder)
    }
*/

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        //playerViewModel.resetPlayer()

        for (i in 0 until currentList.size) {
            recyclerView.findViewHolderForAdapterPosition(i)?.let { holder ->
                holder.itemView.findViewById<View>(R.id.media)?.let { cancelLoader(it) }
            }
        }

        super.onDetachedFromRecyclerView(recyclerView)
    }

    fun setDisplayWidth(width: Int) { displayWidth = width }
    fun setPauseVideo(shouldPause: Boolean) { shouldPauseVideo = shouldPause }

    inner class PhotoViewHolder(itemView: View, private val displayWidth: Int): RecyclerView.ViewHolder(itemView) {
        private val ivMedia: PhotoView
        private var baseWidth = 0f
        private var currentWidth = 0
        private var edgeDetected = 0

        init {
            ivMedia = itemView.findViewById<PhotoView>(R.id.media).apply {
                clickListener?.let {
                    setAllowParentInterceptOnEdge(true)
                    maximumScale = 5.0f
                    mediumScale = 2.5f

                    // Tapping on image will zoom out to normal if currently zoomed in, otherwise show bottom menu
                    setOnPhotoTapListener { _, _, _ -> touchHandler(this) }
                    setOnOutsidePhotoTapListener { touchHandler(this) }
                    setOnLongClickListener { view ->
                        touchHandler(view as PhotoView)
                        true
                    }

                    // Disable viewpager2 swipe when in zoom mode
                    setOnScaleChangeListener { _, _, _ ->
                        setAllowParentInterceptOnEdge(abs(1.0f - scale) < 0.05f)
                        if (scale == 1.0f) baseWidth = displayRect.width()
                        currentWidth = (baseWidth * scale).toInt()
                    }

                    // Keep swiping on edge will enable viewpager2 swipe again
                    edgeDetected = 0
                    setOnMatrixChangeListener {
                        if (currentWidth > displayWidth) {
                            when {
                                it.right.toInt() <= displayWidth -> edgeDetected++
                                it.left.toInt() >= 0 -> edgeDetected++
                                else -> {
                                    edgeDetected = 0
                                    setAllowParentInterceptOnEdge(false)
                                }
                            }

                            if (edgeDetected > 30) setAllowParentInterceptOnEdge(true)
                        } else edgeDetected = 0
                    }
                }
            }
        }

        fun <T> bind(photo: T, transitionName: String, imageLoader: (T, ImageView?, String) -> Unit) {
            ivMedia.apply {
                imageLoader(photo, this, NCShareViewModel.TYPE_FULL)
                ViewCompat.setTransitionName(this, transitionName)
            }
        }

        fun getPhotoView() = ivMedia

        private fun touchHandler(photoView: PhotoView) {
            photoView.run {
                if (scale != 1.0f) setScale(1.0f, true)
                setAllowParentInterceptOnEdge(true)
                currentWidth = baseWidth.toInt()
                clickListener?.let { it(null) }
            }
        }
    }

    inner class AnimatedViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivMedia = itemView.findViewById<ImageView>(R.id.media).apply { clickListener?.let { setOnClickListener { it(null) } }}

        fun <T> bind(photo: T, transitionName: String, imageLoader: (T, ImageView?, String) -> Unit) {
            ivMedia.apply {
                imageLoader(photo, this, NCShareViewModel.TYPE_FULL)
                ViewCompat.setTransitionName(this, transitionName)
            }
        }

        @RequiresApi(Build.VERSION_CODES.P)
        fun getAnimatedDrawable(): AnimatedImageDrawable? = try  { ivMedia.drawable as AnimatedImageDrawable } catch (_: ClassCastException) { null }
    }

    @SuppressLint("ClickableViewAccessibility")
    inner class VideoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        var videoUri: Uri = Uri.EMPTY

        val videoView = itemView.findViewById<PlayerView>(R.id.media).apply { gestureDetector?.let { gd -> setOnTouchListener { _, event -> gd.onTouchEvent(event) }}}
        val knobLayout = itemView.findViewById<FrameLayout>(R.id.knob)
        val knobIcon = itemView.findViewById<ImageView>(R.id.knob_icon)
        val knobPosition = itemView.findViewById<CircularProgressIndicator>(R.id.knob_position)
        val forwardMessage = itemView.findViewById<TextView>(R.id.fast_forward_msg)
        val rewindMessage = itemView.findViewById<TextView>(R.id.fast_rewind_msg)
        init {
            playerViewModel?.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    super.onIsPlayingChanged(isPlaying)
                    videoView.keepScreenOn = isPlaying
                }
            })
        }

        fun <T> bind(item: T, video: VideoItem, imageLoader: (T, ImageView?, String) -> Unit) {
            this.videoUri = video.uri

            videoView.apply {
                // Need to call imageLoader here to start postponed enter transition
                ViewCompat.setTransitionName(this, video.transitionName)
                imageLoader(item, null, NCShareViewModel.TYPE_NULL)
            }
        }

        fun play() { playerViewModel?.play() }
        fun pause() { playerViewModel?.pause(videoUri) }
    }

    inner class PanoramaViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivMedia = itemView.findViewById<ImageView>(R.id.media)

        fun <T> bind(photo: T, transitionName: String, imageLoader: (T, ImageView?, String) -> Unit) {
            ivMedia.apply {
                imageLoader(photo, this, NCShareViewModel.TYPE_PANORAMA)
                ViewCompat.setTransitionName(this, transitionName)
            }
        }
    }

    @Parcelize
    data class VideoItem(
        var uri: Uri,
        var mimeType: String,
        var width: Int,
        var height: Int,
        var transitionName: String,
    ): Parcelable

    companion object {
        private const val TYPE_PHOTO = 0
        private const val TYPE_ANIMATED = 1
        private const val TYPE_VIDEO = 2
        private const val TYPE_PANORAMA = 3
    }
}