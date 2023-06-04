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
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class BackupSettingViewModel(application: Application): AndroidViewModel(application) {
    private val backupSettingRepository = BackupSettingRepository(application)

    fun getSetting(folder: String): Flow<BackupSetting?> = backupSettingRepository.getSetting(folder)
    fun updateSetting(setting: BackupSetting) { viewModelScope.launch(Dispatchers.IO) { backupSettingRepository.updateSetting(setting) }}
    fun getBackupEnableStates(): Flow<List<FolderState>> = backupSettingRepository.getBackupEnableStates()
    fun enableBackup(folder: String) { viewModelScope.launch(Dispatchers.IO) { backupSettingRepository.enableBackup(folder) }}
    fun disableBackup(folder: String) { viewModelScope.launch(Dispatchers.IO) { backupSettingRepository.disableBackup(folder) }}
    fun updateLastBackupTimestamp(folder: String, timestamp: Long) { viewModelScope.launch(Dispatchers.IO) { backupSettingRepository.updateLastBackupTimestamp(folder, timestamp) }}
}