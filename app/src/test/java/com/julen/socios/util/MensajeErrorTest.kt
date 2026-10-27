package com.julen.socios.util

import com.julen.socios.R
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Test

class MensajeErrorTest {

    @Test
    fun losMensajesPropiosSeMuestranTalCual() {
        assertEquals(UiText.Texto("archivo raro"), motivoDeError(ErrorParaUsuario("archivo raro")))
        assertEquals(UiText.Texto("no es una copia"), motivoDeError(SerializationException("no es una copia")))
    }

    @Test
    fun unDiscoLlenoSeExplicaSinElMensajeDelSistema() {
        val e = IOException("write failed: ENOSPC (No space left on device)")
        assertEquals(UiText.Res(R.string.error_sin_espacio), motivoDeError(e))
    }

    @Test
    fun unArchivoInaccesibleSeExplica() {
        val esperado = UiText.Res(R.string.error_archivo_inaccesible)
        assertEquals(esperado, motivoDeError(FileNotFoundException("/storage/x: open failed: ENOENT")))
        assertEquals(esperado, motivoDeError(SecurityException("Permission Denial")))
    }

    @Test
    fun loDemasEsUnErrorInesperadoConElNombreDeLaExcepcion() {
        assertEquals(
            UiText.Res(R.string.error_inesperado, listOf("IllegalStateException")),
            motivoDeError(IllegalStateException("Cannot access database on the main thread"))
        )
    }
}
