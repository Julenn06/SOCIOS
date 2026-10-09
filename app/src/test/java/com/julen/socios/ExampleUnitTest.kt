package com.julen.socios

import com.julen.socios.model.BonoRegaloInfo
import com.julen.socios.model.Socio
import com.julen.socios.util.CalculoComisiones
import com.julen.socios.util.DateUtils
import com.julen.socios.util.toCurrencyString
import com.julen.socios.util.toFormattedEuros
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun testBaseX2Calculation() {
        assertEquals(40.0, CalculoComisiones.calcularBaseX2(20.0), 0.001)
        assertEquals(60.0, CalculoComisiones.calcularBaseX2(30.0), 0.001)
        assertEquals(30.0, CalculoComisiones.calcularBaseX2(15.0), 0.001)
    }

    @Test
    fun testBonusLevels() {
        // < 5
        assertEquals(0.0, CalculoComisiones.calcularBonusSemanal(0), 0.001)
        assertEquals(0.0, CalculoComisiones.calcularBonusSemanal(4), 0.001)

        // 5 - 6 -> 50€
        assertEquals(50.0, CalculoComisiones.calcularBonusSemanal(5), 0.001)
        assertEquals(50.0, CalculoComisiones.calcularBonusSemanal(6), 0.001)

        // 7 - 9 -> 100€
        assertEquals(100.0, CalculoComisiones.calcularBonusSemanal(7), 0.001)
        assertEquals(100.0, CalculoComisiones.calcularBonusSemanal(9), 0.001)

        // 10 -> 140€
        assertEquals(140.0, CalculoComisiones.calcularBonusSemanal(10), 0.001)
        assertEquals(140.0, CalculoComisiones.calcularBonusSemanal(12), 0.001)

        // 13 -> 140 + 50 = 190€
        assertEquals(190.0, CalculoComisiones.calcularBonusSemanal(13), 0.001)
        assertEquals(190.0, CalculoComisiones.calcularBonusSemanal(15), 0.001)

        // 16 -> 140 + 100 = 240€
        assertEquals(240.0, CalculoComisiones.calcularBonusSemanal(16), 0.001)
    }

    @Test
    fun testIrpfDeductionAndNetTotal() {
        // 10 socios de 20€ hechos
        val socios = List(10) {
            Socio(colaboracion = 20.0, hecho = true, diaSemanaId = 1, semanaKey = "2025-W09")
        }

        val resumen = CalculoComisiones.calcularResumenSemana(socios)

        // Base: 10 * (20 * 2) = 400€
        assertEquals(400.0, resumen.gananciasBaseX2, 0.001)

        // Bonus: 140€
        assertEquals(140.0, resumen.bonusSemanal, 0.001)

        // Total Bruto: 540€
        assertEquals(540.0, resumen.totalBruto, 0.001)

        // IRPF (21.48% de 540€): 115.992€
        assertEquals(115.992, resumen.retencionIrpf, 0.001)

        // Total Neto (540 - 115.992): 424.008€
        assertEquals(424.008, resumen.totalNeto, 0.001)
    }

    @Test
    fun testGlobalHistoricalSummary() {
        val week1 = "2025-W08"
        val week2 = "2025-W09"

        val socios = listOf(
            // Week 1: 5 socios de 20€ hechos
            Socio(colaboracion = 20.0, hecho = true, diaSemanaId = 1, semanaKey = week1),
            Socio(colaboracion = 20.0, hecho = true, diaSemanaId = 2, semanaKey = week1),
            Socio(colaboracion = 20.0, hecho = true, diaSemanaId = 3, semanaKey = week1),
            Socio(colaboracion = 20.0, hecho = true, diaSemanaId = 4, semanaKey = week1),
            Socio(colaboracion = 20.0, hecho = true, diaSemanaId = 5, semanaKey = week1),

            // Week 2: 10 socios de 20€ hechos
            Socio(colaboracion = 20.0, hecho = true, diaSemanaId = 1, semanaKey = week2),
            Socio(colaboracion = 20.0, hecho = true, diaSemanaId = 1, semanaKey = week2),
            Socio(colaboracion = 20.0, hecho = true, diaSemanaId = 2, semanaKey = week2),
            Socio(colaboracion = 20.0, hecho = true, diaSemanaId = 2, semanaKey = week2),
            Socio(colaboracion = 20.0, hecho = true, diaSemanaId = 3, semanaKey = week2),
            Socio(colaboracion = 20.0, hecho = true, diaSemanaId = 3, semanaKey = week2),
            Socio(colaboracion = 20.0, hecho = true, diaSemanaId = 4, semanaKey = week2),
            Socio(colaboracion = 20.0, hecho = true, diaSemanaId = 4, semanaKey = week2),
            Socio(colaboracion = 20.0, hecho = true, diaSemanaId = 5, semanaKey = week2),
            Socio(colaboracion = 20.0, hecho = true, diaSemanaId = 5, semanaKey = week2)
        )

        val global = CalculoComisiones.calcularResumenHistoricoGlobal(socios)

        assertEquals(2, global.semanasHistoricas.size)
        assertEquals(15, global.totalSociosHechosAcumulado)
        assertEquals(169.692, global.totalIrpfRetenidoAcumulado, 0.001)
        assertEquals(620.308, global.totalNetoAcumulado, 0.001)
    }

    @Test
    fun testBonoRegaloCalculation() {
        // 8 socios hechos de 20€ -> Normal bonus is 100€
        val socios = List(8) {
            Socio(colaboracion = 20.0, hecho = true, diaSemanaId = 1, semanaKey = "2025-W09")
        }

        // Grant 10-socio bonus level (140€) as gift
        val bonoInfo = BonoRegaloInfo(
            semanaKey = "2025-W09",
            activo = true,
            esMontoFijo = true,
            monto = 140.0,
            nota = "Regalo jefa"
        )

        val resumen = CalculoComisiones.calcularResumenSemana(socios, bonoInfo)

        assertTrue(resumen.esBonoRegaloAplicado)
        assertEquals(140.0, resumen.bonusSemanal, 0.001)
        assertEquals(100.0, resumen.bonusAutomatico, 0.001)
        assertEquals(40.0, resumen.montoBonoRegaloExtra, 0.001)
    }

    @Test
    fun testDateUtilsWeekOffsetCalculation() {
        val currentKey = DateUtils.getSemanaKey(0)
        assertEquals(0, DateUtils.getWeekOffsetFromKey(currentKey))

        val prevKey = DateUtils.getSemanaKey(-2)
        assertEquals(-2, DateUtils.getWeekOffsetFromKey(prevKey))

        val nextKey = DateUtils.getSemanaKey(5)
        assertEquals(5, DateUtils.getWeekOffsetFromKey(nextKey))
    }

    @Test
    fun testNumberExtensionsFormatting() {
        val amount = 123.456
        assertEquals("123,46 €", amount.toFormattedEuros())
        assertEquals("+123,46 €", amount.toCurrencyString(prefix = "+"))
    }
}
