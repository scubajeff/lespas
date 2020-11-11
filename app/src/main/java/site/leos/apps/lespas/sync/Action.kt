package site.leos.apps.lespas.sync

import android.content.ContentValues
import android.database.Cursor
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import site.leos.apps.lespas.BaseDao

@Entity(tableName = Action.TABLE_NAME)
data class Action (
    @PrimaryKey(autoGenerate = true) val id: Long?,
    val action: Int,
    val folderId: String,
    val fileName: String,
    val date: Long) {
   companion object {
        const val TABLE_NAME = "actions"

        const val ACTION_DELETE_FILES_ON_SERVER = 1
        const val ACTION_DELETE_DIRECTORY_ON_SERVER = 2
        const val ACTION_ADD_FILES_ON_SERVER = 3
        const val ACTION_ADD_DIRECTORY_ON_SERVER = 4
        const val ACTION_MODIFY_ALBUM_ON_SERVER = 5

       fun fromContentValues(values: ContentValues): Action =
           Action(values.getAsLong("id"), values.getAsInteger("action"), values.getAsString("folderId"), values.getAsString("fileName"), values.getAsLong("date"))
   }
}

@Dao
abstract class ActionDao: BaseDao<Action>() {
    @Query("SELECT * FROM ${Action.TABLE_NAME} ORDER BY id ASC")
    abstract fun getAllPendingActions(): List<Action>

    @Query("SELECT * FROM ${Action.TABLE_NAME} ORDER BY id ASC")
    abstract fun getAllCursor(): Cursor

    @Query("SELECT * FROM ${Action.TABLE_NAME} WHERE id = :actionId")
    abstract fun getByIdCursor(actionId: Long): Cursor

    @Query("DELETE FROM ${Action.TABLE_NAME} WHERE id = :rowId")
    abstract fun deleteById(rowId: Long): Int

    @Query("SELECT COUNT(*) FROM ${Action.TABLE_NAME}")
    abstract fun getPendingTotal(): Int
}