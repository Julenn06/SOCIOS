package com.julen.socios

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.drawable.GradientDrawable
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils

/**
 * El color de la placa de un socio: verde si suma, ámbar si está pendiente. Es un color liso (los dos extremos
 * iguales): el estado ya lo dice el color, y un degradado encima solo era adorno.
 */
fun coloresDegradadoSocio(context: Context, hecho: Boolean): IntArray {
    val color = ContextCompat.getColor(context, if (hecho) R.color.badge_gradient_start else R.color.pendiente_gradient_start)
    return intArrayOf(color, color)
}

/**
 * Lleva el degradado a los colores [destino] con un fundido, que [configurar] ajusta (duración, curva). Sin
 * [animar], o si aún no tenía colores, los pone al instante. Devuelve el fundido en curso, para cancelarlo.
 */
fun GradientDrawable.fundirA(destino: IntArray, animar: Boolean, configurar: ValueAnimator.() -> Unit = {}): ValueAnimator? {
    val origen = colors
    if (!animar || origen == null) {
        colors = destino
        return null
    }
    return ValueAnimator.ofFloat(0f, 1f).apply {
        configurar()
        addUpdateListener { animacion ->
            val f = animacion.animatedValue as Float
            colors = intArrayOf(ColorUtils.blendARGB(origen[0], destino[0], f), ColorUtils.blendARGB(origen[1], destino[1], f))
        }
        start()
    }
}
