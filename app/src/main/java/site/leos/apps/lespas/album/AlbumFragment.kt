package site.leos.apps.lespas.album

import android.os.Bundle
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
import site.leos.apps.lespas.photo.PhotoListFragment

class AlbumFragment : Fragment() {
    private lateinit var mAdapter: AlbumListAdapter
    private lateinit var viewModel: AlbumViewModel

    companion object {
        fun newInstance() = AlbumFragment()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mAdapter = AlbumListAdapter(object: AlbumListAdapter.OnItemClickListener {
            override fun onItemClick(album: Album) {
                parentFragmentManager.beginTransaction().replace(R.id.container_root, PhotoListFragment.newInstance(album.id)).addToBackStack(null).commit()
            }
        })
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

        view.findViewById<RecyclerView>(R.id.albumlist).apply {
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
    class AlbumListAdapter(private val itemClickListener: OnItemClickListener): RecyclerView.Adapter<AlbumListAdapter.AlbumViewHolder>() {
        private var albums = emptyList<Album>()

        interface OnItemClickListener {
            fun onItemClick(album: Album)
        }

        inner class AlbumViewHolder(private val itemView: View): RecyclerView.ViewHolder(itemView) {
            fun bindViewItems(album: Album, clickListener: OnItemClickListener) {
                itemView.apply {
                    findViewById<TextView>(R.id.title).text = album.name
                    //findViewById<TextView>(R.id.duration).text = String.format("%tF - %tF", album.startDate, album.endDate)
                    findViewById<ImageView>(R.id.coverart).apply {
                        setImageResource(R.drawable.ic_footprint)
                        scrollBy(0, 200)
                    }
                    setOnClickListener { clickListener.onItemClick(album) }
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlbumListAdapter.AlbumViewHolder  {
            val itemView = LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_album, parent,false)
            return AlbumViewHolder(itemView)
        }

        override fun onBindViewHolder(holder: AlbumListAdapter.AlbumViewHolder, position: Int) {
            holder.bindViewItems(albums[position], itemClickListener)
        }

        internal fun setAlbums(albums: List<Album>){
            this.albums = albums
            notifyDataSetChanged()
        }

        override fun getItemCount() = albums.size
    }
}