/*
 *   Copyright 2019 Jeffrey Liu (scubajeffrey@criptext.com)
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

package site.leos.apps.lespas

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import site.leos.apps.lespas.album.Album
import site.leos.apps.lespas.album.AlbumDao
import site.leos.apps.lespas.helper.Converter
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.photo.PhotoDao
import site.leos.apps.lespas.sync.Action
import site.leos.apps.lespas.sync.ActionDao

@Database(entities = [Album::class, Photo::class, Action::class], version = 10)
@TypeConverters(Converter::class)
abstract class LespasDatabase: RoomDatabase() {
    abstract fun albumDao(): AlbumDao
    abstract fun photoDao(): PhotoDao
    abstract fun actionDao(): ActionDao

    companion object {
        @Volatile
        private var INSTANCE: LespasDatabase? = null

        fun getDatabase(context: Context): LespasDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(context, LespasDatabase::class.java, "lespas.db").fallbackToDestructiveMigration().build()
                INSTANCE = instance

                instance
            }
        }
    }
}