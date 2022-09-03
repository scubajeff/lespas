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
import android.content.Context
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.os.Bundle
import android.text.format.DateUtils
import android.util.AttributeSet
import android.widget.TextView
import androidx.core.view.doOnPreDraw
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreferenceCompat
import site.leos.apps.lespas.R
import site.leos.apps.lespas.sync.Action
import site.leos.apps.lespas.sync.SyncAdapter
import java.util.*

class SyncStatusPreference (private val ctx: Context, attributeSet: AttributeSet): Preference(ctx, attributeSet), OnSharedPreferenceChangeListener {
    private lateinit var sp: SharedPreferences
    private var currentActionTextView: TextView? = null
    private val syncPreferenceKey = ctx.getString(R.string.sync_pref_key)

    init {
        // layout can't contain view only, must has layout
        layoutResource = R.layout.preference_sync_status
    }

    override fun onAttached() {
        super.onAttached()
        sp = preferenceManager.sharedPreferences.apply { registerOnSharedPreferenceChangeListener(this@SyncStatusPreference) }
        setVisibility()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder?) {
        super.onBindViewHolder(holder)

        currentActionTextView = holder?.findViewById(R.id.current_action) as? TextView
        currentActionTextView?.doOnPreDraw { currentActionTextView?.text = getCurrentActionSummary() }
    }

    override fun onDetached() {
        super.onDetached()
        sp.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onClick() {
        super.onClick()

        // Start sync
        ContentResolver.requestSync(AccountManager.get(ctx).getAccountsByType(ctx.getString(R.string.account_type_nc))[0], ctx.getString(R.string.sync_authority), Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putInt(SyncAdapter.ACTION, SyncAdapter.SYNC_BOTH_WAY)
        })

        // Disable clickable immediately
        this@SyncStatusPreference.isSelectable = false
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == this@SyncStatusPreference.key) currentActionTextView?.text = getCurrentActionSummary()
        if (key == syncPreferenceKey) setVisibility()
    }

    private fun getCurrentActionSummary(): String {
        return try {
            sp.getString(this.key, "")?.split("``")?.let { action ->
                // action is String array of: actionId|folderId|folderName|fileId|fileName|date
                if (action.isNotEmpty()) {
                    val actionId = action[0].toInt()
                    
                    // Enable push to sync if the sync is finished
                    this@SyncStatusPreference.isSelectable = actionId >= Action.ACTION_RESULT_FINISHED
                    
                    when(actionId) {
                        Action.ACTION_DELETE_FILES_ON_SERVER -> String.format(ctx.getString(R.string.sync_status_action_delete_media), action[4], action[2])
                        Action.ACTION_DELETE_DIRECTORY_ON_SERVER -> String.format(ctx.getString(R.string.sync_status_action_delete_album), action[2])
                        Action.ACTION_ADD_FILES_ON_SERVER -> String.format(ctx.getString(R.string.sync_status_action_add_media), action[4], action[2])
                        Action.ACTION_ADD_DIRECTORY_ON_SERVER -> String.format(ctx.getString(R.string.sync_status_action_delete_album), action[2])
                        Action.ACTION_RENAME_DIRECTORY -> {
                            when {
                                action[4].first() == '.' -> String.format(ctx.getString(R.string.sync_status_action_hide_album), action[2])
                                action[2].first() == '.' -> String.format(ctx.getString(R.string.sync_status_action_unhide_album), action[4])
                                else -> String.format(ctx.getString(R.string.sync_status_action_rename_album), action[2], action[4])
                            }
                        }
                        Action.ACTION_RENAME_FILE -> String.format(ctx.getString(R.string.sync_status_action_update_file), action[3], action[2])
                        Action.ACTION_UPDATE_ALBUM_COVER -> String.format(ctx.getString(R.string.sync_status_action_update_album_cover), action[2])
                        Action.ACTION_ADD_FILES_TO_JOINT_ALBUM -> String.format(ctx.getString(R.string.sync_status_action_add_media_to_joint_album), action[4], action[2].substringAfterLast('/'))
                        Action.ACTION_UPDATE_JOINT_ALBUM_PHOTO_META -> String.format(ctx.getString(R.string.sync_status_action_add_media_to_joint_album), action[2].substringAfterLast('/'))
                        Action.ACTION_UPDATE_THIS_ALBUM_META -> String.format(ctx.getString(R.string.sync_status_action_update_album_meta), action[2])
                        Action.ACTION_UPDATE_THIS_CONTENT_META -> String.format(ctx.getString(R.string.sync_status_action_update_album_content_meta), action[2])
                        Action.ACTION_UPDATE_ALBUM_BGM -> String.format(ctx.getString(R.string.sync_status_action_update_album_bgm), action[2])
                        Action.ACTION_DELETE_ALBUM_BGM -> String.format(ctx.getString(R.string.sync_status_action_delete_album_bgm), action[2])
                        Action.ACTION_COPY_ON_SERVER, Action.ACTION_MOVE_ON_SERVER ->
                            String.format(
                                ctx.getString(if (action[0].toInt() == Action.ACTION_COPY_ON_SERVER) R.string.sync_status_action_copy else R.string.sync_status_action_move),
                                action[4].substringBefore('|'),
                                if (action[1].substringBefore('/') == "DCIM") ctx.getString(R.string.item_camera_roll) else action[1].substringAfterLast('/'),
                                action[2].substringAfterLast('/')
                            )
                        Action.ACTION_DELETE_CAMERA_BACKUP_FILE -> String.format(ctx.getString(R.string.sync_status_action_delete_media_from_backup), action[4])
                        Action.ACTION_PATCH_PROPERTIES -> String.format(ctx.getString(R.string.sync_status_action_patch_property), action[4])
                        Action.ACTION_REMOTE_SYNC -> ctx.getString(R.string.sync_status_action_sync_remote)

                        // Various status
                        Action.ACTION_RESULT_FINISHED -> getLastDateString(ctx.getString(R.string.sync_status_result_finished), action[5].toLong())
                        Action.ACTION_RESULT_NO_WIFI -> getLastDateString(ctx.getString(R.string.sync_status_result_no_wifi), action[5].toLong())
                        Action.ACTION_SYNC_STARTED -> ctx.getString(R.string.sync_status_sync_starting)
                        else -> ""
                    }
                } else ""
            } ?: ""
        } catch (_: Exception) { "" }
    }

    // timestamp must be in millisecond
    private fun getLastDateString(message: String, timestamp: Long): String =
        String.format(message, DateUtils.getRelativeTimeSpanString(timestamp, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString().lowercase(Locale.ROOT))

    private fun setVisibility() {
        preferenceManager.findPreference<SwitchPreferenceCompat>(syncPreferenceKey)?.let { this@SyncStatusPreference.isVisible = it.isChecked }
    }
}