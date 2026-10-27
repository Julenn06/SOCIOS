package com.julen.socios.util

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View

object AnimationExtensions {

    /**
     * Al pulsar, la vista se hunde un poco bajo el dedo y al soltar vuelve con un pequeño rebote. Con muelles
     * (ver [Movimiento]): si se pulsa otra vez antes de terminar, sigue desde donde iba sin saltos.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun setupPressScaleAnimation(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> Movimiento.hundir(v)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> Movimiento.soltar(v)
            }
            false
        }
    }
}
