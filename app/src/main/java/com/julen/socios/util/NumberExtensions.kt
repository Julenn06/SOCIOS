package com.julen.socios.util

import java.math.BigDecimal
import java.util.Locale
import kotlin.math.abs

/** Español de España: importes, fechas y nombres de los meses de toda la app. */
val LOCALE_ES: Locale = Locale.forLanguageTag("es-ES")

/**
 * Espacio que no se parte entre una cifra y su símbolo: así "50 €" o "21 %" nunca dejan el símbolo solo en
 * la línea siguiente.
 */
const val ESPACIO_CIFRA = ' '

/**
 * Formats a Double as a currency string with thousands separators (e.g., "12,50 €", "+1.250,00 €", "-12,50 €").
 */
fun Double.toCurrencyString(decimals: Int = 2, prefix: String = ""): String {
    val formatted = String.format(LOCALE_ES, "%,.${decimals}f", this)
    return "$prefix$formatted$ESPACIO_CIFRA€"
}

/**
 * Decimals of a compact amount: none when it is whole ("20 €"), two otherwise ("10,50 €").
 */
fun Double.decimalesCompactos(): Int = if (this % 1.0 == 0.0) 0 else 2

/**
 * Formats euros without decimals when the amount is whole ("20 €") and with cents otherwise ("10,50 €").
 */
fun Double.toEurosCompacto(prefix: String = ""): String =
    toCurrencyString(decimals = decimalesCompactos(), prefix = prefix)

/**
 * What was deducted from the calculated net, with its sign: "-15,31 €" if less was paid, "+5,00 €" if more
 * and "0,00 €" if it matches. [compacto] drops the decimals of whole amounts.
 */
fun Double.descontadoConSigno(compacto: Boolean = false): String {
    val signo = if (this < 0.0) "+" else if (this > 0.0) "-" else ""
    return if (compacto) abs(this).toEurosCompacto(prefix = signo) else abs(this).toCurrencyString(prefix = signo)
}

/**
 * Formats a percentage with Spanish decimals, without trailing zeros (e.g., "21,48 %", "15 %").
 */
fun Double.toPorcentajeString(): String =
    BigDecimal.valueOf(this).stripTrailingZeros().toPlainString().replace('.', ',') + "$ESPACIO_CIFRA%"

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
