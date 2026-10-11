package com.julen.socios.data.backup

/*
 * Formato de las copias de seguridad, separado del modelo de dominio para que renombrar un campo del
 * modelo no rompa los archivos ya exportados. Todos los campos son anulables: Gson no respeta la
 * nulabilidad ni los valores por defecto de Kotlin, así que un campo ausente llega como null y se
 * valida en [ConversionBackup] en lugar de provocar un NullPointerException al guardar.
 */

data class BackupDto(
    val version: Int? = null,
    val timestamp: Long? = null,
    val socios: List<SocioDto?>? = null,
    val bonosRegalo: List<BonoRegaloDto?>? = null,
    val irpfVigencias: List<IrpfVigenciaDto?>? = null
)

data class SocioDto(
    val id: String? = null,
    val colaboracion: Double? = null,
    val hecho: Boolean? = null,
    val diaSemanaId: Int? = null,
    val semanaKey: String? = null,
    val nombreSocio: String? = null,
    val notas: String? = null,
    val timestamp: Long? = null
)

data class BonoRegaloDto(
    val semanaKey: String? = null,
    val activo: Boolean? = null,
    val esMontoFijo: Boolean? = null,
    val monto: Double? = null,
    val nota: String? = null
)

data class IrpfVigenciaDto(
    val desdeSemanaKey: String? = null,
    val porcentaje: Double? = null
)
