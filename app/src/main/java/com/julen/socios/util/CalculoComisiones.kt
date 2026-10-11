package com.julen.socios.util

import com.julen.socios.model.BonoRegaloInfo
import com.julen.socios.model.IrpfVigencia
import com.julen.socios.model.Socio
import java.math.BigDecimal
import java.math.RoundingMode

object CalculoComisiones {
    /** Retención aplicada a las semanas anteriores a cualquier vigencia configurada. */
    const val IRPF_POR_DEFECTO = 21.48

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

    /** Porcentaje de IRPF vigente en [semanaKey]: el de la vigencia más reciente que empiece en o antes de esa semana. */
    fun irpfParaSemana(semanaKey: String, vigencias: List<IrpfVigencia>): Double =
        vigencias.filter { it.desdeSemanaKey <= semanaKey }
            .maxByOrNull { it.desdeSemanaKey }
            ?.porcentaje ?: IRPF_POR_DEFECTO

    /** Redondea un importe a céntimos (mitad hacia arriba), como en una nómina. */
    fun redondearCentimos(importe: Double): Double =
        BigDecimal.valueOf(importe).setScale(2, RoundingMode.HALF_UP).toDouble()

    data class ResumenCalculo(
        val totalSociosRegistrados: Int,
        val totalSociosHechos: Int,
        val sumaColaboracionesBase: Double, // Suma de cuotas
        val gananciasBaseX2: Double,        // Suma de cuotas x2
        val bonusSemanal: Double,           // Bonus total acumulado (automático o ajustado)
        val bonusAutomatico: Double,        // Bonus que correspondería sin ajuste manual
        val esBonoRegaloAplicado: Boolean,
        val totalBruto: Double,             // Base x2 + Bonus
        val porcentajeIrpf: Double,         // Porcentaje de IRPF aplicado a la semana
        val retencionIrpf: Double,          // Bruto * porcentaje, redondeado a céntimos
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
        val totalBaseX2Acumulado: Double,
        val totalBonusAcumulado: Double,
        val totalSociosHechosAcumulado: Int,
        val totalSociosRegistradosAcumulado: Int,
        val semanasHistoricas: List<SemanaHistoricaItem>
    )

    enum class Periodo { MES, ANIO }

    /** Totales de un mes ([mes] 1-12) o de un año completo ([mes] null). */
    data class ResumenPeriodo(
        val anio: Int,
        val mes: Int?,
        val semanas: Int,
        val sociosHechos: Int,
        val bonus: Double,
        val bruto: Double,
        val irpf: Double,
        val neto: Double
    )

    /**
     * Agrupa las semanas por mes o por año. Una semana que cruza de mes o de año cuenta en el de su
     * jueves (criterio ISO-8601, el mismo de las claves de semana), así ninguna se cuenta dos veces.
     */
    fun agruparPorPeriodo(semanas: List<SemanaHistoricaItem>, periodo: Periodo): List<ResumenPeriodo> =
        semanas
            .mapNotNull { item -> DateUtils.lunesDeSemanaKey(item.semanaKey)?.plusDays(3)?.let { it to item } }
            .groupBy { (jueves, _) -> jueves.year to (if (periodo == Periodo.MES) jueves.monthValue else null) }
            .map { (clave, items) ->
                val resumenes = items.map { it.second.resumenSemana }
                ResumenPeriodo(
                    anio = clave.first,
                    mes = clave.second,
                    semanas = resumenes.size,
                    sociosHechos = resumenes.sumOf { it.totalSociosHechos },
                    bonus = redondearCentimos(resumenes.sumOf { it.bonusSemanal }),
                    bruto = redondearCentimos(resumenes.sumOf { it.totalBruto }),
                    irpf = redondearCentimos(resumenes.sumOf { it.retencionIrpf }),
                    neto = redondearCentimos(resumenes.sumOf { it.totalNeto })
                )
            }
            .sortedWith(compareByDescending<ResumenPeriodo> { it.anio }.thenByDescending { it.mes ?: 0 })

    fun calcularResumenSemana(
        socios: List<Socio>,
        bonoRegalo: BonoRegaloInfo? = null,
        porcentajeIrpf: Double = IRPF_POR_DEFECTO
    ): ResumenCalculo {
        val hechos = socios.filter { it.hecho }
        val countHechos = hechos.size

        val sumaCuotas = redondearCentimos(hechos.sumOf { it.colaboracion })
        val baseX2 = redondearCentimos(sumaCuotas * 2.0)
        val bonusAuto = calcularBonusSemanal(countHechos)

        val bonoAplicado = bonoRegalo?.takeIf { it.activo }
        val bonusFinal = when {
            bonoAplicado == null -> bonusAuto
            bonoAplicado.esMontoFijo -> bonoAplicado.monto
            else -> bonusAuto + bonoAplicado.monto
        }

        val bruto = redondearCentimos(baseX2 + bonusFinal)
        val irpf = redondearCentimos(bruto * porcentajeIrpf / 100.0)
        val neto = redondearCentimos(bruto - irpf)

        val (siguienteMeta, siguienteBonus) = obtenerSiguienteMetaBonus(countHechos)

        return ResumenCalculo(
            totalSociosRegistrados = socios.size,
            totalSociosHechos = countHechos,
            sumaColaboracionesBase = sumaCuotas,
            gananciasBaseX2 = baseX2,
            bonusSemanal = bonusFinal,
            bonusAutomatico = bonusAuto,
            esBonoRegaloAplicado = bonoAplicado != null,
            totalBruto = bruto,
            porcentajeIrpf = porcentajeIrpf,
            retencionIrpf = irpf,
            totalNeto = neto,
            siguienteMetaSocios = siguienteMeta,
            siguienteMetaBonus = siguienteBonus,
            sociosFaltantesParaSiguienteMeta = (siguienteMeta - countHechos).coerceAtLeast(0)
        )
    }

    /**
     * Calcula el total acumulado de IRPF retenido, Neto ganado y Bonus en TODAS las semanas recibidas.
     * Cada semana se calcula por separado (su propio tramo de bonus, bono manual e IRPF) y después se suma.
     */
    fun calcularResumenHistoricoGlobal(
        todosLosSocios: List<Socio>,
        bonosRegaloMap: Map<String, BonoRegaloInfo> = emptyMap(),
        irpfVigencias: List<IrpfVigencia> = emptyList()
    ): ResumenHistoricoGlobal {
        val sociosPorSemana = todosLosSocios.groupBy { it.semanaKey }

        // Combinar todas las semanas presentes en socios O con un bono activo
        val todasLasSemanas = (sociosPorSemana.keys + bonosRegaloMap.filterValues { it.activo }.keys)
            .distinct()
            .sortedDescending()

        val itemsSemanas = todasLasSemanas.map { semanaKey ->
            SemanaHistoricaItem(
                semanaKey = semanaKey,
                resumenSemana = calcularResumenSemana(
                    socios = sociosPorSemana[semanaKey].orEmpty(),
                    bonoRegalo = bonosRegaloMap[semanaKey],
                    porcentajeIrpf = irpfParaSemana(semanaKey, irpfVigencias)
                )
            )
        }
        val resumenes = itemsSemanas.map { it.resumenSemana }

        return ResumenHistoricoGlobal(
            totalIrpfRetenidoAcumulado = redondearCentimos(resumenes.sumOf { it.retencionIrpf }),
            totalNetoAcumulado = redondearCentimos(resumenes.sumOf { it.totalNeto }),
            totalBrutoAcumulado = redondearCentimos(resumenes.sumOf { it.totalBruto }),
            totalBaseX2Acumulado = redondearCentimos(resumenes.sumOf { it.gananciasBaseX2 }),
            totalBonusAcumulado = redondearCentimos(resumenes.sumOf { it.bonusSemanal }),
            totalSociosHechosAcumulado = resumenes.sumOf { it.totalSociosHechos },
            totalSociosRegistradosAcumulado = resumenes.sumOf { it.totalSociosRegistrados },
            semanasHistoricas = itemsSemanas
        )
    }
}
