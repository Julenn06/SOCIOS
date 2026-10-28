package com.julen.socios.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EscalaGraficoTest {

    @Test
    fun lasLineasDeGuiaSonImportesRedondos() {
        val escala = EscalaGrafico.para(listOf(59.94, 523.26, 280.5))

        // 523,26 / 3 ≈ 174 → paso de 200: guías en 0, 200, 400 y 600.
        assertEquals(listOf(0.0, 200.0, 400.0, 600.0), escala.guias)
        assertEquals(600.0, escala.rango, 0.0)
    }

    @Test
    fun conNegativosBajaDelCero() {
        val escala = EscalaGrafico.para(listOf(-30.0, 120.0))

        assertEquals(-50.0, escala.suelo, 0.0)
        assertTrue(escala.esCero(escala.guias.first { it > -1 && it < 1 }))
    }

    @Test
    fun sinImportesHayUnaEscalaMinima() {
        assertEquals(listOf(0.0, 100.0), EscalaGrafico.para(emptyList()).guias)
    }

    @Test
    fun pasosDe1225Y5PorPotenciasDe10() {
        assertEquals(1.0, EscalaGrafico.pasoRedondo(0.8), 0.0)
        assertEquals(250.0, EscalaGrafico.pasoRedondo(240.0), 0.0)
        assertEquals(5000.0, EscalaGrafico.pasoRedondo(3100.0), 0.0)
        assertEquals(10.0, EscalaGrafico.pasoRedondo(6.0), 0.0)
    }
}
