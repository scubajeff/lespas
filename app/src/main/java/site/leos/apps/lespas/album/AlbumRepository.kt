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
    fun setCover(albumId: String, cover: Cover) { albumDao.setCover(albumId, cover.cover, cover.coverBaseline, cover.coverWidth, cover.coverHeight) }
    fun getMeta(albumId: String): Meta = albumDao.getMeta(albumId)
    fun deleteAlbums(albums: List<Album>) { albumDao.delete(albums) }
    fun getAllAlbumIdAndETag(): List<IDandETag> = albumDao.getAllIdAndETag()
    fun getAlbumDetail(albumId: String): Flow<AlbumWithPhotos> = albumDao.getAlbumDetail(albumId)
    fun updateAlbumSyncStatus(albumId: String, progress: Float, startDate: LocalDateTime, endDate: LocalDateTime) { albumDao.updateAlbumSyncStatus(albumId, progress, startDate, endDate)}
    fun fixNewLocalAlbumId(oldId: String, newId: String, coverId: String) { albumDao.fixNewLocalAlbumId(oldId, newId, coverId)}
    fun fixCoverId(albumId: String, newCoverId: String) { albumDao.fixCoverId(albumId, newCoverId)}
    fun setSortOrder(albumId: String, sortOrder: Int) { albumDao.setSortOrder(albumId, sortOrder) }
    fun getAllAlbumIdName(): List<IDandName> = albumDao.getAllAlbumIdName()
    fun getAlbumTotal(): Int = albumDao.getAlbumTotal()
    fun getAllAlbumName(): List<String> = albumDao.getAllAlbumName()
    fun getAllHiddenAlbumsFlow(): Flow<List<Album>> = albumDao.getAllHiddenAlbumsFlow()
    fun getAllHiddenAlbumIds(): List<String> = albumDao.getAllHiddenAlbumIds()
}