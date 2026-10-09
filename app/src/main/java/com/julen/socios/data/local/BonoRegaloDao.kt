package com.julen.socios.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BonoRegaloDao {
    @Query("SELECT * FROM bonos_regalo WHERE semanaKey = :semanaKey LIMIT 1")
    suspend fun getBonoRegalo(semanaKey: String): BonoRegaloEntity?

    @Query("SELECT * FROM bonos_regalo WHERE semanaKey = :semanaKey LIMIT 1")
    fun getBonoRegaloFlow(semanaKey: String): Flow<BonoRegaloEntity?>

    @Query("SELECT * FROM bonos_regalo WHERE activo = 1")
    suspend fun getAllBonosRegalo(): List<BonoRegaloEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveBonoRegalo(bono: BonoRegaloEntity)

    @Query("DELETE FROM bonos_regalo WHERE semanaKey = :semanaKey")
    suspend fun deleteBonoRegalo(semanaKey: String)
}
