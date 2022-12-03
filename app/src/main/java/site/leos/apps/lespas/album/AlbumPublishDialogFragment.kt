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

package site.leos.apps.lespas.album

import android.os.Bundle
import android.view.View
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayoutMediator
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.LesPasDialogFragment
import site.leos.apps.lespas.helper.Tools.parcelable
import site.leos.apps.lespas.helper.doOnEachNextLayout
import site.leos.apps.lespas.publication.NCShareViewModel

class AlbumPublishDialogFragment: LesPasDialogFragment(R.layout.fragment_album_publish_dialog) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<ViewPager2>(R.id.view_pager).apply {
            adapter = PublishFragmentAdapter(this@AlbumPublishDialogFragment, requireArguments().parcelable(CURRENT_SHARE)!!)
            TabLayoutMediator(view.findViewById(R.id.tab_layout), this) { tab, position -> tab.text = getString(if (position == 0) R.string.internal_publish else R.string.external_publish)}.attach()
        }

        // Make sure the dialog window won't shrink
        view.findViewById<LinearLayoutCompat>(R.id.background).doOnEachNextLayout { if (it.minimumHeight < it.height) it.minimumHeight = it.height }
    }

    class PublishFragmentAdapter(fragment: Fragment, private val currentShare: NCShareViewModel.ShareByMe): FragmentStateAdapter(fragment) {
        override fun getItemCount(): Int = 2
        override fun createFragment(position: Int): Fragment = if (position == 0) AlbumPublishInternalFragment.newInstance(currentShare) else AlbumPublishExternalFragment.newInstance(currentShare)
    }

    companion object {
        private const val CURRENT_SHARE = "CURRENT_SHARE"

        @JvmStatic
        fun newInstance(currentShare: NCShareViewModel.ShareByMe) = AlbumPublishDialogFragment().apply {
            arguments = Bundle().apply { putParcelable(CURRENT_SHARE, currentShare) }
        }
    }
}