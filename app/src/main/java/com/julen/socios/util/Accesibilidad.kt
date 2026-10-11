package com.julen.socios.util

import android.widget.TextView

// Emojis y símbolos gráficos (✓ ⏳ 🏆 ✏️ 🎁 📅…), más el selector de variación y el unión de ancho cero que
// forman parte de algunos emojis. No incluye €, « » ni signos de puntuación.
private val SIMBOLOS_DECORATIVOS = Regex("[\\p{So}\\uFE0F\\u200D]")
private val ESPACIOS = Regex("\\s{2,}")
private val ESPACIO_ANTES_DE_CIERRE = Regex("\\s+([)\\]])")

/** El texto sin emojis ni símbolos decorativos, tal como debería leerlo un lector de pantalla. */
fun CharSequence.sinSimbolosDecorativos(): String =
    toString().replace(SIMBOLOS_DECORATIVOS, "").replace(ESPACIOS, " ").replace(ESPACIO_ANTES_DE_CIERRE, "$1").trim()

/**
 * Muestra [texto] tal cual, pero hace que TalkBack lo lea sin los emojis decorativos
 * (de lo contrario lee "marca de verificación", "trofeo", "lápiz"…).
 */
fun TextView.setTextoAccesible(texto: CharSequence) {
    text = texto
    val leido = texto.sinSimbolosDecorativos()
    contentDescription = leido.takeIf { it != texto.toString() }
}
