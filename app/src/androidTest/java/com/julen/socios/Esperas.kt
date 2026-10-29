package com.julen.socios

import android.app.Dialog
import android.os.SystemClock
import android.provider.Settings
import android.view.animation.AnimationUtils

/**
 * Repite [comprobacion] hasta que pase o se acabe el [plazoMs]. Espresso espera a la interfaz, pero no a la base de
 * datos: lo que se pinta al llegar sus datos (o al cerrarse un panel con su animación) puede tardar un momento.
 */
fun reintentar(plazoMs: Long = 5_000, comprobacion: () -> Unit) {
    val fin = SystemClock.uptimeMillis() + plazoMs
    while (true) {
        try {
            comprobacion()
            return
        } catch (e: Throwable) {
            if (SystemClock.uptimeMillis() > fin) throw e
            Thread.sleep(100)
        }
    }
}

/**
 * Lo que tarda en entrar la ventana de [dialogo] (un panel inferior sube desde abajo), con la escala de animaciones
 * del sistema. Espresso no espera a esa animación: un toque durante ella cae donde aún no está el botón. Con las
 * animaciones desactivadas, como en la CI, es 0.
 */
fun duracionEntrada(dialogo: Dialog): Long {
    val contexto = dialogo.context
    val escala = Settings.Global.getFloat(contexto.contentResolver, Settings.Global.WINDOW_ANIMATION_SCALE, 1f)
    val estilo = dialogo.window?.attributes?.windowAnimations ?: 0
    if (escala == 0f || estilo == 0) return 0
    val atributos = contexto.obtainStyledAttributes(estilo, intArrayOf(android.R.attr.windowEnterAnimation))
    val animacion = atributos.getResourceId(0, 0)
    atributos.recycle()
    if (animacion == 0) return 0
    // La de Material pone la duración en el <set> y no en cada animación, que es lo único que suma computeDurationHint.
    val entrada = AnimationUtils.loadAnimation(contexto, animacion)
    return (maxOf(entrada.startOffset + entrada.duration, entrada.computeDurationHint()) * escala).toLong()
}
