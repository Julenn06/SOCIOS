package com.julen.socios

import android.os.SystemClock

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
