package site.leos.apps.lespas.sync

import android.app.Application
import kotlinx.coroutines.flow.Flow
import site.leos.apps.lespas.LespasDatabase
import site.leos.apps.lespas.album.Album

class ActionRepository(application: Application){
    private val actionDao = LespasDatabase.getDatabase(application).actionDao()
    private val photoDao = LespasDatabase.getDatabase(application).photoDao()

    fun delete(action: Action) = actionDao.delete(action)
    fun pendingActionsFlow(): Flow<List<Action>> = actionDao.pendingActionsFlow()
    fun getAllPendingActions(): List<Action> = actionDao.getAllPendingActions()
    //fun hasPendingActions(): Boolean = actionDao.getPendingTotal() > 0
    //fun deleteAllActions() = actionDao.deleteAllSync()
    suspend fun addActions(actions: List<Action>) = actionDao.insert(actions)
    suspend fun addActions(action: Action) = actionDao.insert(action)
    fun addAction(action: Action) = actionDao.insert(action)
    fun updateCover(albumId: String, coverId: String) { actionDao.updateCover(albumId, coverId) }
    fun safeToRemoveFile(photoName: String): Boolean = !actionDao.fileInUse(photoName)
    fun updateAlbumMeta(album: Album) { actionDao.updateAlbumMeta(album.id, photoDao.getName(album.cover)) }
    fun updateAlbumMeta(albumId: String, coverId: String) { actionDao.updateAlbumMeta(albumId, photoDao.getName(coverId)) }
    fun discardCurrentWorkingAction() { actionDao.delete(actionDao.getAllPendingActions()[0]) }
}