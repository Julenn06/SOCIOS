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
    fun leeElApkAdjuntoConSuTamanoYSuSha256() {
        val apk = GitHubReleases.leerRelease(
            """{"tag_name":"v1.5.0","html_url":"https://github.com/Julenn06/SOCIOS/releases/tag/v1.5.0","assets":[""" +
                """{"name":"notas.txt","size":10,""" +
                """"browser_download_url":"https://github.com/Julenn06/SOCIOS/releases/download/v1.5.0/notas.txt"},""" +
                """{"name":"socios-1.5.0.apk","size":2293784,""" +
                """"digest":"sha256:B35DA16FBB3701E9F3E944800E5B83D9C4DED1E64FC9283070A624C2BA805138",""" +
                """"browser_download_url":"https://github.com/Julenn06/SOCIOS/releases/download/v1.5.0/socios-1.5.0.apk"}]}"""
        ).apk

        assertEquals("https://github.com/Julenn06/SOCIOS/releases/download/v1.5.0/socios-1.5.0.apk", apk?.url)
        assertEquals(2293784L, apk?.tamano)
        assertEquals("b35da16fbb3701e9f3e944800e5b83d9c4ded1e64fc9283070a624c2ba805138", apk?.sha256)
    }

    @Test
    fun lasReleasesAntiguasNoTraenSha256() {
        val apk = GitHubReleases.leerRelease(
            """{"tag_name":"v1.3.0","assets":[{"name":"socios-1.3.0.apk","size":100,"digest":null,""" +
                """"browser_download_url":"https://github.com/Julenn06/SOCIOS/releases/download/v1.3.0/socios-1.3.0.apk"}]}"""
        ).apk

        assertEquals(100L, apk?.tamano)
        assertNull(apk?.sha256)
    }

    @Test
    fun soloDescargaApksDeLasReleasesDelRepositorio() {
        fun apkDe(nombre: String, url: String) = GitHubReleases.leerRelease(
            """{"tag_name":"v1.5.0","assets":[{"name":"$nombre","size":1,"browser_download_url":"$url"}]}"""
        ).apk

        assertNull(apkDe("socios-1.5.0.apk", "https://ejemplo.com/socios-1.5.0.apk"))
        assertNull(apkDe("socios-1.5.0.apk", "https://github.com/otro/SOCIOS/releases/download/v1.5.0/socios-1.5.0.apk"))
        assertNull(
            apkDe("socios-1.5.0-debug.apk", "https://github.com/Julenn06/SOCIOS/releases/download/v1.5.0/socios-1.5.0-debug.apk")
        )
        assertNull(GitHubReleases.leerRelease("""{"tag_name":"v1.5.0"}""").apk)
    }

    @Test
    fun unaRespuestaInesperadaEsUnErrorDeConexion() {
        assertThrows(IOException::class.java) { GitHubReleases.leerRelease("""{"message":"Not Found"}""") }
        assertThrows(IOException::class.java) { GitHubReleases.leerRelease("""{"tag_name":"nightly"}""") }
        assertThrows(IOException::class.java) { GitHubReleases.leerRelease("<html>") }
    }
}
