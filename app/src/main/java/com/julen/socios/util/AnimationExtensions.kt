package com.julen.socios.util

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.ProgressBar

object AnimationExtensions {

    /**
     * Adds a touch scaling animation to the view so it shrinks slightly when pressed and springs back when released.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun setupPressScaleAnimation(view: View, scaleFactor: Float = 0.96f) {
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.animate().scaleX(scaleFactor).scaleY(scaleFactor).setDuration(80).start()
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120)
                        .setInterpolator(OvershootInterpolator(2.0f)).start()
                }
            }
            false
        }
    }

    /**
     * Smoothly animates progress of a ProgressBar with OvershootInterpolator for a springy feel.
     */
    fun animateProgressSmooth(
        progressBar: ProgressBar, targetProgress: Int, duration: Long = 500L
    ) {
        val animation =
            ObjectAnimator.ofInt(progressBar, "progress", progressBar.progress, targetProgress)
        animation.duration = duration
        animation.interpolator = OvershootInterpolator(1.2f)
        animation.start()
    }
}
