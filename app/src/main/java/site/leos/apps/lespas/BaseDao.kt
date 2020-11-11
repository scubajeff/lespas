package site.leos.apps.lespas

import androidx.room.*

abstract class BaseDao<T> {
    // async functions
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insert(obj: T): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insert(obj: List<T>): List<Long>

    @Update
    abstract suspend fun update(obj: T)

    @Update
    abstract suspend fun update(obj: List<T>)

    @Delete
    abstract suspend fun delete(obj: T)

    @Delete
    abstract suspend fun delete(obj: List<T>)

    @Transaction
    open suspend fun upsert(obj: T) {
        if (insert(obj) == -1L) {
            update(obj)
        }
    }

    @Transaction
    open suspend fun upsert(objList: List<T>) {
        val insertResult = insert(objList)
        val updateList = mutableListOf<T>()

        for (i in insertResult.indices) {
            if (insertResult[i] == -1L) updateList.add(objList[i])
        }

        if (updateList.isNotEmpty()) update(updateList)
    }


    // sync functions
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract fun insertSync(obj: T): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract fun insertSync(obj: List<T>): List<Long>

    @Update
    abstract fun updateSync(obj: T): Int

    @Update
    abstract fun updateSync(obj: List<T>)

    @Delete
    abstract fun deleteSync(obj: T)

    @Delete
    abstract fun deleteSync(obj: List<T>)

    @Transaction
    open fun upsertSync(obj: T) {
        if (insertSync(obj) == -1L) {
            updateSync(obj)
        }
    }

    @Transaction
    open fun upsertSync(objList: List<T>) {
        val insertResult = insertSync(objList)
        val updateList = mutableListOf<T>()

        for (i in insertResult.indices) {
            if (insertResult[i] == -1L) updateList.add(objList[i])
        }

        if (updateList.isNotEmpty()) updateSync(updateList)
    }
}