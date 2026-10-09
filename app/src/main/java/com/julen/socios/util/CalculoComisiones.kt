package com.julen.socios.util

import com.julen.socios.model.BonoRegaloInfo
import com.julen.socios.model.Socio

object CalculoComisiones {
    const val IRPF_PORCENTAJE = 0.2148 // 21,48% IRPF

    /**
     * Retorna la ganancia base (colaboración x2).
     */
    fun calcularBaseX2(colaboracion: Double): Double {
        return colaboracion * 2.0
    }

    /**
     * Calcula el bonus acumulado según el número de socios HECHOS en la semana.
     * Escalones:
     * - < 5 socios: 0 €
     * - 5 a 6 socios: +50 €
     * - 7 a 9 socios: +100 €
     * - 10 socios: +140 €
     * - A partir de 10 socios: +140 € + 50 € por cada 3 socios extra (13->190€, 16->240€, 19->290€...)
     */
    fun calcularBonusSemanal(numSociosHechos: Int): Double {
        return when {
            numSociosHechos < 5 -> 0.0
            numSociosHechos in 5..6 -> 50.0
            numSociosHechos in 7..9 -> 100.0
            else -> {
                val tramosExtra = (numSociosHechos - 10) / 3
                140.0 + (tramosExtra * 50.0)
            }
        }
    }

    /**
     * Retorna el número de socios objetivo para el siguiente escalón de bonus y el importe de ese bonus.
     */
    fun obtenerSiguienteMetaBonus(numSociosHechos: Int): Pair<Int, Double> {
        return when {
            numSociosHechos < 5 -> Pair(5, 50.0)
            numSociosHechos < 7 -> Pair(7, 100.0)
            numSociosHechos < 10 -> Pair(10, 140.0)
            else -> {
                val tramosCompletos = (numSociosHechos - 10) / 3
                val siguienteMeta = 10 + (tramosCompletos + 1) * 3
                val siguienteBonus = 140.0 + ((tramosCompletos + 1) * 50.0)
                Pair(siguienteMeta, siguienteBonus)
            }
        }
    }

    data class ResumenCalculo(
        val totalSociosRegistrados: Int,
        val totalSociosHechos: Int,
        val sumaColaboracionesBase: Double, // Suma de cuotas
        val gananciasBaseX2: Double,        // Suma de cuotas x2
        val bonusSemanal: Double,           // Bonus total acumulado (automático o ajustado)
        val bonusAutomatico: Double = 0.0,  // Bonus automático sin ajuste
        val esBonoRegaloAplicado: Boolean = false,
        val montoBonoRegaloExtra: Double = 0.0,
        val bonoRegaloNota: String = "",
        val totalBruto: Double,             // Base x2 + Bonus
        val retencionIrpf: Double,          // Bruto * 0.2148
        val totalNeto: Double,              // Bruto - Retención IRPF
        val siguienteMetaSocios: Int,
        val siguienteMetaBonus: Double,
        val sociosFaltantesParaSiguienteMeta: Int
    )

    data class SemanaHistoricaItem(
        val semanaKey: String, val resumenSemana: ResumenCalculo
    )

    data class ResumenHistoricoGlobal(
        val totalIrpfRetenidoAcumulado: Double,
        val totalNetoAcumulado: Double,
        val totalBrutoAcumulado: Double,
        val totalBonusAcumulado: Double,
        val totalSociosHechosAcumulado: Int,
        val semanasHistoricas: List<SemanaHistoricaItem>
    )

    fun calcularResumenSemana(
        socios: List<Socio>, bonoRegalo: BonoRegaloInfo? = null
    ): ResumenCalculo {
        val hechos = socios.filter { it.hecho }
        val countHechos = hechos.size
        val countTotal = socios.size

        val sumaCuotas = hechos.sumOf { it.colaboracion }
        val baseX2 = sumaCuotas * 2.0
        val bonusAuto = calcularBonusSemanal(countHechos)

        var bonusFinal = bonusAuto
        var esRegaloAplicado = false
        var extraRegalo = 0.0

        if (bonoRegalo != null && bonoRegalo.activo) {
            esRegaloAplicado = true
            bonusFinal = if (bonoRegalo.esMontoFijo) {
                bonoRegalo.monto
            } else {
                bonusAuto + bonoRegalo.monto
            }
            extraRegalo = (bonusFinal - bonusAuto).coerceAtLeast(0.0)
        }

        val bruto = baseX2 + bonusFinal
        val irpf = bruto * IRPF_PORCENTAJE
        val neto = bruto - irpf

        val (siguienteMeta, siguienteBonus) = obtenerSiguienteMetaBonus(countHechos)
        val faltantes = (siguienteMeta - countHechos).coerceAtLeast(0)

        return ResumenCalculo(
            totalSociosRegistrados = countTotal,
            totalSociosHechos = countHechos,
            sumaColaboracionesBase = sumaCuotas,
            gananciasBaseX2 = baseX2,
            bonusSemanal = bonusFinal,
            bonusAutomatico = bonusAuto,
            esBonoRegaloAplicado = esRegaloAplicado,
            montoBonoRegaloExtra = extraRegalo,
            bonoRegaloNota = bonoRegalo?.nota.orEmpty(),
            totalBruto = bruto,
            retencionIrpf = irpf,
            totalNeto = neto,
            siguienteMetaSocios = siguienteMeta,
            siguienteMetaBonus = siguienteBonus,
            sociosFaltantesParaSiguienteMeta = faltantes
        )
    }

    /**
     * Calcula el total acumulado de IRPF retenido, Neto ganado y Bonus en TODAS las semanas registradas.
     */
    fun calcularResumenHistoricoGlobal(
        todosLosSocios: List<Socio>, bonosRegaloMap: Map<String, BonoRegaloInfo> = emptyMap()
    ): ResumenHistoricoGlobal {
        val sociosPorSemana = todosLosSocios.groupBy { it.semanaKey }

        var totalIrpf = 0.0
        var totalNeto = 0.0
        var totalBruto = 0.0
        var totalBonus = 0.0
        var totalSociosHechos = 0

        val itemsSemanas = mutableListOf<SemanaHistoricaItem>()

        // Combinar todas las semanas presentes en socios O en bonosRegaloMap
        val todasLasSemanas =
            (sociosPorSemana.keys + bonosRegaloMap.keys).distinct().sortedDescending()

        for (semanaKey in todasLasSemanas) {
            val sociosDeSemana = sociosPorSemana[semanaKey] ?: emptyList()
            val bonoInfo = bonosRegaloMap[semanaKey]
            val resumenSemana = calcularResumenSemana(sociosDeSemana, bonoInfo)

            totalIrpf += resumenSemana.retencionIrpf
            totalNeto += resumenSemana.totalNeto
            totalBruto += resumenSemana.totalBruto
            totalBonus += resumenSemana.bonusSemanal
            totalSociosHechos += resumenSemana.totalSociosHechos

            itemsSemanas.add(
                SemanaHistoricaItem(
                    semanaKey = semanaKey, resumenSemana = resumenSemana
                )
            )
        }

        return ResumenHistoricoGlobal(
            totalIrpfRetenidoAcumulado = totalIrpf,
            totalNetoAcumulado = totalNeto,
            totalBrutoAcumulado = totalBruto,
            totalBonusAcumulado = totalBonus,
            totalSociosHechosAcumulado = totalSociosHechos,
            semanasHistoricas = itemsSemanas
        )
    }
}
