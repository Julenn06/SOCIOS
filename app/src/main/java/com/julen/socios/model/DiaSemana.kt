package com.julen.socios.model

enum class DiaSemana(val id: Int, val nombreCorto: String, val nombreCompleto: String) {
    LUNES(1, "Lun", "Lunes"), MARTES(2, "Mar", "Martes"), MIERCOLES(
        3, "Mié", "Miércoles"
    ),
    JUEVES(4, "Jue", "Jueves"), VIERNES(5, "Vie", "Viernes");

    companion object {
        fun fromId(id: Int): DiaSemana {
            return entries.find { it.id == id } ?: LUNES
        }
    }
}
