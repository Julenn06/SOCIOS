package com.julen.socios.model

import java.util.UUID

data class Socio(
    val id: String = UUID.randomUUID().toString(),
    val colaboracion: Double, // Importe de la colaboración en euros (10, 12, 15, 18, 20, 25, 30, etc.)
    var hecho: Boolean = true, // true = hecho (socio conseguido), false = no hecho (pendiente)
    val diaSemanaId: Int, // 1=Lunes, 2=Martes, 3=Miércoles, 4=Jueves, 5=Viernes
    val semanaKey: String, // Clave de la semana (ej: "2025-W09")
    val nombreSocio: String = "",
    val notas: String = "",
    val timestamp: Long = System.currentTimeMillis()
)
