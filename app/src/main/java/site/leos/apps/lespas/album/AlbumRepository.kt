package site.leos.apps.lespas.album

import android.app.Application
import kotlinx.coroutines.flow.Flow
import site.leos.apps.lespas.LespasDatabase
import java.time.LocalDateTime

class AlbumRepository(application: Application){
    private val albumDao = LespasDatabase.getDatabase(application).albumDao()

    fun getAllAlbumsSortByEndDate(): Flow<List<Album>> = albumDao.getAllSortByEndDate()
    fun getThisAlbum(albumId: String): List<Album> = albumDao.getThisAlbum(albumId)
    suspend fun insert(album: Album){ albumDao.insert(album) }
    fun upsertSync(album: Album) { albumDao.upsertSync(album) }
    suspend fun upsert(album: Album) { albumDao.upsert(album) }
    fun updateSync(album: Album) { albumDao.updateSync(album) }
    suspend fun update(album: Album){ albumDao.update(album) }
    fun deleteByIdSync(albumId: String) { albumDao.deleteByIdSync(albumId) }
    fun changeName(albumId: String, newName: String) = albumDao.changeName(albumId, newName)
    suspend fun setCover(albumId: String, cover: Cover) { albumDao.setCover(albumId, cover.cover, cover.coverBaseline, cover.coverWidth, cover.coverHeight) }
    fun getCover(albumId: String): Cover = albumDao.getCover(albumId)
    suspend fun deleteAlbums(albums: List<Album>) { albumDao.delete(albums) }
    fun isAlbumExisted(name: String) = albumDao.isAlbumExisted(name)
    fun getAllAlbumIds(): List<IDandCover> = albumDao.getAllIdAndCover()
    fun getAlbumDetail(albumId: String): Flow<AlbumWithPhotos> = albumDao.getAlbumDetail(albumId)
    fun updateAlbumSyncStatus(albumId: String, progress: Float, startDate: LocalDateTime, endDate: LocalDateTime) { albumDao.updateAlbumSyncStatus(albumId, progress, startDate, endDate)}
    fun fixNewLocalAlbumId(oldId: String, newId: String, coverId: String) { albumDao.fixNewLocalAlbumId(oldId, newId, coverId)}
    fun fixCoverId(albumId: String, newCoverId: String) { albumDao.fixCoverId(albumId, newCoverId)}
    suspend fun setSortOrder(albumId: String, sortOrder: Int) { albumDao.setSortOrder(albumId, sortOrder) }
    fun getAllAlbumName(): List<IDandName> = albumDao.getAllAlbumName()
    suspend fun getAlbumTotal(): Int = albumDao.getAlbumTotal()
    //suspend fun replaceCover(albumId: String, newCoverId: String, newWidth: Int, newHeight: Int, newBaseline: Int) { albumDao.replaceCover(albumId, newCoverId, newWidth, newHeight, newBaseline) }
}