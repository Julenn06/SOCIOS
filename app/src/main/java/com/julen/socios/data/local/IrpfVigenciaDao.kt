package com.julen.socios.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface IrpfVigenciaDao {
    @Query("SELECT * FROM irpf_vigencias ORDER BY desdeSemanaKey")
    suspend fun getAll(): List<IrpfVigenciaEntity>

    @Query("SELECT * FROM irpf_vigencias ORDER BY desdeSemanaKey")
    fun getAllFlow(): Flow<List<IrpfVigenciaEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(vigencias: List<IrpfVigenciaEntity>)
}
