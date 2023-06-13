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

package site.leos.apps.lespas.sync

import android.os.Parcelable
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.parcelize.Parcelize
import site.leos.apps.lespas.BaseDao

@Entity(tableName = BackupSetting.TABLE_NAME)
@Parcelize
data class BackupSetting (
    @PrimaryKey var folder: String = "",
    var enabled: Boolean = true,
    var lastBackup: Long = 0L,
    var autoRemove: Int = REMOVE_NEVER,
    var exclude: MutableSet<String> = mutableSetOf(),
) : Parcelable {
    companion object {
        const val TABLE_NAME = "backup_settings"

        const val REMOVE_NEVER = 0
        const val REMOVE_ONE_DAY = 1
        const val REMOVE_ONE_WEEK = 7
        const val REMOVE_ONE_MONTH = 30
    }
}

@Dao
abstract class BackupSettingDao: BaseDao<BackupSetting>() {
    @Query("SELECT * FROM ${BackupSetting.TABLE_NAME} WHERE folder = :folder LIMIT 1")
    abstract fun getSettingDistinctFlow(folder: String): Flow<BackupSetting?>
    fun getSetting(folder: String): Flow<BackupSetting?> = getSettingDistinctFlow(folder).distinctUntilChanged()

    @Query("SELECT * FROM ${BackupSetting.TABLE_NAME} WHERE enabled = 1")
    abstract fun getEnabledSettings(): List<BackupSetting>

    @Query("SELECT * FROM ${BackupSetting.TABLE_NAME} WHERE enabled = 1")
    abstract fun getEnabledSettingsDistinctFlow(): Flow<List<BackupSetting>>
    fun getEnabledFlow(): Flow<List<BackupSetting>> = getEnabledSettingsDistinctFlow().distinctUntilChanged()

    @Query("UPDATE ${BackupSetting.TABLE_NAME} SET enabled = 1 WHERE folder = :folder")
    abstract fun enableBackup(folder: String)

    @Query("UPDATE ${BackupSetting.TABLE_NAME} SET enabled = 0 WHERE folder = :folder")
    abstract fun disableBackup(folder: String)

    @Query(value = "SELECT EXISTS (SELECT folder FROM ${BackupSetting.TABLE_NAME} WHERE folder = :folder LIMIT 1)")
    abstract fun isExisted(folder: String): Boolean

    @Query("UPDATE ${BackupSetting.TABLE_NAME} SET lastBackup = :timestamp WHERE folder = :folder")
    abstract fun updateLastBackupTimestamp(folder: String, timestamp: Long)
}