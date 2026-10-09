package com.julen.socios.data

import android.content.Context
import com.julen.socios.data.local.AppDatabase
import com.julen.socios.data.local.SocioDao
import com.julen.socios.data.local.SocioEntity
import com.julen.socios.model.Socio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class SocioRepository(context: Context) {
    private val socioDao: SocioDao = AppDatabase.getDatabase(context).socioDao()

    suspend fun getAllSocios(): List<Socio> = withContext(Dispatchers.IO) {
        socioDao.getAllSocios().map { it.toSocio() }
    }

    suspend fun getSociosPorSemana(semanaKey: String): List<Socio> = withContext(Dispatchers.IO) {
        socioDao.getSociosPorSemana(semanaKey).map { it.toSocio() }
    }

    fun getSociosPorSemanaFlow(semanaKey: String): Flow<List<Socio>> {
        return socioDao.getSociosPorSemanaFlow(semanaKey).map { list ->
            list.map { it.toSocio() }
        }
    }

    suspend fun addSocio(socio: Socio) = withContext(Dispatchers.IO) {
        socioDao.insertSocio(SocioEntity.fromSocio(socio))
    }

    suspend fun importSocios(socios: List<Socio>): Int = withContext(Dispatchers.IO) {
        val entities = socios.map { SocioEntity.fromSocio(it) }
        socioDao.insertSocios(entities)
        entities.size
    }

    suspend fun updateSocio(socio: Socio) = withContext(Dispatchers.IO) {
        socioDao.updateSocio(SocioEntity.fromSocio(socio))
    }

    suspend fun deleteSocio(socioId: String) = withContext(Dispatchers.IO) {
        socioDao.deleteSocio(socioId)
    }

    suspend fun toggleSocioHecho(socioId: String): Boolean = withContext(Dispatchers.IO) {
        val existing = socioDao.getSocioById(socioId)
        if (existing != null) {
            val updated = existing.copy(hecho = !existing.hecho)
            socioDao.updateSocio(updated)
            updated.hecho
        } else {
            false
        }
    }
}
