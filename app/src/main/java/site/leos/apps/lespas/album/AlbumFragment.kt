package site.leos.apps.lespas.album

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import site.leos.apps.lespas.R

class AlbumFragment : Fragment() {
    //private val mAdapter: AlbumListAdapter = AlbumListAdapter()
    private lateinit var mAdapter: AlbumListAdapter
    private lateinit var viewModel: AlbumViewModel

    companion object {
        fun newInstance() = AlbumFragment()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mAdapter = AlbumListAdapter()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_album, container, false)
        mAdapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver(){
            init {
                toggleEmptyView()
            }

            private fun toggleEmptyView() {
                if (mAdapter.itemCount == 0) {
                    view.findViewById<RecyclerView>(R.id.albumlist).visibility = View.GONE
                    view.findViewById<ImageView>(R.id.emptyview).visibility = View.VISIBLE
                } else {
                    view.findViewById<RecyclerView>(R.id.albumlist).visibility = View.VISIBLE
                    view.findViewById<ImageView>(R.id.emptyview).visibility = View.GONE
                }
            }

            override fun onChanged() {
                super.onChanged()
                toggleEmptyView()
            }

            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                super.onItemRangeInserted(positionStart, itemCount)
                toggleEmptyView()
            }

            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
                super.onItemRangeRemoved(positionStart, itemCount)
                toggleEmptyView()
            }
        })

        val recyclerView = view.findViewById<RecyclerView>(R.id.albumlist).apply {
            adapter = mAdapter
        }

        val fab = view.findViewById<FloatingActionButton>(R.id.fab).setOnClickListener{
            //TODO: album fragment fab action
        }

        return view
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        viewModel = ViewModelProvider(this).get(AlbumViewModel::class.java)
        viewModel.allAlbumsByEndDate.observe(viewLifecycleOwner, Observer { albums -> mAdapter.setAlbums(albums)})
    }

    // List adapter for Albums' recyclerView
    class AlbumListAdapter internal constructor(): RecyclerView.Adapter<AlbumListAdapter.AlbumViewHolder>() {
        private var albums = emptyList<Album>()

        inner class AlbumViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
            val coverartView: ImageView = itemView.findViewById(R.id.coverart)
            val titleView: TextView = itemView.findViewById(R.id.title)
            val durationView: TextView = itemView.findViewById(R.id.duration)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlbumListAdapter.AlbumViewHolder {
            val itemView = LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_album, parent,false)
            return AlbumViewHolder(itemView)
        }

        override fun onBindViewHolder(holder: AlbumListAdapter.AlbumViewHolder, position: Int) {
            val current = albums[position]
            holder.titleView.text = current.name
            //holder.durationView.text = String.format("%tF - %tF", current.startDate, current.endDate)
            // TODO: load coverart
        }

        internal fun setAlbums(albums: List<Album>){
            this.albums = albums
            Log.e("======", "setAlbums ${this.albums.size}")
            notifyDataSetChanged()
        }

        override fun getItemCount() = albums.size
    }
}