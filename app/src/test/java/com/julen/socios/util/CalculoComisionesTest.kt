package com.julen.socios.util

import com.julen.socios.model.BonoRegaloInfo
import com.julen.socios.model.DiaSemana
import com.julen.socios.model.IrpfVigencia
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
        assertEquals(CalculoComisiones.IRPF_POR_DEFECTO, resumen.porcentajeIrpf, 0.0)
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
    fun cadaSemanaUsaElIrpfVigenteEnEsaSemana() {
        val vigencias = listOf(IrpfVigencia("2026-W10", 15.0), IrpfVigencia("2026-W20", 19.0))

        assertEquals(CalculoComisiones.IRPF_POR_DEFECTO, CalculoComisiones.irpfParaSemana("2026-W09", vigencias), 0.0)
        assertEquals(15.0, CalculoComisiones.irpfParaSemana("2026-W10", vigencias), 0.0)
        assertEquals(15.0, CalculoComisiones.irpfParaSemana("2026-W19", vigencias), 0.0)
        assertEquals(19.0, CalculoComisiones.irpfParaSemana("2026-W20", vigencias), 0.0)
        assertEquals(19.0, CalculoComisiones.irpfParaSemana("2027-W01", vigencias), 0.0)

        val global = CalculoComisiones.calcularResumenHistoricoGlobal(
            socios(10, "2026-W09") + socios(10, "2026-W10"), irpfVigencias = vigencias
        )
        // 540 × 21,48 % = 115,99 y 540 × 15 % = 81,00
        assertEquals(196.99, global.totalIrpfRetenidoAcumulado, 0.0)
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
        assertEquals(2, enero.semanas)
        assertEquals(15, enero.sociosHechos)
        assertEquals(190.0, enero.bonus, 0.0) // 50 + 140, cada semana con su tramo
        assertEquals(790.0, enero.bruto, 0.0)
        assertEquals(53.70 + 115.99, enero.irpf, 0.001)

        val anios = CalculoComisiones.agruparPorPeriodo(historico.semanasHistoricas, CalculoComisiones.Periodo.ANIO)
        assertEquals(listOf(2026, 2025), anios.map { it.anio })
        assertEquals(listOf(null, null), anios.map { it.mes })
        assertEquals(3, anios[0].semanas)
        assertEquals(historico.totalBrutoAcumulado, anios.sumOf { it.bruto }, 0.001)
    }

    @Test
    fun redondeoMitadHaciaArriba() {
        assertEquals(0.13, CalculoComisiones.redondearCentimos(0.125), 0.0)
        assertEquals(2.68, CalculoComisiones.redondearCentimos(2.675), 0.0)
        assertEquals(115.99, CalculoComisiones.redondearCentimos(115.992), 0.0)
    }
}
