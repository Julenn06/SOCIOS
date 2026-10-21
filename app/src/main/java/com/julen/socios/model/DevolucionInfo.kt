package com.julen.socios.model

/**
 * Lo que la empresa descuenta en el pago de una semana por cuotas devueltas (primera o segunda cuota de
 * socios de semanas anteriores). Se apunta en la semana en la que se descuenta, no en la del socio.
 */
data class DevolucionInfo(
    val semanaKey: String,
    val monto: Double,
    val nota: String = "" // Qué se ha devuelto, escrito por el usuario
) {
    /** Motivo a mostrar, o null si no hay. */
    val motivo: String?
        get() = nota.trim().takeIf { it.isNotEmpty() }
}
