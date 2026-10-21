package com.julen.socios.data

import com.julen.socios.data.local.BonoRegaloDao
import com.julen.socios.data.local.BonoRegaloEntity
import com.julen.socios.data.local.DevolucionDao
import com.julen.socios.data.local.DevolucionEntity
import com.julen.socios.data.local.IrpfVigenciaDao
import com.julen.socios.data.local.IrpfVigenciaEntity
import com.julen.socios.data.local.SocioDao
import com.julen.socios.data.local.SocioEntity
import com.julen.socios.data.local.TransactionRunner
import com.julen.socios.export.AlcanceExport
import com.julen.socios.model.DatosBackup
import com.julen.socios.model.ImportPreview
import com.julen.socios.model.ResultadoLectura

/** Acceso a datos para exportar e importar copias de seguridad. */
class BackupRepository(
    private val socioDao: SocioDao,
    private val bonoDao: BonoRegaloDao,
    private val irpfDao: IrpfVigenciaDao,
    private val devolucionDao: DevolucionDao,
    private val transactionRunner: TransactionRunner
) {

    suspend fun cargarDatos(alcance: AlcanceExport): DatosBackup {
        val vigencias = irpfDao.getAll().map { it.toIrpfVigencia() }
        val bonos = bonoDao.getAllBonosRegalo().map { it.toBonoRegaloInfo() }
        val devoluciones = devolucionDao.getAll().map { it.toDevolucionInfo() }
        return when (alcance) {
            is AlcanceExport.Semanas -> {
                val keys = alcance.semanaKeys
                // Por tandas para no superar el límite de parámetros de SQLite en versiones antiguas.
                val socios = keys.toList().chunked(500).flatMap { socioDao.getSociosPorSemanas(it) }
                    .sortedByDescending { it.timestamp }
                    .map { it.toSocio() }.toList()
                DatosBackup(
                    socios,
                    bonos.filter { it.semanaKey in keys },
                    vigencias,
                    devoluciones.filter { it.semanaKey in keys }
                )
            }

            AlcanceExport.Historico ->
                DatosBackup(socioDao.getAllSocios().map { it.toSocio() }, bonos, vigencias, devoluciones)
        }
    }

    suspend fun previsualizar(lectura: ResultadoLectura): ImportPreview {
        val existentes = socioDao.getAllIds().toSet()
        val sobrescritos = lectura.datos.socios.count { it.id in existentes }
        return ImportPreview(
            sociosNuevos = lectura.datos.socios.size - sobrescritos,
            sociosSobrescritos = sobrescritos,
            bonos = lectura.datos.bonosRegalo.size,
            irpfVigencias = lectura.datos.irpfVigencias.size,
            devoluciones = lectura.datos.devoluciones.size,
            descartados = lectura.descartados
        )
    }

    /** Guarda todo en una única transacción: o se importa el archivo completo o no se importa nada. */
    suspend fun importar(datos: DatosBackup) = transactionRunner.run {
        if (datos.socios.isNotEmpty()) socioDao.insertSocios(datos.socios.map(SocioEntity::fromSocio))
        if (datos.bonosRegalo.isNotEmpty()) bonoDao.saveBonosRegalo(datos.bonosRegalo.map(BonoRegaloEntity::fromBonoRegaloInfo))
        if (datos.irpfVigencias.isNotEmpty()) irpfDao.upsertAll(datos.irpfVigencias.map(IrpfVigenciaEntity::fromIrpfVigencia))
        if (datos.devoluciones.isNotEmpty()) devolucionDao.upsertAll(datos.devoluciones.map(DevolucionEntity::fromDevolucionInfo))
    }
}
