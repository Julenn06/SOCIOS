package com.julen.socios.model

data class BonoRegaloInfo(
    val semanaKey: String,
    val activo: Boolean = false,
    val esMontoFijo: Boolean = true, // true = sustituye al bonus automático, false = se suma al bonus automático
    val monto: Double = 0.0,
    val nota: String = "" // Motivo del ajuste, escrito por el usuario
) {
    /** Motivo a mostrar, o null si no hay. Ignora el texto que versiones anteriores ponían automáticamente. */
    val motivo: String?
        get() = nota.trim().takeIf { it.isNotEmpty() && it != NOTA_AUTOMATICA_ANTIGUA }

    private companion object {
        const val NOTA_AUTOMATICA_ANTIGUA = "Ajustado a mano"
    }
}
