package com.julen.socios.data.backup

import com.julen.socios.model.DatosBackup
import com.julen.socios.model.ResultadoLectura
import com.julen.socios.util.parseImporte
import java.io.InputStream
import java.io.OutputStream
import java.io.Writer

/**
 * CSV con tres secciones (# SOCIOS, # BONOS_REGALO, # COBROS), precedidas de la versión del formato
 * (# VERSION 5). Las filas de la sección # DEVOLUCIONES de las hojas v3 no se importan: cuentan como
 * descartadas. Las de # IRPF de las hojas v2–v4 tampoco: se cuentan aparte para decirlo en el resumen. Los
 * campos con comas, comillas, saltos de línea o espacios en los extremos van entre comillas, y el lector respeta
 * esas comillas aunque el campo ocupe varias líneas.
 */
object BackupCsv {

    private const val SECCION_SOCIOS = "# SOCIOS"
    private const val SECCION_BONOS = "# BONOS_REGALO"
    private const val SECCION_IRPF = "# IRPF"
    private const val SECCION_COBROS = "# COBROS"
    private const val SECCION_DEVOLUCIONES = "# DEVOLUCIONES"
    private const val PREFIJO_VERSION = "# VERSION "
    private val PATRON_VERSION = Regex("""#\s*VERSION\s+\d+""", RegexOption.IGNORE_CASE)

    private const val CABECERA_SOCIOS = "ID,Colaboracion,Hecho,DiaSemanaId,SemanaKey,NombreSocio,Notas,Timestamp"
    private const val CABECERA_BONOS = "SemanaKey,Activo,EsMontoFijo,Monto,Nota"
    private const val CABECERA_COBROS = "SemanaKey,NetoCobrado,Nota"

    /** Cada sección, con los campos que necesita una fila para no descartarse. Las de IGNORADA se descartan todas. */
    private enum class Seccion(val camposMinimos: Int) { SOCIOS(5), BONOS(4), IRPF(2), COBROS(2), IGNORADA(Int.MAX_VALUE) }

    fun escribir(datos: DatosBackup, outputStream: OutputStream) {
        val out = outputStream.bufferedWriter(Charsets.UTF_8)
        // Con el BOM, Excel abre el archivo como UTF-8 y muestra bien las tildes y las eñes.
        out.write(BOM_UTF8)
        // Las versiones anteriores de la app la toman por una sección desconocida y la ignoran.
        out.linea(PREFIJO_VERSION + ConversionBackup.VERSION_ACTUAL)
        out.linea(SECCION_SOCIOS)
        out.linea(CABECERA_SOCIOS)
        for (socio in datos.socios) {
            out.fila(
                socio.id, socio.colaboracion, socio.hecho, socio.dia.id, socio.semanaKey,
                socio.nombreSocio, socio.notas, socio.timestamp
            )
        }
        if (datos.bonosRegalo.isNotEmpty()) {
            out.linea(SECCION_BONOS)
            out.linea(CABECERA_BONOS)
            for (bono in datos.bonosRegalo) {
                out.fila(bono.semanaKey, bono.activo, bono.esMontoFijo, bono.monto, bono.nota)
            }
        }
        if (datos.cobros.isNotEmpty()) {
            out.linea(SECCION_COBROS)
            out.linea(CABECERA_COBROS)
            for (cobro in datos.cobros) {
                out.fila(cobro.semanaKey, cobro.netoCobrado, cobro.nota)
            }
        }
        out.flush()
    }

    fun leer(inputStream: InputStream): ResultadoLectura {
        val texto = leerTextoCopia(inputStream)

        val socios = mutableListOf<SocioDto>()
        val bonos = mutableListOf<BonoRegaloDto>()
        val cobros = mutableListOf<CobroDto>()
        var descartados = 0
        var irpfIgnorados = 0
        var seccion = Seccion.SOCIOS

        for (campos in parsearRegistros(texto)) {
            if (campos.all { it.isBlank() }) continue

            val primero = campos.first().trim()
            if (campos.size == 1 && primero.startsWith("#")) {
                // La línea de la versión no cambia de sección: se lee igual cualquier versión.
                if (PATRON_VERSION.matches(primero)) continue
                seccion = when {
                    primero.equals(SECCION_BONOS, ignoreCase = true) -> Seccion.BONOS
                    primero.equals(SECCION_IRPF, ignoreCase = true) -> Seccion.IRPF
                    primero.equals(SECCION_COBROS, ignoreCase = true) -> Seccion.COBROS
                    primero.equals(SECCION_DEVOLUCIONES, ignoreCase = true) -> Seccion.IGNORADA
                    else -> Seccion.SOCIOS
                }
                continue
            }
            if (primero.equals("ID", ignoreCase = true) ||
                primero.equals("SemanaKey", ignoreCase = true) ||
                primero.equals("DesdeSemanaKey", ignoreCase = true)
            ) {
                continue
            }

            if (campos.size < seccion.camposMinimos) {
                descartados++
                continue
            }
            when (seccion) {
                Seccion.SOCIOS -> socios += SocioDto(
                    id = campos[0].trim(),
                    colaboracion = parseImporte(campos[1]),
                    hecho = parseBooleano(campos[2]),
                    diaSemanaId = campos[3].trim().toIntOrNull(),
                    semanaKey = campos[4].trim(),
                    nombreSocio = campos.getOrNull(5),
                    notas = campos.getOrNull(6),
                    timestamp = campos.getOrNull(7)?.trim()?.toLongOrNull()
                )

                Seccion.BONOS -> bonos += BonoRegaloDto(
                    semanaKey = campos[0].trim(),
                    activo = parseBooleano(campos[1]),
                    esMontoFijo = parseBooleano(campos[2]),
                    monto = parseImporte(campos[3]),
                    nota = campos.getOrNull(4)
                )

                Seccion.IRPF -> irpfIgnorados++
                Seccion.COBROS -> cobros += CobroDto(campos[0].trim(), parseImporte(campos[1]), campos.getOrNull(2))
                Seccion.IGNORADA -> Unit // Ya descartada arriba: ninguna fila llega a tantos campos.
            }
        }

        val resultado = ConversionBackup.desdeDto(
            BackupDto(socios = socios, bonosRegalo = bonos, cobros = cobros)
        )
        return resultado.copy(descartados = resultado.descartados + descartados, irpfIgnorados = irpfIgnorados)
    }

    /**
     * Divide el texto en registros y campos según RFC 4180: comillas dobles, "" como comilla escapada
     * y saltos de línea permitidos dentro de un campo entre comillas.
     */
    internal fun parsearRegistros(texto: String): List<List<String>> {
        val registros = mutableListOf<List<String>>()
        var campos = mutableListOf<String>()
        val campo = StringBuilder()
        var entreComillas = false
        var i = 0

        fun cerrarCampo() {
            campos += campo.toString()
            campo.clear()
        }

        fun cerrarRegistro() {
            cerrarCampo()
            registros += campos
            campos = mutableListOf()
        }

        while (i < texto.length) {
            val c = texto[i]
            when {
                entreComillas && c == '"' && texto.getOrNull(i + 1) == '"' -> {
                    campo.append('"')
                    i++
                }

                c == '"' -> entreComillas = !entreComillas
                !entreComillas && c == ',' -> cerrarCampo()
                !entreComillas && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && texto.getOrNull(i + 1) == '\n') i++
                    cerrarRegistro()
                }

                else -> campo.append(c)
            }
            i++
        }
        if (campo.isNotEmpty() || campos.isNotEmpty()) cerrarRegistro()
        return registros
    }

    private fun parseBooleano(texto: String): Boolean? = when (texto.trim().lowercase()) {
        "true", "1" -> true
        "false", "0" -> false
        else -> null
    }

    private fun escapar(valor: Any): String {
        val texto = valor.toString()
        val necesitaComillas = texto.any { it == ',' || it == '"' || it == '\n' || it == '\r' } ||
            texto != texto.trim()
        return if (necesitaComillas) "\"${texto.replace("\"", "\"\"")}\"" else texto
    }

    private fun Writer.fila(vararg valores: Any) = linea(valores.joinToString(",") { escapar(it) })

    private fun Writer.linea(texto: String) {
        write(texto)
        write("\n")
    }
}
