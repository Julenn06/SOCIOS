package com.julen.socios.data.backup

import com.julen.socios.model.DatosBackup
import com.julen.socios.model.ResultadoLectura
import com.julen.socios.util.parseImporte
import java.io.InputStream
import java.io.OutputStream
import java.io.Writer

/**
 * CSV con tres secciones (# SOCIOS, # BONOS_REGALO, # IRPF). Los campos con comas, comillas, saltos de
 * línea o espacios en los extremos van entre comillas, y el lector respeta esas comillas aunque el
 * campo ocupe varias líneas.
 */
object BackupCsv {

    private const val SECCION_SOCIOS = "# SOCIOS"
    private const val SECCION_BONOS = "# BONOS_REGALO"
    private const val SECCION_IRPF = "# IRPF"

    private const val CABECERA_SOCIOS = "ID,Colaboracion,Hecho,DiaSemanaId,SemanaKey,NombreSocio,Notas,Timestamp"
    private const val CABECERA_BONOS = "SemanaKey,Activo,EsMontoFijo,Monto,Nota"
    private const val CABECERA_IRPF = "DesdeSemanaKey,Porcentaje"

    private enum class Seccion { SOCIOS, BONOS, IRPF }

    fun escribir(datos: DatosBackup, outputStream: OutputStream) {
        val out = outputStream.bufferedWriter(Charsets.UTF_8)
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
        if (datos.irpfVigencias.isNotEmpty()) {
            out.linea(SECCION_IRPF)
            out.linea(CABECERA_IRPF)
            for (vigencia in datos.irpfVigencias) {
                out.fila(vigencia.desdeSemanaKey, vigencia.porcentaje)
            }
        }
        out.flush()
    }

    fun leer(inputStream: InputStream): ResultadoLectura {
        val texto = inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.removePrefix(BOM_UTF8)

        val socios = mutableListOf<SocioDto>()
        val bonos = mutableListOf<BonoRegaloDto>()
        val vigencias = mutableListOf<IrpfVigenciaDto>()
        var descartados = 0
        var seccion = Seccion.SOCIOS

        for (campos in parsearRegistros(texto)) {
            if (campos.all { it.isBlank() }) continue

            val primero = campos.first().trim()
            if (campos.size == 1 && primero.startsWith("#")) {
                seccion = when {
                    primero.equals(SECCION_BONOS, ignoreCase = true) -> Seccion.BONOS
                    primero.equals(SECCION_IRPF, ignoreCase = true) -> Seccion.IRPF
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

            when (seccion) {
                Seccion.SOCIOS -> if (campos.size >= 5) {
                    socios += SocioDto(
                        id = campos[0].trim(),
                        colaboracion = parseImporte(campos[1]),
                        hecho = parseBooleano(campos[2]),
                        diaSemanaId = campos[3].trim().toIntOrNull(),
                        semanaKey = campos[4].trim(),
                        nombreSocio = campos.getOrNull(5),
                        notas = campos.getOrNull(6),
                        timestamp = campos.getOrNull(7)?.trim()?.toLongOrNull()
                    )
                } else {
                    descartados++
                }

                Seccion.BONOS -> if (campos.size >= 4) {
                    bonos += BonoRegaloDto(
                        semanaKey = campos[0].trim(),
                        activo = parseBooleano(campos[1]),
                        esMontoFijo = parseBooleano(campos[2]),
                        monto = parseImporte(campos[3]),
                        nota = campos.getOrNull(4)
                    )
                } else {
                    descartados++
                }

                Seccion.IRPF -> if (campos.size >= 2) {
                    vigencias += IrpfVigenciaDto(campos[0].trim(), parseImporte(campos[1]))
                } else {
                    descartados++
                }
            }
        }

        val resultado = ConversionBackup.desdeDto(
            BackupDto(socios = socios, bonosRegalo = bonos, irpfVigencias = vigencias)
        )
        return resultado.copy(descartados = resultado.descartados + descartados)
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
