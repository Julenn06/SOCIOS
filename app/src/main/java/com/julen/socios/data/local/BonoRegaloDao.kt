package com.julen.socios.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface BonoRegaloDao {
    @Query("SELECT * FROM bonos_regalo WHERE semanaKey = :semanaKey LIMIT 1")
    fun getBonoRegaloFlow(semanaKey: String): Flow<BonoRegaloEntity?>

    @Query("SELECT * FROM bonos_regalo WHERE activo = 1")
    suspend fun getAllBonosRegalo(): List<BonoRegaloEntity>

    @Query("SELECT * FROM bonos_regalo WHERE activo = 1")
    fun getAllBonosRegaloFlow(): Flow<List<BonoRegaloEntity>>

    @Query("SELECT semanaKey FROM bonos_regalo WHERE activo = 1")
    fun getSemanaKeysFlow(): Flow<List<String>>

    @Upsert
    suspend fun saveBonoRegalo(bono: BonoRegaloEntity)

    @Upsert
    suspend fun saveBonosRegalo(bonos: List<BonoRegaloEntity>)

    @Query("DELETE FROM bonos_regalo WHERE semanaKey = :semanaKey")
    suspend fun deleteBonoRegalo(semanaKey: String)
}
