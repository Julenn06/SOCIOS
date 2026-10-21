package com.julen.socios.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DevolucionDao {
    @Query("SELECT * FROM devoluciones WHERE semanaKey = :semanaKey LIMIT 1")
    fun getDevolucionFlow(semanaKey: String): Flow<DevolucionEntity?>

    @Query("SELECT * FROM devoluciones")
    suspend fun getAll(): List<DevolucionEntity>

    @Query("SELECT * FROM devoluciones")
    fun getAllFlow(): Flow<List<DevolucionEntity>>

    @Query("SELECT semanaKey FROM devoluciones")
    fun getSemanaKeysFlow(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(devolucion: DevolucionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(devoluciones: List<DevolucionEntity>)

    @Query("DELETE FROM devoluciones WHERE semanaKey = :semanaKey")
    suspend fun delete(semanaKey: String)
}
