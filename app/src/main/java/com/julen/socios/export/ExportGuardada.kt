package com.julen.socios.export

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.StateFlow

/**
 * Una exportación a medias guardada en el [SavedStateHandle] bajo [clave]: así sobrevive a que la Activity se
 * recree o el proceso muera mientras el usuario elige destino o tiene abierto el selector de archivos.
 */
class ExportGuardada(private val handle: SavedStateHandle, private val clave: String) {

    /** Cambia con cada petición que se guarda, aunque repita el formato de la anterior. */
    val cambios: StateFlow<String?> = handle.getStateFlow(clave + SUFIJO_FORMATO, null)

    fun guardar(request: ExportRequest?) {
        // El formato es la clave que observa [cambios]: se vacía primero y se escribe al final para que
        // cualquier cambio de la petición se emita aunque el formato se repita.
        handle[clave + SUFIJO_FORMATO] = null
        handle[clave + SUFIJO_SEMANAS] = (request?.alcance as? AlcanceExport.Semanas)?.semanaKeys?.let { ArrayList(it) }
        handle[clave + SUFIJO_FORMATO] = request?.formato?.name
    }

    fun leer(): ExportRequest? {
        val formato = handle.get<String>(clave + SUFIJO_FORMATO)
            ?.let { nombre -> ExportFormat.entries.find { it.name == nombre } }
            ?: return null
        val semanas = handle.get<ArrayList<String>>(clave + SUFIJO_SEMANAS)
        val alcance = if (semanas != null) AlcanceExport.Semanas(semanas.toSet()) else AlcanceExport.Historico
        return ExportRequest(formato, alcance)
    }

    /** La lee y la borra: cada petición se atiende una sola vez. */
    fun tomar(): ExportRequest? = leer().also { guardar(null) }

    private companion object {
        const val SUFIJO_FORMATO = ".formato"
        const val SUFIJO_SEMANAS = ".semanas"
    }
}
