package site.leos.apps.lespas.publication

import android.content.Context
import android.net.ConnectivityManager
import android.os.Bundle
import android.view.*
import android.widget.ImageView
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

class PublicationListFragment: Fragment() {
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
                    if (parentFragmentManager.findFragmentByTag(CONFIRM_DIALOG) == null) ConfirmDialogFragment.newInstance(getString(R.string.confirm_download_publication), getString(R.string.yes_i_do)).show(parentFragmentManager, CONFIRM_DIALOG)
                } else viewDetail()
            },
            { share: NCShareViewModel.ShareWithMe, view: AppCompatImageView ->
                shareModel.getPhoto(
                    NCShareViewModel.RemotePhoto(share.cover.cover, "${share.sharePath}/${share.coverFileName}", "image/jpeg", share.cover.coverWidth, share.cover.coverHeight, share.cover.coverBaseline, 0L),
                    view,
                    ImageLoaderViewModel.TYPE_COVER
                )
            },
            { user, view -> shareModel.getAvatar(user, view, null) }
        ).apply { stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY }

        setHasOptionsMenu(true)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View = inflater.inflate(R.layout.fragment_publication_list, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        shareListRecyclerView = view.findViewById<RecyclerView>(R.id.sharelist).apply {
            adapter = shareListAdapter
        }

        shareModel.shareWithMe.asLiveData().observe(viewLifecycleOwner, { shareListAdapter.submitList(it) })
/*

        lifecycleScope.launch { shareModel.themeColor.collect { (requireActivity() as MainActivity).themeToolbar(ColorUtils.setAlphaComponent(it, 255)) }}
*/

        // Use mobile data confirm dialog result handler
        parentFragmentManager.setFragmentResultListener(ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY, viewLifecycleOwner) { key, bundle ->
            if (key == ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY && bundle.getBoolean(ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY, false)) viewDetail()
        }
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

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.publication_list_menu, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            R.id.option_menu_refresh_publication-> {
                shareModel.updateShareWithMe()
                true
            }
            else-> false
        }

    private fun viewDetail() {
        parentFragmentManager.beginTransaction().replace(R.id.container_root, PublicationDetailFragment.newInstance(shareSelected!!), PublicationDetailFragment::class.java.canonicalName).addToBackStack(null).commit()
    }

    class ShareListAdapter(private val clickListener: (NCShareViewModel.ShareWithMe) -> Unit, private val imageLoader: (NCShareViewModel.ShareWithMe, AppCompatImageView) -> Unit, private val avatarLoader: (NCShareViewModel.Sharee, View) -> Unit
    ): ListAdapter<NCShareViewModel.ShareWithMe, ShareListAdapter.ViewHolder>(ShareDiffCallback()) {
        inner class ViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
            fun bind(item: NCShareViewModel.ShareWithMe) {
                imageLoader(item, itemView.findViewById(R.id.coverart))
                //itemView.findViewById<TextView>(R.id.title).text = String.format(itemView.context.getString(R.string.publication_detail_fragment_title), item.albumName, item.shareByLabel)
                itemView.findViewById<TextView>(R.id.title).apply {
                    text = item.albumName
                    avatarLoader(NCShareViewModel.Sharee(item.shareBy, item.shareByLabel, NCShareViewModel.SHARE_TYPE_USER), this)
                }
                itemView.findViewById<ImageView>(R.id.joint_album_indicator).visibility = if (item.permission == NCShareViewModel.PERMISSION_JOINT) View.VISIBLE else View.INVISIBLE
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
        override fun areContentsTheSame(oldItem: NCShareViewModel.ShareWithMe, newItem: NCShareViewModel.ShareWithMe): Boolean = (oldItem.shareId == newItem.shareId && oldItem.cover.cover == newItem.cover.cover && oldItem.cover.coverBaseline == newItem.cover.coverBaseline)
    }

    companion object {
        private const val CONFIRM_DIALOG = "CONFIRM_DIALOG"

        private const val SELECTED_SHARE = "SELECTED_SHARE"
    }
}