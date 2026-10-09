package com.julen.socios.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object DateUtils {

    /**
     * Retorna una clave única de semana en formato YYYY-Www (ej. "2025-W09").
     */
    fun getSemanaKey(weekOffset: Int = 0): String {
        val cal = Calendar.getInstance()
        cal.firstDayOfWeek = Calendar.MONDAY
        cal.add(Calendar.WEEK_OF_YEAR, weekOffset)
        val year = cal.get(Calendar.YEAR)
        val week = cal.get(Calendar.WEEK_OF_YEAR)
        return String.format(Locale.getDefault(), "%d-W%02d", year, week)
    }

    /**
     * Calcula el offset de semana respecto a la semana actual a partir de la clave semanaKey.
     */
    fun getWeekOffsetFromKey(semanaKey: String): Int {
        try {
            val currentKey = getSemanaKey(0)
            if (semanaKey == currentKey) return 0

            val parts = semanaKey.split("-W")
            if (parts.size == 2) {
                val targetYear = parts[0].toInt()
                val targetWeek = parts[1].toInt()

                val nowCal = Calendar.getInstance()
                nowCal.firstDayOfWeek = Calendar.MONDAY

                val currentYear = nowCal.get(Calendar.YEAR)
                val currentWeek = nowCal.get(Calendar.WEEK_OF_YEAR)
                val estimatedOffset = (targetYear - currentYear) * 52 + (targetWeek - currentWeek)

                // Refinar en un entorno cercano (+/- 5 semanas)
                for (offset in (estimatedOffset - 5)..(estimatedOffset + 5)) {
                    if (getSemanaKey(offset) == semanaKey) {
                        return offset
                    }
                }
            }
        } catch (_: Exception) {
        }

        // Búsqueda de respaldo ampliada a 10 años
        for (offset in -520..520) {
            if (getSemanaKey(offset) == semanaKey) {
                return offset
            }
        }
        return 0
    }

    /**
     * Retorna una cadena de texto legible para el rango de la semana (Lunes a Viernes).
     */
    fun getRangoSemanaTexto(weekOffset: Int = 0): String {
        val cal = Calendar.getInstance()
        cal.firstDayOfWeek = Calendar.MONDAY
        cal.add(Calendar.WEEK_OF_YEAR, weekOffset)

        cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        val lunesDate = cal.time

        cal.set(Calendar.DAY_OF_WEEK, Calendar.FRIDAY)
        val viernesDate = cal.time

        val localeEs = Locale.forLanguageTag("es-ES")
        val sdf = SimpleDateFormat("d 'de' MMM", localeEs)
        val yearSdf = SimpleDateFormat("yyyy", localeEs)

        return "${sdf.format(lunesDate)} - ${sdf.format(viernesDate)} (${yearSdf.format(lunesDate)})"
    }

    /**
     * Retorna el texto legible del rango de fechas a partir de la clave semanaKey (ej. "2025-W09").
     */
    fun getRangoSemanaTextoFromKey(semanaKey: String): String {
        val offset = getWeekOffsetFromKey(semanaKey)
        return getRangoSemanaTexto(offset)
    }

    /**
     * Retorna el ID del día actual (1=Lunes..5=Viernes).
     * Si es sábado o domingo, mapea por defecto al Lunes o Viernes.
     */
    fun getDiaSemanaActualId(): Int {
        val cal = Calendar.getInstance()
        return when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> 1
            Calendar.TUESDAY -> 2
            Calendar.WEDNESDAY -> 3
            Calendar.THURSDAY -> 4
            Calendar.FRIDAY -> 5
            Calendar.SATURDAY -> 5
            Calendar.SUNDAY -> 1
            else -> 1
        }
    }
}
