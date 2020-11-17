package site.leos.apps.lespas.album

import android.app.Application
import kotlinx.coroutines.flow.Flow
import site.leos.apps.lespas.LespasDatabase

class AlbumRepository(application: Application){
    private val albumDao = LespasDatabase.getDatabase(application).albumDao()

    fun getAllAlbumsSortByEndDate(): Flow<List<Album>> = albumDao.getAllSortByEndDate()
    fun getAlbumByID(albumId: String): Flow<Album> = albumDao.getAlbumByID(albumId)
    suspend fun insert(album: Album){ albumDao.insert(album) }
    fun upsertSync(album: Album) { albumDao.upsertSync(album) }
    suspend fun update(album: Album){ albumDao.update(album) }
    fun deleteByIdSync(albumId: String) { albumDao.deleteByIdSync(albumId) }
    fun getETagsMap(): Map<String, String> = albumDao.getETagsMap().map { it.id to it.eTag}.toMap()
    fun getNamesMap(): Map<String, String> = albumDao.getNamesMap().map { it.id to it.name}.toMap()
    fun changeName(albumId: String, newName: String) = albumDao.changeName(albumId, newName)
    suspend fun setCover(album: Album, cover: Cover) { albumDao.setCover(album.id, cover.name, cover.baseLine) }
    fun getAlbumName(albumId: String): String = albumDao.getAlbumName(albumId)
    suspend fun deleteAlbums(albums: List<Album>) { albumDao.delete(albums) }
    fun getAllAlbumNamesAndIds(): Flow<List<AlbumNameAndId>> = albumDao.getAllAlbumNamesAndId()

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