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

package site.leos.apps.lespas.album

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.photo.PhotoRepository
import java.io.File

class AlbumViewModel(application: Application) : AndroidViewModel(application){
    private val albumRepository = AlbumRepository(application)
    private val photoRepository = PhotoRepository(application)
    private val localRootFolder = Tools.getLocalRoot(application)

    private var filterText = ""

    val allAlbumsByEndDate: LiveData<List<Album>> = albumRepository.getAllAlbumsSortByEndDate().asLiveData()
    fun getAlbumDetail(albumId: String): LiveData<AlbumWithPhotos> = albumRepository.getAlbumDetail(albumId).asLiveData()
    fun getAllPhotoInAlbum(albumId: String): LiveData<List<Photo>> = photoRepository.getAlbumPhotosFlow(albumId).asLiveData()
    fun setSortOrder(albumId: String, sortOrder: Int) = viewModelScope.launch(Dispatchers.IO) { albumRepository.setSortOrder(albumId, sortOrder) }
    fun getAllAlbumIdName(): List<IDandName> = albumRepository.getAllAlbumIdName()
    fun getThisAlbum(albumId: String): Album = albumRepository.getThisAlbum(albumId)
    fun getAllAlbumName(): List<String> = albumRepository.getAllAlbumName()
    val allHiddenAlbums: LiveData<List<Album>> = albumRepository.getAllHiddenAlbumsFlow().asLiveData()

    fun setAsRemote(albumIds: List<String>, asRemote: Boolean) {
        // Update local db
        albumRepository.setAsRemote(albumIds, asRemote)

        if (asRemote) {
            // If changing from local to remote
            albumIds.forEach {
                photoRepository.getAlbumPhotos(it).forEach { photo ->
                    // Remove all local media files (named after file id), for those media files pending upload, they will be remove after upload complete since album attribute has been changed to remote
                    if (photo.eTag != Photo.ETAG_NOT_YET_UPLOADED) {
                        try { File(localRootFolder, photo.id).delete() } catch (e: Exception) { e.printStackTrace() }
                        if (photo.mimeType.startsWith("video")) try { File(localRootFolder, "${photo.id}.thumbnail").delete() } catch (e: Exception) { e.printStackTrace() }
                    }
                }
            }
        } else {
            photoRepository.setAsLocal(albumIds)
        }
    }

    fun setWideList(albumId: String, wideList: Boolean) { viewModelScope.launch(Dispatchers.IO) { albumRepository.setWideList(albumId, wideList) }}
    fun setExcludeFromBlog(inclusion: List<String>, exclusion: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            photoRepository.setExcludeFromBlog(inclusion, false)
            photoRepository.setExcludeFromBlog(exclusion, true)
        }
    }

    fun saveFilter(text: String) { filterText = text }
    fun restoreFilter(): String = filterText
}