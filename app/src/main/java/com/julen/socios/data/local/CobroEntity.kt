package com.julen.socios.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.julen.socios.model.CobroInfo
import com.julen.socios.util.Centimos

/** Una fila por semana: el neto cobrado de verdad esa semana, en céntimos (ver [Centimos]). */
@Entity(tableName = "cobros")
data class CobroEntity(
    @PrimaryKey val semanaKey: String,
    val netoCobradoCentimos: Long,
    val nota: String
) {
    fun toCobroInfo() = CobroInfo(semanaKey = semanaKey, netoCobrado = Centimos.aEuros(netoCobradoCentimos), nota = nota)

    companion object {
        fun fromCobroInfo(info: CobroInfo) =
            CobroEntity(semanaKey = info.semanaKey, netoCobradoCentimos = Centimos.de(info.netoCobrado), nota = info.nota)
    }
}
