package com.julen.socios.data.backup

import com.julen.socios.model.DatosBackup
import com.julen.socios.model.ResultadoLectura
import java.io.InputStream
import java.io.OutputStream
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

object BackupJson {

    private val json = Json {
        prettyPrint = true
        // Tolerante con lo que admitía Gson en las copias antiguas: campos desconocidos, números y
        // booleanos entre comillas, y textos sin comillas.
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }
    private val serializerListaSocios = ListSerializer(SocioDto.serializer().nullable)

    fun escribir(datos: DatosBackup, outputStream: OutputStream, timestamp: Long = System.currentTimeMillis()) {
        val writer = outputStream.bufferedWriter(Charsets.UTF_8)
        writer.write(json.encodeToString(BackupDto.serializer(), ConversionBackup.aDto(datos, timestamp)))
        writer.flush()
    }

    /**
     * Lee una copia completa (objeto con socios, bonos e IRPF) o el formato antiguo (lista de socios).
     * @throws SerializationException si el archivo no es un JSON con alguno de esos formatos.
     */
    fun leer(inputStream: InputStream): ResultadoLectura {
        val texto = inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.removePrefix(BOM_UTF8)
        val dto = try {
            when (val raiz = json.parseToJsonElement(texto)) {
                is JsonObject -> json.decodeFromJsonElement(BackupDto.serializer(), raiz)
                is JsonArray -> BackupDto(socios = json.decodeFromJsonElement(serializerListaSocios, raiz))
                else -> null
            }
        } catch (e: SerializationException) {
            throw SerializationException(MENSAJE_FORMATO_NO_VALIDO, e)
        }
        return ConversionBackup.desdeDto(dto ?: throw SerializationException(MENSAJE_FORMATO_NO_VALIDO))
    }

    private const val MENSAJE_FORMATO_NO_VALIDO = "el archivo no es una copia de seguridad JSON válida"
}
