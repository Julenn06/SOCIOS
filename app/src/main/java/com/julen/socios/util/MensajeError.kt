package com.julen.socios.util

import android.database.sqlite.SQLiteFullException
import com.julen.socios.R
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.serialization.SerializationException

/** Error cuyo mensaje ya está escrito para el usuario: se muestra tal cual. */
class ErrorParaUsuario(mensaje: String, causa: Throwable? = null) : IOException(mensaje, causa)

/**
 * Explica [e] al usuario. Los mensajes propios se muestran tal cual; los del sistema, que suelen estar en
 * inglés y ser técnicos, se cambian por lo que normalmente ha pasado.
 */
fun motivoDeError(e: Throwable): UiText {
    val mensaje = e.message
    return when {
        (e is ErrorParaUsuario || e is SerializationException) && !mensaje.isNullOrBlank() -> UiText.Texto(mensaje)
        e is SQLiteFullException || mensaje?.contains("ENOSPC") == true -> UiText.Res(R.string.error_sin_espacio)
        e is FileNotFoundException || e is SecurityException -> UiText.Res(R.string.error_archivo_inaccesible)
        // El nombre de la excepción ayuda a saber qué ha fallado si el usuario lo cuenta.
        else -> UiText.Res(R.string.error_inesperado, listOf(e.javaClass.simpleName))
    }
}
