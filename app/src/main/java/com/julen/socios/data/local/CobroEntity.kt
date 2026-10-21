package com.julen.socios.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.julen.socios.model.CobroInfo

/** Una fila por semana: el neto cobrado de verdad esa semana. */
@Entity(tableName = "cobros")
data class CobroEntity(
    @PrimaryKey val semanaKey: String,
    val netoCobrado: Double,
    val nota: String
) {
    fun toCobroInfo() = CobroInfo(semanaKey = semanaKey, netoCobrado = netoCobrado, nota = nota)

    companion object {
        fun fromCobroInfo(info: CobroInfo) =
            CobroEntity(semanaKey = info.semanaKey, netoCobrado = info.netoCobrado, nota = info.nota)
    }
}
