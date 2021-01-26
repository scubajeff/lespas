package site.leos.apps.lespas.sync

import android.app.Application
import kotlinx.coroutines.flow.Flow
import site.leos.apps.lespas.LespasDatabase

class ActionRepository(application: Application){
    private val actionDao = LespasDatabase.getDatabase(application).actionDao()

    fun deleteSync(action: Action) = actionDao.deleteSync(action)
    fun pendingActionsFlow(): Flow<List<Action>> = actionDao.pendingActionsFlow()
    fun getAllPendingActions(): List<Action> = actionDao.getAllPendingActions()
    fun hasPendingActions(): Boolean = actionDao.getPendingTotal() > 0
    fun deleteAllActions() = actionDao.deleteAllSync()
    suspend fun addActions(actions: List<Action>) = actionDao.insert(actions)
    suspend fun addActions(action: Action) = actionDao.insert(action)
    fun addAction(action: Action) = actionDao.insertSync(action)
    suspend fun updateCover(albumId: String, coverId: String) { actionDao.updateCover(albumId, coverId) }
}