package site.leos.apps.lespas

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import site.leos.apps.lespas.album.Album
import site.leos.apps.lespas.album.AlbumDao
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.photo.PhotoDao
import site.leos.apps.lespas.sync.Action
import site.leos.apps.lespas.sync.ActionDao

@Database(entities = [Album::class, Photo::class, Action::class], version = 5, exportSchema = false)
@TypeConverters(Converter::class)
abstract class LespasDatabase: RoomDatabase() {
    abstract fun albumDao(): AlbumDao
    abstract fun photoDao(): PhotoDao
    abstract fun actionDao(): ActionDao

    companion object {
        @Volatile
        private var INSTANCE: LespasDatabase? = null

        fun getDatabase(context: Context): LespasDatabase {
            val tempInstance = INSTANCE

            if (tempInstance != null) return tempInstance
            synchronized(this) {
                // TODO: proper database migration!!!!!!!!!!!!!!!!!!!
                //val instance = Room.databaseBuilder(context, LespasDatabase::class.java, "lespas.db").build()
                val instance = Room.databaseBuilder(context, LespasDatabase::class.java, "lespas.db").fallbackToDestructiveMigration().build()
                INSTANCE = instance
                return instance
            }
        }
    }
}