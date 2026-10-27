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
        decimals: Int = 2
    ) {
        // El animador y el último valor se guardan en la propia vista: así no se retienen
        // TextViews (ni su Activity) en un objeto global cuando la Activity se recrea.
        (textView.getTag(R.id.tag_numeric_animator) as? ValueAnimator)?.cancel()

        val startValue = (textView.getTag(R.id.tag_numeric_value) as? Double) ?: 0.0
        textView.setTag(R.id.tag_numeric_value, newValue)

        // El cero, sin signo ni decimales: "0 €" y no "-0,00 €" en una semana sin nada.
        fun texto(valor: Double) =
            if (abs(valor) < 0.005) 0.0.toCurrencyString(decimals = 0) else valor.toCurrencyString(decimals, prefix)

        if (abs(startValue - newValue) < 0.001) {
            textView.text = texto(newValue)
            return
        }

        val animator = ValueAnimator.ofFloat(startValue.toFloat(), newValue.toFloat()).apply {
            duration = 350L
            interpolator = FastOutSlowInInterpolator()
            addUpdateListener { anim ->
                val current = (anim.animatedValue as Float).toDouble()
                textView.text = texto(current)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // Terminar siempre en el valor exacto, sin el error de redondeo del Float.
                    textView.text = texto(newValue)
                    textView.setTag(R.id.tag_numeric_animator, null)
                }
            })
        }

        textView.setTag(R.id.tag_numeric_animator, animator)
        animator.start()
    }
}
