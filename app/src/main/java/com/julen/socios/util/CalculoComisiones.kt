package com.julen.socios.util

import com.julen.socios.model.BonoRegaloInfo
import com.julen.socios.model.CobroInfo
import com.julen.socios.model.Socio
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

object CalculoComisiones {
    /** Retención de IRPF: la misma en todas las semanas. */
    const val IRPF = 21.48

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

    /** Socios con los que empieza cada tramo del bonus, de los escalones de [calcularBonusSemanal]: 5, 7, 10, 13, 16… */
    fun tramosBonus(): Sequence<Int> = sequenceOf(5, 7) + generateSequence(10) { it + 3 }

    /**
     * Retorna el número de socios objetivo para el siguiente escalón de bonus y el importe de ese bonus.
     */
    fun obtenerSiguienteMetaBonus(numSociosHechos: Int): Pair<Int, Double> {
        val meta = tramosBonus().first { it > numSociosHechos }
        return meta to calcularBonusSemanal(meta)
    }

    /** Socios del tramo anterior a la meta [meta] (0 antes del primero): 7 → 5, 10 → 7, 13 → 10… */
    fun tramoAnterior(meta: Int): Int = tramosBonus().takeWhile { it < meta }.lastOrNull() ?: 0

    /** Redondea un importe a céntimos (mitad hacia arriba), como en una nómina. */
    fun redondearCentimos(importe: Double): Double =
        BigDecimal.valueOf(importe).setScale(2, RoundingMode.HALF_UP).toDouble()

    /**
     * Cuentas de una semana:
     *
     *     base (cuotas × 2) + bonus = total bruto
     *     total bruto − IRPF        = neto calculado
     *
     * Si se ha apuntado lo cobrado de verdad, el neto de la semana es ese y lo que falta hasta el calculado
     * es lo descontado (devoluciones u otros descuentos de la empresa). No se supone nada sobre cómo se
     * reparte ese descuento entre bruto e IRPF: el bruto y el IRPF que se muestran son los calculados.
     */
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
        val netoCalculado: Double,          // Bruto - Retención IRPF: lo que se debería cobrar
        val netoCobrado: Double?,           // Lo cobrado de verdad, si se ha apuntado
        val totalNeto: Double,              // Lo cobrado si se ha apuntado; si no, el calculado
        val siguienteMetaSocios: Int,
        val siguienteMetaBonus: Double,
        val sociosFaltantesParaSiguienteMeta: Int
    ) {
        val tieneCobroReal: Boolean get() = netoCobrado != null

        /** Lo que falta de lo calculado: positivo si te han descontado, negativo si has cobrado de más. */
        val descontado: Double get() = netoCobrado?.let { redondearCentimos(netoCalculado - it) } ?: 0.0
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
        val totalDescontadoAcumulado: Double,
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
        val bruto: Double,
        val descontado: Double, // Lo que se cobró de menos (o de más, en negativo) respecto a lo calculado
        val irpf: Double,
        val neto: Double
    ) {
        /** "2026-10" para un mes, "2026" para un año. */
        val clave: String get() = mes?.let { claveMes(anio, it) } ?: anio.toString()
    }

    private fun claveMes(anio: Int, mes: Int) = String.format(Locale.ROOT, "%04d-%02d", anio, mes)

    /** El jueves de la semana [semanaKey], que decide a qué mes y año pertenece (criterio ISO-8601). */
    private fun juevesDe(semanaKey: String) = DateUtils.lunesDeSemanaKey(semanaKey)?.plusDays(3)

    /**
     * Agrupa las semanas por mes o por año. Una semana que cruza de mes o de año cuenta en el de su
     * jueves (criterio ISO-8601, el mismo de las claves de semana), así ninguna se cuenta dos veces.
     */
    fun agruparPorPeriodo(semanas: List<SemanaHistoricaItem>, periodo: Periodo): List<ResumenPeriodo> =
        semanas
            .mapNotNull { item -> juevesDe(item.semanaKey)?.let { it to item } }
            .groupBy { (jueves, _) -> jueves.year to (if (periodo == Periodo.MES) jueves.monthValue else null) }
            .map { (clave, items) ->
                val resumenes = items.map { it.second.resumenSemana }
                ResumenPeriodo(
                    anio = clave.first,
                    mes = clave.second,
                    sociosHechos = resumenes.sumOf { it.totalSociosHechos },
                    bruto = redondearCentimos(resumenes.sumOf { it.totalBruto }),
                    descontado = redondearCentimos(resumenes.sumOf { it.descontado }),
                    irpf = redondearCentimos(resumenes.sumOf { it.retencionIrpf }),
                    neto = redondearCentimos(resumenes.sumOf { it.totalNeto })
                )
            }
            .sortedWith(compareByDescending<ResumenPeriodo> { it.anio }.thenByDescending { it.mes ?: 0 })

    fun calcularResumenSemana(
        socios: List<Socio>,
        bonoRegalo: BonoRegaloInfo? = null,
        porcentajeIrpf: Double = IRPF,
        cobro: CobroInfo? = null
    ): ResumenCalculo {
        val hechos = socios.filter { it.hecho }
        val countHechos = hechos.size

        val sumaCuotas = redondearCentimos(hechos.sumOf { it.colaboracion })
        val baseX2 = redondearCentimos(calcularBaseX2(sumaCuotas))
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
        val cobrado = cobro?.netoCobrado?.let(::redondearCentimos)

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
            netoCalculado = neto,
            netoCobrado = cobrado,
            totalNeto = cobrado ?: neto,
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
        cobrosMap: Map<String, CobroInfo> = emptyMap()
    ): ResumenHistoricoGlobal {
        val sociosPorSemana = todosLosSocios.groupBy { it.semanaKey }

        // Todas las semanas con socios, con un bono activo o con lo cobrado apuntado: una semana sin socios
        // en la que se cobró algo (o se descontó una devolución) también cuenta.
        val todasLasSemanas = (sociosPorSemana.keys + bonosRegaloMap.filterValues { it.activo }.keys + cobrosMap.keys)
            .distinct()
            .sortedDescending()

        val itemsSemanas = todasLasSemanas.map { semanaKey ->
            SemanaHistoricaItem(
                semanaKey = semanaKey,
                resumenSemana = calcularResumenSemana(
                    socios = sociosPorSemana[semanaKey].orEmpty(),
                    bonoRegalo = bonosRegaloMap[semanaKey],
                    cobro = cobrosMap[semanaKey]
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
            totalDescontadoAcumulado = redondearCentimos(resumenes.sumOf { it.descontado }),
            totalSociosHechosAcumulado = resumenes.sumOf { it.totalSociosHechos },
            totalSociosRegistradosAcumulado = resumenes.sumOf { it.totalSociosRegistrados },
            semanasHistoricas = itemsSemanas
        )
    }

    // --- Mejor semana y evolución ---

    /** La semana con más neto, o null si ninguna ha dado neto. */
    fun mejorSemana(semanas: List<SemanaHistoricaItem>): SemanaHistoricaItem? =
        semanas.maxByOrNull { it.resumenSemana.totalNeto }?.takeIf { it.resumenSemana.totalNeto > 0.0 }

    /** Una barra del gráfico de evolución: una semana ("2026-W42"), un mes ("2026-10") o un año ("2026"). */
    data class Barra(val clave: String, val neto: Double)

    /** Diez años de semanas: el gráfico se desplaza, pero no crece sin límite si hay una fecha absurda. */
    const val MAX_BARRAS = 520

    /**
     * Todas las semanas del gráfico, de la primera con algo registrado hasta [hasta], seguidas; al menos las
     * [minimo] últimas, para que con poco historial no queden cuatro barras sueltas.
     */
    fun evolucionSemanas(semanas: List<SemanaHistoricaItem>, hasta: String, minimo: Int = 12): List<Barra> {
        val desdeLaPrimera = semanas.minOfOrNull { it.semanaKey }?.let { DateUtils.semanasEntre(it, hasta) }?.plus(1) ?: 0
        return ultimasSemanas(semanas, hasta, desdeLaPrimera.coerceIn(minimo, MAX_BARRAS))
    }

    /** Como [evolucionSemanas], con todos los meses o años desde el primero con datos hasta el de [hasta]. */
    fun evolucionPeriodos(periodos: List<ResumenPeriodo>, periodo: Periodo, hasta: String, minimo: Int = 12): List<Barra> {
        val jueves = juevesDe(hasta) ?: return emptyList()
        val desdeElPrimero = when (periodo) {
            Periodo.MES -> periodos.minOfOrNull { it.anio * 12 + (it.mes ?: 1) }?.let { jueves.year * 12 + jueves.monthValue - it + 1 }
            Periodo.ANIO -> periodos.minOfOrNull { it.anio }?.let { jueves.year - it + 1 }
        } ?: 0
        return ultimosPeriodos(periodos, periodo, hasta, desdeElPrimero.coerceIn(minimo, MAX_BARRAS))
    }

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
        val jueves = juevesDe(hasta) ?: return emptyList()
        val netos = periodos.associate { it.clave to it.neto }
        return when (periodo) {
            Periodo.MES -> (cantidad - 1 downTo 0).map { atras ->
                val mes = jueves.withDayOfMonth(1).minusMonths(atras.toLong())
                val clave = claveMes(mes.year, mes.monthValue)
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
