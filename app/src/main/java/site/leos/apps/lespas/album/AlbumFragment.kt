package site.leos.apps.lespas.album

import android.os.Bundle
import android.view.*
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.selection.*
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import site.leos.apps.lespas.R
import site.leos.apps.lespas.photo.PhotoListFragment

class AlbumFragment : Fragment(), ActionMode.Callback {
    private lateinit var mAdapter: AlbumListAdapter
    private lateinit var viewModel: AlbumViewModel
    private lateinit var selectionTracker: SelectionTracker<Long>
    private var actionMode: ActionMode? = null
    private lateinit var fab: FloatingActionButton

    companion object {
        fun newInstance() = AlbumFragment()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mAdapter = AlbumListAdapter(object: AlbumListAdapter.OnItemClickListener {
            override fun onItemClick(album: Album) {
                parentFragmentManager.beginTransaction().replace(R.id.container_root, PhotoListFragment.newInstance(album)).addToBackStack(null).commit()
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

            selectionTracker = SelectionTracker.Builder<Long> (
                "albumSelection",
                this,
                AlbumListAdapter.AlbumKeyProvider(),
                AlbumListAdapter.AlbumDetailsLookup(this),
                StorageStrategy.createLongStorage()
            ).withSelectionPredicate(SelectionPredicates.createSelectAnything()).build().apply {
                addObserver(object : SelectionTracker.SelectionObserver<Long>() {
                    override fun onSelectionChanged() {
                        super.onSelectionChanged()

                        if (selectionTracker.hasSelection() && actionMode == null) {
                            actionMode = (activity as? AppCompatActivity)?.startSupportActionMode(this@AlbumFragment)
                            actionMode?.let { it.title = getString(R.string.selected_count, selectionTracker.selection?.size())}
                        } else if (!selectionTracker.hasSelection() && actionMode != null) {
                            actionMode?.finish()
                            actionMode = null
                        } else actionMode?.title = getString(R.string.selected_count, selectionTracker.selection?.size())
                    }
                })

                if (savedInstanceState != null) onRestoreInstanceState(savedInstanceState)
            }
        }

        mAdapter.setSelectionTracker(selectionTracker as SelectionTracker<Long>)

        fab = view.findViewById<FloatingActionButton>(R.id.fab).apply {
            setOnClickListener {
                //TODO: album fragment fab action
            }
        }

        return view
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        viewModel = ViewModelProvider(this).get(AlbumViewModel::class.java)
        viewModel.allAlbumsByEndDate.observe(viewLifecycleOwner, Observer { albums -> mAdapter.setAlbums(albums)})
    }

    override fun onResume() {
        super.onResume()

        (activity as? AppCompatActivity)?.supportActionBar?.run {
            setDisplayHomeAsUpEnabled(false)
            title = getString(R.string.app_name)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        selectionTracker.onSaveInstanceState(outState)
    }

    override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
        mode?.menuInflater?.inflate(R.menu.actions_album, menu)
        fab.isEnabled = false

        return true
    }

    override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
        return false
    }

    override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
        return when(item?.itemId) {
            R.id.remove_album -> {
                selectionTracker.selection?.forEach { _ -> }

                selectionTracker.clearSelection()
                true
            }
            R.id.share_album -> {
                selectionTracker.selection?.forEach { _ -> }

                selectionTracker.clearSelection()
                true
            }
            else -> false
        }
    }

    override fun onDestroyActionMode(mode: ActionMode?) {
        selectionTracker.clearSelection()
        this.actionMode = null
        fab.isEnabled = true
    }

    // List adapter for Albums' recyclerView
    class AlbumListAdapter(private val itemClickListener: OnItemClickListener): RecyclerView.Adapter<AlbumListAdapter.AlbumViewHolder>() {
        private var albums = emptyList<Album>()
        private lateinit var selectionTracker: SelectionTracker<Long>

        init {
            setHasStableIds(true)
        }

        interface OnItemClickListener {
            fun onItemClick(album: Album)
        }

        inner class AlbumViewHolder(private val itemView: View): RecyclerView.ViewHolder(itemView) {
            fun bindViewItems(album: Album, clickListener: OnItemClickListener, isActivated: Boolean) {
                itemView.apply {
                    findViewById<TextView>(R.id.title).text = album.name
                    //findViewById<TextView>(R.id.duration).text = String.format("%tF - %tF", album.startDate, album.endDate)
                    findViewById<ImageView>(R.id.coverart).apply {
                        setImageResource(R.drawable.ic_footprint)
                        scrollTo(0, 200)
                    }
                    findViewById<TextView>(R.id.duration).text = "1970.01.17 - 1977.01.10"
                    setOnClickListener { clickListener.onItemClick(album) }
                    this.isActivated = isActivated
                }
            }

            fun getItemDetails() = object : ItemDetailsLookup.ItemDetails<Long>() {
                override fun getPosition(): Int = adapterPosition
                override fun getSelectionKey(): Long? = itemId
                //override fun inSelectionHotspot(e: MotionEvent): Boolean = true
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlbumListAdapter.AlbumViewHolder  {
            val itemView = LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_album, parent,false)
            return AlbumViewHolder(itemView)
        }

        override fun onBindViewHolder(holder: AlbumListAdapter.AlbumViewHolder, position: Int) {
            holder.bindViewItems(albums[position], itemClickListener, selectionTracker.isSelected(position.toLong()))
        }

        internal fun setAlbums(albums: List<Album>){
            this.albums = albums
            notifyDataSetChanged()
        }

        override fun getItemCount() = albums.size

        override fun getItemId(position: Int): Long = position.toLong()

        fun setSelectionTracker(selectionTracker: SelectionTracker<Long>) { this.selectionTracker = selectionTracker }

        class AlbumKeyProvider: ItemKeyProvider<Long>(ItemKeyProvider.SCOPE_CACHED) {
            override fun getKey(position: Int): Long? = position.toLong()
            override fun getPosition(key: Long): Int = key.toInt()
        }

        class AlbumDetailsLookup(private val recyclerView: RecyclerView) : ItemDetailsLookup<Long>() {
            override fun getItemDetails(e: MotionEvent): ItemDetails<Long>? {
                recyclerView.findChildViewUnder(e.x, e.y)?.let {
                    return (recyclerView.getChildViewHolder(it) as AlbumViewHolder).getItemDetails()
                }
                return null
            }
        }
    }
}