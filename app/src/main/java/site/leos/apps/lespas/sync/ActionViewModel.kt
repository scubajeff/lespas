package site.leos.apps.lespas.sync

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import site.leos.apps.lespas.album.Album
import site.leos.apps.lespas.album.AlbumRepository
import site.leos.apps.lespas.album.Cover
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.photo.PhotoRepository
import java.io.File

class ActionViewModel(application: Application): AndroidViewModel(application) {
    private val actionRepository = ActionRepository(application)
    private val albumRepository = AlbumRepository(application)
    private val photoRepository = PhotoRepository(application)
    private val localRootFolder = Tools.getLocalRoot(application)

    val allPendingActions: LiveData<List<Action>> = actionRepository.pendingActionsFlow().asLiveData()

    fun deleteAlbums(albums: List<Album>) {
        viewModelScope.launch(Dispatchers.IO) {
            albumRepository.deleteAlbums(albums)

            val actions = mutableListOf<Action>()
            val timestamp = System.currentTimeMillis()
            albums.forEach {album->
                // Delete album's photo from database and disk
                val allPhoto = photoRepository.getAlbumPhotos(album.id)
                photoRepository.deletePhotosByAlbum(album.id)
                allPhoto.forEach { photo ->
                    if (photo.eTag.isEmpty()) try { if (actionRepository.safeToRemoveFile(photo.name)) File(localRootFolder, photo.name).delete() } catch (e: Exception) { e.printStackTrace() }
                    else try { File(localRootFolder, photo.id).delete() } catch (e: Exception) { e.printStackTrace() }
                }

                // Remove local meta file
                try { File(localRootFolder, "${album.id}.json").delete() } catch (e: Exception) { e.printStackTrace() }

                actions.add(Action(null, Action.ACTION_DELETE_DIRECTORY_ON_SERVER, album.id, album.name,"", "", timestamp,1))

            }

            actionRepository.addActions(actions)
        }
    }

    fun deletePhotos(photos: List<Photo>, albumName: String)  {
        viewModelScope.launch(Dispatchers.IO) {
            // Delete from local database
            photoRepository.deletePhotos(photos)

            // Create new actions on server side
            val actions = mutableListOf<Action>()
            val timestamp = System.currentTimeMillis()

            photos.forEach { photo->
                if (photo.eTag.isEmpty()) try { if (actionRepository.safeToRemoveFile(photo.name)) File(localRootFolder, photo.name).delete() } catch (e: Exception) { e.printStackTrace() }
                else {
                    try { File(localRootFolder, photo.id).delete() } catch (e: Exception) { e.printStackTrace() }
                    // Remove video thumbnail too
                    if (photo.mimeType.startsWith("video")) try { File(localRootFolder, "${photo.id}.thumbnail").delete() } catch (e: Exception) { e.printStackTrace() }
                }

                // For a synced photo, id can not be the same as name (sort of, in very rare case, filename can be the same as it's future fileid on server, if this ever happens,
                // the only problem is that it would reappear after next sync, e.g. can only be deleted on server. This can be solved with adding another column in Photo table)
                // folderName field can be empty in these actions
                if (photo.id != photo.name) actions.add(Action(null, Action.ACTION_DELETE_FILES_ON_SERVER, photo.albumId, albumName, photo.id, photo.name, timestamp, 1))
            }

            // Get remaining photos in album, the return list is sort by dateTaken ASC
            val photosLeft = photoRepository.getAlbumPhotos(photos[0].albumId)
            if (photosLeft.isNotEmpty()) {
                val album = albumRepository.getThisAlbum(photos[0].albumId)
                album.startDate = photosLeft.first().dateTaken
                album.endDate = photosLeft.last().dateTaken
                albumRepository.update(album)
            } else {
                // All photos under this album removed, delete album
                albumRepository.deleteById(photos[0].albumId)
                // Delete folder instead of deleting photos 1 by 1
                actions.clear()
                actions.add(Action(null, Action.ACTION_DELETE_DIRECTORY_ON_SERVER, photos[0].albumId, albumName, "", "", timestamp, 1))
            }

            actionRepository.addActions(actions)
        }
    }

    fun renameAlbum(albumId: String, oldName: String, newName: String, sharedAlbum: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            albumRepository.changeName(albumId, newName)
            if (!sharedAlbum) actionRepository.addActions(Action(null, Action.ACTION_RENAME_DIRECTORY, albumId, oldName, "", newName, System.currentTimeMillis(), 1))
        }
    }

    fun updateCover(albumId: String, cover: Cover) {
        viewModelScope.launch(Dispatchers.IO) {
            albumRepository.setCover(albumId, cover)

            // Don't update meta if cover does not have a a proper fileID, in that case, meta file will be maintained in SyncAdapter when the fileId is ready
            if (!cover.cover.contains('.')) actionRepository.updateAlbumMeta(albumId, cover.cover)

            // If album has not been uploaded yet, update the cover id in action table too
            actionRepository.updateCover(albumId, cover.cover)
        }
    }

    fun updateAlbumMeta(album: Album) { viewModelScope.launch(Dispatchers.IO) { actionRepository.updateAlbumMeta(album) }}

    fun hideAlbums(albums: List<Album>) {
        viewModelScope.launch(Dispatchers.IO) {
            val actions = mutableListOf<Action>()
            albums.forEach {
                albumRepository.changeName(it.id, ".${it.name}")
                actions.add(Action(null, Action.ACTION_RENAME_DIRECTORY, it.id, it.name, "", ".${it.name}", System.currentTimeMillis(), 1))
            }
            actionRepository.addActions(actions)
        }
    }

    fun unhideAlbums(albums: List<Album>) {
        viewModelScope.launch(Dispatchers.IO) {
            val actions = mutableListOf<Action>()
            albums.forEach {
                albumRepository.changeName(it.id, it.name.substring(1))
                actions.add(Action(null, Action.ACTION_RENAME_DIRECTORY, it.id, it.name, "", it.name.substring(1), System.currentTimeMillis(), 1))
            }
            actionRepository.addActions(actions)
        }
    }

    fun refreshAlbumList() {
        viewModelScope.launch(Dispatchers.IO) {
            actionRepository.addAction(Action(null, Action.ACTION_REFRESH_ALBUM_LIST, "", "", "", "", System.currentTimeMillis(), 1))
        }
    }

    fun updateBGM(albumName: String, mimeType: String, bgmFileName: String) { viewModelScope.launch(Dispatchers.IO) { actionRepository.addAction(Action(null, Action.ACTION_UPDATE_ALBUM_BGM, mimeType, albumName, bgmFileName, bgmFileName, System.currentTimeMillis(), 1)) }}
    fun removeBGM(albumName: String) { viewModelScope.launch(Dispatchers.IO) { actionRepository.addAction(Action(null, Action.ACTION_DELETE_ALBUM_BGM, "", albumName, "", "", System.currentTimeMillis(), 1)) }}
}