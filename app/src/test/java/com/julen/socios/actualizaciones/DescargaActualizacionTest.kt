package com.julen.socios.actualizaciones

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DescargaActualizacionTest {

    private val contenido = ByteArray(200_000) { (it % 251).toByte() }
    private val sha256 = MessageDigest.getInstance("SHA-256").digest(contenido).joinToString("") { "%02x".format(it) }

    private suspend fun copiar(datos: ByteArray, apk: ApkPublicado, progreso: MutableList<Progreso> = mutableListOf()) =
        ByteArrayOutputStream().also { salida ->
            DescargadorGitHub.copiarVerificando(ByteArrayInputStream(datos), salida, apk, progreso::add)
        }.toByteArray()

    @Test
    fun copiaElArchivoEInformaDelProgresoHastaElTotal() = runTest {
        val progreso = mutableListOf<Progreso>()
        val copiado = copiar(contenido, ApkPublicado("https://x", contenido.size.toLong(), sha256), progreso)

        assertArrayEquals(contenido, copiado)
        assertEquals(Progreso(0, contenido.size.toLong()), progreso.first())
        assertEquals(Progreso(contenido.size.toLong(), contenido.size.toLong()), progreso.last())
        assertEquals(progreso.sortedBy { it.descargados }, progreso)
    }

    @Test
    fun sinSha256BastaConQueCoincidaElTamano() = runTest {
        assertArrayEquals(contenido, copiar(contenido, ApkPublicado("https://x", contenido.size.toLong(), null)))
    }

    @Test
    fun unaDescargaIncompletaOMasGrandeEstaDanada() {
        val apk = ApkPublicado("https://x", contenido.size.toLong(), null)
        assertThrows(DescargaDanadaException::class.java) {
            runBlocking { copiar(contenido.copyOf(contenido.size - 1), apk) }
        }
        assertThrows(DescargaDanadaException::class.java) {
            runBlocking { copiar(contenido + 0, apk) }
        }
    }

    @Test
    fun unArchivoConOtroSha256EstaDanado() {
        val modificado = contenido.copyOf().also { it[1000] = 99 }
        assertThrows(DescargaDanadaException::class.java) {
            runBlocking { copiar(modificado, ApkPublicado("https://x", contenido.size.toLong(), sha256)) }
        }
    }

    @Test
    fun laFraccionVaDe0a1() {
        assertEquals(0.25f, Progreso(25, 100).fraccion)
        assertEquals(1f, Progreso(100, 100).fraccion)
    }
}
