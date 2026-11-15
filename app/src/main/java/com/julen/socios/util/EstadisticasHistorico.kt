package com.julen.socios.util

import com.julen.socios.model.DiaSemana
import com.julen.socios.model.Socio
import com.julen.socios.util.CalculoComisiones.Periodo
import com.julen.socios.util.CalculoComisiones.SemanaHistoricaItem

/**
 * Datos del histórico para ver cómo se trabaja: qué día rinde más, la cuota media, cuántos de los apuntados se
 * acaban haciendo y lo que se descuenta al mes. Cada uno es null si aún no hay datos con los que calcularlo.
 */
object EstadisticasHistorico {

    /** Semanas que cuentan como "últimamente" para la cuota media: unos dos meses. */
    const val SEMANAS_RECIENTES = 8

    /** Meses que cuentan como "últimamente" para lo descontado. */
    const val MESES_RECIENTES = 3

    data class Estadisticas(
        val mejorDia: MejorDia?,
        val cuota: CuotaMedia?,
        val apuntados: Apuntados?,
        val descontado: DescontadoAlMes?
    )

    /** El día con más socios hechos, cuántos y qué parte de todos los hechos son (de 0 a 1). */
    data class MejorDia(val dia: DiaSemana, val hechos: Int, val proporcion: Double)

    /**
     * La cuota media de los socios hechos ([hechos]) y la de las últimas [SEMANAS_RECIENTES] semanas, si en ellas se
     * hizo alguno y también antes (si no, las dos medias serían la misma).
     */
    data class CuotaMedia(val media: Double, val hechos: Int, val mediaReciente: Double?)

    /** De los socios apuntados en semanas ya pasadas, cuántos se hicieron y cuántos siguen pendientes. */
    data class Apuntados(val total: Int, val hechos: Int) {
        val pendientes: Int get() = total - hechos
        val proporcionHechos: Double get() = hechos.toDouble() / total
    }

    /**
     * Lo descontado de media en cada mes en el que se apuntó lo cobrado ([meses]), y la media de los últimos
     * [MESES_RECIENTES] de esos meses si hay más de esos (si no, sería la misma). Negativo si se cobró de más.
     */
    data class DescontadoAlMes(val media: Double, val meses: Int, val mediaReciente: Double?) {
        /** Cómo va lo descontado últimamente frente a la media: más de un euro de diferencia cuenta como cambio. */
        val tendencia: Tendencia
            get() = when {
                mediaReciente == null -> Tendencia.SIN_DATOS
                mediaReciente - media > UMBRAL_TENDENCIA -> Tendencia.SUBE
                media - mediaReciente > UMBRAL_TENDENCIA -> Tendencia.BAJA
                else -> Tendencia.IGUAL
            }
    }

    enum class Tendencia { SUBE, BAJA, IGUAL, SIN_DATOS }

    private const val UMBRAL_TENDENCIA = 1.0

    fun calcular(socios: List<Socio>, semanas: List<SemanaHistoricaItem>, semanaActual: String): Estadisticas {
        val hechos = socios.filter { it.hecho }
        return Estadisticas(
            mejorDia = mejorDia(hechos),
            cuota = cuotaMedia(hechos, semanaActual),
            apuntados = apuntados(socios, semanaActual),
            descontado = descontadoAlMes(semanas)
        )
    }

    /** En caso de empate, el primero de la semana. */
    private fun mejorDia(hechos: List<Socio>): MejorDia? {
        if (hechos.isEmpty()) return null
        val porDia = hechos.groupingBy { it.dia }.eachCount()
        val (dia, cuantos) = porDia.entries.sortedBy { it.key.id }.maxBy { it.value }
        return MejorDia(dia, cuantos, cuantos.toDouble() / hechos.size)
    }

    private fun cuotaMedia(hechos: List<Socio>, semanaActual: String): CuotaMedia? {
        if (hechos.isEmpty()) return null
        val desde = DateUtils.semanaKeyDesplazada(semanaActual, -(SEMANAS_RECIENTES - 1))
        val (recientes, anteriores) = hechos.partition { desde != null && it.semanaKey >= desde && it.semanaKey <= semanaActual }
        return CuotaMedia(
            media = media(hechos),
            hechos = hechos.size,
            mediaReciente = media(recientes).takeIf { recientes.isNotEmpty() && anteriores.isNotEmpty() }
        )
    }

    private fun media(socios: List<Socio>): Double =
        if (socios.isEmpty()) 0.0 else CalculoComisiones.redondearCentimos(Centimos.suma(socios) { it.colaboracion } / socios.size)

    /** Solo semanas anteriores a la actual: los de esta semana aún se pueden hacer. */
    private fun apuntados(socios: List<Socio>, semanaActual: String): Apuntados? {
        val pasados = socios.filter { it.semanaKey < semanaActual }
        if (pasados.isEmpty()) return null
        return Apuntados(total = pasados.size, hechos = pasados.count { it.hecho })
    }

    private fun descontadoAlMes(semanas: List<SemanaHistoricaItem>): DescontadoAlMes? {
        val conCobro = semanas.filter { it.resumenSemana.tieneCobroReal }
        // De más antiguo a más reciente.
        val meses = CalculoComisiones.agruparPorPeriodo(conCobro, Periodo.MES).reversed()
        if (meses.isEmpty()) return null
        fun mediaDe(lista: List<CalculoComisiones.ResumenPeriodo>) =
            CalculoComisiones.redondearCentimos(Centimos.suma(lista) { it.descontado } / lista.size)
        return DescontadoAlMes(
            media = mediaDe(meses),
            meses = meses.size,
            mediaReciente = meses.takeLast(MESES_RECIENTES).takeIf { meses.size > MESES_RECIENTES }?.let(::mediaDe)
        )
    }
}
