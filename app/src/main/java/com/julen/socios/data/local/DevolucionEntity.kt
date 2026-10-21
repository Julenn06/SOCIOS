package com.julen.socios.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.julen.socios.model.DevolucionInfo

/** Una fila por semana: el total descontado esa semana por devoluciones. */
@Entity(tableName = "devoluciones")
data class DevolucionEntity(
    @PrimaryKey val semanaKey: String,
    val monto: Double,
    val nota: String
) {
    fun toDevolucionInfo() = DevolucionInfo(semanaKey = semanaKey, monto = monto, nota = nota)

    companion object {
        fun fromDevolucionInfo(info: DevolucionInfo) =
            DevolucionEntity(semanaKey = info.semanaKey, monto = info.monto, nota = info.nota)
    }
}
