package com.julen.socios.data

import com.julen.socios.data.local.BonoRegaloDao
import com.julen.socios.data.local.BonoRegaloEntity
import com.julen.socios.model.BonoRegaloInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class BonoRegaloRepository(private val bonoDao: BonoRegaloDao) {

    /** Bono de la semana, o null si la semana usa el bonus automático. */
    fun getBonoRegaloFlow(semanaKey: String): Flow<BonoRegaloInfo?> =
        bonoDao.getBonoRegaloFlow(semanaKey).map { entity -> entity?.toBonoRegaloInfo()?.takeIf { it.activo } }

    fun getAllBonosRegaloFlow(): Flow<Map<String, BonoRegaloInfo>> =
        bonoDao.getAllBonosRegaloFlow().map { entities ->
            entities.associateBy({ it.semanaKey }, { it.toBonoRegaloInfo() })
        }

    fun getSemanaKeysFlow(): Flow<List<String>> = bonoDao.getSemanaKeysFlow()

    suspend fun saveBonoRegalo(bonoInfo: BonoRegaloInfo) =
        bonoDao.saveBonoRegalo(BonoRegaloEntity.fromBonoRegaloInfo(bonoInfo))

    suspend fun deleteBonoRegalo(semanaKey: String) = bonoDao.deleteBonoRegalo(semanaKey)
}
