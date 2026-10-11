package com.julen.socios.actualizaciones

import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ComprobadorActualizacionesTest {

    private val ahora = Instant.parse("2026-10-11T12:00:00Z")
    private val clock = Clock.fixed(ahora, ZoneOffset.UTC)

    private class RegistroEnMemoria(override var ultimaComprobacion: Long = 0) : RegistroComprobaciones

    private class FuenteFalsa(var publicada: String = "1.4.0", var falla: Boolean = false) : FuenteVersiones {
        var consultas = 0
        override suspend fun ultimaPublicada(): VersionPublicada {
            consultas++
            if (falla) throw IOException("sin conexión")
            return VersionPublicada(Version.parse(publicada)!!, "https://github.com/x")
        }
    }

    private fun comprobador(instalada: String, fuente: FuenteVersiones, registro: RegistroComprobaciones) =
        ComprobadorActualizaciones(fuente, registro, instalada, clock)

    @Test
    fun avisaSiLaPublicadaEsMasNueva() = runTest {
        val registro = RegistroEnMemoria()
        val resultado = comprobador("1.3.0", FuenteFalsa("1.4.0"), registro).comprobarSiToca()

        assertEquals("1.4.0", resultado?.version.toString())
        assertEquals(ahora.toEpochMilli(), registro.ultimaComprobacion)
    }

    @Test
    fun noAvisaSiYaTieneLaUltimaOUnaPosterior() = runTest {
        assertNull(comprobador("1.4.0", FuenteFalsa("1.4.0"), RegistroEnMemoria()).comprobarSiToca())
        assertNull(comprobador("1.5.0", FuenteFalsa("1.4.0"), RegistroEnMemoria()).comprobarSiToca())
    }

    @Test
    fun compruebaComoMuchoUnaVezAlDia() = runTest {
        val fuente = FuenteFalsa()
        val haceUnRato = RegistroEnMemoria(ahora.toEpochMilli() - ComprobadorActualizaciones.INTERVALO + 60_000)
        assertNull(comprobador("1.3.0", fuente, haceUnRato).comprobarSiToca())
        assertEquals(0, fuente.consultas)

        val ayer = RegistroEnMemoria(ahora.toEpochMilli() - ComprobadorActualizaciones.INTERVALO)
        comprobador("1.3.0", fuente, ayer).comprobarSiToca()
        assertEquals(1, fuente.consultas)
    }

    @Test
    fun unaFechaGuardadaEnElFuturoNoBloqueaLasComprobaciones() = runTest {
        // Por ejemplo, si el reloj del móvil estuvo adelantado.
        val fuente = FuenteFalsa()
        comprobador("1.3.0", fuente, RegistroEnMemoria(ahora.toEpochMilli() + 60_000)).comprobarSiToca()
        assertEquals(1, fuente.consultas)
    }

    @Test
    fun laComprobacionAutomaticaFallaEnSilencioYReintentaEnElSiguienteArranque() = runTest {
        val registro = RegistroEnMemoria()
        assertNull(comprobador("1.3.0", FuenteFalsa(falla = true), registro).comprobarSiToca())
        assertEquals(0L, registro.ultimaComprobacion)
    }

    @Test
    fun lasBuildsDeDesarrolloNoConsultanAlArrancar() = runTest {
        val fuente = FuenteFalsa()
        assertNull(comprobador("dev-12", fuente, RegistroEnMemoria()).comprobarSiToca())
        assertEquals(0, fuente.consultas)
    }

    @Test
    fun laComprobacionManualSiempreConsultaYPropagaLosErrores() = runTest {
        val registro = RegistroEnMemoria(ahora.toEpochMilli())
        val resultado = comprobador("1.3.0", FuenteFalsa("1.3.0"), registro).comprobar()
        assertFalse(resultado.hayNueva)

        val desarrollo = comprobador("dev-12", FuenteFalsa("1.4.0"), RegistroEnMemoria()).comprobar()
        assertFalse(desarrollo.hayNueva)

        assertThrows(IOException::class.java) {
            runBlocking { comprobador("1.3.0", FuenteFalsa(falla = true), RegistroEnMemoria()).comprobar() }
        }
    }

    @Test
    fun leeLaRespuestaDeLaApiDeGitHub() {
        val publicada = GitHubReleases.leerRelease(
            """{"id":1,"tag_name":"v1.4.0","name":"Release v1.4.0",""" +
                """"html_url":"https://github.com/Julenn06/SOCIOS/releases/tag/v1.4.0","assets":[]}"""
        )
        assertEquals("1.4.0", publicada.version.toString())
        assertEquals("https://github.com/Julenn06/SOCIOS/releases/tag/v1.4.0", publicada.url)
    }

    @Test
    fun soloAbrePaginasDeLasReleasesDelRepositorio() {
        val publicada = GitHubReleases.leerRelease("""{"tag_name":"v1.4.0","html_url":"https://ejemplo.com/apk"}""")
        assertEquals("https://github.com/Julenn06/SOCIOS/releases/latest", publicada.url)
    }

    @Test
    fun unaRespuestaInesperadaEsUnErrorDeConexion() {
        assertThrows(IOException::class.java) { GitHubReleases.leerRelease("""{"message":"Not Found"}""") }
        assertThrows(IOException::class.java) { GitHubReleases.leerRelease("""{"tag_name":"nightly"}""") }
        assertThrows(IOException::class.java) { GitHubReleases.leerRelease("<html>") }
    }
}
