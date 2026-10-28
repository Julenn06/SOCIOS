package com.julen.socios.data

import com.julen.socios.data.local.SocioDao
import com.julen.socios.data.local.SocioEntity
import com.julen.socios.model.Socio
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Los DAO suspend de Room ya se ejecutan fuera del hilo principal: no hace falta withContext(IO).
class SocioRepository(private val socioDao: SocioDao) {

    fun getSociosPorSemanaFlow(semanaKey: String): Flow<List<Socio>> =
        socioDao.getSociosPorSemanaFlow(semanaKey).aSocios()

    fun getAllSociosFlow(): Flow<List<Socio>> =
        socioDao.getAllSociosFlow().aSocios()

    fun getSemanaKeysFlow(): Flow<List<String>> = socioDao.getSemanaKeysFlow()

    /** Socios que siguen pendientes en semanas anteriores a [semanaKey], de la más reciente a la más antigua. */
    fun getPendientesAnterioresFlow(semanaKey: String): Flow<List<Socio>> =
        socioDao.getPendientesAnterioresFlow(semanaKey).aSocios()

    suspend fun addSocio(socio: Socio) = socioDao.insertSocio(SocioEntity.fromSocio(socio))

    suspend fun updateSocio(socio: Socio) = socioDao.updateSocio(SocioEntity.fromSocio(socio))

    suspend fun deleteSocio(socioId: String) = socioDao.deleteSocio(socioId)

    /** Devuelve true si el socio existía y se ha actualizado. */
    suspend fun setHecho(socioId: String, hecho: Boolean): Boolean = socioDao.setHecho(socioId, hecho) > 0

    private fun Flow<List<SocioEntity>>.aSocios(): Flow<List<Socio>> = map { lista -> lista.map { it.toSocio() } }
}
