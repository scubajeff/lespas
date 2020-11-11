package site.leos.apps.lespas.album

import android.app.Application
import androidx.lifecycle.LiveData
import site.leos.apps.lespas.LespasDatabase

class AlbumRepository(application: Application){
    private val albumDao = LespasDatabase.getDatabase(application).albumDao()

    val allAlbumsSortByEndDate: LiveData<List<Album>> = albumDao.getAllSortByEndDate()
    fun getAlbumByID(albumId: String): LiveData<Album> = albumDao.getAlbumByID(albumId)
    suspend fun insert(album: Album){ albumDao.insert(album) }
    fun upsertSync(album: Album) { albumDao.upsertSync(album) }
    suspend fun update(album: Album){ albumDao.update(album) }
    fun deleteByIdSync(albumId: String) { albumDao.deleteByIdSync(albumId) }
    fun getSyncStatus(): Map<String, String> = albumDao.getSyncStatus().map { it.id to it.eTag}.toMap()
    suspend fun setCover(album: Album, cover: Cover) { albumDao.setCover(album.id, cover.name, cover.baseLine) }
    fun getAlbumName(albumId: String): String = albumDao.getAlbumName(albumId)
    suspend fun deleteAlbums(albums: List<Album>) { albumDao.delete(albums) }

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