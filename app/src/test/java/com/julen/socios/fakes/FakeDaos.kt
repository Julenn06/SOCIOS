package com.julen.socios.fakes

import android.net.Uri
import com.julen.socios.data.local.BonoRegaloDao
import com.julen.socios.data.local.BonoRegaloEntity
import com.julen.socios.data.local.IrpfVigenciaDao
import com.julen.socios.data.local.IrpfVigenciaEntity
import com.julen.socios.data.local.SocioDao
import com.julen.socios.data.local.SocioEntity
import com.julen.socios.data.local.TransactionRunner
import com.julen.socios.export.ImportFormat
import com.julen.socios.export.ExportRequest
import com.julen.socios.export.ExportService
import com.julen.socios.model.DatosBackup
import com.julen.socios.model.ResultadoLectura
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Implementaciones en memoria de los DAO, con Flows reactivos como los de Room. */
class FakeSocioDao : SocioDao {
    val socios = MutableStateFlow<List<SocioEntity>>(emptyList())

    override suspend fun getAllSocios() = socios.value.sortedByDescending { it.timestamp }
    override fun getAllSociosFlow(): Flow<List<SocioEntity>> = socios.map { l -> l.sortedByDescending { it.timestamp } }
    override suspend fun getSociosPorSemanas(semanaKeys: List<String>) =
        socios.value.filter { it.semanaKey in semanaKeys }.sortedByDescending { it.timestamp }

    override fun getSociosPorSemanaFlow(semanaKey: String): Flow<List<SocioEntity>> =
        socios.map { l -> l.filter { it.semanaKey == semanaKey }.sortedByDescending { it.timestamp } }

    override fun getSemanaKeysFlow(): Flow<List<String>> = socios.map { l -> l.map { it.semanaKey }.distinct() }
    override suspend fun getAllIds() = socios.value.map { it.id }

    override suspend fun insertSocio(socio: SocioEntity) {
        socios.value = socios.value.filterNot { it.id == socio.id } + socio
    }

    override suspend fun insertSocios(socios: List<SocioEntity>) = socios.forEach { insertSocio(it) }

    override suspend fun updateSocio(socio: SocioEntity) {
        socios.value = socios.value.map { if (it.id == socio.id) socio else it }
    }

    override suspend fun setHecho(socioId: String, hecho: Boolean): Int {
        if (socios.value.none { it.id == socioId }) return 0
        socios.value = socios.value.map { if (it.id == socioId) it.copy(hecho = hecho) else it }
        return 1
    }

    override suspend fun deleteSocio(socioId: String) {
        socios.value = socios.value.filterNot { it.id == socioId }
    }
}

class FakeBonoRegaloDao : BonoRegaloDao {
    val bonos = MutableStateFlow<Map<String, BonoRegaloEntity>>(emptyMap())

    override fun getBonoRegaloFlow(semanaKey: String): Flow<BonoRegaloEntity?> = bonos.map { it[semanaKey] }
    override suspend fun getAllBonosRegalo() = bonos.value.values.filter { it.activo }
    override fun getAllBonosRegaloFlow(): Flow<List<BonoRegaloEntity>> = bonos.map { m -> m.values.filter { it.activo } }
    override fun getSemanaKeysFlow(): Flow<List<String>> = bonos.map { m -> m.values.filter { it.activo }.map { it.semanaKey } }

    override suspend fun saveBonoRegalo(bono: BonoRegaloEntity) {
        bonos.value = bonos.value + (bono.semanaKey to bono)
    }

    override suspend fun saveBonosRegalo(bonos: List<BonoRegaloEntity>) = bonos.forEach { saveBonoRegalo(it) }

    override suspend fun deleteBonoRegalo(semanaKey: String) {
        bonos.value = bonos.value - semanaKey
    }
}

class FakeIrpfVigenciaDao : IrpfVigenciaDao {
    val vigencias = MutableStateFlow<Map<String, IrpfVigenciaEntity>>(emptyMap())

    override suspend fun getAll() = vigencias.value.values.sortedBy { it.desdeSemanaKey }
    override fun getAllFlow(): Flow<List<IrpfVigenciaEntity>> = vigencias.map { m -> m.values.sortedBy { it.desdeSemanaKey } }

    override suspend fun upsert(vigencia: IrpfVigenciaEntity) {
        vigencias.value = vigencias.value + (vigencia.desdeSemanaKey to vigencia)
    }

    override suspend fun upsertAll(vigencias: List<IrpfVigenciaEntity>) = vigencias.forEach { upsert(it) }

    override suspend fun delete(desdeSemanaKey: String) {
        vigencias.value = vigencias.value - desdeSemanaKey
    }
}

class FakeTransactionRunner : TransactionRunner {
    override suspend fun <R> run(block: suspend () -> R): R = block()
}

class FakeExportService : ExportService {
    val escritos = mutableListOf<Pair<ExportRequest, DatosBackup>>()

    override fun nombreArchivo(request: ExportRequest) = "socios_test.${request.formato.extension}"

    override suspend fun escribirEnUri(request: ExportRequest, datos: DatosBackup, uri: Uri) {
        escritos += request to datos
    }

    override suspend fun crearArchivoCompartible(request: ExportRequest, datos: DatosBackup): Uri =
        throw UnsupportedOperationException("No se puede crear una Uri en tests JVM")

    override suspend fun leerImportacion(uri: Uri, formato: ImportFormat): ResultadoLectura =
        throw UnsupportedOperationException("No se puede crear una Uri en tests JVM")
}
