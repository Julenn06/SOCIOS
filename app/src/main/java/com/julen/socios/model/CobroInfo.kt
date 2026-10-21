package com.julen.socios.model

/**
 * Lo que se ha cobrado de verdad en una semana (el neto que llega a la cuenta). Si es menos de lo calculado,
 * la diferencia son devoluciones u otros descuentos de la empresa.
 */
data class CobroInfo(
    val semanaKey: String,
    val netoCobrado: Double,
    val nota: String = "" // Por qué no coincide con lo calculado, escrito por el usuario
) {
    /** Motivo a mostrar, o null si no hay. */
    val motivo: String?
        get() = nota.trim().takeIf { it.isNotEmpty() }
}
