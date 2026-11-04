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
        val enCurso = (textView.getTag(R.id.tag_numeric_animator) as? ValueAnimator)?.takeIf { it.isRunning }
        val objetivoAnterior = textView.getTag(R.id.tag_numeric_value) as? Double
        // El mismo importe otra vez mientras cuenta hacia él: sigue contando, sin volver a empezar.
        if (enCurso != null && objetivoAnterior == newValue) return
        // Si estaba contando, sale del número que se ve y no del objetivo anterior (no salta).
        val startValue = (enCurso?.animatedValue as? Float)?.toDouble() ?: objetivoAnterior ?: 0.0
        // Se suelta antes de cancelarlo: su final ya no es el actual y no pinta el importe anterior.
        textView.setTag(R.id.tag_numeric_animator, null)
        enCurso?.cancel()
        textView.setTag(R.id.tag_numeric_value, newValue)

        // El cero, sin signo ni decimales: "0 €" y no "-0,00 €" en una semana sin nada.
        fun texto(valor: Double) =
            if (abs(valor) < 0.005) 0.0.toCurrencyString(decimals = 0) else valor.toCurrencyString(decimals, prefix)

        if (abs(startValue - newValue) < 0.001) {
            textView.text = texto(newValue)
            return
        }

        val animator = ValueAnimator.ofFloat(startValue.toFloat(), newValue.toFloat()).apply {
            // Fuera de util/Movimiento a propósito: así contaban los importes al cambiar de semana y se quiere así.
            duration = DURACION_MS
            interpolator = FastOutSlowInInterpolator()
            addUpdateListener { anim ->
                val current = (anim.animatedValue as Float).toDouble()
                textView.text = texto(current)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // Cancelado porque llega otro importe: el nuevo animador sigue desde aquí.
                    if (textView.getTag(R.id.tag_numeric_animator) !== animation) return
                    // Terminar siempre en el valor exacto, sin el error de redondeo del Float.
                    textView.text = texto(newValue)
                    textView.setTag(R.id.tag_numeric_animator, null)
                }
            })
        }

        textView.setTag(R.id.tag_numeric_animator, animator)
        animator.start()
    }

    private const val DURACION_MS = 350L
}
