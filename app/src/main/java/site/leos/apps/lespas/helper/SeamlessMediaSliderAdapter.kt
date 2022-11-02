/*
 *   Copyright 2019 Jeffrey Liu (scubajeffrey@criptext.com)
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
import android.net.Uri
import android.os.Handler
import android.os.Parcelable
import android.view.*
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.GestureDetectorCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
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
    context: Context,
    private var displayWidth: Int,
    diffCallback: ItemCallback<T>,
    private val playerViewModel: VideoPlayerViewModel,
    private val clickListener: (Boolean?) -> Unit, private val imageLoader: (T, ImageView?, String) -> Unit, private val cancelLoader: (View) -> Unit
): ListAdapter<T, RecyclerView.ViewHolder>(diffCallback) {
    val volumeDrawable = ContextCompat.getDrawable(context, R.drawable.ic_baseline_volume_on_24)
    val brightnessDrawable = ContextCompat.getDrawable(context, R.drawable.ic_baseline_brightness_24)

    var currentVideoView: PlayerView? = null
    var knobLayout: FrameLayout? = null
    var knobIcon: ImageView? = null
    var knobPosition: CircularProgressIndicator? = null
    var forwardMessage: TextView? = null
    var rewindMessage: TextView? = null

    val handler = Handler(context.mainLooper)
    val hideSettingCallback = Runnable { knobLayout?.isVisible = false }
    val hideProgressCallback = Runnable { currentVideoView?.hideController() }
    val hideForwardMessageCallback = Runnable { forwardMessage?.isVisible = false }
    val hideRewindMessageCallback = Runnable { rewindMessage?.isVisible = false }

    val gestureDetector: GestureDetectorCompat
    init {
        gestureDetector = GestureDetectorCompat(context, object: GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (currentVideoView?.isControllerVisible == false) {
                    currentVideoView?.showController()
                    clickListener(true)
                    handler.removeCallbacks(hideProgressCallback)
                    handler.postDelayed(hideProgressCallback, 3000)
                    return true
                }
                return false
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (e.x < displayWidth / 2) {
                    playerViewModel.skip( -5)
                    rewindMessage?.isVisible = true
                    forwardMessage?.isVisible = false
                    handler.removeCallbacks(hideRewindMessageCallback)
                    handler.postDelayed(hideRewindMessageCallback, 1000)
                } else {
                    playerViewModel.skip( 5)
                    forwardMessage?.isVisible = true
                    rewindMessage?.isVisible = false
                    handler.removeCallbacks(hideForwardMessageCallback)
                    handler.postDelayed(hideForwardMessageCallback, 1000)
                }
                return true
            }

            override fun onScroll(e1: MotionEvent, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (abs(distanceX) < abs(distanceY)) {
                    knobLayout?.isVisible = true
                    // Response to vertical scroll only, horizontal scroll reserved for viewpager sliding
                    if (e1.x > displayWidth / 2) {
                        knobIcon?.setImageDrawable(volumeDrawable)
                        playerViewModel.setVolume(distanceY / 200)
                        knobPosition?.progress = (playerViewModel.getVolume() * 100).toInt()
                    }
                    else {
                        knobIcon?.setImageDrawable(brightnessDrawable)
                        playerViewModel.setBrightness(distanceY / 200)
                        knobPosition?.progress = (playerViewModel.getBrightness() * 100).toInt()
                    }

                    handler.removeCallbacks(hideSettingCallback)
                    handler.postDelayed(hideSettingCallback, 1000)
                }
                return true
            }
        })
    }

    abstract fun getVideoItem(position: Int): VideoItem
    abstract fun getItemTransitionName(position: Int): String
    abstract fun getItemMimeType(position: Int): String

    override fun getItemViewType(position: Int): Int {
        with(getItemMimeType(position)) {
            return when {
                this == "image/agif" || this == "image/awebp" -> TYPE_ANIMATED
                this.startsWith("video/") -> TYPE_VIDEO
                else -> TYPE_PHOTO
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when(viewType) {
            TYPE_PHOTO -> PhotoViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.viewpager_item_photo, parent, false), displayWidth)
            TYPE_ANIMATED -> AnimatedViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.viewpager_item_gif, parent, false))
            else-> VideoViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.viewpager_item_exoplayer, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when(holder) {
            is SeamlessMediaSliderAdapter<*>.VideoViewHolder -> holder.bind(getItem(position), getVideoItem(position), imageLoader)
            is SeamlessMediaSliderAdapter<*>.AnimatedViewHolder -> holder.bind(getItem(position), getItemTransitionName(position), imageLoader)
            else-> (holder as SeamlessMediaSliderAdapter<*>.PhotoViewHolder).bind(getItem(position), getItemTransitionName(position), imageLoader)
        }
    }

    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        // Always fired before last shown view's onViewDetachedFromWindow
        super.onViewAttachedToWindow(holder)
        if (holder is SeamlessMediaSliderAdapter<*>.VideoViewHolder) {
            playerViewModel.resume(holder.videoView, holder.videoUri)
            currentVideoView = holder.videoView
            knobLayout = holder.knobLayout
            knobIcon = holder.knobIcon
            knobPosition = holder.knobPosition
            forwardMessage = holder.forwardMessage
            rewindMessage = holder.rewindMessage

            handler.removeCallbacksAndMessages(null)
            clickListener(false)
        }
    }

    override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
        // Last view holder's onViewDetachedFromWindow event always fired after new view holder's onViewAttachedToWindow event, so it's safe to resetVideoViewPlayer here
        if (holder is SeamlessMediaSliderAdapter<*>.VideoViewHolder) {
            playerViewModel.pause(holder.videoUri)
            playerViewModel.resetBrightness()
            holder.videoView.player = null
            holder.knobLayout.isVisible = false
            holder.forwardMessage.isVisible = false
            holder.rewindMessage.isVisible = false
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

    inner class PhotoViewHolder(itemView: View, private val displayWidth: Int): RecyclerView.ViewHolder(itemView) {
        private val ivMedia: PhotoView
        private var baseWidth = 0f
        private var currentWidth = 0
        private var edgeDetected = 0

        init {
            ivMedia = itemView.findViewById<PhotoView>(R.id.media).apply {
                setAllowParentInterceptOnEdge(true)
                maximumScale = 5.0f
                mediumScale = 2.5f

                // Tapping on iamge will zoom out to normal if currently zoomed in, otherwise show bottom menu
                setOnPhotoTapListener { _, _, _ ->
                    if (scale != 1.0f) setScale(1.0f, true)
                    setAllowParentInterceptOnEdge(true)
                    currentWidth = baseWidth.toInt()
                    clickListener(null)
                }
                setOnOutsidePhotoTapListener {
                    if (scale != 1.0f) setScale(1.0f, true)
                    setAllowParentInterceptOnEdge(true)
                    currentWidth = baseWidth.toInt()
                    clickListener(null)
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

        fun <T> bind(photo: T, transitionName: String, imageLoader: (T, ImageView?, String) -> Unit) {
            ivMedia.apply {
                imageLoader(photo, this, NCShareViewModel.TYPE_FULL)
                ViewCompat.setTransitionName(this, transitionName)
            }
        }
    }

    inner class AnimatedViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivMedia: ImageView

        init {
            ivMedia = itemView.findViewById<ImageView>(R.id.media).apply { setOnClickListener { clickListener(null) } }
        }

        fun <T> bind(photo: T, transitionName: String, imageLoader: (T, ImageView?, String) -> Unit) {
            ivMedia.apply {
                imageLoader(photo, this, NCShareViewModel.TYPE_FULL)
                ViewCompat.setTransitionName(this, transitionName)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    inner class VideoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        var videoUri: Uri = Uri.EMPTY
        private var videoMimeType = ""

        val videoView: PlayerView
        val knobLayout: FrameLayout
        val knobIcon: ImageView
        val knobPosition: CircularProgressIndicator
        val forwardMessage: TextView
        val rewindMessage: TextView
        init {
            videoView = itemView.findViewById<PlayerView>(R.id.media).apply {
                setOnTouchListener { _, event -> gestureDetector.onTouchEvent(event) }
            }
            knobLayout = itemView.findViewById(R.id.knob)
            knobIcon = itemView.findViewById(R.id.knob_icon)
            knobPosition = itemView.findViewById(R.id.knob_position)
            forwardMessage = itemView.findViewById(R.id.fast_forward_msg)
            rewindMessage = itemView.findViewById(R.id.fast_rewind_msg)
        }

        fun <T> bind(item: T, video: VideoItem, imageLoader: (T, ImageView?, String) -> Unit) {
            this.videoUri = video.uri
            videoMimeType = video.mimeType

            videoView.apply {
                // Need to call imageLoader here to start postponed enter transition
                ViewCompat.setTransitionName(this, video.transitionName)
                imageLoader(item, null, NCShareViewModel.TYPE_NULL)
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
    }
}