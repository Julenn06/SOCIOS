package com.julen.socios.util

import com.julen.socios.util.ReparacionSemanaKeys.SocioRef
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReparacionSemanaKeysTest {

    private val zona = ZoneId.of("Europe/Madrid")

    private fun ts(fecha: String): Long =
        LocalDate.parse(fecha).atTime(10, 0).atZone(zona).toInstant().toEpochMilli()

    @Test
    fun corrigeLosDiasDeDiciembreGuardadosComoSemana1DelAnioEnCurso() {
        assertEquals("2026-W01", ReparacionSemanaKeys.claveCorregida("2025-W01", ts("2025-12-30"), zona))
        assertEquals("2026-W01", ReparacionSemanaKeys.claveCorregida("2025-W01", ts("2026-01-05"), zona))
        assertEquals("2025-W01", ReparacionSemanaKeys.claveCorregida("2024-W01", ts("2024-12-31"), zona))
    }

    @Test
    fun noTocaLaSemana1Real() {
        assertNull(ReparacionSemanaKeys.claveCorregida("2025-W01", ts("2025-01-02"), zona))
        assertNull(ReparacionSemanaKeys.claveCorregida("2025-W01", ts("2024-12-30"), zona))
    }

    @Test
    fun noTocaAniosSinAmbiguedad() {
        // La semana 1 de 2027 empieza el 4 de enero: una clave "2026-W01" nunca vino del error.
        assertNull(ReparacionSemanaKeys.claveCorregida("2026-W01", ts("2026-12-30"), zona))
        assertNull(ReparacionSemanaKeys.claveCorregida("2026-W42", ts("2026-10-14"), zona))
    }

    @Test
    fun corrigeLaSemana53Inexistente() {
        assertEquals("2026-W53", ReparacionSemanaKeys.claveCorregida("2027-W53", ts("2027-01-01"), zona))
        assertNull(ReparacionSemanaKeys.claveCorregida("2026-W53", ts("2026-12-28"), zona))
    }

    @Test
    fun mueveElBonoSiTodosLosSociosDeSuSemanaSeHanMovido() {
        val resultado = ReparacionSemanaKeys.calcular(
            socios = listOf(
                SocioRef("a", "2025-W01", ts("2025-12-29")),
                SocioRef("b", "2025-W01", ts("2025-12-31")),
                SocioRef("c", "2026-W01", ts("2026-01-02"))
            ),
            bonoKeys = setOf("2025-W01"),
            zona = zona
        )
        assertEquals(mapOf("a" to "2026-W01", "b" to "2026-W01"), resultado.socios)
        assertEquals(mapOf("2025-W01" to "2026-W01"), resultado.bonos)
    }

    @Test
    fun noMueveElBonoSiQuedanSociosLegitimosEnSuSemana() {
        val resultado = ReparacionSemanaKeys.calcular(
            socios = listOf(
                SocioRef("enero", "2025-W01", ts("2025-01-02")),
                SocioRef("diciembre", "2025-W01", ts("2025-12-30"))
            ),
            bonoKeys = setOf("2025-W01"),
            zona = zona
        )
        assertEquals(mapOf("diciembre" to "2026-W01"), resultado.socios)
        assertTrue(resultado.bonos.isEmpty())
    }

    @Test
    fun noMueveElBonoSiElDestinoYaTieneUno() {
        val resultado = ReparacionSemanaKeys.calcular(
            socios = listOf(SocioRef("a", "2025-W01", ts("2025-12-30"))),
            bonoKeys = setOf("2025-W01", "2026-W01"),
            zona = zona
        )
        assertTrue(resultado.bonos.isEmpty())
    }
}
