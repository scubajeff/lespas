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

package site.leos.apps.lespas.settings

import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.format.DateUtils
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.LesPasDialogFragment
import site.leos.apps.lespas.sync.Action
import site.leos.apps.lespas.sync.SyncAdapter
import java.util.*

class SyncStatusDialogFragment: LesPasDialogFragment(R.layout.fragment_sync_status_dialog), SharedPreferences.OnSharedPreferenceChangeListener {
    private lateinit var sp: SharedPreferences
    private lateinit var keySyncStatus: String
    private lateinit var keySyncStatusLocalAction: String
    private lateinit var keyBackupStatus: String

    private lateinit var currentStageTextView: TextView
    private lateinit var currentLocalActionTextView: TextView
    private lateinit var currentFileTextView: TextView
    private lateinit var remainingTextView: TextView
    private lateinit var backupProgressBar: ProgressBar
    private lateinit var reSyncButton: MaterialButton

    private val pendingBackupList = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sp = PreferenceManager.getDefaultSharedPreferences(requireContext())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        keySyncStatus = getString(R.string.sync_status_pref_key)
        keySyncStatusLocalAction = getString(R.string.sync_status_local_action_pref_key)
        keyBackupStatus = getString(R.string.cameraroll_backup_status_pref_key)

        currentStageTextView = view.findViewById(R.id.current_status)
        currentStageTextView.doOnPreDraw { currentStageTextView.text = getCurrentStage() }

        currentLocalActionTextView = view.findViewById(R.id.current_local_action)
        currentLocalActionTextView.doOnPreDraw { currentLocalActionTextView.text = getCurrentActionSummary() }

        backupProgressBar = view.findViewById(R.id.camera_backup_progress)
        currentFileTextView = view.findViewById(R.id.current_file)
        remainingTextView = view.findViewById(R.id.remaining)

        reSyncButton = view.findViewById<MaterialButton>(R.id.resync_button).apply {
            setOnClickListener {
                // Start sync
                ContentResolver.requestSync(AccountManager.get(requireContext()).getAccountsByType(getString(R.string.account_type_nc))[0], getString(R.string.sync_authority), Bundle().apply {
                    putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                    putInt(SyncAdapter.ACTION, SyncAdapter.SYNC_BOTH_WAY)
                })
            }
        }
        view.findViewById<MaterialButton>(R.id.help_button).run {
            setOnClickListener {  }
        }

        lifecycleScope.launch(Dispatchers.IO) {
            pendingBackupList.clear()
            if (sp.getBoolean(getString(R.string.cameraroll_backup_pref_key), false)) {
                // If camera roll backup setting is On, prepare a list of pending backups, with same sorting order which sync adapter backup procedure uses
                try {
                    @Suppress("DEPRECATION")
                    val pathSelection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Files.FileColumns.RELATIVE_PATH else MediaStore.Files.FileColumns.DATA
                    requireContext().contentResolver.query(
                        MediaStore.Files.getContentUri("external"),
                        arrayOf(MediaStore.Files.FileColumns.DISPLAY_NAME),
                        "(${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})" + " AND " +
                                "($pathSelection LIKE '%DCIM%')" + " AND " + "(${MediaStore.Files.FileColumns.DATE_ADDED} > ${sp.getLong(SettingsFragment.LAST_BACKUP, System.currentTimeMillis() / 1000)})",
                        null,
                        "${MediaStore.Files.FileColumns.DATE_ADDED} ASC"
                    )?.use { cursor ->
                        while (cursor.moveToNext()) {
                            pendingBackupList.add(cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)))
                        }
                    }
                } catch (_: Exception) {}
            }

            backupProgressBar.max = pendingBackupList.size
            if (pendingBackupList.size > 0) showBackupStatusViews()
        }
    }

    override fun onResume() {
        super.onResume()
        sp.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onPause() {
        super.onPause()
        sp.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when(key) {
            keySyncStatus -> currentStageTextView.text = getCurrentStage()
            keySyncStatusLocalAction -> currentLocalActionTextView.text = getCurrentActionSummary()
            keyBackupStatus -> if (pendingBackupList.size > 0) {
                sp.getString(keyBackupStatus, "")?.let { fileName ->
                    backupProgressBar.setProgress(pendingBackupList.indexOf(fileName.substringBeforeLast(" (")).let { index ->
                        if (index == -1) {
                            if (fileName.isEmpty()) {
                                hideBackupStatusViews()
                                //currentFileTextView.text = getLastDateString(getString(R.string.cameraroll_backup_last_time), System.currentTimeMillis())
                                pendingBackupList.clear()
                            }
                            0
                        } else {
                            currentFileTextView.text = fileName
                            (pendingBackupList.size - index - 1).let { left ->
                                if (left > 0) remainingTextView.text = String.format(getString(R.string.cameraroll_backup_remaining), left)
                                else remainingTextView.isVisible = false
                            }
                            index
                        }
                    }, true)
                }
            } else hideBackupStatusViews()
        }
    }

    private fun hideBackupStatusViews() {
        backupProgressBar.isVisible = false
        currentFileTextView.isVisible = false
        remainingTextView.isVisible = false
    }

    private fun showBackupStatusViews() {
        backupProgressBar.isVisible = true
        currentFileTextView.isVisible = true
        remainingTextView.isVisible = true
    }

    private fun getCurrentActionSummary(): String {
        return try {
            sp.getString(keySyncStatusLocalAction, "")?.split("``")?.let { action ->
                // action is String array of: actionId``folderId``folderName``fileId``fileName``date
                if (action.isNotEmpty()) {
                    when(action[0].toInt()) {
                        // Various actions
                        Action.ACTION_DELETE_FILES_ON_SERVER -> String.format(getString(R.string.sync_status_action_delete_media), action[4], action[2])
                        Action.ACTION_DELETE_DIRECTORY_ON_SERVER -> String.format(getString(R.string.sync_status_action_delete_album), action[2])
                        Action.ACTION_ADD_FILES_ON_SERVER -> String.format(getString(R.string.sync_status_action_add_media), action[4], action[2])
                        Action.ACTION_ADD_DIRECTORY_ON_SERVER -> String.format(getString(R.string.sync_status_action_delete_album), action[2])
                        Action.ACTION_RENAME_DIRECTORY -> {
                            when {
                                action[4].first() == '.' -> String.format(getString(R.string.sync_status_action_hide_album), action[2])
                                action[2].first() == '.' -> String.format(getString(R.string.sync_status_action_unhide_album), action[4])
                                else -> String.format(getString(R.string.sync_status_action_rename_album), action[2], action[4])
                            }
                        }
                        Action.ACTION_RENAME_FILE -> String.format(getString(R.string.sync_status_action_update_file), action[3], action[2])
                        Action.ACTION_UPDATE_ALBUM_COVER -> String.format(getString(R.string.sync_status_action_update_album_cover), action[2])
                        Action.ACTION_ADD_FILES_TO_JOINT_ALBUM -> String.format(getString(R.string.sync_status_action_add_media_to_joint_album), action[4], action[2].substringAfterLast('/'))
                        Action.ACTION_UPDATE_JOINT_ALBUM_PHOTO_META -> String.format(getString(R.string.sync_status_action_add_media_to_joint_album), action[2].substringAfterLast('/'))
                        Action.ACTION_UPDATE_THIS_ALBUM_META -> String.format(getString(R.string.sync_status_action_update_album_meta), action[2])
                        Action.ACTION_UPDATE_THIS_CONTENT_META -> String.format(getString(R.string.sync_status_action_update_album_content_meta), action[2])
                        Action.ACTION_UPDATE_ALBUM_BGM -> String.format(getString(R.string.sync_status_action_update_album_bgm), action[2])
                        Action.ACTION_DELETE_ALBUM_BGM -> String.format(getString(R.string.sync_status_action_delete_album_bgm), action[2])
                        Action.ACTION_COPY_ON_SERVER, Action.ACTION_MOVE_ON_SERVER ->
                            String.format(
                                getString(if (action[0].toInt() == Action.ACTION_COPY_ON_SERVER) R.string.sync_status_action_copy else R.string.sync_status_action_move),
                                action[4].substringBefore('|'),
                                if (action[1].substringBefore('/') == "DCIM") getString(R.string.item_camera_roll) else action[1].substringAfterLast('/'),
                                action[2].substringAfterLast('/')
                            )
                        Action.ACTION_DELETE_CAMERA_BACKUP_FILE -> String.format(getString(R.string.sync_status_action_delete_media_from_backup), action[4].substringAfterLast('/'))
                        Action.ACTION_PATCH_PROPERTIES -> String.format(getString(R.string.sync_status_action_patch_property), action[4])
                        else -> ""
                    }
                } else ""
            } ?: ""
        } catch (_: Exception) { "" }
    }

    private fun getCurrentStage(): String {
        return try {
            sp.getString(keySyncStatus, "")?.split("``")?.let { action ->
                // stage is String array of: actionId``timestamp
                if (action.isNotEmpty()) {
                    val stageId = action[0].toInt()

                    // Disable re-sync when syncing
                    reSyncButton.isEnabled = stageId >= Action.SYNC_RESULT_FINISHED
                    // Hide local action status when in other stages
                    currentLocalActionTextView.isVisible = stageId == Action.SYNC_STAGE_LOCAL || stageId == Action.SYNC_RESULT_ERROR_GENERAL

                    when(stageId) {
                        // Various stages
                        Action.SYNC_STAGE_STARTED -> getString(R.string.sync_status_stage_started)
                        Action.SYNC_STAGE_LOCAL -> getString(R.string.sync_status_stage_sync_local)
                        Action.SYNC_STAGE_REMOTE -> getString(R.string.sync_status_stage_sync_remote)
                        Action.SYNC_STAGE_BACKUP -> getString(R.string.sync_status_stage_backup)

                        // Various results
                        Action.SYNC_RESULT_FINISHED -> String.format(getString(R.string.sync_status_result_finished), getLastDateString(action[1].toLong()))
                        Action.SYNC_RESULT_NO_WIFI -> String.format(getString(R.string.sync_status_result_no_wifi), getLastDateString(action[1].toLong()))
                        Action.SYNC_RESULT_ERROR_GENERAL -> String.format(getString(R.string.sync_status_result_general_error), getLastDateString(action[1].toLong()))

                        else -> ""
                    }
                } else ""
            } ?: ""
        } catch (_: Exception) { "" }
    }

    // timestamp must be in millisecond
    private fun getLastDateString(timestamp: Long): String = DateUtils.getRelativeTimeSpanString(timestamp, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString().lowercase(Locale.ROOT)
}