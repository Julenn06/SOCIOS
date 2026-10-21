package com.julen.socios.util

import com.julen.socios.model.DiaSemana
import com.julen.socios.model.Socio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BusquedaSociosTest {

    private val socios = listOf(
        Socio(id = "maria", colaboracion = 20.0, dia = DiaSemana.MARTES, semanaKey = "2026-W40", nombreSocio = "María José Pérez"),
        Socio(id = "jose", colaboracion = 12.5, dia = DiaSemana.LUNES, semanaKey = "2026-W42", nombreSocio = "José", notas = "Llamar el jueves"),
        Socio(id = "anon", colaboracion = 20.0, dia = DiaSemana.VIERNES, semanaKey = "2026-W42"),
        Socio(id = "ana", colaboracion = 15.0, dia = DiaSemana.JUEVES, semanaKey = "2026-W41", nombreSocio = "Ana")
    )

    private fun buscar(texto: String) = BusquedaSocios.filtrar(socios, texto).map { it.id }

    @Test
    fun noDistingueTildesNiMayusculas() {
        assertEquals(listOf("jose", "maria"), buscar("JOSE"))
        assertEquals(listOf("maria"), buscar("maría pérez"))
    }

    @Test
    fun buscaTambienEnLasNotas() {
        assertEquals(listOf("jose"), buscar("jueves"))
    }

    @Test
    fun todasLasPalabrasTienenQueAparecer() {
        assertEquals(listOf("maria"), buscar("jose perez"))
        assertTrue(buscar("ana perez").isEmpty())
    }

    @Test
    fun unNumeroEncuentraLosSociosConEsaCuota() {
        assertEquals(listOf("anon", "maria"), buscar("20"))
        assertEquals(listOf("jose"), buscar("12,5"))
    }

    @Test
    fun sinTextoNoHayResultados() {
        assertTrue(buscar("   ").isEmpty())
    }
}
