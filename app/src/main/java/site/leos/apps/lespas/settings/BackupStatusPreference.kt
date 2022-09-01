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

import android.content.Context
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.os.Build
import android.provider.MediaStore
import android.text.format.DateUtils
import android.util.AttributeSet
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import site.leos.apps.lespas.R
import java.util.*

class BackupStatusPreference (private val ctx: Context, attributeSet: AttributeSet): Preference(ctx, attributeSet), OnSharedPreferenceChangeListener {
    private lateinit var sp: SharedPreferences
    private var statusProgressBar: ProgressBar? = null
    private var currentFileTextView: TextView? = null
    private var remainingTextView: TextView? = null
    private val pendingBackupList = mutableListOf<String>()

    init {
        layoutResource = R.layout.preference_backup_status
    }

    override fun onAttached() {
        super.onAttached()
        sp = preferenceManager.sharedPreferences
        sp.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onDependencyChanged(dependency: Preference?, disableDependent: Boolean) {
        super.onDependencyChanged(dependency, disableDependent)

        this@BackupStatusPreference.isVisible = !disableDependent
        getPendingBackupList()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder?) {
        super.onBindViewHolder(holder)

        holder?.run {
            statusProgressBar = findViewById(R.id.camera_backup_progress) as? ProgressBar
            currentFileTextView = findViewById(R.id.current_file) as? TextView
            remainingTextView = findViewById(R.id.remaining) as? TextView
        }
        currentFileTextView?.text = getLastDateString(sp.getLong(SettingsFragment.LAST_BACKUP, System.currentTimeMillis()).let { if (it == 0L) System.currentTimeMillis() else it * 1000 })
    }

    override fun onDetached() {
        super.onDetached()
        sp.unregisterOnSharedPreferenceChangeListener(this)
    }

    private fun getPendingBackupList() {
        Thread {
            pendingBackupList.clear()
            try {
                @Suppress("DEPRECATION")
                val pathSelection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Files.FileColumns.RELATIVE_PATH else MediaStore.Files.FileColumns.DATA
                ctx.contentResolver.query(
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
        }.start()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == this.key && pendingBackupList.size > 0) {
            sp.getString(this.key, "")?.let { fileName ->
                statusProgressBar?.setProgress(pendingBackupList.indexOf(fileName.substringBeforeLast(" (")).let { index ->
                    if (index == -1) {
                        if (fileName.isEmpty()) {
                            statusProgressBar?.isVisible = false
                            currentFileTextView?.text = getLastDateString(System.currentTimeMillis())
                            pendingBackupList.clear()
                        }
                        0
                    } else {
                        statusProgressBar?.run {
                            isVisible = true
                            max = pendingBackupList.size
                        }
                        currentFileTextView?.text = fileName
                        (pendingBackupList.size - index - 1).let { left ->
                            if (left > 0) {
                                remainingTextView?.run {
                                    isVisible = true
                                    text = String.format(ctx.getString(R.string.cameraroll_backup_remaining), left)
                                }
                            } else remainingTextView?.isVisible = false
                        }
                        index
                    }
                }, true)
            }
        }
    }

    // timestamp must be in millisecond
    private fun getLastDateString(timestamp: Long): String =
        String.format(ctx.getString(R.string.cameraroll_backup_last_time), DateUtils.getRelativeTimeSpanString(timestamp, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString().lowercase(Locale.ROOT))
}