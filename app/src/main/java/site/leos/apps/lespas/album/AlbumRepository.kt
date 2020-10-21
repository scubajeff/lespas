package site.leos.apps.lespas.album

import androidx.lifecycle.LiveData

class AlbumRepository(private val albumDao: AlbumDao){
    val allAlbumsByEndDate: LiveData<List<Album>> = albumDao.getAllByEndDate()

    suspend fun insert(album: Album){
        albumDao.insert(album)
    }
}