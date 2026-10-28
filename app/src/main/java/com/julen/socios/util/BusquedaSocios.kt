package com.julen.socios.util

import com.julen.socios.model.Socio
import java.text.Normalizer
import java.util.Locale

/** Búsqueda de socios en todo el histórico, por nombre, notas o cuota. */
object BusquedaSocios {

    private val MARCAS_DIACRITICAS = Regex("\\p{Mn}+")
    private val ESPACIOS = Regex("\\s+")

    /**
     * Socios cuyo nombre o notas contienen todas las palabras de [texto], sin distinguir mayúsculas ni tildes
     * ("maria" encuentra a "María"). Un número ("20" o "12,5") encuentra además a los socios con esa cuota.
     * Del más reciente al más antiguo; vacío si no se ha escrito nada.
     */
    fun filtrar(socios: List<Socio>, texto: String): List<Socio> {
        val palabras = normalizar(texto).split(' ').filter { it.isNotEmpty() }
        if (palabras.isEmpty()) return emptyList()
        val cuota = parseImporte(texto)

        return socios
            .filter { socio ->
                val donde = normalizar("${socio.nombreSocio} ${socio.notas}")
                palabras.all { it in donde } || (cuota != null && socio.colaboracion == cuota)
            }
            .sortedWith(compareByDescending<Socio> { it.semanaKey }.thenBy { it.dia.id }.thenByDescending { it.timestamp })
    }

    internal fun normalizar(texto: String): String =
        Normalizer.normalize(texto, Normalizer.Form.NFD)
            .replace(MARCAS_DIACRITICAS, "")
            .lowercase(Locale.ROOT)
            .replace(ESPACIOS, " ")
            .trim()
}
