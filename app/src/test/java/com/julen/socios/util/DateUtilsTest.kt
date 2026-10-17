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
    fun laDistanciaEntreSemanasEsElInversoDeGetSemanaKey() {
        val clock = clockEn("2026-10-14")
        val actual = DateUtils.getSemanaKey(0, clock)
        for (offset in -120..120) {
            val key = DateUtils.getSemanaKey(offset, clock)
            assertEquals(key, offset, DateUtils.semanasEntre(actual, key))
            assertEquals(key, DateUtils.semanaKeyDesplazada(actual, offset))
        }
    }

    @Test
    fun lasClavesImposiblesNoSonValidas() {
        assertTrue(DateUtils.esSemanaKeyValida("2026-W53"))
        assertFalse(DateUtils.esSemanaKeyValida("2027-W53"))
        assertFalse(DateUtils.esSemanaKeyValida("2025-W00"))
        assertFalse(DateUtils.esSemanaKeyValida("2025-9"))
        assertFalse(DateUtils.esSemanaKeyValida(""))
        assertNull(DateUtils.semanasEntre("2026-W42", "no-es-una-semana"))
        assertNull(DateUtils.semanaKeyDesplazada("no-es-una-semana", 1))
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
        assertTrue(rango, rango.endsWith("(2026)"))
        assertEquals("clave-rara", DateUtils.getRangoSemanaTextoFromKey("clave-rara"))
    }

    @Test
    fun elRangoCortoJuntaElMesSiNoCambia() {
        assertEquals("12–16 oct", DateUtils.rangoCorto("2026-W42"))
        assertEquals("29 dic – 2 ene", DateUtils.rangoCorto("2026-W01"))
        assertEquals("clave-rara", DateUtils.rangoCorto("clave-rara"))
    }

    @Test
    fun elMomentoRelativoDiceHoyAyerOLaFecha() {
        val ahora = clockEn("2026-10-14")
        fun millis(fecha: String, hora: Int, minuto: Int) =
            LocalDate.parse(fecha).atTime(hora, minuto).atZone(zona).toInstant().toEpochMilli()

        assertEquals("hoy a las 9:05", DateUtils.momentoRelativo(millis("2026-10-14", 9, 5), ahora))
        assertEquals("ayer a las 23:59", DateUtils.momentoRelativo(millis("2026-10-13", 23, 59), ahora))
        assertEquals("el 3 oct", DateUtils.momentoRelativo(millis("2026-10-03", 12, 0), ahora))
    }

    @Test
    fun elFinDeSemanaSeAsignaAlDiaLaborableMasCercano() {
        assertEquals(DiaSemana.MIERCOLES, DateUtils.getDiaSemanaActual(clockEn("2026-10-14")))
        assertEquals(DiaSemana.VIERNES, DateUtils.getDiaSemanaActual(clockEn("2026-10-17")))
        assertEquals(DiaSemana.LUNES, DateUtils.getDiaSemanaActual(clockEn("2026-10-18")))
    }
}
