package site.leos.apps.lespas.helper

import android.view.View
import android.widget.ImageView
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.github.chrisbanes.photoview.PhotoView
import site.leos.apps.lespas.R

class PhotoViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
    fun bind(photo: Any, transitionName: String, imageLoader: (Any, ImageView, String) -> Unit, clickListener:() -> Unit) {
        itemView.findViewById<PhotoView>(R.id.media).apply {
            imageLoader(photo, this, ImageLoaderViewModel.TYPE_FULL)
            setOnPhotoTapListener { _, _, _ -> clickListener() }
            setOnOutsidePhotoTapListener { clickListener() }
            maximumScale = 5.0f
            mediumScale = 2.5f
            ViewCompat.setTransitionName(this, transitionName)
        }
    }
}