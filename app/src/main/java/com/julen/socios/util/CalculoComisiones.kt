package com.julen.socios.util

import com.julen.socios.model.BonoRegaloInfo
import com.julen.socios.model.DevolucionInfo
import com.julen.socios.model.IrpfVigencia
import com.julen.socios.model.Socio
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

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

    /** Socios del tramo anterior a la meta [meta] (0 antes del primero): 7 → 5, 10 → 7, 13 → 10… */
    fun tramoAnterior(meta: Int): Int = when {
        meta <= 5 -> 0
        meta <= 7 -> 5
        meta <= 10 -> 7
        else -> meta - 3
    }

    /** Porcentaje de IRPF vigente en [semanaKey]: el de la vigencia más reciente que empiece en o antes de esa semana. */
    fun irpfParaSemana(semanaKey: String, vigencias: List<IrpfVigencia>): Double =
        vigencias.filter { it.desdeSemanaKey <= semanaKey }
            .maxByOrNull { it.desdeSemanaKey }
            ?.porcentaje ?: IRPF_POR_DEFECTO

    /** Redondea un importe a céntimos (mitad hacia arriba), como en una nómina. */
    fun redondearCentimos(importe: Double): Double =
        BigDecimal.valueOf(importe).setScale(2, RoundingMode.HALF_UP).toDouble()

    /**
     * Cuentas de una semana:
     *
     *     base (cuotas × 2) + bonus           = bruto antes de devoluciones
     *     − devoluciones                      = total bruto (lo que paga la empresa)
     *     − IRPF (porcentaje del total bruto) = neto
     *
     * Una devolución anula una comisión ya cobrada: la empresa la resta del pago, así que también deja de
     * retenerse IRPF por ella. Si las devoluciones superan lo ganado, el bruto queda en negativo (se debe
     * dinero) y no se retiene nada, porque no hay ingreso sobre el que retener.
     */
    data class ResumenCalculo(
        val totalSociosRegistrados: Int,
        val totalSociosHechos: Int,
        val sumaColaboracionesBase: Double, // Suma de cuotas
        val gananciasBaseX2: Double,        // Suma de cuotas x2
        val bonusSemanal: Double,           // Bonus total acumulado (automático o ajustado)
        val bonusAutomatico: Double,        // Bonus que correspondería sin ajuste manual
        val esBonoRegaloAplicado: Boolean,
        val brutoAntesDevoluciones: Double, // Base x2 + Bonus
        val devoluciones: Double,           // Descontado esta semana por cuotas devueltas (positivo)
        val totalBruto: Double,             // Base x2 + Bonus - Devoluciones: sobre lo que se retiene
        val porcentajeIrpf: Double,         // Porcentaje de IRPF aplicado a la semana
        val retencionIrpf: Double,          // Bruto * porcentaje, redondeado a céntimos (0 si el bruto es negativo)
        val totalNeto: Double,              // Bruto - Retención IRPF
        val siguienteMetaSocios: Int,
        val siguienteMetaBonus: Double,
        val sociosFaltantesParaSiguienteMeta: Int
    ) {
        val tieneDevoluciones: Boolean get() = devoluciones > 0.0
    }

    data class SemanaHistoricaItem(
        val semanaKey: String, val resumenSemana: ResumenCalculo
    )

    data class ResumenHistoricoGlobal(
        val totalIrpfRetenidoAcumulado: Double,
        val totalNetoAcumulado: Double,
        val totalBrutoAcumulado: Double,
        val totalBaseX2Acumulado: Double,
        val totalBonusAcumulado: Double,
        val totalDevolucionesAcumulado: Double,
        val totalSociosHechosAcumulado: Int,
        val totalSociosRegistradosAcumulado: Int,
        val semanasHistoricas: List<SemanaHistoricaItem>
    )

    enum class Periodo { MES, ANIO }

    /** Totales de un mes ([mes] 1-12) o de un año completo ([mes] null). */
    data class ResumenPeriodo(
        val anio: Int,
        val mes: Int?,
        val sociosHechos: Int,
        val bruto: Double, // Ya descontadas las devoluciones, como el de cada semana
        val devoluciones: Double,
        val irpf: Double,
        val neto: Double
    ) {
        /** "2026-10" para un mes, "2026" para un año. */
        val clave: String get() = mes?.let { String.format(Locale.ROOT, "%04d-%02d", anio, it) } ?: anio.toString()
    }

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
                    sociosHechos = resumenes.sumOf { it.totalSociosHechos },
                    bruto = redondearCentimos(resumenes.sumOf { it.totalBruto }),
                    devoluciones = redondearCentimos(resumenes.sumOf { it.devoluciones }),
                    irpf = redondearCentimos(resumenes.sumOf { it.retencionIrpf }),
                    neto = redondearCentimos(resumenes.sumOf { it.totalNeto })
                )
            }
            .sortedWith(compareByDescending<ResumenPeriodo> { it.anio }.thenByDescending { it.mes ?: 0 })

    fun calcularResumenSemana(
        socios: List<Socio>,
        bonoRegalo: BonoRegaloInfo? = null,
        porcentajeIrpf: Double = IRPF_POR_DEFECTO,
        devolucion: DevolucionInfo? = null
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

        val brutoAntes = redondearCentimos(baseX2 + bonusFinal)
        val devoluciones = redondearCentimos(devolucion?.monto?.coerceAtLeast(0.0) ?: 0.0)
        val bruto = redondearCentimos(brutoAntes - devoluciones)
        val irpf = redondearCentimos(bruto.coerceAtLeast(0.0) * porcentajeIrpf / 100.0)
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
            brutoAntesDevoluciones = brutoAntes,
            devoluciones = devoluciones,
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
        irpfVigencias: List<IrpfVigencia> = emptyList(),
        devolucionesMap: Map<String, DevolucionInfo> = emptyMap()
    ): ResumenHistoricoGlobal {
        val sociosPorSemana = todosLosSocios.groupBy { it.semanaKey }

        // Todas las semanas con socios, con un bono activo o con devoluciones: una semana sin socios en la
        // que te descuentan una devolución también cuenta (su neto es negativo).
        val todasLasSemanas = (
            sociosPorSemana.keys +
                bonosRegaloMap.filterValues { it.activo }.keys +
                devolucionesMap.filterValues { it.monto > 0.0 }.keys
            )
            .distinct()
            .sortedDescending()

        val itemsSemanas = todasLasSemanas.map { semanaKey ->
            SemanaHistoricaItem(
                semanaKey = semanaKey,
                resumenSemana = calcularResumenSemana(
                    socios = sociosPorSemana[semanaKey].orEmpty(),
                    bonoRegalo = bonosRegaloMap[semanaKey],
                    porcentajeIrpf = irpfParaSemana(semanaKey, irpfVigencias),
                    devolucion = devolucionesMap[semanaKey]
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
            totalDevolucionesAcumulado = redondearCentimos(resumenes.sumOf { it.devoluciones }),
            totalSociosHechosAcumulado = resumenes.sumOf { it.totalSociosHechos },
            totalSociosRegistradosAcumulado = resumenes.sumOf { it.totalSociosRegistrados },
            semanasHistoricas = itemsSemanas
        )
    }

    // --- Comparativa y evolución ---

    /** Cómo va una semana frente a la anterior, a la media del resto y a la mejor. */
    data class Comparativa(
        val semanaKey: String,
        val neto: Double,
        /** Neto de la semana inmediatamente anterior, o null si esa semana no tiene nada registrado. */
        val netoSemanaAnterior: Double?,
        /** Media del neto de las demás semanas registradas, o null si no hay ninguna más. */
        val mediaNeto: Double?,
        val semanasEnMedia: Int,
        /** Semana con más neto (puede ser la propia [semanaKey]), o null si no hay ninguna. */
        val mejorSemana: SemanaHistoricaItem?
    ) {
        val diferenciaSemanaAnterior: Double? get() = netoSemanaAnterior?.let { redondearCentimos(neto - it) }
        val diferenciaMedia: Double? get() = mediaNeto?.let { redondearCentimos(neto - it) }
        val esLaMejor: Boolean get() = mejorSemana?.semanaKey == semanaKey
    }

    fun comparar(semanaKey: String, semanas: List<SemanaHistoricaItem>): Comparativa {
        val porClave = semanas.associateBy { it.semanaKey }
        val resto = semanas.filter { it.semanaKey != semanaKey }
        val anterior = DateUtils.semanaKeyDesplazada(semanaKey, -1)?.let(porClave::get)
        return Comparativa(
            semanaKey = semanaKey,
            neto = porClave[semanaKey]?.resumenSemana?.totalNeto ?: 0.0,
            netoSemanaAnterior = anterior?.resumenSemana?.totalNeto,
            mediaNeto = resto.takeIf { it.isNotEmpty() }
                ?.let { redondearCentimos(it.sumOf { s -> s.resumenSemana.totalNeto } / it.size) },
            semanasEnMedia = resto.size,
            mejorSemana = semanas.maxByOrNull { it.resumenSemana.totalNeto }?.takeIf { it.resumenSemana.totalNeto > 0.0 }
        )
    }

    /** Una barra del gráfico de evolución: una semana ("2026-W42"), un mes ("2026-10") o un año ("2026"). */
    data class Barra(val clave: String, val neto: Double)

    /**
     * Las [cantidad] semanas que terminan en [hasta], seguidas y en orden: las que no tienen nada registrado
     * van con 0 €, para que el gráfico no junte semanas que no lo están.
     */
    fun ultimasSemanas(semanas: List<SemanaHistoricaItem>, hasta: String, cantidad: Int = 12): List<Barra> {
        val netos = semanas.associate { it.semanaKey to it.resumenSemana.totalNeto }
        return (cantidad - 1 downTo 0).mapNotNull { atras ->
            DateUtils.semanaKeyDesplazada(hasta, -atras)?.let { Barra(it, netos[it] ?: 0.0) }
        }
    }

    /**
     * Los últimos [cantidad] meses, o años, hasta el de la semana [hasta] (por su jueves, como al agrupar),
     * seguidos y en orden, con 0 € en los que no hay nada. Los años empiezan en el primero con datos.
     */
    fun ultimosPeriodos(
        periodos: List<ResumenPeriodo>,
        periodo: Periodo,
        hasta: String,
        cantidad: Int = 12
    ): List<Barra> {
        val jueves = DateUtils.lunesDeSemanaKey(hasta)?.plusDays(3) ?: return emptyList()
        val netos = periodos.associate { it.clave to it.neto }
        return when (periodo) {
            Periodo.MES -> (cantidad - 1 downTo 0).map { atras ->
                val mes = jueves.withDayOfMonth(1).minusMonths(atras.toLong())
                val clave = String.format(Locale.ROOT, "%04d-%02d", mes.year, mes.monthValue)
                Barra(clave, netos[clave] ?: 0.0)
            }

            Periodo.ANIO -> {
                val primero = (periodos.minOfOrNull { it.anio } ?: jueves.year).coerceAtMost(jueves.year)
                (maxOf(primero, jueves.year - cantidad + 1)..jueves.year).map { anio ->
                    Barra(anio.toString(), netos[anio.toString()] ?: 0.0)
                }
            }
        }
    }
}
