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
                    if (photo.eTag == Photo.ETAG_NOT_YET_UPLOADED) removeLocalMediaFile(photo)
                    else {
                        if (Tools.isRemoteAlbum(album)) {
                            if (photo.mimeType.startsWith("video")) try { File("$localRootFolder/cache", "${photo.id}.thumbnail").delete() } catch (e: Exception) {}
                        } else removeLocalMediaFile(photo)
                    }
                }

                // Remove local meta file
                try { File(localRootFolder, "${album.id}.json").delete() } catch (e: Exception) {}

                actions.add(Action(null, Action.ACTION_DELETE_DIRECTORY_ON_SERVER, album.id, album.name,"", "", timestamp,1))
            }

            actionRepository.addActions(actions)
        }
    }

    fun deletePhotos(photos: List<Photo>, album: Album)  {
        viewModelScope.launch(Dispatchers.IO) {
            // Delete from local database
            photoRepository.deletePhotos(photos)

            // Create new actions on server side
            val actions = mutableListOf<Action>()
            val timestamp = System.currentTimeMillis()

            photos.forEach { photo->
/*
                if (photo.eTag == Photo.ETAG_NOT_YET_UPLOADED) try { if (actionRepository.safeToRemoveFile(photo.name)) File(localRootFolder, photo.name).delete() } catch (e: Exception) { e.printStackTrace() }
                else if (!Tools.isRemoteAlbum(album)){
                    // Delete media file if album is "Local"
                    try { File(localRootFolder, photo.id).delete() } catch (e: Exception) { e.printStackTrace() }
                    // Remove video thumbnail too
                    if (photo.mimeType.startsWith("video")) try { File(localRootFolder, "${photo.id}.thumbnail").delete() } catch (e: Exception) { e.printStackTrace() }
                }

                // For a synced photo, id can not be the same as name (sort of, in very rare case, filename can be the same as it's future fileid on server, if this ever happens,
                // the only problem is that it would reappear after next sync, e.g. can only be deleted on server. This can be solved with adding another column in Photo table)
                // folderName field can be empty in these actions
                if (photo.id != photo.name) actions.add(Action(null, Action.ACTION_DELETE_FILES_ON_SERVER, photo.albumId, album.name, photo.id, photo.name, timestamp, 1))
*/
                if (photo.eTag == Photo.ETAG_NOT_YET_UPLOADED) {
                    // Deleting the media file will prevent ACTION_ADD_FILES_ON_SERVER from starting since in that process file existence will be checked at the beginning
                    removeLocalMediaFile(photo)
                } else {
                    // Remove media file on server
                    actions.add(Action(null, Action.ACTION_DELETE_FILES_ON_SERVER, photo.albumId, album.name, photo.id, photo.name, timestamp, 1))

                    if (Tools.isRemoteAlbum(album)){
                        // Remove video thumbnail in cache folder
                        if (photo.mimeType.startsWith("video")) try { File("$localRootFolder/cache", "${photo.id}.thumbnail").delete() } catch (e: Exception) {}
                    }
                    else {
                        // Remove local media file if it's a Local album
                        removeLocalMediaFile(photo)
                    }
                }
            }

            // Get remaining photos in album, the return list is sort by dateTaken ASC
            val photosLeft = photoRepository.getAlbumPhotos(photos[0].albumId)
            if (photosLeft.isNotEmpty()) {
                album.startDate = photosLeft.first().dateTaken
                album.endDate = photosLeft.last().dateTaken
                albumRepository.update(album)
            } else {
                // All photos under this album removed, delete album
                albumRepository.deleteById(photos[0].albumId)
                // Delete folder instead of deleting photos 1 by 1
                actions.clear()
                actions.add(Action(null, Action.ACTION_DELETE_DIRECTORY_ON_SERVER, photos[0].albumId, album.name, "", "", timestamp, 1))

                // Remove local meta file
                try { File(localRootFolder, "${album.id}.json").delete() } catch (e: Exception) {}
            }

            actionRepository.addActions(actions)
        }
    }

    fun deletePhotosLocalRecord(photos: List<Photo>) {
        photos.forEach { photo ->
            // Remove local media file only if photo has finished uploading. Since this is for moving photos among albums, let those uploading finished first, then the actually deletion will be synced back
            //  to local in the next sync
            if (photo.eTag != Photo.ETAG_NOT_YET_UPLOADED) removeLocalMediaFile(photo)
        }

        viewModelScope.launch(Dispatchers.IO) { photoRepository.deletePhotos(photos) }
    }

    fun renameAlbum(albumId: String, oldName: String, newName: String, sharedAlbum: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            albumRepository.changeName(albumId, newName)
            if (!sharedAlbum) actionRepository.addAction(Action(null, Action.ACTION_RENAME_DIRECTORY, albumId, oldName, "", newName, System.currentTimeMillis(), 1))
        }
    }

    fun renamePhoto(photo: Photo, album: Album, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            actionRepository.addAction(Action(null, Action.ACTION_RENAME_FILE, album.id, album.name, photo.name, newName, System.currentTimeMillis(), 1))
        }
    }

    fun updateCover(albumId: String, cover: Cover) {
        viewModelScope.launch(Dispatchers.IO) {
            albumRepository.setCover(albumId, cover)

            // Don't update meta if cover does not have a a proper fileID, in that case, meta file will be maintained in SyncAdapter when the fileId is ready
            if (!cover.cover.contains('.')) actionRepository.addAction(Action(null, Action.ACTION_UPDATE_ALBUM_META, albumId, "", "", "", System.currentTimeMillis(), 1))

            // If album has not been uploaded yet, update pending action ACTION_ADD_DIRECTORY_ON_SERVER's cover id action table too
            actionRepository.updateCoverInPendingActions(albumId, cover.cover)
        }
    }

    fun updateAlbumSortOrderInMeta(album: Album) {
        viewModelScope.launch(Dispatchers.IO) {
            // Don't update meta if cover does not have a a proper fileID, in that case, meta file will be maintained in SyncAdapter when the fileId is ready
            if (!album.cover.contains('.')) actionRepository.addAction(Action(null, Action.ACTION_UPDATE_ALBUM_META, album.id, "", "", "", System.currentTimeMillis(), 1))
        }
    }

    fun hideAlbums(albums: List<Album>) { setHiddenState(albums, true) }
    fun unhideAlbums(albums: List<Album>) { setHiddenState(albums, false) }
    private fun setHiddenState(albums: List<Album>, hide: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val actions = mutableListOf<Action>()
            var newName: String
            albums.forEach {
                newName = if (hide) ".${it.name}" else it.name.substring(1)
                albumRepository.changeName(it.id, newName)
                actions.add(Action(null, Action.ACTION_RENAME_DIRECTORY, it.id, it.name, "", newName, System.currentTimeMillis(), 1))
            }
            actionRepository.addActions(actions)
        }
    }

/*
    fun refreshAlbumList() {
        viewModelScope.launch(Dispatchers.IO) {
            actionRepository.addAction(Action(null, Action.ACTION_REFRESH_ALBUM_LIST, "", "", "", "", System.currentTimeMillis(), 1))
        }
    }
*/

    fun updateBGM(albumName: String, mimeType: String, bgmFileName: String) { viewModelScope.launch(Dispatchers.IO) { actionRepository.addAction(Action(null, Action.ACTION_UPDATE_ALBUM_BGM, mimeType, albumName, bgmFileName, bgmFileName, System.currentTimeMillis(), 1)) }}
    fun removeBGM(albumName: String) { viewModelScope.launch(Dispatchers.IO) { actionRepository.addAction(Action(null, Action.ACTION_DELETE_ALBUM_BGM, "", albumName, "", "", System.currentTimeMillis(), 1)) }}

    fun addActions(actions: List<Action>) { viewModelScope.launch(Dispatchers.IO) { actionRepository.addActions(actions) }}

    private fun removeLocalMediaFile(photo: Photo) {
        try { File(localRootFolder, photo.id).delete() } catch (e: Exception) {}
        // Remove video thumbnail too
        if (photo.mimeType.startsWith("video")) try { File(localRootFolder, "${photo.id}.thumbnail").delete() } catch (e: Exception) {}
    }
}