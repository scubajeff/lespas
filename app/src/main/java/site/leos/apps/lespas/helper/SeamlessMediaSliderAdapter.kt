package site.leos.apps.lespas.helper

import android.net.Uri
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.DiffUtil.ItemCallback
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.github.chrisbanes.photoview.PhotoView
import kotlinx.parcelize.Parcelize
import site.leos.apps.lespas.R
import site.leos.apps.lespas.publication.NCShareViewModel
import kotlin.math.abs

@androidx.annotation.OptIn(UnstableApi::class)
abstract class SeamlessMediaSliderAdapter<T>(
    private var displayWidth: Int,
    diffCallback: ItemCallback<T>,
    private val playerViewModel: VideoPlayerViewModel,
    private val clickListener: (Boolean?) -> Unit, private val imageLoader: (T, ImageView?, String) -> Unit, private val cancelLoader: (View) -> Unit
): ListAdapter<T, RecyclerView.ViewHolder>(diffCallback) {

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
            is SeamlessMediaSliderAdapter<*>.VideoViewHolder -> holder.bind(getItem(position), getVideoItem(position), clickListener, imageLoader)
            is SeamlessMediaSliderAdapter<*>.AnimatedViewHolder -> holder.bind(getItem(position), getItemTransitionName(position), clickListener, imageLoader)
            else-> (holder as SeamlessMediaSliderAdapter<*>.PhotoViewHolder).bind(getItem(position), getItemTransitionName(position), clickListener, imageLoader)
        }
    }

    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        super.onViewAttachedToWindow(holder)
        if (holder is SeamlessMediaSliderAdapter<*>.VideoViewHolder) playerViewModel.resume(holder.videoView, holder.videoUri)
    }

    override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
        // Last view holder's onViewDetachedFromWindow event always fired after new view holder's onViewAttachedToWindow event, so it's safe to resetVideoViewPlayer here
        if (holder is SeamlessMediaSliderAdapter<*>.VideoViewHolder) {
            playerViewModel.pause(holder.videoUri)
            holder.videoView.player = null
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
        private val ivMedia = itemView.findViewById<PhotoView>(R.id.media)
        private var baseWidth = 0f
        private var currentWidth = 0
        private var edgeDetected = 0

        init {
            ivMedia.setAllowParentInterceptOnEdge(true)
        }

        fun <T> bind(photo: T, transitionName: String, clickListener: (Boolean?) -> Unit, imageLoader: (T, ImageView?, String) -> Unit) {
            ivMedia.apply {
                imageLoader(photo, this, NCShareViewModel.TYPE_FULL)
                ViewCompat.setTransitionName(this, transitionName)
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
    }

    inner class AnimatedViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivMedia = itemView.findViewById<ImageView>(R.id.media)

        fun <T> bind(photo: T, transitionName: String, clickListener: (Boolean?) -> Unit, imageLoader: (T, ImageView?, String) -> Unit) {
            ivMedia.apply {
                imageLoader(photo, this, NCShareViewModel.TYPE_FULL)
                setOnClickListener { clickListener(null) }
                ViewCompat.setTransitionName(this, transitionName)
            }
        }
    }

    inner class VideoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        var videoUri: Uri = Uri.EMPTY
        private var videoMimeType = ""

        lateinit var videoView: PlayerView
        private lateinit var muteButton: ImageButton
        private val clVideoViewContainer = itemView.findViewById<ConstraintLayout>(R.id.videoview_container)

        fun <T> bind(item: T, video: VideoItem, clickListener: (Boolean?) -> Unit, imageLoader: (T, ImageView?, String) -> Unit) {
            this.videoUri = video.uri
            videoMimeType = video.mimeType

            muteButton = itemView.findViewById<ImageButton>(R.id.exo_mute).apply {
                isActivated = !playerViewModel.isMuted()
                setOnClickListener {
                    playerViewModel.toggleMuteState()
                    muteButton.isActivated = muteButton.isActivated == false
                }
            }
            // Muted by default during late night hours
            muteButton.isActivated = !playerViewModel.isMuted()

            videoView = itemView.findViewById<PlayerView>(R.id.media).apply {
                hideController()
                controllerShowTimeoutMs = CONTROLLER_VIEW_TIMEOUT
                setOnClickListener { clickListener(!videoView.isControllerVisible) }

                // Need to call imageLoader here to start postponed enter transition
                ViewCompat.setTransitionName(this, video.transitionName)
                imageLoader(item, null, NCShareViewModel.TYPE_NULL)
            }

            clVideoViewContainer.setOnClickListener { clickListener(!videoView.isControllerVisible) }
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

        private const val CONTROLLER_VIEW_TIMEOUT = 3000
    }
}