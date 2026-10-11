package com.julen.socios.model

data class BonoRegaloInfo(
    val semanaKey: String,
    val activo: Boolean = false,
    val esMontoFijo: Boolean = true, // true = sustituye al bonus automático, false = se suma al bonus automático
    val monto: Double = 0.0,
    val nota: String = ""
)
