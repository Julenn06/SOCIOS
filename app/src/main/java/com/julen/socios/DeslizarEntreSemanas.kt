package com.julen.socios

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.HorizontalScrollView
import kotlin.math.abs

/**
 * Cambio de semana deslizando en horizontal por cualquier parte de [raiz]. La Activity le pasa cada toque
 * desde dispatchTouchEvent, antes que a las vistas, para que funcione también encima de la lista.
 */
class DeslizarEntreSemanas(
    context: Context,
    private val raiz: View,
    /** -1 para ir a la semana anterior y 1 para la siguiente. */
    private val alDeslizar: (delta: Int) -> Unit
) {
    private var gestoSobreControlHorizontal = false

    // En dp y no en píxeles: así el gesto pide el mismo recorrido en cualquier pantalla. 30 dp son los
    // 80 px de antes en un móvil normal; en uno de baja densidad 80 px eran casi media pantalla.
    private val densidad = context.resources.displayMetrics.density
    private val distanciaMinima = DISTANCIA_MINIMA_DP * densidad
    private val velocidadMinima = VELOCIDAD_MINIMA_DP * densidad

    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onFling(
            e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float
        ): Boolean {
            if (e1 == null) return false
            val diffX = e2.x - e1.x
            val diffY = e2.y - e1.y

            if (abs(diffX) > abs(diffY) * 1.3f &&
                abs(diffX) > distanciaMinima && abs(velocityX) > velocidadMinima
            ) {
                alDeslizar(if (diffX < 0) 1 else -1)
                return true
            }
            return false
        }
    })

    /** Devuelve true si el gesto ha cambiado de semana: entonces no debe contar también como un toque. */
    fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            // Un deslizamiento que empieza en un control que ya se arrastra en horizontal (switch,
            // chip, fila desplazable) es para ese control, no para cambiar de semana.
            gestoSobreControlHorizontal = hayControlHorizontalEn(raiz, ev.rawX, ev.rawY)
        }

        val cambiado = !gestoSobreControlHorizontal && detector.onTouchEvent(ev)

        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            gestoSobreControlHorizontal = false
        }
        return cambiado
    }

    private fun hayControlHorizontalEn(view: View, rawX: Float, rawY: Float): Boolean {
        if (!view.isShown) return false
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val dentro = rawX >= location[0] && rawX <= location[0] + view.width &&
            rawY >= location[1] && rawY <= location[1] + view.height
        if (!dentro) return false
        if (view is HorizontalScrollView || view is CompoundButton) return true
        // Vistas propias que también se arrastran a los lados, como los avisos (se descartan así).
        if (view.getTag(R.id.tag_arrastre_horizontal) == true) return true
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                if (hayControlHorizontalEn(view.getChildAt(i), rawX, rawY)) return true
            }
        }
        return false
    }

    private companion object {
        const val DISTANCIA_MINIMA_DP = 30f
        const val VELOCIDAD_MINIMA_DP = 30f
    }
}
