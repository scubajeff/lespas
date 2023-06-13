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

import android.app.Application
import kotlinx.coroutines.flow.Flow
import site.leos.apps.lespas.LespasDatabase

class BackupSettingRepository(application: Application) {
    private val backupSettingDao = LespasDatabase.getDatabase(application).backupSettingDao()

    fun getSetting(folder: String): Flow<BackupSetting?> = backupSettingDao.getSetting(folder)
    fun getEnabled(): List<BackupSetting> = backupSettingDao.getEnabledSettings()
    fun getEnabledFlow(): Flow<List<BackupSetting>> = backupSettingDao.getEnabledFlow()
    fun updateSetting(setting: BackupSetting) { backupSettingDao.update(setting) }
    fun enableBackup(folder: String) {
        if (backupSettingDao.isExisted(folder)) backupSettingDao.enableBackup(folder)
        else backupSettingDao.upsert(BackupSetting(folder))
    }
    fun disableBackup(folder: String) { backupSettingDao.disableBackup(folder) }
    fun updateLastBackupTimestamp(folder: String, timestamp: Long) { backupSettingDao.updateLastBackupTimestamp(folder, timestamp) }
}