package com.julen.socios.data.backup

import kotlinx.serialization.Serializable

/*
 * Formato de las copias de seguridad, separado del modelo de dominio para que renombrar un campo del
 * modelo no rompa los archivos ya exportados. Todos los campos son anulables: un campo ausente o nulo
 * llega como null y se valida en [ConversionBackup], que descarta el registro o pone un valor por
 * defecto, en lugar de rechazar el archivo entero.
 */

@Serializable
data class BackupDto(
    val version: Int? = null,
    val timestamp: Long? = null,
    val socios: List<SocioDto?>? = null,
    val bonosRegalo: List<BonoRegaloDto?>? = null,
    val irpfVigencias: List<IrpfVigenciaDto?>? = null
)

@Serializable
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

@Serializable
data class BonoRegaloDto(
    val semanaKey: String? = null,
    val activo: Boolean? = null,
    val esMontoFijo: Boolean? = null,
    val monto: Double? = null,
    val nota: String? = null
)

@Serializable
data class IrpfVigenciaDto(
    val desdeSemanaKey: String? = null,
    val porcentaje: Double? = null
)
