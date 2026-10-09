package com.julen.socios.util

import java.util.Locale

/**
 * Formats a Double as a currency string (e.g., "12.50 €", "+12.50 €", "-12.50 €").
 */
fun Double.toCurrencyString(decimals: Int = 2, prefix: String = "", suffix: String = " €"): String {
    val formatStr = "%.${decimals}f"
    val formatted = String.format(Locale.getDefault(), formatStr, this)
    return "$prefix$formatted$suffix"
}

/**
 * Formats a Double as euros (e.g., "12.50 €").
 */
fun Double.toFormattedEuros(decimals: Int = 2): String {
    return this.toCurrencyString(decimals = decimals, prefix = "", suffix = " €")
}
