package com.julen.socios.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BonoRegaloInfoTest {

    private fun bono(nota: String) = BonoRegaloInfo(semanaKey = "2026-W42", activo = true, monto = 50.0, nota = nota)

    @Test
    fun elMotivoEsLaNotaEscritaPorElUsuario() {
        assertEquals("Regalo por buen trabajo", bono("  Regalo por buen trabajo ").motivo)
    }

    @Test
    fun sinNotaOConElTextoAutomaticoAntiguoNoHayMotivo() {
        assertNull(bono("").motivo)
        assertNull(bono("   ").motivo)
        assertNull(bono("Ajustado a mano").motivo)
    }
}
