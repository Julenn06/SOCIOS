package com.julen.socios.util

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator

object AnimationExtensions {

    /**
     * Adds a touch scaling animation to the view so it shrinks slightly when pressed and springs back when released.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun setupPressScaleAnimation(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(80).start()
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120)
                        .setInterpolator(OvershootInterpolator(2.0f)).start()
                }
            }
            false
        }
    }
}
