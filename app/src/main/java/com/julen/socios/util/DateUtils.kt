package com.julen.socios.util

import com.julen.socios.model.DiaSemana
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields
import java.util.Locale

/**
 * Utilidades de semanas ISO-8601 (lunes a domingo, la semana 1 es la que contiene el primer jueves).
 *
 * La clave de semana usa el año de la semana ISO, no el año natural: del 29 al 31 de diciembre de 2025
 * pertenecen a la semana "2026-W01".
 */
object DateUtils {

    private val LOCALE_ES: Locale = Locale.forLanguageTag("es-ES")
    private val FORMATO_DIA: DateTimeFormatter = DateTimeFormatter.ofPattern("d 'de' MMM", LOCALE_ES)
    private val FORMATO_DIA_CORTO: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", LOCALE_ES)
    private val FORMATO_HORA: DateTimeFormatter = DateTimeFormatter.ofPattern("H:mm", LOCALE_ES)
    private val PATRON_SEMANA_KEY = Regex("""(\d{4})-W(\d{2})""")

    /** Lunes de la semana actual desplazada [weekOffset] semanas. */
    fun lunesDeSemana(weekOffset: Int = 0, clock: Clock = Clock.systemDefaultZone()): LocalDate =
        LocalDate.now(clock).with(DayOfWeek.MONDAY).plusWeeks(weekOffset.toLong())

    /** Retorna la clave de semana en formato YYYY-Www (ej. "2025-W09"). */
    fun getSemanaKey(weekOffset: Int = 0, clock: Clock = Clock.systemDefaultZone()): String =
        semanaKeyDe(lunesDeSemana(weekOffset, clock))

    /** Clave de la semana ISO a la que pertenece [fecha]. */
    fun semanaKeyDe(fecha: LocalDate): String = String.format(
        Locale.ROOT,
        "%04d-W%02d",
        fecha.get(IsoFields.WEEK_BASED_YEAR),
        fecha.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
    )

    /** Lunes de la semana representada por [semanaKey], o null si la clave no es una semana ISO válida. */
    fun lunesDeSemanaKey(semanaKey: String): LocalDate? {
        val match = PATRON_SEMANA_KEY.matchEntire(semanaKey) ?: return null
        val anio = match.groupValues[1].toInt()
        val semana = match.groupValues[2].toLong()
        // El 4 de enero siempre pertenece a la semana 1 de su año ISO.
        val referencia = LocalDate.of(anio, 1, 4)
        if (!referencia.range(IsoFields.WEEK_OF_WEEK_BASED_YEAR).isValidValue(semana)) return null
        return referencia.with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, semana).with(DayOfWeek.MONDAY)
    }

    fun esSemanaKeyValida(semanaKey: String): Boolean = lunesDeSemanaKey(semanaKey) != null

    /** Número de semanas ISO que tiene el año ISO [anio] (52 o 53). */
    fun semanasEnAnio(anio: Int): Int =
        LocalDate.of(anio, 1, 4).range(IsoFields.WEEK_OF_WEEK_BASED_YEAR).maximum.toInt()

    /**
     * Desplazamiento en semanas de [semanaKey] respecto a la semana actual,
     * o null si la clave no es válida.
     */
    fun getWeekOffsetFromKey(semanaKey: String, clock: Clock = Clock.systemDefaultZone()): Int? {
        val lunes = lunesDeSemanaKey(semanaKey) ?: return null
        return ChronoUnit.WEEKS.between(lunesDeSemana(0, clock), lunes).toInt()
    }

    /** Texto legible del rango de la semana de lunes a viernes, ej. "24 de feb. - 28 de feb. (2025)". */
    fun getRangoSemanaTexto(weekOffset: Int = 0, clock: Clock = Clock.systemDefaultZone()): String =
        rangoTextoDe(lunesDeSemana(weekOffset, clock))

    /** Texto legible del rango de la semana [semanaKey], o la propia clave si no es válida. */
    fun getRangoSemanaTextoFromKey(semanaKey: String): String =
        lunesDeSemanaKey(semanaKey)?.let(::rangoTextoDe) ?: semanaKey

    private fun rangoTextoDe(lunes: LocalDate): String {
        val viernes = lunes.plusDays(4)
        return "${FORMATO_DIA.format(lunes)} - ${FORMATO_DIA.format(viernes)} (${lunes.year})"
    }

    /** Rango corto de lunes a viernes, ej. "12–16 oct" o "29 dic – 2 ene"; la propia clave si no es válida. */
    fun rangoCorto(semanaKey: String): String {
        val lunes = lunesDeSemanaKey(semanaKey) ?: return semanaKey
        val viernes = lunes.plusDays(4)
        return if (lunes.month == viernes.month) {
            "${lunes.dayOfMonth}–${diaCorto(viernes)}"
        } else {
            "${diaCorto(lunes)} – ${diaCorto(viernes)}"
        }
    }

    /** Cuándo fue [millis], dicho con naturalidad: "hoy a las 16:05", "ayer a las 9:30" o "el 3 oct". */
    fun momentoRelativo(millis: Long, clock: Clock = Clock.systemDefaultZone()): String {
        val momento = Instant.ofEpochMilli(millis).atZone(clock.zone)
        val hoy = LocalDate.now(clock)
        return when (momento.toLocalDate()) {
            hoy -> "hoy a las ${FORMATO_HORA.format(momento)}"
            hoy.minusDays(1) -> "ayer a las ${FORMATO_HORA.format(momento)}"
            else -> "el ${diaCorto(momento.toLocalDate())}"
        }
    }

    // Java escribe los meses abreviados con punto ("oct."); en un rango corto sobra.
    private fun diaCorto(fecha: LocalDate) = FORMATO_DIA_CORTO.format(fecha).removeSuffix(".")

    /** Día laborable de hoy; el fin de semana se asigna al viernes (sábado) o al lunes (domingo). */
    fun getDiaSemanaActual(clock: Clock = Clock.systemDefaultZone()): DiaSemana =
        DiaSemana.desde(LocalDate.now(clock).dayOfWeek)
}
