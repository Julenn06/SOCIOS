package com.julen.socios.ajustes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BloqueoTest {

    private var guardado = false
    private var ahora = 0L
    private fun bloqueo() = Bloqueo(leerActivo = { guardado }, guardarActivo = { guardado = it }, reloj = { ahora })

    @Test
    fun sinActivarNuncaSeBloquea() {
        val bloqueo = bloqueo()
        bloqueo.alSalir()
        ahora += Bloqueo.ESPERA_MS * 10
        bloqueo.alVolver()
        assertFalse(bloqueo.bloqueada)
    }

    @Test
    fun alAbrirLaAppConElBloqueoPuestoSePide() {
        guardado = true
        assertTrue(bloqueo().bloqueada)
    }

    @Test
    fun quienLoActivaNoTieneQueIdentificarseOtraVez() {
        val bloqueo = bloqueo()
        bloqueo.activo = true
        assertFalse(bloqueo.bloqueada)
    }

    @Test
    fun soloSeVuelveABloquearTrasUnMinutoFuera() {
        guardado = true
        val bloqueo = bloqueo()
        bloqueo.desbloquear()

        bloqueo.alSalir()
        ahora += Bloqueo.ESPERA_MS - 1
        bloqueo.alVolver()
        assertFalse("Elegir un archivo o confirmar el PIN no debe bloquearla", bloqueo.bloqueada)

        bloqueo.alSalir()
        ahora += Bloqueo.ESPERA_MS
        bloqueo.alVolver()
        assertTrue(bloqueo.bloqueada)
    }

    @Test
    fun tardarEnEscribirElPinEnOtraPantallaNoVuelveABloquearla() {
        guardado = true
        val bloqueo = bloqueo()
        // Android 9 o anterior: el PIN se pide en otra pantalla, que deja la app en segundo plano.
        bloqueo.alSalir()
        ahora += Bloqueo.ESPERA_MS * 2
        bloqueo.desbloquear()
        bloqueo.alVolver()
        assertFalse(bloqueo.bloqueada)
    }

    @Test
    fun elEstadoSigueAlBloqueoYCadaBloqueoNuevoEsOtroTurno() {
        guardado = true
        val bloqueo = bloqueo()
        assertTrue(bloqueo.estado.value)
        val primero = bloqueo.turno

        bloqueo.desbloquear()
        assertFalse(bloqueo.estado.value)

        bloqueo.alSalir()
        ahora += Bloqueo.ESPERA_MS
        bloqueo.alVolver()
        assertTrue(bloqueo.estado.value)
        assertNotEquals(primero, bloqueo.turno)

        val segundo = bloqueo.turno
        bloqueo.alSalir()
        ahora += 1_000
        bloqueo.alVolver()
        assertEquals("Una vuelta corta no es un bloqueo nuevo", segundo, bloqueo.turno)
    }

    @Test
    fun quitarloDesbloquea() {
        guardado = true
        val bloqueo = bloqueo()
        bloqueo.activo = false
        assertFalse(bloqueo.bloqueada)
        assertFalse(bloqueo.estado.value)
    }

    @Test
    fun volverSinHaberSalidoNoBloquea() {
        guardado = true
        val bloqueo = bloqueo()
        bloqueo.desbloquear()
        ahora += Bloqueo.ESPERA_MS * 5
        // onResume también llega tras el cuadro de la huella, sin haber salido de la app.
        bloqueo.alVolver()
        assertFalse(bloqueo.bloqueada)
    }
}
