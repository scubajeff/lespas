package site.leos.apps.lespas

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import site.leos.apps.lespas.album.Album
import site.leos.apps.lespas.album.AlbumDao

@Database(entities = [Album::class], version = 1, exportSchema = false)
abstract class LespasDatabase: RoomDatabase() {
    abstract fun albumDao(): AlbumDao

    companion object {
        @Volatile
        private var INSTANCE: LespasDatabase? = null

        fun getDatabase(context: Context): LespasDatabase {
            val tempInstance = INSTANCE

            if (tempInstance != null) return tempInstance
            synchronized(this) {
                val instance = Room.databaseBuilder(context, LespasDatabase::class.java, "lespas.db").build()
                INSTANCE = instance
                return instance
            }
        }
    }
}