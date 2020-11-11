package site.leos.apps.lespas.sync

import android.app.Application
import site.leos.apps.lespas.LespasDatabase

class ActionRepository(application: Application){
    private val actionDao = LespasDatabase.getDatabase(application).actionDao()

    fun getAllPendingActions(): List<Action> = actionDao.getAllPendingActions()
    fun hasPendingActions(): Boolean = actionDao.getPendingTotal() > 0
    suspend fun addActions(actions: List<Action>) = actionDao.insert(actions)
}