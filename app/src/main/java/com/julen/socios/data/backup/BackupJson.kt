package com.julen.socios.data.backup

import com.google.gson.GsonBuilder
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.julen.socios.model.DatosBackup
import com.julen.socios.model.ResultadoLectura
import java.io.InputStream
import java.io.OutputStream

object BackupJson {

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val tipoListaSocios = object : TypeToken<List<SocioDto?>>() {}.type

    fun escribir(datos: DatosBackup, outputStream: OutputStream, timestamp: Long = System.currentTimeMillis()) {
        val writer = outputStream.bufferedWriter(Charsets.UTF_8)
        gson.toJson(ConversionBackup.aDto(datos, timestamp), writer)
        writer.flush()
    }

    /**
     * Lee una copia completa (objeto con socios, bonos e IRPF) o el formato antiguo (lista de socios).
     * @throws JsonParseException si el archivo no es un JSON con alguno de esos formatos.
     */
    fun leer(inputStream: InputStream): ResultadoLectura {
        val texto = inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.removePrefix(BOM_UTF8)
        val raiz = JsonParser.parseString(texto)
        val dto = when {
            raiz.isJsonObject -> gson.fromJson(raiz, BackupDto::class.java)
            raiz.isJsonArray -> BackupDto(socios = gson.fromJson(raiz, tipoListaSocios))
            else -> throw JsonParseException("Formato de copia de seguridad no reconocido")
        }
        return ConversionBackup.desdeDto(dto)
    }
}
