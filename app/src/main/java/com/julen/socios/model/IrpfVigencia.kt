package com.julen.socios.model

/**
 * Porcentaje de IRPF aplicable desde la semana [desdeSemanaKey] (incluida) en adelante,
 * hasta la siguiente vigencia. Así un cambio de retención no altera las semanas anteriores.
 */
data class IrpfVigencia(
    val desdeSemanaKey: String,
    val porcentaje: Double
)
