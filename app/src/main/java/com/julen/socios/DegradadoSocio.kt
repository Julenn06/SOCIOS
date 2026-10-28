package com.julen.socios

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.drawable.GradientDrawable
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils

/** Los dos colores del degradado de un socio: verde si suma, ámbar si está pendiente. */
fun coloresDegradadoSocio(context: Context, hecho: Boolean): IntArray = if (hecho) {
    intArrayOf(ContextCompat.getColor(context, R.color.badge_gradient_start), ContextCompat.getColor(context, R.color.badge_gradient_end))
} else {
    intArrayOf(ContextCompat.getColor(context, R.color.pendiente_gradient_start), ContextCompat.getColor(context, R.color.pendiente_gradient_end))
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
