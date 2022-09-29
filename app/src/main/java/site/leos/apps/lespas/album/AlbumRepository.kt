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

package site.leos.apps.lespas.album

import android.app.Application
import kotlinx.coroutines.flow.Flow
import site.leos.apps.lespas.LespasDatabase
import java.time.LocalDateTime

class AlbumRepository(application: Application){
    private val albumDao = LespasDatabase.getDatabase(application).albumDao()

    fun getAllAlbumsSortByEndDate(): Flow<List<Album>> = albumDao.getAllSortByEndDate()
    fun getThisAlbum(albumId: String): Album = albumDao.getThisAlbum(albumId)
    fun getThisAlbumList(albumId: String): List<Album> = albumDao.getThisAlbumList(albumId)
    fun getAlbumByName(albumName: String): Album? = albumDao.getAlbumByName(albumName)
    fun upsert(album: Album) { albumDao.upsert(album) }
    fun update(album: Album){ albumDao.update(album) }
    fun deleteById(albumId: String) { albumDao.deleteById(albumId) }
    fun changeName(albumId: String, newName: String) = albumDao.changeName(albumId, newName)
    fun setCover(albumId: String, cover: Cover) { albumDao.setCover(albumId, cover.cover, cover.coverBaseline, cover.coverWidth, cover.coverHeight, cover.coverFileName, cover.coverMimeType, cover.coverOrientation) }
    fun getMeta(albumId: String): Meta = albumDao.getMeta(albumId)
    fun deleteAlbums(albums: List<Album>) { albumDao.delete(albums) }
    fun getAllAlbumIdAndETag(): List<IDandETag> = albumDao.getAllIdAndETag()
    fun getAlbumDetail(albumId: String): Flow<AlbumWithPhotos> = albumDao.getAlbumDetail(albumId)
    fun getAlbumWithPhotos(albumId: String): AlbumWithPhotos = albumDao.getAlbumWithPhotos(albumId)
    fun updateAlbumSyncStatus(albumId: String, progress: Float, startDate: LocalDateTime, endDate: LocalDateTime) { albumDao.updateAlbumSyncStatus(albumId, progress, startDate, endDate)}
    fun fixNewLocalAlbumId(oldId: String, newId: String, coverId: String) { albumDao.fixNewLocalAlbumId(oldId, newId, coverId)}
    fun fixCoverId(albumId: String, newCoverId: String) { albumDao.fixCoverId(albumId, newCoverId)}
    fun setSortOrder(albumId: String, sortOrder: Int) { albumDao.setSortOrder(albumId, sortOrder) }
    fun getAllAlbumIdName(): List<IDandName> = albumDao.getAllAlbumIdName()
    fun getAlbumTotal(): Int = albumDao.getAlbumTotal()
    fun getAllAlbumName(): List<String> = albumDao.getAllAlbumName()
    fun getAllHiddenAlbumsFlow(): Flow<List<Album>> = albumDao.getAllHiddenAlbumsFlow()
    fun getAllHiddenAlbumIds(): List<String> = albumDao.getAllHiddenAlbumIds()
    fun setAsRemote(albumIds: List<String>, asRemote: Boolean) { if (asRemote) albumDao.setAsRemote(albumIds) else albumDao.setAsLocal(albumIds) }
    fun fixBGM(albumId: String, bgmId: String, bgmETag: String) { albumDao.fixBGM(albumId, bgmId, bgmETag) }
    fun getAllAlbumAttribute(): List<IDandAttribute> = albumDao.getAllAlbumAttribute()
    fun setWideList(albumId: String, wideList: Boolean) { if (wideList) albumDao.enableWideList(albumId) else albumDao.disableWideList(albumId) }
    fun changeCoverFileName(albumId: String, newCoverFileName: String) { albumDao.changeCoverFileName(albumId, newCoverFileName) }
}