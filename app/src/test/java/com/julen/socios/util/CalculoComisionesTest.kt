package com.julen.socios.util

import com.julen.socios.model.BonoRegaloInfo
import com.julen.socios.model.CobroInfo
import com.julen.socios.model.DiaSemana
import com.julen.socios.model.Socio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalculoComisionesTest {

    private fun socios(n: Int, semana: String, cuota: Double = 20.0, hecho: Boolean = true) =
        List(n) { Socio(colaboracion = cuota, hecho = hecho, dia = DiaSemana.LUNES, semanaKey = semana) }

    @Test
    fun baseX2() {
        assertEquals(40.0, CalculoComisiones.calcularBaseX2(20.0), 0.0)
        assertEquals(60.0, CalculoComisiones.calcularBaseX2(30.0), 0.0)
        assertEquals(21.0, CalculoComisiones.calcularBaseX2(10.5), 0.0)
    }

    @Test
    fun tramosDeBonus() {
        assertEquals(0.0, CalculoComisiones.calcularBonusSemanal(0), 0.0)
        assertEquals(0.0, CalculoComisiones.calcularBonusSemanal(4), 0.0)
        assertEquals(50.0, CalculoComisiones.calcularBonusSemanal(5), 0.0)
        assertEquals(50.0, CalculoComisiones.calcularBonusSemanal(6), 0.0)
        assertEquals(100.0, CalculoComisiones.calcularBonusSemanal(7), 0.0)
        assertEquals(100.0, CalculoComisiones.calcularBonusSemanal(9), 0.0)
        assertEquals(140.0, CalculoComisiones.calcularBonusSemanal(10), 0.0)
        assertEquals(140.0, CalculoComisiones.calcularBonusSemanal(12), 0.0)
        assertEquals(190.0, CalculoComisiones.calcularBonusSemanal(13), 0.0)
        assertEquals(190.0, CalculoComisiones.calcularBonusSemanal(15), 0.0)
        assertEquals(240.0, CalculoComisiones.calcularBonusSemanal(16), 0.0)
    }

    @Test
    fun siguienteMeta() {
        assertEquals(5 to 50.0, CalculoComisiones.obtenerSiguienteMetaBonus(0))
        assertEquals(7 to 100.0, CalculoComisiones.obtenerSiguienteMetaBonus(5))
        assertEquals(10 to 140.0, CalculoComisiones.obtenerSiguienteMetaBonus(8))
        assertEquals(13 to 190.0, CalculoComisiones.obtenerSiguienteMetaBonus(10))
        assertEquals(16 to 240.0, CalculoComisiones.obtenerSiguienteMetaBonus(13))
        assertEquals(listOf(7, 10, 13, 16), listOf(6, 9, 12, 14).map { CalculoComisiones.obtenerSiguienteMetaBonus(it).first })
    }

    @Test
    fun tramoAnteriorDeCadaMeta() {
        assertEquals(listOf(0, 5, 7, 10, 13), listOf(5, 7, 10, 13, 16).map(CalculoComisiones::tramoAnterior))
    }

    @Test
    fun irpfYNetoSeRedondeanACentimos() {
        val resumen = CalculoComisiones.calcularResumenSemana(socios(10, "2025-W09"))

        assertEquals(400.0, resumen.gananciasBaseX2, 0.0)
        assertEquals(140.0, resumen.bonusSemanal, 0.0)
        assertEquals(540.0, resumen.totalBruto, 0.0)
        // 540 × 21,48 % = 115,992 → 115,99 €
        assertEquals(115.99, resumen.retencionIrpf, 0.0)
        assertEquals(424.01, resumen.totalNeto, 0.0)
        assertEquals(CalculoComisiones.IRPF, resumen.porcentajeIrpf, 0.0)
    }

    @Test
    fun soloCuentanLosSociosHechos() {
        val resumen = CalculoComisiones.calcularResumenSemana(
            socios(4, "2025-W09") + socios(3, "2025-W09", hecho = false)
        )
        assertEquals(7, resumen.totalSociosRegistrados)
        assertEquals(4, resumen.totalSociosHechos)
        assertEquals(80.0, resumen.sumaColaboracionesBase, 0.0)
        assertEquals(0.0, resumen.bonusSemanal, 0.0)
        assertEquals(1, resumen.sociosFaltantesParaSiguienteMeta)
    }

    @Test
    fun historicoSumaCadaSemanaConSuPropioTramo() {
        val global = CalculoComisiones.calcularResumenHistoricoGlobal(
            socios(5, "2025-W08") + socios(10, "2025-W09")
        )

        assertEquals(2, global.semanasHistoricas.size)
        assertEquals("2025-W09", global.semanasHistoricas.first().semanaKey)
        assertEquals(15, global.totalSociosHechosAcumulado)
        assertEquals(15, global.totalSociosRegistradosAcumulado)
        // 50 € (5 socios) + 140 € (10 socios), no el tramo de 15 socios en una sola semana.
        assertEquals(190.0, global.totalBonusAcumulado, 0.0)
        assertEquals(600.0, global.totalBaseX2Acumulado, 0.0)
        assertEquals(790.0, global.totalBrutoAcumulado, 0.0)
        // 53,70 + 115,99
        assertEquals(169.69, global.totalIrpfRetenidoAcumulado, 0.0)
        assertEquals(620.31, global.totalNetoAcumulado, 0.0)
    }

    @Test
    fun bonoManualFijoSustituyeAlAutomatico() {
        val bono = BonoRegaloInfo(semanaKey = "2025-W09", activo = true, esMontoFijo = true, monto = 140.0)
        val resumen = CalculoComisiones.calcularResumenSemana(socios(8, "2025-W09"), bono)

        assertTrue(resumen.esBonoRegaloAplicado)
        assertEquals(140.0, resumen.bonusSemanal, 0.0)
        assertEquals(100.0, resumen.bonusAutomatico, 0.0)
    }

    @Test
    fun bonoManualSumadoSeAnadeAlAutomatico() {
        val bono = BonoRegaloInfo(semanaKey = "2025-W09", activo = true, esMontoFijo = false, monto = 50.0)
        val resumen = CalculoComisiones.calcularResumenSemana(socios(8, "2025-W09"), bono)

        assertEquals(150.0, resumen.bonusSemanal, 0.0)
    }

    @Test
    fun bonoInactivoNoSeAplica() {
        val bono = BonoRegaloInfo(semanaKey = "2025-W09", activo = false, monto = 999.0)
        val resumen = CalculoComisiones.calcularResumenSemana(socios(8, "2025-W09"), bono)

        assertFalse(resumen.esBonoRegaloAplicado)
        assertEquals(100.0, resumen.bonusSemanal, 0.0)
    }

    @Test
    fun todasLasSemanasRetienenElMismoIrpf() {
        val global = CalculoComisiones.calcularResumenHistoricoGlobal(socios(10, "2025-W09") + socios(10, "2026-W10"))

        assertTrue(global.semanasHistoricas.all { it.resumenSemana.porcentajeIrpf == CalculoComisiones.IRPF })
        // 540 × 21,48 % = 115,99 en cada semana
        assertEquals(231.98, global.totalIrpfRetenidoAcumulado, 0.0)
    }

    @Test
    fun agrupaPorMesYAnioSegunElJuevesDeCadaSemana() {
        val historico = CalculoComisiones.calcularResumenHistoricoGlobal(
            socios(5, "2025-W52") +  // 22-26 dic 2025 → diciembre 2025
                socios(5, "2026-W01") +  // 29 dic 2025 - 2 ene 2026, jueves 1 ene → enero 2026
                socios(10, "2026-W02") + // 5-9 ene 2026 → enero 2026
                socios(5, "2026-W40")    // 28 sep - 2 oct 2026, jueves 1 oct → octubre 2026
        )

        val meses = CalculoComisiones.agruparPorPeriodo(historico.semanasHistoricas, CalculoComisiones.Periodo.MES)
        assertEquals(listOf(2026 to 10, 2026 to 1, 2025 to 12), meses.map { it.anio to it.mes })

        val enero = meses[1]
        assertEquals(15, enero.sociosHechos)
        assertEquals(790.0, enero.bruto, 0.0) // 600 de base y 50 + 140 de bonus, cada semana con su tramo
        assertEquals(53.70 + 115.99, enero.irpf, 0.001)

        val anios = CalculoComisiones.agruparPorPeriodo(historico.semanasHistoricas, CalculoComisiones.Periodo.ANIO)
        assertEquals(listOf(2026, 2025), anios.map { it.anio })
        assertEquals(listOf(null, null), anios.map { it.mes })
        assertEquals(20, anios[0].sociosHechos)
        assertEquals(historico.totalBrutoAcumulado, anios.sumOf { it.bruto }, 0.001)
    }

    @Test
    fun loCobradoPasaASerElNetoYLaDiferenciaEsLoDescontado() {
        val sin = CalculoComisiones.calcularResumenSemana(socios(10, "2026-W42"))
        val con = CalculoComisiones.calcularResumenSemana(socios(10, "2026-W42"), cobro = CobroInfo("2026-W42", 408.70))

        assertEquals(424.01, con.netoCalculado, 0.0)
        assertEquals(408.70, con.totalNeto, 0.0)
        assertEquals(15.31, con.descontado, 0.0)
        assertTrue(con.tieneCobroReal)
        assertFalse(sin.tieneCobroReal)
        assertEquals(0.0, sin.descontado, 0.0)
        // El bruto, el IRPF, el bonus y la siguiente meta son los calculados: no dependen de lo cobrado.
        assertEquals(sin.totalBruto, con.totalBruto, 0.0)
        assertEquals(sin.retencionIrpf, con.retencionIrpf, 0.0)
        assertEquals(sin.bonusSemanal, con.bonusSemanal, 0.0)
        assertEquals(sin.siguienteMetaSocios, con.siguienteMetaSocios)
    }

    @Test
    fun siSeCobraDeMasLoDescontadoEsNegativo() {
        val resumen = CalculoComisiones.calcularResumenSemana(socios(10, "2026-W42"), cobro = CobroInfo("2026-W42", 430.0))

        assertEquals(430.0, resumen.totalNeto, 0.0)
        assertEquals(-5.99, resumen.descontado, 0.0)
    }

    @Test
    fun unaSemanaConSoloLoCobradoCuentaEnElHistorico() {
        val global = CalculoComisiones.calcularResumenHistoricoGlobal(
            todosLosSocios = socios(10, "2026-W41"),
            cobrosMap = mapOf(
                "2026-W42" to CobroInfo("2026-W42", 10.0),
                "2026-W41" to CobroInfo("2026-W41", 400.0)
            )
        )

        assertEquals(listOf("2026-W42", "2026-W41"), global.semanasHistoricas.map { it.semanaKey })
        // 24,01 € descontados en la W41 y 10 € cobrados de más en la W42.
        assertEquals(14.01, global.totalDescontadoAcumulado, 0.0)
        assertEquals(410.0, global.totalNetoAcumulado, 0.0)
        assertEquals(540.0, global.totalBrutoAcumulado, 0.0)

        val mes = CalculoComisiones.agruparPorPeriodo(global.semanasHistoricas, CalculoComisiones.Periodo.MES).single()
        assertEquals(14.01, mes.descontado, 0.0)
        assertEquals(global.totalNetoAcumulado, mes.neto, 0.0)
    }

    @Test
    fun lasDevolucionesDeLaVersionAnteriorSePasanAlNetoQueDaban() {
        // En la 1.9.0, 40 € de devolución con 10 socios: 540 - 40 = 500 de bruto, 107,40 de IRPF, 392,60 de neto.
        assertEquals(392.60, DevolucionesAntiguas.netoCobrado(socios(10, "2026-W42"), null, CalculoComisiones.IRPF, 40.0), 0.0)
        // Si superaban lo ganado no se retenía nada.
        assertEquals(-30.0, DevolucionesAntiguas.netoCobrado(socios(1, "2026-W42"), null, CalculoComisiones.IRPF, 70.0), 0.0)
    }

    @Test
    fun laMejorSemanaEsLaDeMasNeto() {
        val semanas = CalculoComisiones.calcularResumenHistoricoGlobal(
            socios(5, "2026-W40") + socios(10, "2026-W41") + socios(7, "2026-W42")
        ).semanasHistoricas

        assertEquals("2026-W41", CalculoComisiones.mejorSemana(semanas)?.semanaKey)
        assertEquals(null, CalculoComisiones.mejorSemana(emptyList()))
    }

    @Test
    fun sinNetoNoHayMejorSemana() {
        // Una semana solo con pendientes no ha dado nada: no es "la mejor".
        val semanas = CalculoComisiones.calcularResumenHistoricoGlobal(
            socios(3, "2026-W42", hecho = false)
        ).semanasHistoricas

        assertEquals(null, CalculoComisiones.mejorSemana(semanas))
    }

    @Test
    fun laEvolucionEmpiezaEnLaPrimeraSemanaConDatos() {
        val semanas = CalculoComisiones.calcularResumenHistoricoGlobal(
            socios(5, "2025-W40") + socios(10, "2026-W02")
        ).semanasHistoricas

        val barras = CalculoComisiones.evolucionSemanas(semanas, hasta = "2026-W02")

        // De la 2025-W40 a la 2026-W02 (2025 tiene 52 semanas): 15 semanas seguidas, más de las 12 mínimas.
        assertEquals(15, barras.size)
        assertEquals("2025-W40", barras.first().clave)
        assertEquals("2026-W02", barras.last().clave)
        // Con poco historial (solo la última semana, la primera de la lista) se ven igualmente las 12 últimas.
        assertEquals(12, CalculoComisiones.evolucionSemanas(semanas.take(1), hasta = "2026-W02").size)
    }

    @Test
    fun laEvolucionPorMesesYAniosEmpiezaEnElPrimeroConDatos() {
        val semanas = CalculoComisiones.calcularResumenHistoricoGlobal(
            socios(5, "2024-W10") + socios(10, "2026-W02")
        ).semanasHistoricas
        val meses = CalculoComisiones.agruparPorPeriodo(semanas, CalculoComisiones.Periodo.MES)
        val anios = CalculoComisiones.agruparPorPeriodo(semanas, CalculoComisiones.Periodo.ANIO)

        val porMes = CalculoComisiones.evolucionPeriodos(meses, CalculoComisiones.Periodo.MES, "2026-W02")
        assertEquals("2024-03", porMes.first().clave) // El jueves de la 2024-W10 es el 7 de marzo.
        assertEquals("2026-01", porMes.last().clave)
        assertEquals(23, porMes.size)

        val porAnio = CalculoComisiones.evolucionPeriodos(anios, CalculoComisiones.Periodo.ANIO, "2026-W02")
        assertEquals(listOf("2024", "2025", "2026"), porAnio.map { it.clave })
    }

    @Test
    fun lasUltimasSemanasVanSeguidasConCeroEnLasVacias() {
        val semanas = CalculoComisiones.calcularResumenHistoricoGlobal(socios(10, "2025-W52")).semanasHistoricas

        val barras = CalculoComisiones.ultimasSemanas(semanas, hasta = "2026-W02", cantidad = 4)

        assertEquals(listOf("2025-W51", "2025-W52", "2026-W01", "2026-W02"), barras.map { it.clave })
        assertEquals(listOf(0.0, 424.01, 0.0, 0.0), barras.map { it.neto })
    }

    @Test
    fun losUltimosMesesYAniosLleganHastaElDeLaSemanaElegida() {
        val semanas = CalculoComisiones.calcularResumenHistoricoGlobal(socios(10, "2025-W48")).semanasHistoricas
        val meses = CalculoComisiones.agruparPorPeriodo(semanas, CalculoComisiones.Periodo.MES)
        val anios = CalculoComisiones.agruparPorPeriodo(semanas, CalculoComisiones.Periodo.ANIO)

        // La semana 2026-W01 tiene su jueves el 1 de enero de 2026.
        val porMes = CalculoComisiones.ultimosPeriodos(meses, CalculoComisiones.Periodo.MES, "2026-W01", cantidad = 3)
        assertEquals(listOf("2025-11", "2025-12", "2026-01"), porMes.map { it.clave })
        assertEquals(listOf(424.01, 0.0, 0.0), porMes.map { it.neto })

        val porAnio = CalculoComisiones.ultimosPeriodos(anios, CalculoComisiones.Periodo.ANIO, "2026-W01")
        assertEquals(listOf("2025", "2026"), porAnio.map { it.clave })
    }

    @Test
    fun redondeoMitadHaciaArriba() {
        assertEquals(0.13, CalculoComisiones.redondearCentimos(0.125), 0.0)
        assertEquals(2.68, CalculoComisiones.redondearCentimos(2.675), 0.0)
        assertEquals(115.99, CalculoComisiones.redondearCentimos(115.992), 0.0)
    }
}
