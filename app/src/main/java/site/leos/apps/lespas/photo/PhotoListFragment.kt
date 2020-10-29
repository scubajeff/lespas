package site.leos.apps.lespas.photo

import android.app.Application
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
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
        const val ALBUM_ID = "ALBUM_ID"
        //fun newInstance() = PhotoListFragment()
        fun newInstance(album: String): PhotoListFragment {
            val fragment = PhotoListFragment()
            fragment.arguments = Bundle().apply { putString(ALBUM_ID, album) }
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mAdapter = PhotoGridAdapter()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_photolist, container, false)

        view.findViewById<RecyclerView>(R.id.photogrid).adapter = mAdapter

        val fab = view.findViewById<FloatingActionButton>(R.id.fab).setOnClickListener {
            //TODO: album fragment fab action
        }

        return view
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        //viewModel = ViewModelProvider(this).get(PhotoViewModel::class.java)
        viewModel = ViewModelProvider(this, ExtraParamsViewModelFactory(this.requireActivity().application, arguments!!.getString(ALBUM_ID)!!)).get(PhotoViewModel::class.java)
        viewModel.allPhotoInAlbum.observe(viewLifecycleOwner, { photos -> mAdapter.setPhotos(photos) })
    }

    // Adpater for photo grid
    class PhotoGridAdapter internal constructor() : RecyclerView.Adapter<PhotoGridAdapter.GridViewHolder>() {
        private var photos = emptyList<Photo>()

        inner class GridViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val picView: ImageView = itemView.findViewById(R.id.pic)
            val titleView: TextView = itemView.findViewById(R.id.title)
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
            val current = photos[position]
            holder.titleView.text = current.name
            // TODO: load picture
        }

        override fun getItemCount() = photos.size
    }

    // ViewModelFactory to pass String parameter to ViewModel object
    class ExtraParamsViewModelFactory(private val application: Application, private val myExtraParam: String) : ViewModelProvider.NewInstanceFactory() {
        override fun <T : ViewModel?> create(modelClass: Class<T>): T = PhotoViewModel(application, myExtraParam) as T
    }
}