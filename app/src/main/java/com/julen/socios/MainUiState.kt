package com.julen.socios

import android.net.Uri
import com.julen.socios.export.ExportFormat
import com.julen.socios.export.ImportFormat
import com.julen.socios.model.BonoRegaloInfo
import com.julen.socios.model.CobroInfo
import com.julen.socios.model.DiaSemana
import com.julen.socios.model.Socio
import com.julen.socios.util.CalculoComisiones
import com.julen.socios.util.UiText

data class MainUiState(
    val weekOffset: Int,
    val semanaKey: String,
    val diaFiltro: DiaSemana? = null,
    val todosSociosSemana: List<Socio> = emptyList(),
    val sociosFiltrados: List<Socio> = emptyList(),
    val conteoPorDia: Map<DiaSemana, Int> = emptyMap(),
    val bonoInfoSemana: BonoRegaloInfo? = null,
    val cobroSemana: CobroInfo? = null,
    val resumenSemana: CalculoComisiones.ResumenCalculo? = null
)

/** Acciones puntuales que la Activity debe ejecutar una sola vez. */
sealed interface MainEvent {
    /** Un aviso: [titulo] dice qué ha pasado y [detalle], si hay, a qué o por qué. */
    data class Mensaje(val titulo: UiText, val tipo: TipoAviso, val detalle: UiText? = null) : MainEvent
    data class CrearDocumento(val formato: ExportFormat, val nombreSugerido: String) : MainEvent
    data class Compartir(val uri: Uri, val formato: ExportFormat) : MainEvent {
        val mimeType: String get() = formato.mimeType
    }
    data class SeleccionarArchivoImport(val formato: ImportFormat) : MainEvent

    /** Se ha borrado un socio: la vista ofrece deshacerlo con [MainViewModel.restaurarSocio]. */
    data class SocioEliminado(val socio: Socio) : MainEvent
}
