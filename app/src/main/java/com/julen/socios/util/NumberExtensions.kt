package com.julen.socios.util

import java.math.BigDecimal
import java.util.Locale

private val spanishLocale = Locale.forLanguageTag("es-ES")

/**
 * Formats a Double as a currency string (e.g., "12,50 €", "+12,50 €", "-12,50 €").
 */
fun Double.toCurrencyString(decimals: Int = 2, prefix: String = ""): String {
    val formatStr = "%.${decimals}f"
    val formatted = String.format(spanishLocale, formatStr, this)
    return "$prefix$formatted €"
}

/**
 * Formats a Double as euros (e.g., "12,50 €").
 */
fun Double.toFormattedEuros(decimals: Int = 2): String {
    return this.toCurrencyString(decimals = decimals)
}

/**
 * Formats euros without decimals when the amount is whole ("20 €") and with cents otherwise ("10,50 €").
 */
fun Double.toEurosCompacto(prefix: String = ""): String =
    toCurrencyString(decimals = if (this % 1.0 == 0.0) 0 else 2, prefix = prefix)

/**
 * Formats a percentage with Spanish decimals, without trailing zeros (e.g., "21,48 %", "15 %").
 */
fun Double.toPorcentajeString(): String =
    BigDecimal.valueOf(this).stripTrailingZeros().toPlainString().replace('.', ',') + " %"

/**
 * Text to pre-fill an amount input: "10" or "10.5". The dot is always accepted by numeric keyboards.
 */
fun Double.toImporteInput(): String = BigDecimal.valueOf(this).stripTrailingZeros().toPlainString()

/**
 * Parses an amount typed by the user, accepting both "10,5" and "10.5". Returns null if it is not a number.
 */
fun parseImporte(texto: CharSequence?): Double? =
    texto?.toString()
        ?.trim()
        ?.replace(',', '.')
        ?.toDoubleOrNull()
        ?.takeIf { it.isFinite() }
