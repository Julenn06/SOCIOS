package com.julen.socios.util

import org.junit.Assert.assertEquals
import org.junit.Test

class CentimosTest {

    @Test
    fun pasaAEurosYVuelveSinPerderNada() {
        listOf(0.0, 0.1, 0.29, 10.5, 12.35, 99_999.99, -15.5).forEach { euros ->
            assertEquals(euros, Centimos.aEuros(Centimos.de(euros)), 0.0)
        }
    }

    @Test
    fun redondeaElMedioCentimoHaciaArribaComoUnaNomina() {
        assertEquals(1235L, Centimos.de(12.345))
        assertEquals(268L, Centimos.de(2.675))
        assertEquals(13L, Centimos.de(0.125))
        assertEquals(-13L, Centimos.de(-0.125))
    }

    @Test
    fun laRetencionSeRedondeaACentimos() {
        // 140 € al 21,48 % son 30,072 € → 30,07 €; 0,125 € de 50 céntimos al 25 % → 0,13 €.
        assertEquals(3007L, Centimos.porcentaje(14_000, 21.48))
        assertEquals(13L, Centimos.porcentaje(50, 25.0))
    }

    @Test
    fun sumaSinArrastrarRestos() {
        // Con Double, sumar diez veces 0,1 da 0,9999999999999999.
        assertEquals(1.0, Centimos.suma(List(10) { 0.1 }) { it }, 0.0)
        assertEquals(0.3, Centimos.suma(listOf(0.1, 0.2)) { it }, 0.0)
    }
}
