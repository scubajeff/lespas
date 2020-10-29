package site.leos.apps.lespas.album

import android.app.Application
import androidx.lifecycle.LiveData
import site.leos.apps.lespas.LespasDatabase

class AlbumRepository(application: Application){
    private val albumDao = LespasDatabase.getDatabase(application).albumDao()

    val allAlbumsByEndDate: LiveData<List<Album>> = albumDao.getAllByEndDate()
    suspend fun insert(album: Album){ albumDao.insert(album) }
    fun upsertSync(album: Album) { albumDao.upsertSync(album) }
    suspend fun update(album: Album){ albumDao.update(album) }
    fun deleteByIdSync(albumId: String) { albumDao.deleteByIdSync(albumId) }
    fun getSyncStatus(): Map<String, String> {
        return albumDao.getSyncStatus().map { it.id to it.eTag}.toMap()
    }

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