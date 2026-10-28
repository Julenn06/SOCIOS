package com.julen.socios.util

import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId
import java.util.Locale

/**
 * Corrige las claves de semana generadas por la versión antigua de DateUtils, que combinaba el año
 * natural con el número de semana:
 *
 * - Los últimos días de diciembre que pertenecen a la semana 1 del año siguiente se guardaban como
 *   "AAAA-W01" del año en curso (ej. 29-dic-2025 → "2025-W01" en vez de "2026-W01").
 * - Los primeros días de enero que pertenecen a la semana 53 del año anterior se guardaban con una
 *   clave inexistente (ej. 1-ene-2027 → "2027-W53" en vez de "2026-W53").
 */
object ReparacionSemanaKeys {

    /** [timestamp] null: no se sabe cuándo se creó, así que solo se corrige si la clave es imposible. */
    data class SocioRef(val id: String, val semanaKey: String, val timestamp: Long?)

    data class Resultado(
        /** id del socio → clave corregida. */
        val socios: Map<String, String>,
        /** clave antigua del bono → clave corregida. */
        val bonos: Map<String, String>
    )

    fun calcular(
        socios: List<SocioRef>,
        bonoKeys: Set<String>,
        zona: ZoneId = ZoneId.systemDefault()
    ): Resultado {
        val sociosCorregidos = socios.mapNotNull { socio ->
            val corregida = socio.timestamp?.let { claveCorregida(socio.semanaKey, it, zona) }
                ?: claveImposibleCorregida(socio.semanaKey)
            corregida?.let { socio.id to it }
        }.toMap()

        // Un bono no tiene fecha propia: se mueve si es una clave imposible, o si todos los socios
        // de su semana se han movido a la misma semana corregida y allí no hay ya otro bono.
        val restantesPorClave = socios.filter { it.id !in sociosCorregidos }.groupBy { it.semanaKey }
        val destinosPorClave = socios.filter { it.id in sociosCorregidos }
            .groupBy({ it.semanaKey }, { sociosCorregidos.getValue(it.id) })
            .mapValues { (_, destinos) -> destinos.toSet() }

        val bonosCorregidos = mutableMapOf<String, String>()
        for (clave in bonoKeys) {
            val destino = claveImposibleCorregida(clave)
                ?: destinosPorClave[clave]?.singleOrNull()?.takeIf { restantesPorClave[clave].isNullOrEmpty() }
                ?: continue
            if (destino !in bonoKeys && destino !in bonosCorregidos.values) {
                bonosCorregidos[clave] = destino
            }
        }
        return Resultado(sociosCorregidos, bonosCorregidos)
    }

    /** Clave corregida para un registro guardado con [semanaKey] en el instante [timestamp], o null si es correcta. */
    fun claveCorregida(semanaKey: String, timestamp: Long, zona: ZoneId = ZoneId.systemDefault()): String? {
        claveImposibleCorregida(semanaKey)?.let { return it }

        val (anio, semana) = DateUtils.partesDeSemanaKey(semanaKey) ?: return null
        if (semana != 1) return null
        // Solo hay ambigüedad si la semana 1 del año siguiente empieza en diciembre de este año.
        val lunesSemana1Siguiente = DateUtils.lunesDeSemanaKey(String.format(Locale.ROOT, "%04d-W01", anio + 1)) ?: return null
        if (lunesSemana1Siguiente.year != anio) return null

        // Un registro de la semana 1 real de [anio] se crea en torno a enero; uno creado a partir de
        // diciembre de ese mismo año corresponde a la semana 1 del año siguiente.
        val creadoEl = Instant.ofEpochMilli(timestamp).atZone(zona).toLocalDate()
        val inicioDiciembre = LocalDate.of(anio, Month.DECEMBER, 1)
        return if (!creadoEl.isBefore(inicioDiciembre)) DateUtils.semanaKeyDe(lunesSemana1Siguiente) else null
    }

    /** "AAAA-W53" en un año ISO que solo tiene 52 semanas era la semana 53 del año anterior; null si no es el caso. */
    fun claveImposibleCorregida(semanaKey: String): String? {
        val (anio, semana) = DateUtils.partesDeSemanaKey(semanaKey) ?: return null
        if (semana != 53 || DateUtils.semanasEnAnio(anio) == 53) return null
        return if (DateUtils.semanasEnAnio(anio - 1) == 53) String.format(Locale.ROOT, "%04d-W53", anio - 1) else null
    }
}
