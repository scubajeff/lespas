package site.leos.apps.lespas

import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Update

interface BaseDao<T> {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(obj: T): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertSync(obj: T): Long

    @Insert
    suspend fun insert(vararg obj: T): LongArray

    @Update(onConflict = OnConflictStrategy.REPLACE)
    suspend fun update(obj: T): Int

    @Delete
    suspend fun delete(obj: T): Int
}