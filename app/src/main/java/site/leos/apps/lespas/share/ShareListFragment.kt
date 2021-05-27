package site.leos.apps.lespas.share

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.asLiveData
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.ImageLoaderViewModel

class ShareListFragment: Fragment() {
    private val shareModel: NCShareViewModel by activityViewModels()
    private val imageLoaderViewModel: ImageLoaderViewModel by activityViewModels()

    private lateinit var shareListAdapter: ShareListAdapter
    private lateinit var shareListRecyclerView: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        shareListAdapter = ShareListAdapter(
            {shareWithMe ->  },
            { share: NCShareViewModel.ShareWithMe, view: AppCompatImageView -> shareModel.getPhoto(share, view) }
        ).apply {
            stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View = inflater.inflate(R.layout.fragment_share_list, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        shareListRecyclerView = view.findViewById<RecyclerView>(R.id.sharelist).apply {
            adapter = shareListAdapter
        }

        shareModel.shareWithMe.asLiveData().observe(viewLifecycleOwner, { shareListAdapter.submitList(it) })
    }

    override fun onResume() {
        super.onResume()

        (activity as? AppCompatActivity)?.supportActionBar?.run {
            setDisplayHomeAsUpEnabled(true)
        }
    }

    class ShareListAdapter(private val clickListener: (NCShareViewModel.ShareWithMe) -> Unit, private val imageLoader: (NCShareViewModel.ShareWithMe, AppCompatImageView) -> Unit
    ): ListAdapter<NCShareViewModel.ShareWithMe, ShareListAdapter.ViewHolder>(ShareDiffCallback()) {
        inner class ViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
            fun bind(item: NCShareViewModel.ShareWithMe) {
                imageLoader(item, itemView.findViewById(R.id.coverart))
                itemView.findViewById<TextView>(R.id.title).text = item.albumName
                itemView.findViewById<TextView>(R.id.duration).text = item.shareBy
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
            ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_album, parent, false))

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        override fun submitList(list: List<NCShareViewModel.ShareWithMe>?) {
            super.submitList(list?.toMutableList())
        }
    }

    class ShareDiffCallback: DiffUtil.ItemCallback<NCShareViewModel.ShareWithMe>() {
        override fun areItemsTheSame(oldItem: NCShareViewModel.ShareWithMe, newItem: NCShareViewModel.ShareWithMe): Boolean = oldItem.shareId == newItem.shareId
        override fun areContentsTheSame(oldItem: NCShareViewModel.ShareWithMe, newItem: NCShareViewModel.ShareWithMe): Boolean = oldItem.shareId == newItem.shareId
    }
}