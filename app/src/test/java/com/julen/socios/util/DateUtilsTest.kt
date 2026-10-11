package com.julen.socios.util

import com.julen.socios.model.DiaSemana
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DateUtilsTest {

    private val zona = ZoneId.of("Europe/Madrid")

    private fun clockEn(fecha: String): Clock =
        Clock.fixed(LocalDate.parse(fecha).atTime(12, 0).atZone(zona).toInstant(), zona)

    @Test
    fun laSemanaDelCambioDeAnio2025TieneUnaUnicaClave() {
        // Antes: lunes a miércoles daban "2025-W01" y jueves a domingo "2026-W01".
        listOf("2025-12-29", "2025-12-30", "2025-12-31", "2026-01-01", "2026-01-02", "2026-01-03", "2026-01-04")
            .forEach { fecha -> assertEquals(fecha, "2026-W01", DateUtils.getSemanaKey(0, clockEn(fecha))) }
    }

    @Test
    fun elUnoDeEnero2027PerteneceALaSemana53De2026() {
        assertEquals("2026-W53", DateUtils.getSemanaKey(0, clockEn("2026-12-28")))
        assertEquals("2026-W53", DateUtils.getSemanaKey(0, clockEn("2027-01-01")))
        assertEquals("2026-W53", DateUtils.getSemanaKey(0, clockEn("2027-01-03")))
        assertEquals("2027-W01", DateUtils.getSemanaKey(0, clockEn("2027-01-04")))
    }

    @Test
    fun laSemanaAnteriorNoDependeDelDiaDesdeElQueSeConsulta() {
        assertEquals("2026-W53", DateUtils.getSemanaKey(-1, clockEn("2027-01-04")))
        assertEquals("2026-W53", DateUtils.getSemanaKey(-1, clockEn("2027-01-08")))
        assertEquals("2026-W01", DateUtils.getSemanaKey(-1, clockEn("2026-01-05")))
        assertEquals("2026-W01", DateUtils.getSemanaKey(-1, clockEn("2026-01-09")))
    }

    @Test
    fun elOffsetDeUnaClaveEsElInversoDeGetSemanaKey() {
        val clock = clockEn("2026-10-14")
        for (offset in -120..120) {
            val key = DateUtils.getSemanaKey(offset, clock)
            assertEquals(key, offset, DateUtils.getWeekOffsetFromKey(key, clock))
        }
    }

    @Test
    fun lasClavesImposiblesNoSonValidas() {
        assertTrue(DateUtils.esSemanaKeyValida("2026-W53"))
        assertFalse(DateUtils.esSemanaKeyValida("2027-W53"))
        assertFalse(DateUtils.esSemanaKeyValida("2025-W00"))
        assertFalse(DateUtils.esSemanaKeyValida("2025-9"))
        assertFalse(DateUtils.esSemanaKeyValida(""))
        assertNull(DateUtils.getWeekOffsetFromKey("no-es-una-semana", clockEn("2026-10-14")))
    }

    @Test
    fun elLunesDeUnaClaveEsCorrecto() {
        assertEquals(LocalDate.parse("2025-12-29"), DateUtils.lunesDeSemanaKey("2026-W01"))
        assertEquals(LocalDate.parse("2026-12-28"), DateUtils.lunesDeSemanaKey("2026-W53"))
        assertEquals(LocalDate.parse("2025-02-24"), DateUtils.lunesDeSemanaKey("2025-W09"))
    }

    @Test
    fun elRangoMuestraDeLunesAViernes() {
        val rango = DateUtils.getRangoSemanaTextoFromKey("2026-W01")
        assertTrue(rango, rango.startsWith("29 de dic"))
        assertTrue(rango, rango.contains("2 de ene"))
        assertTrue(rango, rango.endsWith("(2025)"))
        assertEquals("clave-rara", DateUtils.getRangoSemanaTextoFromKey("clave-rara"))
    }

    @Test
    fun elFinDeSemanaSeAsignaAlDiaLaborableMasCercano() {
        assertEquals(DiaSemana.MIERCOLES, DateUtils.getDiaSemanaActual(clockEn("2026-10-14")))
        assertEquals(DiaSemana.VIERNES, DateUtils.getDiaSemanaActual(clockEn("2026-10-17")))
        assertEquals(DiaSemana.LUNES, DateUtils.getDiaSemanaActual(clockEn("2026-10-18")))
    }
}
