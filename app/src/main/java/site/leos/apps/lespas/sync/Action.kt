package site.leos.apps.lespas.sync

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import site.leos.apps.lespas.BaseDao

@Entity(tableName = Action.TABLE_NAME)
data class Action (
    @PrimaryKey var id: Long,
    var action: Int,
    var target: String) {
    companion object {
        const val TABLE_NAME = "actions"
    }
}

@Dao
abstract class ActionDao: BaseDao<Action>() {
    @Query("SELECT * FROM ${Action.TABLE_NAME}")
    abstract fun getAllPendingActions(): List<Action>
}