package com.julen.socios.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.julen.socios.model.DiaSemana
import com.julen.socios.model.Socio

@Entity(tableName = "socios", indices = [Index("semanaKey")])
data class SocioEntity(
    @PrimaryKey val id: String,
    val colaboracion: Double,
    val hecho: Boolean,
    val diaSemanaId: Int,
    val semanaKey: String,
    val nombreSocio: String,
    val notas: String,
    val timestamp: Long
) {
    fun toSocio(): Socio {
        return Socio(
            id = id,
            colaboracion = colaboracion,
            hecho = hecho,
            dia = DiaSemana.fromId(diaSemanaId),
            semanaKey = semanaKey,
            nombreSocio = nombreSocio,
            notas = notas,
            timestamp = timestamp
        )
    }

    companion object {
        fun fromSocio(socio: Socio): SocioEntity {
            return SocioEntity(
                id = socio.id,
                colaboracion = socio.colaboracion,
                hecho = socio.hecho,
                diaSemanaId = socio.dia.id,
                semanaKey = socio.semanaKey,
                nombreSocio = socio.nombreSocio,
                notas = socio.notas,
                timestamp = socio.timestamp
            )
        }
    }
}
