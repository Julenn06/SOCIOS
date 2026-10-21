package com.julen.socios.data

import com.julen.socios.data.local.CobroDao
import com.julen.socios.data.local.CobroEntity
import com.julen.socios.model.CobroInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class CobroRepository(private val cobroDao: CobroDao) {

    /** Lo cobrado de verdad en la semana, o null si aún no se ha apuntado. */
    fun getCobroFlow(semanaKey: String): Flow<CobroInfo?> =
        cobroDao.getCobroFlow(semanaKey).map { it?.toCobroInfo() }

    fun getAllFlow(): Flow<Map<String, CobroInfo>> =
        cobroDao.getAllFlow().map { entities -> entities.associateBy({ it.semanaKey }, { it.toCobroInfo() }) }

    fun getSemanaKeysFlow(): Flow<List<String>> = cobroDao.getSemanaKeysFlow()

    suspend fun guardar(cobro: CobroInfo) = cobroDao.upsert(CobroEntity.fromCobroInfo(cobro))

    suspend fun eliminar(semanaKey: String) = cobroDao.delete(semanaKey)
}
