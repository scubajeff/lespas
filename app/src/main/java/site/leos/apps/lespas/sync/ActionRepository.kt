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
    fun discardCurrentWorkingAction() { actionDao.deleteFirstRow() }
}