package site.leos.apps.lespas.sync

import android.app.Application
import kotlinx.coroutines.flow.Flow
import site.leos.apps.lespas.LespasDatabase
import site.leos.apps.lespas.album.Album

class ActionRepository(application: Application){
    private val actionDao = LespasDatabase.getDatabase(application).actionDao()
    private val photoDao = LespasDatabase.getDatabase(application).photoDao()

    fun deleteSync(action: Action) = actionDao.deleteSync(action)
    fun pendingActionsFlow(): Flow<List<Action>> = actionDao.pendingActionsFlow()
    fun getAllPendingActions(): List<Action> = actionDao.getAllPendingActions()
    //fun hasPendingActions(): Boolean = actionDao.getPendingTotal() > 0
    //fun deleteAllActions() = actionDao.deleteAllSync()
    suspend fun addActions(actions: List<Action>) = actionDao.insert(actions)
    suspend fun addActions(action: Action) = actionDao.insert(action)
    fun addAction(action: Action) = actionDao.insertSync(action)
    suspend fun updateCover(albumId: String, coverId: String) { actionDao.updateCover(albumId, coverId) }
    suspend fun safeToRemoveFile(photoName: String): Boolean = !actionDao.fileInUse(photoName)
    suspend fun updateMeta(album: Album) { actionDao.updateMeta(album.id, photoDao.getName(album.cover)) }
    suspend fun updateMeta(albumId: String, coverId: String) { actionDao.updateMeta(albumId, photoDao.getName(coverId)) }
}