package com.julen.socios

import android.content.Context
import android.graphics.Paint
import androidx.core.content.res.ResourcesCompat

/**
 * Pintura para cifras centradas en las vistas que se dibujan a mano: Geist semibold con números de ancho fijo,
 * como el estilo Socios.Cifra de los textos. [tamano], en píxeles.
 */
fun pinturaCifra(context: Context, tamano: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    textAlign = Paint.Align.CENTER
    typeface = ResourcesCompat.getFont(context, R.font.geist_semibold)
    fontFeatureSettings = "tnum"
    textSize = tamano
}
