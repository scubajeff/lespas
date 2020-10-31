package site.leos.apps.lespas.photo

import android.app.Application
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.doOnPreDraw
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.Album

class PhotoListFragment : Fragment() {
    private lateinit var mAdapter: PhotoGridAdapter
    private lateinit var viewModel: PhotoViewModel
    private lateinit var album: Album

    companion object {
        private const val ALBUM = "ALBUM"

        @JvmStatic
        fun newInstance(album: Album) = PhotoListFragment().apply { arguments = Bundle().apply{ putParcelable(ALBUM, album) }}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        album = arguments?.getParcelable<Album>(ALBUM)!!

        mAdapter = PhotoGridAdapter(object: PhotoGridAdapter.OnItemClickListener{
            override fun onItemClick(view: View, photo: Photo) {
                parentFragmentManager.beginTransaction()
                    .setReorderingAllowed(true)
                    .addSharedElement(view, "full_image")
                    .replace(R.id.container_root, PhotoDisplayFragment.newInstance(photo)).addToBackStack(null)
                    .commit()
            }
        }).apply { setAlbum(album) }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_photolist, container, false)

        view.findViewById<RecyclerView>(R.id.photogrid).run {
            val defaultSpanCount = (layoutManager as GridLayoutManager).spanCount
            layoutManager = GridLayoutManager(activity?.applicationContext, defaultSpanCount).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int { return if (position == 0) defaultSpanCount else 1 }
                }
            }

            adapter = mAdapter
        }

        val fab = view.findViewById<FloatingActionButton>(R.id.fab).setOnClickListener {
            //TODO: album fragment fab action
        }

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        postponeEnterTransition()

        viewModel = ViewModelProvider(this, ExtraParamsViewModelFactory(this.requireActivity().application, album.id)).get(PhotoViewModel::class.java)
        viewModel.allPhotoInAlbum.observe(viewLifecycleOwner, { photos ->
            mAdapter.setPhotos(photos)
            (view.parent as? ViewGroup)?.doOnPreDraw { startPostponedEnterTransition() }
        })
    }

    override fun onResume() {
        super.onResume()

        (activity as? AppCompatActivity)?.supportActionBar?.run {
            setDisplayHomeAsUpEnabled(true)
            title = album.name
        }
    }

    // Adpater for photo grid
    class PhotoGridAdapter(private val itemClickListener: OnItemClickListener) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private lateinit var album: Album
        private var photos = emptyList<Photo>()

        interface OnItemClickListener {
            fun onItemClick(view: View, photo: Photo)
        }

        inner class CoverViewHolder(private val itemView: View) : RecyclerView.ViewHolder(itemView) {
            fun bindViewItem() {
                itemView.run {
                    findViewById<ImageView>(R.id.cover).run {
                        setImageResource(R.drawable.ic_footprint)
                        scrollBy(0, 200)
                    }
                    findViewById<TextView>(R.id.title).text = album.name
                }
            }
        }

        inner class GridViewHolder(private val itemView: View) : RecyclerView.ViewHolder(itemView) {
            fun bindViewItem(photo: Photo, clickListener: OnItemClickListener) {
                itemView.apply {
                    findViewById<TextView>(R.id.title).text = photo.name
                    findViewById<ImageView>(R.id.pic).run {
                        setImageResource(R.drawable.ic_footprint)
                        ViewCompat.setTransitionName(this, photo.id)
                    }

                    setOnClickListener { clickListener.onItemClick(findViewById<ImageView>(R.id.pic), photo) }
                }
            }
        }

        internal fun setPhotos(photos: List<Photo>) {
            this.photos = photos
            notifyDataSetChanged()
        }

        internal fun setAlbum(album: Album) {
            this.album = album
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == TYPE_COVER) CoverViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_cover, parent, false))
                    else GridViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_photo, parent, false))

        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is GridViewHolder) holder.bindViewItem(photos[position - 1], itemClickListener)
            else (holder as CoverViewHolder).bindViewItem()
        }

        override fun getItemCount() = photos.size + 1

        override fun getItemViewType(position: Int): Int {
            return if (position == 0) TYPE_COVER else TYPE_PHOTO
        }

        companion object {
            private const val TYPE_COVER = 0
            private const val TYPE_PHOTO = 1
        }
    }

    // ViewModelFactory to pass String parameter to ViewModel object
    class ExtraParamsViewModelFactory(private val application: Application, private val myExtraParam: String) : ViewModelProvider.NewInstanceFactory() {
        override fun <T : ViewModel?> create(modelClass: Class<T>): T = PhotoViewModel(application, myExtraParam) as T
    }
}