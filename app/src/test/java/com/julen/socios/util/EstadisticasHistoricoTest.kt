package com.julen.socios.util

import com.julen.socios.model.CobroInfo
import com.julen.socios.model.DiaSemana
import com.julen.socios.model.Socio
import com.julen.socios.util.EstadisticasHistorico.Tendencia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EstadisticasHistoricoTest {

    private val actual = "2026-W42"

    private fun socio(semana: String, dia: DiaSemana = DiaSemana.LUNES, cuota: Double = 20.0, hecho: Boolean = true) =
        Socio(colaboracion = cuota, hecho = hecho, dia = dia, semanaKey = semana)

    private fun calcular(socios: List<Socio>, cobros: Map<String, CobroInfo> = emptyMap()) =
        EstadisticasHistorico.calcular(
            socios,
            CalculoComisiones.calcularResumenHistoricoGlobal(socios, cobrosMap = cobros).semanasHistoricas,
            actual
        )

    @Test
    fun sinDatosNoHayNinguna() {
        val e = calcular(emptyList())
        assertNull(e.mejorDia)
        assertNull(e.cuota)
        assertNull(e.apuntados)
        assertNull(e.descontado)
    }

    @Test
    fun elMejorDiaEsElDeMasSociosHechosYSoloCuentanLosHechos() {
        val e = calcular(
            listOf(
                socio("2026-W40", DiaSemana.MARTES),
                socio("2026-W41", DiaSemana.MARTES),
                socio("2026-W41", DiaSemana.LUNES),
                socio("2026-W41", DiaSemana.LUNES, hecho = false),
                socio("2026-W41", DiaSemana.LUNES, hecho = false)
            )
        )
        val dia = e.mejorDia!!
        assertEquals(DiaSemana.MARTES, dia.dia)
        assertEquals(2, dia.hechos)
        assertEquals(2.0 / 3, dia.proporcion, 1e-9)
    }

    @Test
    fun conEmpateGanaElPrimeroDeLaSemana() {
        val e = calcular(listOf(socio("2026-W41", DiaSemana.JUEVES), socio("2026-W41", DiaSemana.LUNES)))
        assertEquals(DiaSemana.LUNES, e.mejorDia!!.dia)
    }

    @Test
    fun laCuotaMediaYLaDeLasUltimasOchoSemanas() {
        val e = calcular(
            listOf(
                socio("2026-W10", cuota = 10.0),
                socio("2026-W35", cuota = 20.0), // W35 es la primera de las 8 últimas hasta W42
                socio("2026-W42", cuota = 30.0),
                socio("2026-W42", cuota = 99.0, hecho = false)
            )
        )
        val cuota = e.cuota!!
        assertEquals(20.0, cuota.media, 0.0)
        assertEquals(3, cuota.hechos)
        assertEquals(25.0, cuota.mediaReciente!!, 0.0)
    }

    @Test
    fun sinSociosAnterioresLaCuotaRecienteNoSeMuestra() {
        val e = calcular(listOf(socio("2026-W41", cuota = 12.5), socio("2026-W42", cuota = 15.0)))
        assertEquals(13.75, e.cuota!!.media, 0.0)
        assertNull(e.cuota.mediaReciente)
    }

    @Test
    fun losApuntadosSoloCuentanEnSemanasPasadas() {
        val e = calcular(
            listOf(
                socio("2026-W40"),
                socio("2026-W40", hecho = false),
                socio("2026-W41"),
                socio("2026-W41"),
                socio(actual, hecho = false) // esta semana aún se puede hacer
            )
        )
        val apuntados = e.apuntados!!
        assertEquals(4, apuntados.total)
        assertEquals(3, apuntados.hechos)
        assertEquals(1, apuntados.pendientes)
        assertEquals(0.75, apuntados.proporcionHechos, 0.0)
    }

    @Test
    fun loDescontadoAlMesSoloCuentaMesesConLoCobradoApuntado() {
        // Una semana de 10 socios de 20 €: 400 € de base + 140 € de bonus = 540 € brutos, 424,01 € de neto.
        val semanas = listOf("2026-W02", "2026-W06", "2026-W10", "2026-W15", "2026-W19")
        val socios = semanas.flatMap { semana -> List(10) { socio(semana) } }
        // Enero, febrero y marzo, 10 € descontados; abril y mayo, 40 €.
        val cobros = mapOf(
            "2026-W02" to 414.01, "2026-W06" to 414.01, "2026-W10" to 414.01, "2026-W15" to 384.01, "2026-W19" to 384.01
        ).mapValues { (semana, neto) -> CobroInfo(semana, neto) }

        val descontado = calcular(socios, cobros).descontado!!

        assertEquals(5, descontado.meses)
        assertEquals(22.0, descontado.media, 0.0)
        assertEquals(30.0, descontado.mediaReciente!!, 0.0)
        assertEquals(Tendencia.SUBE, descontado.tendencia)
    }

    @Test
    fun conPocosMesesNoHayTendencia() {
        val socios = List(10) { socio("2026-W02") }
        val descontado = calcular(socios, mapOf("2026-W02" to CobroInfo("2026-W02", 414.01))).descontado!!
        assertEquals(1, descontado.meses)
        assertEquals(10.0, descontado.media, 0.0)
        assertEquals(Tendencia.SIN_DATOS, descontado.tendencia)
    }
}
