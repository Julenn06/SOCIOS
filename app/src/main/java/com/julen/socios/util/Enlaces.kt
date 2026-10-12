package com.julen.socios.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

/** Abre [url] en el navegador. Devuelve false si no hay ninguna app que pueda abrirla. */
fun Context.abrirEnlace(url: String): Boolean = try {
    startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    true
} catch (e: ActivityNotFoundException) {
    false
}
