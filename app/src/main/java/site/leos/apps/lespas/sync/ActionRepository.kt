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

package site.leos.apps.lespas.sync

import android.app.Application
import kotlinx.coroutines.flow.Flow
import site.leos.apps.lespas.LespasDatabase

class ActionRepository(application: Application){
    private val actionDao = LespasDatabase.getDatabase(application).actionDao()

    fun delete(action: Action) = actionDao.delete(action)
    fun pendingActionsFlow(): Flow<List<Action>> = actionDao.pendingActionsFlow()
    fun getAllPendingActions(): List<Action> = actionDao.getAllPendingActions()
    //fun hasPendingActions(): Boolean = actionDao.getPendingTotal() > 0
    //fun deleteAllActions() = actionDao.deleteAllSync()
    fun addActions(actions: List<Action>) = actionDao.insert(actions)
    fun addAction(action: Action) = actionDao.insert(action)
    fun updateCoverInPendingActions(albumId: String, coverId: String) { actionDao.updateCoverInPendingActions(albumId, coverId) }
    //fun safeToRemoveFile(photoName: String): Boolean = !actionDao.fileInUse(photoName)
    //fun discardCurrentWorkingAction() { actionDao.deleteFirstRow() }
}