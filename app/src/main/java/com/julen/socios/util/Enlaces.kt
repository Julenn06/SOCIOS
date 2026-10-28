package com.julen.socios.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File

/** Abre [url] en el navegador. Devuelve false si no hay ninguna app que pueda abrirla. */
fun Context.abrirEnlace(url: String): Boolean = try {
    startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    true
} catch (e: ActivityNotFoundException) {
    false
}

/** URI con la que otra app (el instalador, la de compartir) puede leer [archivo] de la caché, por el FileProvider. */
fun Context.uriCompartida(archivo: File): Uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", archivo)
