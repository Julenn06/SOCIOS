package com.julen.socios.model

import java.util.UUID

data class Socio(
    val id: String = UUID.randomUUID().toString(),
    val colaboracion: Double, // Importe de la colaboración en euros (10, 12, 15, 18, 20, 25, 30, etc.)
    val hecho: Boolean = true, // true = hecho (socio conseguido), false = no hecho (pendiente)
    val dia: DiaSemana,
    val semanaKey: String, // Clave de la semana ISO (ej: "2025-W09")
    val nombreSocio: String = "",
    val notas: String = "",
    val timestamp: Long = System.currentTimeMillis()
)
