package com.julen.socios.util

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.widget.TextView
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.julen.socios.R
import kotlin.math.abs

object NumberAnimators {

    fun animateCurrency(
        textView: TextView,
        newValue: Double,
        prefix: String = "",
        suffix: String = " €",
        decimals: Int = 2,
        duration: Long = 350L
    ) {
        // El animador y el último valor se guardan en la propia vista: así no se retienen
        // TextViews (ni su Activity) en un objeto global cuando la Activity se recrea.
        (textView.getTag(R.id.tag_numeric_animator) as? ValueAnimator)?.cancel()

        val startValue = (textView.getTag(R.id.tag_numeric_value) as? Double) ?: 0.0
        textView.setTag(R.id.tag_numeric_value, newValue)

        if (abs(startValue - newValue) < 0.001) {
            textView.text = newValue.toCurrencyString(decimals, prefix, suffix)
            return
        }

        val animator = ValueAnimator.ofFloat(startValue.toFloat(), newValue.toFloat()).apply {
            this.duration = duration
            interpolator = FastOutSlowInInterpolator()
            addUpdateListener { anim ->
                val current = (anim.animatedValue as Float).toDouble()
                textView.text = current.toCurrencyString(decimals, prefix, suffix)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // Terminar siempre en el valor exacto, sin el error de redondeo del Float.
                    textView.text = newValue.toCurrencyString(decimals, prefix, suffix)
                    textView.setTag(R.id.tag_numeric_animator, null)
                }
            })
        }

        textView.setTag(R.id.tag_numeric_animator, animator)
        animator.start()
    }
}
