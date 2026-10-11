package com.julen.socios.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NumberExtensionsTest {

    @Test
    fun formatoDeMonedaEspanol() {
        assertEquals("123,46 €", 123.456.toFormattedEuros())
        assertEquals("+123,46 €", 123.456.toCurrencyString(prefix = "+"))
    }

    @Test
    fun eurosCompactosSinDecimalesSiElImporteEsEntero() {
        assertEquals("20 €", 20.0.toEurosCompacto())
        assertEquals("10,50 €", 10.5.toEurosCompacto())
        assertEquals("+140 €", 140.0.toEurosCompacto(prefix = "+"))
    }

    @Test
    fun porcentajes() {
        assertEquals("21,48 %", 21.48.toPorcentajeString())
        assertEquals("15 %", 15.0.toPorcentajeString())
    }

    @Test
    fun importeParaRellenarUnCampo() {
        assertEquals("10", 10.0.toImporteInput())
        assertEquals("10.5", 10.5.toImporteInput())
    }

    @Test
    fun parseaImportesConComaOPunto() {
        assertEquals(10.5, parseImporte("10,5")!!, 0.0)
        assertEquals(10.5, parseImporte("10.5")!!, 0.0)
        assertEquals(7.0, parseImporte(" 7 ")!!, 0.0)
        assertNull(parseImporte(""))
        assertNull(parseImporte("abc"))
        assertNull(parseImporte("NaN"))
        assertNull(parseImporte(null))
    }
}
