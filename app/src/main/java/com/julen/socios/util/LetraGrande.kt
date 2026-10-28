package com.julen.socios.util

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout

/** A partir de esta letra del sistema un botón al lado de un texto le deja tan poco sitio que ni cabe por palabras. */
private const val ESCALA_LETRA_BOTON_DEBAJO = 1.3f

/**
 * Con la letra del sistema grande, pasa [boton] de su fila al final de [columna], debajo del texto: a su lado, el
 * texto se partía a mitad de palabra ("disponible" en "di" y "sponible") o se cortaba. Con la letra normal no hace nada.
 */
fun ponerDebajoConLetraGrande(boton: View, columna: LinearLayout) {
    val recursos = boton.resources
    if (recursos.configuration.fontScale < ESCALA_LETRA_BOTON_DEBAJO) return
    (boton.parent as? ViewGroup)?.removeView(boton)
    columna.addView(
        boton,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (8 * recursos.displayMetrics.density).toInt()
        }
    )
}
