package com.julen.socios.export

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.julen.socios.data.backup.BackupCsv
import com.julen.socios.data.backup.BackupJson
import com.julen.socios.model.DatosBackup
import com.julen.socios.model.ResultadoLectura
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.time.Clock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Lectura y escritura de archivos de exportación/importación. Interfaz para poder sustituirla en tests. */
interface ExportService {
    fun nombreArchivo(request: ExportRequest): String

    /** Escribe el archivo en un documento elegido por el usuario (Storage Access Framework). */
    suspend fun escribirEnUri(request: ExportRequest, datos: DatosBackup, uri: Uri)

    /** Genera el archivo en la caché privada y devuelve una URI de FileProvider para compartirlo. */
    suspend fun crearArchivoCompartible(request: ExportRequest, datos: DatosBackup): Uri

    suspend fun leerImportacion(uri: Uri, formato: ImportFormat): ResultadoLectura
}

class ExportadorArchivos(
    context: Context,
    private val clock: Clock = Clock.systemDefaultZone()
) : ExportService {

    private val appContext = context.applicationContext

    override fun nombreArchivo(request: ExportRequest): String {
        val sello = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT).format(LocalDateTime.now(clock))
        val alcance = when (val a = request.alcance) {
            is AlcanceExport.Semanas -> a.semanaKeys.singleOrNull() ?: "semanas"
            AlcanceExport.Historico -> "historico"
        }
        return "socios_${alcance}_$sello.${request.formato.extension}"
    }

    override suspend fun escribirEnUri(request: ExportRequest, datos: DatosBackup, uri: Uri) =
        withContext(Dispatchers.IO) {
            val out = appContext.contentResolver.openOutputStream(uri)
                ?: throw IOException("No se pudo abrir el archivo de destino")
            out.use { escribir(request, datos, it) }
        }

    override suspend fun crearArchivoCompartible(request: ExportRequest, datos: DatosBackup): Uri =
        withContext(Dispatchers.IO) {
            val directorio = File(appContext.cacheDir, DIRECTORIO_EXPORTS).apply { mkdirs() }
            borrarArchivosAntiguos(directorio)
            val archivo = File(directorio, nombreArchivo(request))
            archivo.outputStream().use { escribir(request, datos, it) }
            FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", archivo)
        }

    override suspend fun leerImportacion(uri: Uri, formato: ImportFormat): ResultadoLectura =
        withContext(Dispatchers.IO) {
            val input = appContext.contentResolver.openInputStream(uri)
                ?: throw IOException("No se pudo abrir el archivo seleccionado")
            input.use {
                when (formato) {
                    ImportFormat.JSON -> BackupJson.leer(it)
                    ImportFormat.CSV -> BackupCsv.leer(it)
                }
            }
        }

    private fun escribir(request: ExportRequest, datos: DatosBackup, outputStream: OutputStream) {
        when (request.formato) {
            ExportFormat.PDF -> PdfReportGenerator(appContext).generar(datos, request.alcance, outputStream, clock)
            ExportFormat.CSV -> BackupCsv.escribir(datos, outputStream)
            ExportFormat.JSON -> BackupJson.escribir(datos, outputStream, clock.millis())
        }
    }

    /** Los archivos compartidos solo hacen falta mientras la otra app los lee: no se acumulan en la caché. */
    private fun borrarArchivosAntiguos(directorio: File) {
        val limite = clock.millis() - TimeUnit.DAYS.toMillis(1)
        directorio.listFiles()?.filter { it.lastModified() < limite }?.forEach { it.delete() }
    }

    private companion object {
        const val DIRECTORIO_EXPORTS = "exports"
    }
}
