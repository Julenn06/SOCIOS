package com.julen.socios.model

import java.time.DayOfWeek

enum class DiaSemana(val id: Int, val nombreCorto: String, val nombreCompleto: String) {
    LUNES(1, "Lun", "Lunes"),
    MARTES(2, "Mar", "Martes"),
    MIERCOLES(3, "Mié", "Miércoles"),
    JUEVES(4, "Jue", "Jueves"),
    VIERNES(5, "Vie", "Viernes");

    companion object {
        fun fromIdOrNull(id: Int): DiaSemana? = entries.find { it.id == id }

        fun fromId(id: Int): DiaSemana = fromIdOrNull(id) ?: LUNES

        /** Sábado se asigna al viernes y domingo al lunes, como días laborables más cercanos. */
        fun desde(dayOfWeek: DayOfWeek): DiaSemana = when (dayOfWeek) {
            DayOfWeek.MONDAY -> LUNES
            DayOfWeek.TUESDAY -> MARTES
            DayOfWeek.WEDNESDAY -> MIERCOLES
            DayOfWeek.THURSDAY -> JUEVES
            DayOfWeek.FRIDAY, DayOfWeek.SATURDAY -> VIERNES
            DayOfWeek.SUNDAY -> LUNES
        }
    }
}
