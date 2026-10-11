package com.julen.socios.actualizaciones

/**
 * Número de versión "X.Y.Z" de la app, el mismo que el de los tags vX.Y.Z de GitHub. Las builds de
 * desarrollo ("dev-12") no tienen uno y [parse] devuelve null.
 */
class Version private constructor(private val partes: List<Int>) : Comparable<Version> {

    /** "1.3" y "1.3.0" son la misma versión. */
    override fun compareTo(other: Version): Int {
        for (i in 0 until maxOf(partes.size, other.partes.size)) {
            val diferencia = partes.getOrElse(i) { 0 }.compareTo(other.partes.getOrElse(i) { 0 })
            if (diferencia != 0) return diferencia
        }
        return 0
    }

    override fun equals(other: Any?) = other is Version && compareTo(other) == 0

    override fun hashCode() = partes.dropLastWhile { it == 0 }.hashCode()

    override fun toString() = partes.joinToString(".")

    companion object {
        private val PATRON = Regex("""v?\d+(\.\d+)*""")

        fun parse(texto: String): Version? {
            val limpio = texto.trim()
            if (!PATRON.matches(limpio)) return null
            val partes = limpio.removePrefix("v").split('.').map { it.toIntOrNull() ?: return null }
            return Version(partes)
        }
    }
}
