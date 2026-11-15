package com.julen.socios.export

enum class ExportFormat(val extension: String, val mimeType: String) {
    PDF("pdf", "application/pdf"),
    CSV("csv", "text/csv"),
    JSON("json", "application/json")
}

/** Formatos que se pueden volver a importar (un PDF es solo un informe). */
enum class ImportFormat { JSON, CSV }

sealed interface AlcanceExport {
    data class Semanas(val semanaKeys: Set<String>) : AlcanceExport
    data object Historico : AlcanceExport

    /**
     * Las semanas de un año: las que tienen clave "[anio]-Wnn". El año de una semana ISO es el de su jueves, el mismo
     * criterio con el que el histórico agrupa por meses y años, así una semana que cruza de año no cuenta en los dos.
     */
    data class Anio(val anio: Int) : AlcanceExport {
        fun incluye(semanaKey: String): Boolean = semanaKey.startsWith("$anio-W")
    }
}

data class ExportRequest(
    val formato: ExportFormat,
    val alcance: AlcanceExport
)
