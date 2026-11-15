package com.julen.socios.util

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Importes en céntimos enteros. Así se guardan en la base de datos y así se suman las cuentas: un `Double`
 * no representa exactamente la mayoría de los céntimos (0,10 € es 0,1000000000000000055…) y, al sumar muchas
 * semanas, esos restos podían mover el último céntimo. Fuera de la base de datos y de las cuentas, la app sigue
 * trabajando en euros.
 */
object Centimos {

    /** Los céntimos de [euros], redondeando el medio céntimo hacia arriba como en una nómina (12,345 € → 1235). */
    fun de(euros: Double): Long =
        BigDecimal.valueOf(euros).movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong()

    /** [centimos] en euros: 1235 → 12,35. */
    fun aEuros(centimos: Long): Double = BigDecimal.valueOf(centimos, 2).toDouble()

    /** El [porcentaje] de [centimos], redondeado a céntimos: la retención de IRPF de un bruto. */
    fun porcentaje(centimos: Long, porcentaje: Double): Long =
        BigDecimal.valueOf(centimos)
            .multiply(BigDecimal.valueOf(porcentaje))
            .movePointLeft(2)
            .setScale(0, RoundingMode.HALF_UP)
            .toLong()

    /** La suma exacta, en euros, de los importes que da [importe] para cada elemento. */
    inline fun <T> suma(elementos: Iterable<T>, importe: (T) -> Double): Double =
        aEuros(elementos.sumOf { de(importe(it)) })
}
