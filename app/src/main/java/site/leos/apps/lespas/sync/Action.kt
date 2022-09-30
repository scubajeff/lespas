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

package site.leos.apps.lespas.sync

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import site.leos.apps.lespas.BaseDao

@Entity(tableName = Action.TABLE_NAME)
data class Action (
    @PrimaryKey(autoGenerate = true) val id: Long?,
    val action: Int,
    val folderId: String = "",
    val folderName: String = "",
    val fileId: String = "",
    val fileName: String ="",
    val date: Long,
    val retry: Int = 1,
){
   companion object {
       const val TABLE_NAME = "actions"

       // Sync action codes
       const val ACTION_FINISHED = 0
       const val ACTION_DELETE_FILES_ON_SERVER = 1
       const val ACTION_DELETE_DIRECTORY_ON_SERVER = 2
       const val ACTION_ADD_FILES_ON_SERVER = 3
       const val ACTION_ADD_DIRECTORY_ON_SERVER = 4
       const val ACTION_MODIFY_ALBUM_ON_SERVER = 5
       const val ACTION_RENAME_DIRECTORY = 6
       const val ACTION_RENAME_FILE = 7
       const val ACTION_UPDATE_FILE = 8
       const val ACTION_UPDATE_ALBUM_META = 9
       const val ACTION_UPDATE_PHOTO_META = 10
       const val ACTION_ADD_FILES_TO_JOINT_ALBUM = 11
       const val ACTION_UPDATE_JOINT_ALBUM_PHOTO_META = 12
       const val ACTION_UPDATE_THIS_ALBUM_META = 13
       const val ACTION_UPDATE_THIS_CONTENT_META = 14
       const val ACTION_UPDATE_ALBUM_BGM = 15
       const val ACTION_DELETE_ALBUM_BGM = 16
       const val ACTION_REFRESH_ALBUM_LIST = 17
       const val ACTION_COPY_ON_SERVER = 18
       const val ACTION_MOVE_ON_SERVER = 19
       //const val ACTION_BATCH_DELETE_FILE_ON_SERVER = 20
       const val ACTION_DELETE_CAMERA_BACKUP_FILE = 21
       const val ACTION_PATCH_PROPERTIES = 22
       const val ACTION_BACKUP_FILE = 23

       const val ACTION_CREATE_BLOG_POST = 30
       const val ACTION_DELETE_BLOG_POST = 31
       const val ACTION_UPDATE_BLOG_SITE_TITLE = 32

       const val ACTION_COLLECT_REMOTE_CHANGES = 1001
       const val ACTION_CREATE_ALBUM_FROM_SERVER = 1002
       const val ACTION_UPDATE_ALBUM_FROM_SERVER = 1003

       // Syncing stage codes
       const val SYNC_STAGE_STARTED = 9000
       const val SYNC_STAGE_LOCAL = 9001
       const val SYNC_STAGE_REMOTE = 9002
       const val SYNC_STAGE_BACKUP = 9003

       // Syncing result codes
       const val SYNC_RESULT_FINISHED = 10000
       const val SYNC_RESULT_NO_WIFI = 10001
       const val SYNC_RESULT_ERROR_GENERAL = 10002
   }
}

@Dao
abstract class ActionDao: BaseDao<Action>() {
    @Query("SELECT * FROM ${Action.TABLE_NAME} ORDER BY id ASC")
    abstract fun pendingActionsFlow(): Flow<List<Action>>

    @Query("SELECT * FROM ${Action.TABLE_NAME} ORDER BY id ASC")
    abstract fun getAllPendingActions(): List<Action>

    @Query("UPDATE ${Action.TABLE_NAME} SET fileName = :coverId WHERE folderId = :albumId AND action = ${Action.ACTION_ADD_DIRECTORY_ON_SERVER}")
    // cover id is stored in fileName property
    abstract fun updateCoverInPendingActions(albumId: String, coverId: String)

    //@Query(value = "SELECT EXISTS (SELECT fileName FROM ${Action.TABLE_NAME} WHERE fileName = :photoName AND action = ${Action.ACTION_ADD_FILES_ON_SERVER})")
    //abstract fun fileInUse(photoName: String): Boolean

/*
    @Query("SELECT * FROM ${Action.TABLE_NAME} LIMIT 1")
    abstract fun getFirstRow(): Action
    @Transaction
    open fun deleteFirstRow() { delete(getFirstRow()) }
*/
}