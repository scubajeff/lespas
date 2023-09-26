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

package site.leos.apps.lespas.photo

import android.app.Application
import kotlinx.coroutines.flow.Flow
import site.leos.apps.lespas.LespasDatabase
import java.time.LocalDateTime

class PhotoRepository(application: Application) {
    private val photoDao = LespasDatabase.getDatabase(application).photoDao()
    private val albumDao = LespasDatabase.getDatabase(application).albumDao()

    fun getAlbumPhotosFlow(albumId: String): Flow<List<Photo>> = photoDao.getAlbumPhotosFlow(albumId)
    fun upsert(photo: Photo) { photoDao.upsert(photo) }
    fun upsert(photo: List<Photo>) { photoDao.upsert(photo) }
    fun deleteById(photoId: String) { photoDao.deleteById(photoId) }
    fun getETagsMap(albumId: String): Map<String, String> = photoDao.getETagsMap(albumId).map { it.id to it.eTag }.toMap()
    fun getNamesMap(albumId: String): Map<String, String> = photoDao.getNamesMap(albumId).map { it.id to it.name }.toMap()
    fun getAllPhotoIdsByAlbum(albumId: String): List<PhotoName> = photoDao.getNamesMap(albumId)
    fun changeName(photoId: String, newName: String) { photoDao.changeName(photoId, newName) }
    //suspend fun changeName(albumId: String, oldName: String, newName: String) = photoDao.changeName(albumId, oldName, newName)
    fun deletePhotos(photos: List<Photo>) { photoDao.delete(photos)}
    fun deletePhotosByAlbum(albumId: String) = photoDao.deletePhotosByAlbum(albumId)
    fun getAlbumPhotos(albumId: String): List<Photo> = photoDao.getAlbumPhotos(albumId)
    fun insert(photos: List<Photo>) { photoDao.insert(photos) }
    fun fixNewPhotosAlbumId(oldId: String, newId: String) { photoDao.fixNewPhotosAlbumId(oldId, newId) }
    fun fixPhoto(oldId: String, newId: String, newName: String, eTag: String, lastModified: LocalDateTime) { photoDao.fixPhoto(oldId, newId, newName, eTag, lastModified) }
    fun fixPhotoIdEtag(oldId: String, newId: String, eTag: String, setRefreshNetwork: Boolean) {
        if (setRefreshNetwork) photoDao.fixPhotoIdEtagRefresh(oldId, newId, eTag)
        else photoDao.fixPhotoIdEtag(oldId, newId, eTag)
    }
    fun resetNetworkRefresh(photoId: String) { photoDao.resetNetworkRefresh(photoId) }
    fun getAllPhotoNameMap(): List<AlbumPhotoName> = photoDao.getAllPhotoNameMap()
    //suspend fun updatePhoto(oldId: String, newId: String, eTag: String, lastModifiedDate: LocalDateTime, width: Int, height: Int, mimeType: String) { photoDao.updatePhoto(oldId, newId, eTag, lastModifiedDate, width, height, mimeType) }
    //suspend fun replacePhoto(oldPhoto: Photo, newPhoto: Photo) { photoDao.replacePhoto(oldPhoto, newPhoto) }
    //fun removePhoto(photo: Photo) { photoDao.deleteSync(photo) }
    //fun getPhotoName(id: String): String = photoDao.getName(id)
    fun getAllImageNotHidden(): List<Photo> {
        val hiddenAlbums = albumDao.getAllHiddenAlbumIds()
        return photoDao.getAllImage().filter { it.albumId !in hiddenAlbums }
    }
    fun getPhotoTotal(): Int = photoDao.getPhotoTotal()
    //suspend fun getPhotoById(photoId: String): Photo = photoDao.getPhotoById(photoId)
    fun getPhotoMetaInAlbum(albumId: String): List<PhotoMeta> = photoDao.getPhotoMetaInAlbum(albumId)
    fun getMuzeiArtwork(exclusion: List<String>): List<MuzeiPhoto> = photoDao.getMuzeiArtwork(exclusion)
    fun getAlbumDuration(albumId: String): Pair<LocalDateTime, LocalDateTime> = with(photoDao.getAlbumDuration(albumId)) { Pair(this.first(), this.last()) }
    fun setAsLocal(albumIds: List<String>) { photoDao.setAsLocal(albumIds) }
    fun updateAddress(photoId: String, city: String, countryName: String, countryCode: String) { photoDao.updateAddress(photoId, city, countryName, countryCode) }
    fun clearLocality() { photoDao.clearLocality() }
    fun updateDateTaken(photoId: String, dateTaken: LocalDateTime) { photoDao.updateDateTaken(photoId, dateTaken) }
    fun updateCaption(photoId: String, newCaption: String) { photoDao.updateCaption(photoId, newCaption) }
    fun getPhotoExtras(albumId: String): List<PhotoExtras> = photoDao.getPhotoExtras(albumId)
    fun setExcludeFromBlog(photoIds: List<String>, exclude: Boolean) { if (exclude) photoDao.setExcludeFromBlog(photoIds) else photoDao.setIncludeInBlog(photoIds) }
    fun getThisPhoto(photoId: String): Photo = photoDao.getThisPhoto(photoId)
    fun getPhotosForBlog(albumId: String): List<Photo> = photoDao.getPhotosForBlog(albumId)
    fun getPhotoSidecar(albumId: String): List<PhotoSidecar> = photoDao.getPhotoSidecar(albumId)
    fun updateETag(photoId: String, eTag: String) { photoDao.updateETag(photoId, eTag) }
    fun getPhotoIdByNameInAlbum(albumId: String, photoName: String): String = photoDao.getPhotoIdByNameInAlbum(albumId, photoName)
}