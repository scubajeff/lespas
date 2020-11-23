package site.leos.apps.lespas.album

import android.app.Application
import kotlinx.coroutines.flow.Flow
import site.leos.apps.lespas.LespasDatabase

class AlbumRepository(application: Application){
    private val albumDao = LespasDatabase.getDatabase(application).albumDao()

    fun getAllAlbumsSortByEndDate(): Flow<List<Album>> = albumDao.getAllSortByEndDate()
    fun getAlbumByID(albumId: String): Flow<Album> = albumDao.getAlbumByID(albumId)
    fun getThisAlbum(albumId: String): List<Album> = albumDao.getThisAlbum(albumId)
    suspend fun insert(album: Album){ albumDao.insert(album) }
    fun upsertSync(album: Album) { albumDao.upsertSync(album) }
    fun updateSync(albums: List<Album>) { albumDao.updateSync(albums) }
    fun updateSync(album: Album) { albumDao.updateSync(album) }
    suspend fun update(album: Album){ albumDao.update(album) }
    fun deleteByIdSync(albumId: String) { albumDao.deleteByIdSync(albumId) }
    fun changeName(albumId: String, newName: String) = albumDao.changeName(albumId, newName)
    suspend fun setCover(album: Album, cover: Cover) { albumDao.setCover(album.id, cover.id, cover.baseLine) }
    suspend fun deleteAlbums(albums: List<Album>) { albumDao.delete(albums) }
    fun getAllAlbumNamesAndIds(): Flow<List<AlbumNameAndId>> = albumDao.getAllAlbumNamesAndId()
    fun isAlbumExisted(name: String) = albumDao.isAlbumExisted(name)
    fun getAllAlbumIds(): List<String> = albumDao.getAllIds()
    fun getTheseAlbums(albums: ArrayList<String>): List<Album> = albumDao.getTheseAlbums(albums)
    fun getAlbumWithPhotos(albumId: String): List<AlbumWithPhotos> = albumDao.getAlbumWithPhotos(albumId)

    companion object {
        private var repo: AlbumRepository? = null
        fun getRepository(application: Application): AlbumRepository {
            synchronized(Any()) {
                repo = repo ?: AlbumRepository(application)
            }
            return repo!!
        }
    }
}