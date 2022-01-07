package site.leos.apps.lespas.publication

import android.content.Context
import android.net.ConnectivityManager
import android.os.Bundle
import android.view.*
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.view.doOnPreDraw
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.asLiveData
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.transition.MaterialSharedAxis
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.ConfirmDialogFragment
import site.leos.apps.lespas.helper.ImageLoaderViewModel

class PublicationListFragment: Fragment() {
    private val shareModel: NCShareViewModel by activityViewModels()

    private lateinit var shareListAdapter: ShareListAdapter
    private lateinit var shareListRecyclerView: RecyclerView

    private var shareSelected: NCShareViewModel.ShareWithMe? = null

    private var activateRefresh: MenuItem? = null
    private var refreshProgress: MenuItem? = null
    private var progressIndicator: CircularProgressIndicator? = null

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

        enterTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
        returnTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View = inflater.inflate(R.layout.fragment_publication_list, container, false)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        postponeEnterTransition()
        view.doOnPreDraw { startPostponedEnterTransition() }

        shareListRecyclerView = view.findViewById<RecyclerView>(R.id.sharelist).apply {
            adapter = shareListAdapter
        }

        shareModel.shareWithMe.asLiveData().observe(viewLifecycleOwner, { shareListAdapter.submitList(it) })
        shareModel.shareWithMeProgress.asLiveData().observe(viewLifecycleOwner, {
            when(it) {
                0 -> {
                    activateRefresh?.isVisible = false
                    refreshProgress?.isVisible = true
                }
                100 -> {
                    activateRefresh?.isVisible = true
                    refreshProgress?.isVisible = false
                    progressIndicator?.isIndeterminate = true
                }
                else -> {
                    if (refreshProgress?.isVisible == false) {
                        activateRefresh?.isVisible = false
                        progressIndicator?.isIndeterminate = false
                        refreshProgress?.isVisible = true
                    }
                    progressIndicator?.isIndeterminate = false
                    progressIndicator?.progress = it
                }
            }
        })
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
        activateRefresh = menu.findItem(R.id.option_menu_refresh_publication)
        refreshProgress = menu.findItem(R.id.option_menu_refresh_progress)
        progressIndicator = refreshProgress?.actionView?.findViewById<CircularProgressIndicator>(R.id.search_progress)?.apply { isIndeterminate = true }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            R.id.option_menu_refresh_publication-> {
                shareModel.getShareWithMe()
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
            private val ivCover = itemView.findViewById<AppCompatImageView>(R.id.coverart)
            private val tvTitle = itemView.findViewById<TextView>(R.id.title)
            private val ivIndicator = itemView.findViewById<ImageView>(R.id.joint_album_indicator)

            fun bind(item: NCShareViewModel.ShareWithMe) {
                imageLoader(item, ivCover)
                //itemView.findViewById<TextView>(R.id.title).text = String.format(itemView.context.getString(R.string.publication_detail_fragment_title), item.albumName, item.shareByLabel)
                tvTitle.apply {
                    text = item.albumName
                    avatarLoader(NCShareViewModel.Sharee(item.shareBy, item.shareByLabel, NCShareViewModel.SHARE_TYPE_USER), this)
                }
                ivIndicator.visibility = if (item.permission == NCShareViewModel.PERMISSION_JOINT) View.VISIBLE else View.INVISIBLE
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