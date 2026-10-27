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

    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        private val swipeThreshold = 80
        private val swipeVelocityThreshold = 80

        override fun onFling(
            e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float
        ): Boolean {
            if (e1 == null) return false
            val diffX = e2.x - e1.x
            val diffY = e2.y - e1.y

            if (abs(diffX) > abs(diffY) * 1.3f &&
                abs(diffX) > swipeThreshold && abs(velocityX) > swipeVelocityThreshold
            ) {
                alDeslizar(if (diffX < 0) 1 else -1)
                return true
            }
            return false
        }
    })

    fun onTouchEvent(ev: MotionEvent) {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            // Un deslizamiento que empieza en un control que ya se arrastra en horizontal (switch,
            // chip, fila desplazable) es para ese control, no para cambiar de semana.
            gestoSobreControlHorizontal = hayControlHorizontalEn(raiz, ev.rawX, ev.rawY)
        }

        if (!gestoSobreControlHorizontal) {
            detector.onTouchEvent(ev)
        }

        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            gestoSobreControlHorizontal = false
        }
    }

    private fun hayControlHorizontalEn(view: View, rawX: Float, rawY: Float): Boolean {
        if (!view.isShown) return false
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val dentro = rawX >= location[0] && rawX <= location[0] + view.width &&
            rawY >= location[1] && rawY <= location[1] + view.height
        if (!dentro) return false
        if (view is HorizontalScrollView || view is CompoundButton) return true
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                if (hayControlHorizontalEn(view.getChildAt(i), rawX, rawY)) return true
            }
        }
        return false
    }
}
