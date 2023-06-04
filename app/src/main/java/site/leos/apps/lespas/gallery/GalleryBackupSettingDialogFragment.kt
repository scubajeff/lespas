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

package site.leos.apps.lespas.gallery

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckedTextView
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButtonToggleGroup
import kotlinx.coroutines.launch
import site.leos.apps.lespas.BuildConfig
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.ConfirmDialogFragment
import site.leos.apps.lespas.helper.LesPasDialogFragment
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.sync.BackupSetting
import site.leos.apps.lespas.sync.BackupSettingViewModel
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

class GalleryBackupSettingDialogFragment : LesPasDialogFragment(R.layout.fragment_gallery_backup_setting_dialog) {
    private var setting = BackupSetting()

    private val settingModel: BackupSettingViewModel by viewModels(ownerProducer = { requireParentFragment() })

    private lateinit var folderNameAdapter: FolderNameAdapter
    private lateinit var autoRemoveChoice: MaterialButtonToggleGroup
    private lateinit var lastBackupDate: TextView

    private lateinit var manageMediaPermissionRequestLauncher: ActivityResultLauncher<Intent>
    private var lastCheckedId = R.id.remove_never

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        folderNameAdapter = FolderNameAdapter { folder ->
            if (folder.excluded) setting.exclude.add(folder.name)
            else setting.exclude.remove(folder.name)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            manageMediaPermissionRequestLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ ->
                if (MediaStore.canManageMedia(requireContext())) autoRemoveChoice.check(lastCheckedId)
            }
        }

        savedInstanceState?.let { lastCheckedId = it.getInt(KEY_LAST_CHECKED_ID, R.id.remove_never) }
    }

    @SuppressLint("InlinedApi")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Auto sizing sub folder exclusion list view's height
        view.findViewById<ConstraintLayout>(R.id.background).let { rootLayout ->
            rootLayout.doOnLayout {
                ConstraintSet().run {
                    val height = with(resources.displayMetrics) { (heightPixels.toFloat() * 0.75 - autoRemoveChoice.measuredHeight - TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 88f, this)).roundToInt() }
                    clone(rootLayout)
                    constrainHeight(R.id.exclude_list, ConstraintSet.MATCH_CONSTRAINT)
                    constrainMaxHeight(R.id.exclude_list, height)
                    applyTo(rootLayout)
                }
            }
        }

        view.findViewById<RecyclerView?>(R.id.exclude_list).apply { adapter = folderNameAdapter }
        autoRemoveChoice = view.findViewById<MaterialButtonToggleGroup?>(R.id.remove_options).apply {
            // TODO no way to delete files without prompting user for permission in Android 11
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R) this.isEnabled = false
            else {
                addOnButtonCheckedListener { _, checkedId, isChecked ->
                    if (isChecked) {
                        setting.autoRemove = when (checkedId) {
                            R.id.remove_one_day -> BackupSetting.REMOVE_ONE_DAY
                            R.id.remove_one_week -> BackupSetting.REMOVE_ONE_WEEK
                            R.id.remove_one_month -> BackupSetting.REMOVE_ONE_MONTH
                            else -> BackupSetting.REMOVE_NEVER
                        }

                        if (checkedId != BackupSetting.REMOVE_NEVER && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !MediaStore.canManageMedia(requireContext())) {
                            // Ask for MANAGE_MEDIA permission on Android S+ so that we can remove files without asking for user confirmation everytime
                            if (parentFragmentManager.findFragmentByTag(MANAGE_MEDIA_RATIONALE_CONFIRM_DIALOG) == null) ConfirmDialogFragment.newInstance(getString(R.string.manage_media_access_permission_rationale), positiveButtonText = getString(R.string.proceed_request), requestKey = MANAGE_MEDIA_PERMISSION_RATIONALE_REQUEST).show(parentFragmentManager, MANAGE_MEDIA_RATIONALE_CONFIRM_DIALOG)
                        }
                    }
                }
            }
        }
        lastBackupDate = view.findViewById(R.id.last_backup_date)

        setting.folder = requireArguments().getString(ARGUMENT_FOLDER)!!
        view.findViewById<TextView>(R.id.folder_name).text = getString(R.string.gallery_backup_option_dialog_title, setting.folder.let { name -> if (name == "DCIM") getString(R.string.camera_roll_name) else name })

        parentFragmentManager.setFragmentResultListener(ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY, viewLifecycleOwner) { _, bundle ->
            bundle.getString(ConfirmDialogFragment.INDIVIDUAL_REQUEST_KEY)?.let { requestKey ->
                when(requestKey) {
                    MANAGE_MEDIA_PERMISSION_RATIONALE_REQUEST ->
                        if (bundle.getBoolean(ConfirmDialogFragment.CONFIRM_DIALOG_REQUEST_KEY, false)) {
                            lastCheckedId = autoRemoveChoice.checkedButtonId
                            autoRemoveChoice.check(R.id.remove_never)
                            manageMediaPermissionRequestLauncher.launch(Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.parse("package:${BuildConfig.APPLICATION_ID}")))
                        }
                        else autoRemoveChoice.check(R.id.remove_never)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            settingModel.getSetting(setting.folder).collect {
                setting.autoRemove = it?.autoRemove ?: BackupSetting.REMOVE_NEVER
                autoRemoveChoice.check(
                    when(setting.autoRemove) {
                        BackupSetting.REMOVE_ONE_DAY -> R.id.remove_one_day
                        BackupSetting.REMOVE_ONE_WEEK -> R.id.remove_one_week
                        BackupSetting.REMOVE_ONE_MONTH -> R.id.remove_one_month
                        else -> R.id.remove_never
                    }
                )

                setting.lastBackup = it?.lastBackup ?: 0L
                if (setting.lastBackup > 0L) {
                    val last = LocalDateTime.ofEpochSecond(setting.lastBackup / 1000, 0, OffsetDateTime.now().offset)
                    lastBackupDate.text = getString(R.string.msg_last_backup_date, if (last.toLocalDate() == LocalDate.now()) DateTimeFormatter.ISO_LOCAL_TIME.format(last) else DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(last))
                    lastBackupDate.isVisible = true
                }

                setting.exclude = it?.exclude ?: mutableSetOf()
                Tools.listSubFolders(setting.folder, requireActivity().contentResolver).let { subFoldersContainMediaFiles ->
                    if (subFoldersContainMediaFiles.isNotEmpty()) {
                        val folders = mutableListOf<FolderWithState>()
                        subFoldersContainMediaFiles.forEach { folderName -> folders.add(FolderWithState(folderName, setting.exclude.contains(folderName))) }
                        folderNameAdapter.submitList(folders)

                        view.findViewById<TextView>(R.id.exclude_label).isVisible = true
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(KEY_LAST_CHECKED_ID, lastCheckedId)
        super.onSaveInstanceState(outState)
    }

    override fun onDismiss(dialog: DialogInterface) {
        settingModel.updateSetting(setting)
        super.onDismiss(dialog)
    }

    class FolderNameAdapter(private val clickListener: (FolderWithState) -> Unit) : ListAdapter<FolderWithState, RecyclerView.ViewHolder>(FolderNameDiffCallback()) {
        inner class FolderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvName = itemView.findViewById<CheckedTextView>(R.id.name)

            fun bind(item: FolderWithState, position: Int) {
                tvName.run {
                    text = item.name
                    isChecked = item.excluded

                    setOnClickListener {
                        item.excluded = !item.excluded
                        notifyItemChanged(position)

                        clickListener(item)
                    }
                }
            }
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder = FolderViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.recyclerview_item_sub_folder, parent, false))
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) { (holder as FolderViewHolder).bind(currentList[position], position) }
    }

    class FolderNameDiffCallback : DiffUtil.ItemCallback<FolderWithState>() {
        override fun areItemsTheSame(oldItem: FolderWithState, newItem: FolderWithState): Boolean = oldItem.name == newItem.name
        override fun areContentsTheSame(oldItem: FolderWithState, newItem: FolderWithState): Boolean = oldItem.excluded == newItem.excluded
    }

    data class FolderWithState (
        var name: String = "",
        var excluded: Boolean = false
    )

    companion object {
        private const val MANAGE_MEDIA_RATIONALE_CONFIRM_DIALOG = "MANAGE_MEDIA_RATIONALE_CONFIRM_DIALOG"
        private const val MANAGE_MEDIA_PERMISSION_RATIONALE_REQUEST = "MANAGE_MEDIA_PERMISSION_RATIONALE_REQUEST"

        private const val KEY_LAST_CHECKED_ID = "KEY_LAST_CHECKED_ID"

        private const val ARGUMENT_FOLDER = "ARGUMENT_FOLDER"

        @JvmStatic
        fun newInstance(folder: String) = GalleryBackupSettingDialogFragment().apply { arguments = Bundle().apply { putString(ARGUMENT_FOLDER, folder) }}
    }
}