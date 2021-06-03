package site.leos.apps.lespas.publication

import android.content.Context
import android.net.ConnectivityManager
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
import site.leos.apps.lespas.helper.ConfirmDialogFragment
import site.leos.apps.lespas.helper.ImageLoaderViewModel

class PublicationListFragment: Fragment(), ConfirmDialogFragment.OnResultListener {
    private val shareModel: NCShareViewModel by activityViewModels()

    private lateinit var shareListAdapter: ShareListAdapter
    private lateinit var shareListRecyclerView: RecyclerView

    private var shareSelected: NCShareViewModel.ShareWithMe? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        savedInstanceState?.apply { shareSelected = getParcelable(SELECTED_SHARE) }

        shareListAdapter = ShareListAdapter(
            { share ->
                shareSelected = share
                if ((requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered) {
                    if (parentFragmentManager.findFragmentByTag(CONFIRM_DIALOG) == null) ConfirmDialogFragment.newInstance(getString(R.string.confirm_download_publication), getString(R.string.yes_i_do)).let {
                        it.setTargetFragment(this, CONFIRM_DOWNLOAD_PUBLICATION_CODE)
                        it.show(parentFragmentManager, CONFIRM_DIALOG)
                    }
                } else viewDetail()
            },
            { share: NCShareViewModel.ShareWithMe, view: AppCompatImageView ->
                shareModel.getPhoto(
                    NCShareViewModel.RemotePhoto(share.cover.cover, "${share.sharePath}/${share.coverFileName}", "image/jpeg", share.cover.coverWidth, share.cover.coverHeight, share.cover.coverBaseline, 0L),
                    view,
                    ImageLoaderViewModel.TYPE_COVER
                )
            }
        ).apply {
            stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View = inflater.inflate(R.layout.fragment_publication_list, container, false)

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
            title = getString(R.string.publication_list_fragment_title)
            setDisplayHomeAsUpEnabled(true)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        shareSelected?.let { outState.putParcelable(SELECTED_SHARE, it) }
    }

    override fun onResult(positive: Boolean, requestCode: Int) {
        if (requestCode == CONFIRM_DOWNLOAD_PUBLICATION_CODE && positive) viewDetail()
    }

    private fun viewDetail() {
        parentFragmentManager.beginTransaction().replace(R.id.container_root, PublicationDetailFragment.newInstance(shareSelected!!), PublicationDetailFragment::class.java.canonicalName).addToBackStack(null).commit()
    }

    class ShareListAdapter(private val clickListener: (NCShareViewModel.ShareWithMe) -> Unit, private val imageLoader: (NCShareViewModel.ShareWithMe, AppCompatImageView) -> Unit
    ): ListAdapter<NCShareViewModel.ShareWithMe, ShareListAdapter.ViewHolder>(ShareDiffCallback()) {
        inner class ViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
            fun bind(item: NCShareViewModel.ShareWithMe) {
                imageLoader(item, itemView.findViewById(R.id.coverart))
                itemView.findViewById<TextView>(R.id.title).text = String.format(itemView.context.getString(R.string.publication_detail_fragment_title), item.albumName, item.shareByLabel)
                itemView.setOnClickListener { clickListener(item) }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
            ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_publication, parent, false))

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

    companion object {
        private const val CONFIRM_DIALOG = "CONFIRM_DIALOG"
        private const val CONFIRM_DOWNLOAD_PUBLICATION_CODE = 7878

        private const val SELECTED_SHARE = "SELECTED_SHARE"
    }
}