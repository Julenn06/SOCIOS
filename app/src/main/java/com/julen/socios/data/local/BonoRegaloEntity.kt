package com.julen.socios.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.julen.socios.model.BonoRegaloInfo
import com.julen.socios.util.Centimos

@Entity(tableName = "bonos_regalo")
data class BonoRegaloEntity(
    @PrimaryKey
    val semanaKey: String,
    val activo: Boolean,
    val esMontoFijo: Boolean,
    /** El importe en céntimos (ver [Centimos]). */
    val montoCentimos: Long,
    val nota: String
) {
    fun toBonoRegaloInfo(): BonoRegaloInfo {
        return BonoRegaloInfo(
            semanaKey = semanaKey,
            activo = activo,
            esMontoFijo = esMontoFijo,
            monto = Centimos.aEuros(montoCentimos),
            nota = nota
        )
    }

    companion object {
        fun fromBonoRegaloInfo(info: BonoRegaloInfo): BonoRegaloEntity {
            return BonoRegaloEntity(
                semanaKey = info.semanaKey,
                activo = info.activo,
                esMontoFijo = info.esMontoFijo,
                montoCentimos = Centimos.de(info.monto),
                nota = info.nota
            )
        }
    }
}
