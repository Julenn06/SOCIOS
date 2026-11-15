package com.julen.socios.ajustes

import org.junit.Assert.assertFalse
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
