package com.julen.socios.model

data class BonoRegaloInfo(
    val semanaKey: String,
    val activo: Boolean = false,
    val esMontoFijo: Boolean = true, // true = Fijar nivel/monto mínimo (ej: 140€), false = Sumar extra al bonus automático (ej: +50€)
    val monto: Double = 0.0,
    val nota: String = "Regalo de la jefa por buen trabajo"
)
