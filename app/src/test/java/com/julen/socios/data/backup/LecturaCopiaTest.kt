package com.julen.socios.data.backup

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LecturaCopiaTest {

    private fun leer(texto: String, limite: Long) = leerTextoCopia(texto.byteInputStream(Charsets.UTF_8), limite)

    @Test
    fun quitaElBomYConservaLosAcentos() {
        assertEquals("Línea ñ", leer(BOM_UTF8 + "Línea ñ", limite = 100))
    }

    @Test
    fun admiteUnArchivoQueOcupaJustoElLimite() {
        assertEquals("abcd", leer("abcd", limite = 4))
    }

    @Test
    fun rechazaUnArchivoMayorQueElLimiteSinLeerloEntero() {
        assertThrows(IOException::class.java) { leer("abcde", limite = 4) }
    }
}
