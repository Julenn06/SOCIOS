package com.julen.socios.model

data class BackupData(
    val version: Int = 1,
    val timestamp: Long = System.currentTimeMillis(),
    val socios: List<Socio> = emptyList(),
    val bonosRegalo: List<BonoRegaloInfo> = emptyList()
)

data class BackupImportResult(
    val socios: List<Socio> = emptyList(),
    val bonosRegalo: List<BonoRegaloInfo> = emptyList()
)
