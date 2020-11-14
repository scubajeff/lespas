package site.leos.apps.lespas.sync

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.recyclerview.widget.RecyclerView
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.AlbumNameAndId
import site.leos.apps.lespas.album.AlbumViewModel

class DestinationDialogFragment(): DialogFragment() {
    private lateinit var albumAdapter: AlbumNameAdapter
    private val albumNameModel: AlbumViewModel by viewModels()
    private val destinationModel: DestinationViewModel by activityViewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        albumAdapter = AlbumNameAdapter(object : AlbumNameAdapter.OnItemClickListener {
            override fun onItemClick(albumId: AlbumNameAndId) { destinationModel.setDestination(albumId) }
        })
        albumNameModel.allAlbumNamesAndIds.observe(this, { albums-> albumAdapter.setAlbumNames(albums)})
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_destination_dialog, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<RecyclerView>(R.id.destination).adapter = albumAdapter
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)

        // If called by UploadActivity, quit immediately, otherwise return normally
        if (tag == UploadActivity.TAG) activity?.finish()
    }

    class AlbumNameAdapter(private val itemClickListener: OnItemClickListener): RecyclerView.Adapter<AlbumNameAdapter.DestViewHolder>() {
        private var albums = emptyList<AlbumNameAndId>()

        interface OnItemClickListener {
            fun onItemClick(album: AlbumNameAndId)
        }

        inner class DestViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
            fun bindViewItems(position: Int, clickListener: OnItemClickListener) {
                itemView.run {
                    if (position == albums.size) {
                        findViewById<AppCompatImageView>(R.id.cover).apply {
                            setImageResource(R.drawable.ic_baseline_add_24)
                            setColorFilter(0x89000000.toInt(), android.graphics.PorterDuff.Mode.MULTIPLY)   // #89000000 is android's secondaryTextColor, matching text color setting in layout
                        }
                        findViewById<AppCompatTextView>(R.id.name).apply {
                            text = resources.getString(R.string.create_new_album)

                        }
                        setOnClickListener { clickListener.onItemClick(AlbumNameAndId("", "")) }
                    } else {
                        findViewById<AppCompatImageView>(R.id.cover).apply {
                            visibility = View.VISIBLE
                            setImageResource(R.drawable.ic_footprint)
                            clearColorFilter()
                        }
                        findViewById<AppCompatTextView>(R.id.name).text = albums[position].name
                        setOnClickListener { clickListener.onItemClick(albums[position]) }
                    }
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DestViewHolder {
            return DestViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_destination, parent, false))
        }

        override fun onBindViewHolder(holder: DestViewHolder, position: Int) {
            holder.bindViewItems(position, itemClickListener)
        }

        override fun getItemCount(): Int = albums.size + 1

        fun setAlbumNames(albums: List<AlbumNameAndId>) {
            this.albums = albums
            notifyDataSetChanged()
        }
    }

    class DestinationViewModel: ViewModel() {
        private var destination = MutableLiveData<AlbumNameAndId>()

        fun setDestination(newDestination: AlbumNameAndId) { this.destination.value = newDestination }
        fun getDestination(): LiveData<AlbumNameAndId> = destination
    }

    companion object {
        fun newInstance() = DestinationDialogFragment()
    }
}