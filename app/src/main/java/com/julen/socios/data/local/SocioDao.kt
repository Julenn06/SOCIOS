package com.julen.socios.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SocioDao {
    @Query("SELECT * FROM socios ORDER BY timestamp DESC")
    suspend fun getAllSocios(): List<SocioEntity>

    @Query("SELECT * FROM socios WHERE semanaKey = :semanaKey ORDER BY timestamp DESC")
    suspend fun getSociosPorSemana(semanaKey: String): List<SocioEntity>

    @Query("SELECT * FROM socios WHERE semanaKey = :semanaKey ORDER BY timestamp DESC")
    fun getSociosPorSemanaFlow(semanaKey: String): Flow<List<SocioEntity>>

    @Query("SELECT * FROM socios WHERE id = :socioId LIMIT 1")
    suspend fun getSocioById(socioId: String): SocioEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSocio(socio: SocioEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSocios(socios: List<SocioEntity>)

    @Update
    suspend fun updateSocio(socio: SocioEntity)

    @Query("DELETE FROM socios WHERE id = :socioId")
    suspend fun deleteSocio(socioId: String)
}
