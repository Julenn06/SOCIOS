package com.julen.socios.data

import com.julen.socios.data.local.SocioDao
import com.julen.socios.data.local.SocioEntity
import com.julen.socios.model.Socio
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Los DAO suspend de Room ya se ejecutan fuera del hilo principal: no hace falta withContext(IO).
class SocioRepository(private val socioDao: SocioDao) {

    fun getSociosPorSemanaFlow(semanaKey: String): Flow<List<Socio>> =
        socioDao.getSociosPorSemanaFlow(semanaKey).map { list -> list.map { it.toSocio() } }

    fun getAllSociosFlow(): Flow<List<Socio>> =
        socioDao.getAllSociosFlow().map { list -> list.map { it.toSocio() } }

    fun getSemanaKeysFlow(): Flow<List<String>> = socioDao.getSemanaKeysFlow()

    suspend fun addSocio(socio: Socio) = socioDao.insertSocio(SocioEntity.fromSocio(socio))

    suspend fun updateSocio(socio: Socio) = socioDao.updateSocio(SocioEntity.fromSocio(socio))

    suspend fun deleteSocio(socioId: String) = socioDao.deleteSocio(socioId)

    /** Devuelve true si el socio existía y se ha actualizado. */
    suspend fun setHecho(socioId: String, hecho: Boolean): Boolean = socioDao.setHecho(socioId, hecho) > 0
}
