package site.leos.apps.lespas.helper

import android.view.View
import android.widget.ImageView
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import site.leos.apps.lespas.R

class AnimatedViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
    fun bind(photo: Any, transitionName: String, imageLoader: (Any, ImageView, String) -> Unit, clickListener:() -> Unit) {
        itemView.findViewById<ImageView>(R.id.media).apply {
            imageLoader(photo, this, ImageLoaderViewModel.TYPE_FULL)
            setOnClickListener { clickListener() }
            ViewCompat.setTransitionName(this, transitionName)
        }
    }
}