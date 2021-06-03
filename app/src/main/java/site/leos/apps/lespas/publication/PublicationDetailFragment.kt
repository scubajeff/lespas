package site.leos.apps.lespas.publication

import android.os.Bundle
import android.view.*
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import kotlinx.coroutines.launch
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.ImageLoaderViewModel
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.*

class PublicationDetailFragment: Fragment() {
    private lateinit var share: NCShareViewModel.ShareWithMe

    private lateinit var photoListAdapter: PhotoListAdapter
    private lateinit var photoList: RecyclerView

    private val shareModel: NCShareViewModel by activityViewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        share = arguments?.getParcelable(SHARE)!!

        photoListAdapter = PhotoListAdapter(
            { photo->  },
            { photo, view-> shareModel.getPhoto(photo, view, ImageLoaderViewModel.TYPE_GRID) }
        ).apply {
            stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        }

        lifecycleScope.launch {
            shareModel.getRemotePhotoList(share).toMutableList().apply { photoListAdapter.submitList(this) }
        }

        setHasOptionsMenu(true)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? = inflater.inflate(R.layout.fragment_publication_detail, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        photoList = view.findViewById<RecyclerView>(R.id.photo_list).apply {
            layoutManager = StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL).apply { gapStrategy = StaggeredGridLayoutManager.GAP_HANDLING_MOVE_ITEMS_BETWEEN_SPANS }
            adapter = photoListAdapter
        }
    }

    override fun onResume() {
        super.onResume()

        (activity as? AppCompatActivity)?.supportActionBar?.run {
            title = String.format(getString(R.string.publication_detail_fragment_title), share.albumName, share.shareByLabel)
            setDisplayHomeAsUpEnabled(true)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.publication_detail_menu, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when(item.itemId) {
            R.id.option_menu_show_meta-> {
                photoListAdapter.toggleMetaDisplay()
                true
            }
            else-> false
        }

    class PhotoListAdapter(private val clickListener: (NCShareViewModel.RemotePhoto) -> Unit, private val imageLoader: (NCShareViewModel.RemotePhoto, ImageView) -> Unit
    ): ListAdapter<NCShareViewModel.RemotePhoto, PhotoListAdapter.ViewHolder>(PhotoDiffCallback()) {
        private var displayMeta = false

        inner class ViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
            fun bind(item: NCShareViewModel.RemotePhoto) {
                (itemView.findViewById(R.id.media) as ImageView).apply {
                    imageLoader(item, this)
                    ConstraintSet().apply {
                        clone(itemView as ConstraintLayout)
                        setDimensionRatio(R.id.media, "H,${item.width}:${item.height}")
                        applyTo(itemView)
                    }
                    setOnClickListener { clickListener(item) }
                }

                (itemView.findViewById<ImageView>(R.id.play_mark)).visibility = if (item.mimeType.startsWith("video")) View.VISIBLE else View.GONE

                (itemView.findViewById<TextView>(R.id.meta)).apply {
                    LocalDateTime.ofInstant(Instant.ofEpochSecond(item.timestamp), ZoneOffset.systemDefault()).apply {
                        text = "${this.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())}, ${this.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT))}"
                    }
                    visibility = if (displayMeta) View.VISIBLE else View.GONE
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoListAdapter.ViewHolder =
            ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_remote_media, parent, false))

        override fun onBindViewHolder(holder: PhotoListAdapter.ViewHolder, position: Int) { holder.bind(getItem(position)) }

        fun toggleMetaDisplay() {
            displayMeta = !displayMeta
            notifyDataSetChanged()
        }
    }

    class PhotoDiffCallback: DiffUtil.ItemCallback<NCShareViewModel.RemotePhoto>() {
        override fun areItemsTheSame(oldItem: NCShareViewModel.RemotePhoto, newItem: NCShareViewModel.RemotePhoto): Boolean = oldItem.fileId == newItem.fileId
        override fun areContentsTheSame(oldItem: NCShareViewModel.RemotePhoto, newItem: NCShareViewModel.RemotePhoto): Boolean = oldItem.fileId == newItem.fileId
    }

    companion object {
        const val SHARE = "SHARE"

        @JvmStatic
        fun newInstance(share: NCShareViewModel.ShareWithMe) = PublicationDetailFragment().apply {
            arguments = Bundle().apply {
                putParcelable(SHARE, share)
            }
        }
    }
}