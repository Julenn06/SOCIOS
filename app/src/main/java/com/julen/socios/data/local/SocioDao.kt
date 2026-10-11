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

    @Query("SELECT * FROM socios ORDER BY timestamp DESC")
    fun getAllSociosFlow(): Flow<List<SocioEntity>>

    @Query("SELECT * FROM socios WHERE semanaKey IN (:semanaKeys) ORDER BY timestamp DESC")
    suspend fun getSociosPorSemanas(semanaKeys: List<String>): List<SocioEntity>

    @Query("SELECT * FROM socios WHERE semanaKey = :semanaKey ORDER BY timestamp DESC")
    fun getSociosPorSemanaFlow(semanaKey: String): Flow<List<SocioEntity>>

    @Query("SELECT DISTINCT semanaKey FROM socios")
    fun getSemanaKeysFlow(): Flow<List<String>>

    @Query("SELECT id FROM socios")
    suspend fun getAllIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSocio(socio: SocioEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSocios(socios: List<SocioEntity>)

    @Update
    suspend fun updateSocio(socio: SocioEntity)

    /** Fija el estado en lugar de alternarlo: dos pulsaciones seguidas no pueden desincronizar la BD y el switch. */
    @Query("UPDATE socios SET hecho = :hecho WHERE id = :socioId")
    suspend fun setHecho(socioId: String, hecho: Boolean): Int

    @Query("DELETE FROM socios WHERE id = :socioId")
    suspend fun deleteSocio(socioId: String)
}
