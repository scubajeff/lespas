package site.leos.apps.lespas.photo

import android.app.Application
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.doOnPreDraw
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import site.leos.apps.lespas.R

class PhotoListFragment : Fragment() {
    private lateinit var mAdapter: PhotoGridAdapter
    private lateinit var viewModel: PhotoViewModel

    companion object {
        private const val ALBUM_ID = "ALBUM_ID"
        fun newInstance(album: String): PhotoListFragment {
            val fragment = PhotoListFragment()
            fragment.arguments = Bundle().apply { putString(ALBUM_ID, album) }
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mAdapter = PhotoGridAdapter(object: PhotoGridAdapter.OnItemClickListener{
            override fun onItemClick(view: View, photo: Photo) {
                parentFragmentManager.beginTransaction()
                    .setReorderingAllowed(true)
                    .addSharedElement(view, "full_image")
                    .replace(R.id.container_root, PhotoFragment.newInstance(photo)).addToBackStack(null)
                    .commit()
            }
        })
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_photolist, container, false)

        view.findViewById<RecyclerView>(R.id.photogrid).adapter = mAdapter

        val fab = view.findViewById<FloatingActionButton>(R.id.fab).setOnClickListener {
            //TODO: album fragment fab action
        }

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        postponeEnterTransition()

        viewModel = ViewModelProvider(this, ExtraParamsViewModelFactory(this.requireActivity().application, arguments!!.getString(ALBUM_ID)!!)).get(PhotoViewModel::class.java)
        viewModel.allPhotoInAlbum.observe(viewLifecycleOwner, { photos ->
            mAdapter.setPhotos(photos)
            (view.parent as? ViewGroup)?.doOnPreDraw { startPostponedEnterTransition() }
        })
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
    }

    // Adpater for photo grid
    class PhotoGridAdapter(private val itemClickListener: OnItemClickListener) : RecyclerView.Adapter<PhotoGridAdapter.GridViewHolder>() {
        private var photos = emptyList<Photo>()

        interface OnItemClickListener {
            fun onItemClick(view: View, photo: Photo)
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

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoGridAdapter.GridViewHolder {
            val itemView = LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_photo, parent, false)
            return GridViewHolder(itemView)
        }

        override fun onBindViewHolder(holder: PhotoGridAdapter.GridViewHolder, position: Int) {
            holder.bindViewItem(photos[position], itemClickListener)
        }

        override fun getItemCount() = photos.size
    }

    // ViewModelFactory to pass String parameter to ViewModel object
    class ExtraParamsViewModelFactory(private val application: Application, private val myExtraParam: String) : ViewModelProvider.NewInstanceFactory() {
        override fun <T : ViewModel?> create(modelClass: Class<T>): T = PhotoViewModel(application, myExtraParam) as T
    }
}