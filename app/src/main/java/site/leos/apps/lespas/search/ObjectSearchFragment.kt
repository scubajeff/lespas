/*
 *   Copyright 2019 Jeffrey Liu (scubajeffrey@protonmail.ch)
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package site.leos.apps.lespas.search

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnPreDraw
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.transition.MaterialElevationScale
import kotlinx.coroutines.launch
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.LesPasEmptyView
import site.leos.apps.lespas.publication.NCShareViewModel
import site.leos.apps.lespas.sync.ActionViewModel
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

class ObjectSearchFragment : Fragment() {
    private lateinit var searchResultAdapter: SearchResultAdapter
    private lateinit var searchResultRecyclerView: RecyclerView
    private val imageLoaderModel: NCShareViewModel by activityViewModels()
    private val actionModel: ActionViewModel by viewModels()
    private val searchModel: SearchFragment.SearchModel by viewModels(ownerProducer = { requireParentFragment() }) { SearchFragment.SearchModelFactory(requireActivity().application, imageLoaderModel, actionModel)}

    private var searchScope = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        searchScope = requireArguments().getInt(KEY_SEARCH_SCOPE)

        searchResultAdapter = SearchResultAdapter(
            searchScope,
            { position, imageView ->
                searchModel.setCurrentSlideItem(position)

                reenterTransition = MaterialElevationScale(true).apply { duration = resources.getInteger(android.R.integer.config_mediumAnimTime).toLong() }
                exitTransition = MaterialElevationScale(false).apply {
                    duration = resources.getInteger(android.R.integer.config_shortAnimTime).toLong()
                    excludeTarget(imageView, true)
                }
                parentFragmentManager.beginTransaction().setReorderingAllowed(true).addSharedElement(imageView, ViewCompat.getTransitionName(imageView)!!)
                    .replace(R.id.container_child_fragment, ObjectSearchSlideFragment.newInstance(), ObjectSearchSlideFragment::class.java.canonicalName).addToBackStack(null).commit()
            },
            { remotePhoto: NCShareViewModel.RemotePhoto, view: ImageView -> imageLoaderModel.setImagePhoto(remotePhoto, view, NCShareViewModel.TYPE_GRID) { startPostponedEnterTransition() }},
            { view -> imageLoaderModel.cancelSetImagePhoto(view) }
        ).apply {
            stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? = inflater.inflate(R.layout.fragment_search_result, container, false)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        postponeEnterTransition()
        view.doOnPreDraw { startPostponedEnterTransition() }

        searchResultRecyclerView = view.findViewById<RecyclerView?>(R.id.photo_grid).apply {
            adapter = searchResultAdapter

            addItemDecoration(LesPasEmptyView(ContextCompat.getDrawable(requireContext(),
                when(searchScope) {
                    R.id.search_album -> R.drawable.ic_baseline_footprint_24
                    else -> R.drawable.ic_baseline_device_24
                }
            )!!))

            // Avoid window inset overlapping
            ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
                val displayCutoutInset = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
                val navigationBarInset = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
                v.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    rightMargin = displayCutoutInset.right + navigationBarInset.right
                    leftMargin = displayCutoutInset.left + navigationBarInset.left
                }
                insets
            }
        }

        if (searchResultAdapter.itemCount !=0 ) postponeEnterTransition()

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { searchModel.objectDetectResult.collect { searchResultAdapter.submitList(it) }}
            }
        }
    }

    override fun onResume() {
        super.onResume()

        (activity as? AppCompatActivity)?.supportActionBar?.run {
            arguments?.let { title = getString(if (searchScope == R.id.search_album) R.string.title_in_album else R.string.title_in_gallery, it.getString(KEY_CATEGORY_LABEL)) }
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowTitleEnabled(true)
        }
    }

    override fun onDestroyView() {
        searchResultRecyclerView.adapter = null
        super.onDestroyView()
    }

    class SearchResultAdapter(private val searchTarget: Int, private val clickListener: (Int, ImageView) -> Unit, private val imageLoader: (NCShareViewModel.RemotePhoto, ImageView) -> Unit, private val cancelLoader: (View) -> Unit
    ): ListAdapter<SearchFragment.SearchModel.ObjectDetectResult, SearchResultAdapter.ViewHolder>(SearchResultDiffCallback()) {
        private val albumNames = HashMap<String, String>()

        inner class ViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
            private val ivPhoto = itemView.findViewById<ImageView>(R.id.photo).apply { setOnClickListener { clickListener(bindingAdapterPosition, this) } }
            private val tvLabel = itemView.findViewById<TextView>(R.id.label)

            fun bind(item: SearchFragment.SearchModel.ObjectDetectResult) {
                with(ivPhoto) {
                    if (getTag(R.id.PHOTO_ID) != item.remotePhoto.photo.id) {
                        imageLoader(item.remotePhoto, this)
                        ViewCompat.setTransitionName(this, item.remotePhoto.photo.id)

                        //tvLabel.text = "${item.subLabel}${String.format("  %.4f", item.similarity)}"
                        tvLabel.text =
                            if (searchTarget == R.id.search_album) albumNames[item.remotePhoto.photo.albumId]
                            else item.remotePhoto.photo.dateTaken.run { "${this.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())}, ${this.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT))}" }
                    }
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
            ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_search_result, parent, false))

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
            for (i in 0 until currentList.size) recyclerView.findViewHolderForAdapterPosition(i)?.let { holder -> holder.itemView.findViewById<View>(R.id.photo)?.let { cancelLoader(it) }}
            super.onDetachedFromRecyclerView(recyclerView)
        }
    }

    class SearchResultDiffCallback: DiffUtil.ItemCallback<SearchFragment.SearchModel.ObjectDetectResult>() {
        override fun areItemsTheSame(oldItem: SearchFragment.SearchModel.ObjectDetectResult, newItem: SearchFragment.SearchModel.ObjectDetectResult): Boolean = oldItem.remotePhoto.photo.id == newItem.remotePhoto.photo.id
        override fun areContentsTheSame(oldItem: SearchFragment.SearchModel.ObjectDetectResult, newItem: SearchFragment.SearchModel.ObjectDetectResult): Boolean = oldItem == newItem
    }

    companion object {
        private const val KEY_CATEGORY_LABEL = "KEY_CATEGORY_LABEL"
        private const val KEY_SEARCH_SCOPE = "KEY_SEARCH_SCOPE"

        @JvmStatic
        fun newInstance(categoryLabel: String, scope: Int) = ObjectSearchFragment().apply {
            arguments = Bundle().apply {
                putString(KEY_CATEGORY_LABEL, categoryLabel)
                putInt(KEY_SEARCH_SCOPE, scope)
            }
        }
    }
}