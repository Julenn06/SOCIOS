package com.julen.socios.data.backup

import com.julen.socios.util.ErrorParaUsuario
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Marca de orden de bytes que algunos editores (Excel, Bloc de notas) añaden al guardar en UTF-8. */
internal val BOM_UTF8: String = Char(0xFEFF).toString()

/** Muy por encima de cualquier copia real (años de socios ocupan unos pocos MB). */
internal const val TAMANO_MAXIMO_COPIA: Long = 20L * 1024 * 1024

/**
 * El texto de una copia de seguridad, sin BOM. Antes de leerla entera comprueba que no supera [limite]:
 * elegir por error un archivo enorme (un vídeo, por ejemplo) no debe agotar la memoria y cerrar la app.
 * @throws ErrorParaUsuario si el archivo ocupa más de [limite] bytes.
 */
internal fun leerTextoCopia(inputStream: InputStream, limite: Long = TAMANO_MAXIMO_COPIA): String {
    val bytes = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val leidos = inputStream.read(buffer)
        if (leidos < 0) break
        total += leidos
        if (total > limite) throw ErrorParaUsuario("el archivo es demasiado grande para ser una copia de seguridad")
        bytes.write(buffer, 0, leidos)
    }
    return String(bytes.toByteArray(), Charsets.UTF_8).removePrefix(BOM_UTF8)
}
