package com.julen.socios.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.julen.socios.model.BonoRegaloInfo

@Entity(tableName = "bonos_regalo")
data class BonoRegaloEntity(
    @PrimaryKey
    val semanaKey: String,
    val activo: Boolean,
    val esMontoFijo: Boolean,
    val monto: Double,
    val nota: String
) {
    fun toBonoRegaloInfo(): BonoRegaloInfo {
        return BonoRegaloInfo(
            semanaKey = semanaKey,
            activo = activo,
            esMontoFijo = esMontoFijo,
            monto = monto,
            nota = nota
        )
    }

    companion object {
        fun fromBonoRegaloInfo(info: BonoRegaloInfo): BonoRegaloEntity {
            return BonoRegaloEntity(
                semanaKey = info.semanaKey,
                activo = info.activo,
                esMontoFijo = info.esMontoFijo,
                monto = info.monto,
                nota = info.nota
            )
        }
    }
}
