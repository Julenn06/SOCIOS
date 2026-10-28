package com.julen.socios

import android.content.Context
import android.graphics.Paint
import androidx.core.content.res.ResourcesCompat

/**
 * Pintura para cifras en las vistas que se dibujan a mano: Geist semibold con números de ancho fijo, como el
 * estilo Socios.Cifra de los textos. [tamano], en píxeles; sin él, la vista lo pone al conocer su tamaño.
 */
fun pinturaCifra(context: Context, tamano: Float? = null, centrada: Boolean = true) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    if (centrada) textAlign = Paint.Align.CENTER
    typeface = ResourcesCompat.getFont(context, R.font.geist_semibold)
    fontFeatureSettings = "tnum"
    tamano?.let { textSize = it }
}
