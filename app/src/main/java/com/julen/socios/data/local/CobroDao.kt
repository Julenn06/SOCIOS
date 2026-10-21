package com.julen.socios.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CobroDao {
    @Query("SELECT * FROM cobros WHERE semanaKey = :semanaKey LIMIT 1")
    fun getCobroFlow(semanaKey: String): Flow<CobroEntity?>

    @Query("SELECT * FROM cobros")
    suspend fun getAll(): List<CobroEntity>

    @Query("SELECT * FROM cobros")
    fun getAllFlow(): Flow<List<CobroEntity>>

    @Query("SELECT semanaKey FROM cobros")
    fun getSemanaKeysFlow(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(cobro: CobroEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(cobros: List<CobroEntity>)

    @Query("DELETE FROM cobros WHERE semanaKey = :semanaKey")
    suspend fun delete(semanaKey: String)
}
