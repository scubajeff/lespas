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

package site.leos.apps.lespas.settings

import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.SharedPreferences
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.preference.PreferenceManager
import com.google.android.material.button.MaterialButton
import site.leos.apps.lespas.R
import site.leos.apps.lespas.helper.LesPasDialogFragment
import site.leos.apps.lespas.sync.Action
import site.leos.apps.lespas.sync.SyncAdapter
import java.util.Locale

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sp = PreferenceManager.getDefaultSharedPreferences(requireContext())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        keySyncStatus = getString(R.string.sync_status_pref_key)
        keySyncStatusLocalAction = getString(R.string.sync_status_local_action_pref_key)
        keyBackupStatus = getString(R.string.backup_status_pref_key)

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
            keyBackupStatus -> showBackupStatus()
        }
    }

    private fun hideBackupStatusViews() {
        backupProgressBar.isVisible = false
        currentFileTextView.isVisible = false
        remainingTextView.isVisible = false
    }

    private fun unhideBackupStatusViews() {
        backupProgressBar.isVisible = true
        currentFileTextView.isVisible = true
        remainingTextView.isVisible = true
        showBackupStatus()
    }

    private fun showBackupStatus() {
        sp.getString(keyBackupStatus, " | |0|0|0")?.split('|')?.let { message ->
            if (message.isNotEmpty()) {
                try {
                    // backup message is String array of: filename|file size in human readable format|current index in set|set total|timestamp in millisecond
                    val total = message[3].toInt()
                    val current = message[2].toInt()
                    if (total > 0) {
                        backupProgressBar.run {
                            isVisible = true
                            max = total
                            setProgress(current, true)
                        }
                        currentFileTextView.text = String.format("%s (%s)", message[0], message[1])
                        (total - current).let { left ->
                            remainingTextView.isVisible = left > 0
                            remainingTextView.text = String.format(getString(R.string.backup_remaining), left)
                        }
                    } else {
                        backupProgressBar.isVisible = false
                        currentFileTextView.text = ""
                        remainingTextView.text = ""
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun getCurrentActionSummary(): String {
        return try {
            sp.getString(keySyncStatusLocalAction, "")?.split("``")?.let { action ->
                // action is String array of: actionId``folderId``folderName``fileId``fileName``timestamp in millisecond
                if (action.isNotEmpty()) {
                    when(action[0].toInt()) {
                        // Local actions
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
                        Action.ACTION_UPDATE_ALBUM_META -> String.format(getString(R.string.sync_status_action_update_album_meta), action[2])
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
                                if (action[1].substringBefore('/') == "DCIM") getString(R.string.camera_roll_name) else action[1].substringAfterLast('/'),
                                action[2].substringAfterLast('/')
                            )
                        Action.ACTION_DELETE_FILE_IN_ARCHIVE -> String.format(getString(R.string.sync_status_action_delete_media_from_backup), action[4].substringAfterLast('/'))
                        Action.ACTION_PATCH_PROPERTIES -> String.format(getString(R.string.sync_status_action_patch_property), action[4])
                        Action.ACTION_CREATE_BLOG_POST -> String.format(getString(R.string.sync_status_action_create_blog), action[2])

                        // Remote actions
                        Action.ACTION_COLLECT_REMOTE_CHANGES -> getString(R.string.sync_status_action_collect_remote_changes)
                        Action.ACTION_CREATE_ALBUM_FROM_SERVER -> String.format(getString(R.string.sync_status_action_create_album_from_server), action[1])
                        Action.ACTION_UPDATE_ALBUM_FROM_SERVER -> String.format(getString(R.string.sync_status_action_update_album_from_server), action[1])

                        // Backup actions
                        Action.ACTION_BACKUP_FILE -> String.format(getString(R.string.sync_status_action_backup_file), action[1])
                        else -> ""
                    }
                } else ""
            } ?: ""
        } catch (_: Exception) { "" }
    }

    private fun getCurrentStage(): String {
        return try {
            sp.getString(keySyncStatus, "")?.split('|')?.let { action ->
                // stage is String array of: actionId``timestamp in millisecond
                if (action.isNotEmpty()) {
                    val stageId = action[0].toInt()

                    // Disable re-sync when syncing
                    reSyncButton.isEnabled = stageId >= Action.SYNC_RESULT_FINISHED
                    // Show local action status view when in these stages
                    currentLocalActionTextView.isVisible = stageId == Action.SYNC_STAGE_LOCAL || stageId == Action.SYNC_STAGE_REMOTE || stageId == Action.SYNC_RESULT_ERROR_GENERAL
                    // Update backup status view visibility
                    if (stageId == Action.SYNC_STAGE_BACKUP_PICTURES) unhideBackupStatusViews() else hideBackupStatusViews()

                    when(stageId) {
                        // Various stages
                        Action.SYNC_STAGE_STARTED -> getString(R.string.sync_status_stage_started)
                        Action.SYNC_STAGE_LOCAL -> getString(R.string.sync_status_stage_sync_local)
                        Action.SYNC_STAGE_REMOTE -> getString(R.string.sync_status_stage_sync_remote)
                        Action.SYNC_STAGE_BACKUP_PICTURES -> getString(R.string.sync_status_stage_backup_pictures)

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