package com.julen.socios.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NumberExtensionsTest {

    @Test
    fun formatoDeMonedaEspanol() {
        assertEquals("123,46 €", 123.456.toCurrencyString())
        assertEquals("+123,46 €", 123.456.toCurrencyString(prefix = "+"))
    }

    @Test
    fun milesConPunto() {
        assertEquals("8.060,93 €", 8060.93.toCurrencyString())
        assertEquals("-1.234.567,00 €", (-1234567.0).toCurrencyString())
        assertEquals("+2.510 €", 2510.0.toEurosCompacto(prefix = "+"))
    }

    @Test
    fun elSimboloNoSeSeparaDeLaCifra() {
        // Un espacio normal dejaba el "€" solo en la línea siguiente cuando el texto no cabía.
        assertEquals(false, 50.0.toEurosCompacto().contains(' '))
        assertEquals(false, 21.48.toPorcentajeString().contains(' '))
    }

    @Test
    fun eurosCompactosSinDecimalesSiElImporteEsEntero() {
        assertEquals("20 €", 20.0.toEurosCompacto())
        assertEquals("10,50 €", 10.5.toEurosCompacto())
        assertEquals("+140 €", 140.0.toEurosCompacto(prefix = "+"))
    }

    @Test
    fun descontadoConSuSigno() {
        assertEquals("-15,31 €", 15.31.descontadoConSigno())
        assertEquals("+5,00 €", (-5.0).descontadoConSigno())
        assertEquals("0,00 €", 0.0.descontadoConSigno())
        assertEquals("+5 €", (-5.0).descontadoConSigno(compacto = true))
    }

    @Test
    fun porcentajes() {
        assertEquals("21,48 %", 21.48.toPorcentajeString())
        assertEquals("15 %", 15.0.toPorcentajeString())
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
