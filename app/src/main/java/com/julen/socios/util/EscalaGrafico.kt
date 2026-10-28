package com.julen.socios.util

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/**
 * Escala de un gráfico de importes con líneas de guía de números redondos (100 €, 250 €, 500 €…), de [paso] en
 * [paso] desde [suelo] (0, o menos si hay negativos) hasta [techo]. La usan el gráfico del histórico y el PDF.
 */
class EscalaGrafico private constructor(val suelo: Double, val techo: Double, val paso: Double) {

    val rango: Double get() = techo - suelo

    /** Valores de las líneas de guía, de abajo arriba. */
    val guias: List<Double>
        get() = generateSequence(suelo) { it + paso }.takeWhile { it <= techo + paso / 1000 }.toList()

    /** Si [valor] es la línea del cero (la base de las barras), con margen para los errores de redondeo. */
    fun esCero(valor: Double): Boolean = abs(valor) < paso / 1000

    companion object {
        /** La escala que abarca todos los [valores], con unas tres líneas de guía por encima del cero. */
        fun para(valores: Iterable<Double>): EscalaGrafico {
            val maximo = valores.maxOrNull()?.coerceAtLeast(0.0) ?: 0.0
            val minimo = valores.minOrNull()?.coerceAtMost(0.0) ?: 0.0
            val paso = pasoRedondo(max(maximo, -minimo) / 3)
            return EscalaGrafico(floor(minimo / paso) * paso, (ceil(maximo / paso) * paso).coerceAtLeast(paso), paso)
        }

        /** Un paso de 1, 2, 2,5 o 5 por una potencia de 10 (5 €, 25 €, 100 €, 250 €…) que no baje de [aprox]. */
        internal fun pasoRedondo(aprox: Double): Double {
            if (aprox <= 0.0) return 100.0
            val potencia = 10.0.pow(floor(log10(aprox)))
            val factor = aprox / potencia
            return potencia * when {
                factor <= 1.0 -> 1.0
                factor <= 2.0 -> 2.0
                factor <= 2.5 -> 2.5
                factor <= 5.0 -> 5.0
                else -> 10.0
            }
        }
    }
}
