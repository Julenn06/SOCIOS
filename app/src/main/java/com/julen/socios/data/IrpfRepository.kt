package com.julen.socios.data

import com.julen.socios.data.local.IrpfVigenciaDao
import com.julen.socios.data.local.IrpfVigenciaEntity
import com.julen.socios.model.IrpfVigencia
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class IrpfRepository(private val irpfDao: IrpfVigenciaDao) {

    fun getVigenciasFlow(): Flow<List<IrpfVigencia>> =
        irpfDao.getAllFlow().map { list -> list.map { it.toIrpfVigencia() } }

    /** Aplica [porcentaje] desde [desdeSemanaKey] en adelante, sin tocar las semanas anteriores. */
    suspend fun guardarVigencia(desdeSemanaKey: String, porcentaje: Double) =
        irpfDao.upsert(IrpfVigenciaEntity(desdeSemanaKey, porcentaje))
}
