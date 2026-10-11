package com.julen.socios.export

enum class ExportFormat(val extension: String, val mimeType: String) {
    PDF("pdf", "application/pdf"),
    CSV("csv", "text/csv"),
    JSON("json", "application/json")
}

sealed interface AlcanceExport {
    data class Semanas(val semanaKeys: Set<String>) : AlcanceExport
    data object Historico : AlcanceExport
}

data class ExportRequest(
    val formato: ExportFormat,
    val alcance: AlcanceExport
)
