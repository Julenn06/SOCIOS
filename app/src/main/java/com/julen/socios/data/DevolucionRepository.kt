package com.julen.socios.data

import com.julen.socios.data.local.DevolucionDao
import com.julen.socios.data.local.DevolucionEntity
import com.julen.socios.model.DevolucionInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DevolucionRepository(private val devolucionDao: DevolucionDao) {

    /** Devoluciones descontadas en la semana, o null si no hay. */
    fun getDevolucionFlow(semanaKey: String): Flow<DevolucionInfo?> =
        devolucionDao.getDevolucionFlow(semanaKey).map { it?.toDevolucionInfo() }

    fun getAllFlow(): Flow<Map<String, DevolucionInfo>> =
        devolucionDao.getAllFlow().map { entities ->
            entities.associateBy({ it.semanaKey }, { it.toDevolucionInfo() })
        }

    fun getSemanaKeysFlow(): Flow<List<String>> = devolucionDao.getSemanaKeysFlow()

    suspend fun guardar(devolucion: DevolucionInfo) =
        devolucionDao.upsert(DevolucionEntity.fromDevolucionInfo(devolucion))

    suspend fun eliminar(semanaKey: String) = devolucionDao.delete(semanaKey)
}
