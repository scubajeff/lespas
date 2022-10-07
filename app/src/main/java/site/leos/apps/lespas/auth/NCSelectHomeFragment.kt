/*
 *   Copyright 2019 Jeffrey Liu (scubajeffrey@criptext.com)
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

package site.leos.apps.lespas.auth

import android.accounts.AccountManager
import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.transition.Fade
import android.transition.TransitionManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.OkHttpWebDav
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.settings.SettingsFragment
import java.text.Collator

class NCSelectHomeFragment: Fragment() {
    private lateinit var container: ViewGroup
    private lateinit var selectedFolder: String
    private lateinit var folderTextView: TextView
    private lateinit var selectButton: MaterialButton

    private lateinit var folderList: RecyclerView
    private lateinit var folderAdapter: FolderAdapter

    private lateinit var webDav: OkHttpWebDav
    private lateinit var baseUrl: String
    private lateinit var resourceRoot: String

    private lateinit var lespas: String
    private lateinit var serverTheme: NCLoginFragment.AuthenticateViewModel.NCTheming
    private var fetchJob: Job? = null
    private var currentList = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lespas = getString(R.string.lespas_base_folder_name).drop(1)

        @Suppress("DEPRECATION")
        serverTheme = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) requireArguments().getParcelable(KEY_SERVER_THEME, NCLoginFragment.AuthenticateViewModel.NCTheming::class.java) else requireArguments().getParcelable(KEY_SERVER_THEME))
            ?: NCLoginFragment.AuthenticateViewModel.NCTheming().apply {
                color = ContextCompat.getColor(requireContext(), R.color.color_background)
                textColor = ContextCompat.getColor(requireContext(), R.color.lespas_black)
            }
        selectedFolder = savedInstanceState?.run { getString(KEY_CURRENT_FOLDER) ?: "" } ?: ""

        AccountManager.get(requireContext()).run {
            val account = getAccountsByType(getString(R.string.account_type_nc))[0]
            val userName = getUserData(account, getString(R.string.nc_userdata_username))
            baseUrl = getUserData(account, getString(R.string.nc_userdata_server))
            resourceRoot = "$baseUrl${getString(R.string.dav_files_endpoint)}$userName"
            webDav = OkHttpWebDav(
                userName, getUserData(account, getString(R.string.nc_userdata_secret)), baseUrl, getUserData(account, getString(R.string.nc_userdata_selfsigned)).toBoolean(), "${Tools.getLocalRoot(requireContext())}/cache", "LesPas_${getString(R.string.lespas_version)}",
                PreferenceManager.getDefaultSharedPreferences(requireContext()).getInt(SettingsFragment.CACHE_SIZE, 800)
            )
        }

        folderAdapter = FolderAdapter(
            lespas,
            Tools.getAttributeColor(requireContext(), android.R.attr.textColorPrimary),
        ) { name ->
            if (selectButton.isEnabled) {
                var newFolder = ""
                if (name != PARENT_FOLDER) newFolder = "${selectedFolder}/${name}"
                else if (selectedFolder.isNotEmpty()) newFolder = selectedFolder.substringBeforeLast("/")

                fetchFolder(newFolder)
            }
        }.apply { stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY }

        requireActivity().onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (fetchJob?.isActive == true) {
                    fetchJob?.cancel()
                    selectButton.isEnabled = true
                    showSelectedFolder(selectedFolder)
                    folderAdapter.submitList(currentList) { folderList.isVisible = true }
                }
                else if (selectedFolder.isNotEmpty()) {
                    fetchFolder(selectedFolder.substringBeforeLast("/"))
                } else returnResult()
            }
        })
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? = inflater.inflate(R.layout.fragment_select_home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        container = view.findViewById<ViewGroup>(R.id.background).apply { setBackgroundColor(serverTheme.color) }
        folderTextView = view.findViewById<TextView>(R.id.home_folder_label).apply { setTextColor(serverTheme.textColor) }
        view.findViewById<TextView>(R.id.title).run { setTextColor(serverTheme.textColor) }
        view.findViewById<TextView>(R.id.note).run { setTextColor(serverTheme.textColor) }
        selectButton = view.findViewById<MaterialButton>(R.id.ok_button).apply { setOnClickListener { returnResult() }}

        folderList = view.findViewById<RecyclerView?>(R.id.folder_grid).apply {
            adapter = folderAdapter
            setBackgroundColor(serverTheme.color)
        }
        fetchFolder(selectedFolder)
    }

    override fun onResume() {
        super.onResume()
        requireActivity().window.statusBarColor = serverTheme.color
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        outState.putString(KEY_CURRENT_FOLDER, selectedFolder)
    }

    private fun showSelectedFolder(name: String) {
        folderTextView.text = SpannableString("$name/$lespas").apply { setSpan(ForegroundColorSpan(Color.GRAY), this.lastIndexOf('/') + 1, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) }
    }

    private fun fetchFolder(target: String) {
        selectButton.isEnabled = false
        showSelectedFolder(target)

        currentList = folderAdapter.currentList
        if (folderList.isVisible) {
            TransitionManager.beginDelayedTransition(container, Fade().apply { duration = 300 })
            folderList.isVisible = false
        }

        fetchJob = lifecycleScope.launch(Dispatchers.IO) {
            val nameList = mutableListOf<String>()
            try {
                webDav.list("${resourceRoot}/${target}", OkHttpWebDav.FOLDER_CONTENT_DEPTH, forceNetwork = false).drop(1).forEach {
                    if (it.isFolder) nameList.add(it.name)
                }
                nameList.sortWith(compareBy(Collator.getInstance().apply { strength = Collator.TERTIARY }) { it })
                if (target.isNotEmpty()) nameList.add(0, PARENT_FOLDER)
            } catch (_: Exception) {}

            withContext(Dispatchers.Main) {
                folderAdapter.clearList()
                folderAdapter.submitList(nameList) { folderList.isVisible = true }
                selectedFolder = target
                selectButton.isEnabled = true
            }
        }
    }

    private fun returnResult() {
        parentFragmentManager.run {
            setFragmentResult(RESULT_KEY_HOME_FOLDER, Bundle().apply { putString(RESULT_KEY_HOME_FOLDER, selectedFolder) })
            popBackStack()
        }
    }

    class FolderAdapter(private val lespas: String, private val textColor: Int, val clickListener: (String) -> Unit) : ListAdapter<String, FolderAdapter.ViewHolder>(FolderDiffCallback()) {
        inner class ViewHolder(itemView: View): RecyclerView.ViewHolder(itemView) {
            private val tvName: TextView = itemView.findViewById<TextView>(R.id.name).apply { setOnClickListener { clickListener(this.text.toString()) }}

            fun bind(name: String) {
                with(tvName) {
                    text = name
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { tooltipText = name }

                    if (name == lespas) {
                        setTextColor(Color.LTGRAY)
                        compoundDrawableTintList = ColorStateList.valueOf(Color.LTGRAY)
                        isClickable = false
                    } else {
                        setTextColor(textColor)
                        compoundDrawableTintList = ColorStateList.valueOf(textColor)
                        isClickable = true
                    }
                }
            }
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_folder, parent, false))
        override fun onBindViewHolder(holder: ViewHolder, position: Int) { holder.bind(currentList[position]) }

        @SuppressLint("NotifyDataSetChanged")
        fun clearList() {
            submitList(emptyList())
            notifyDataSetChanged()
        }
    }

    class FolderDiffCallback: DiffUtil.ItemCallback<String>() {
        override fun areItemsTheSame(oldItem: String, newItem: String): Boolean = oldItem == newItem
        override fun areContentsTheSame(oldItem: String, newItem: String): Boolean = true
    }

    companion object {
        const val RESULT_KEY_HOME_FOLDER = "RESULT_KEY_HOME_FOLDER"

        private const val PARENT_FOLDER = ".."
        private const val KEY_CURRENT_FOLDER = "KEY_CURRENT_FOLDER"

        private const val KEY_SERVER_THEME = "KEY_SERVER_THEME"
        @JvmStatic
        fun newInstance(theme: NCLoginFragment.AuthenticateViewModel.NCTheming) = NCSelectHomeFragment().apply { arguments = Bundle().apply { putParcelable(KEY_SERVER_THEME, theme) }}
    }
}