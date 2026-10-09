package com.julen.socios.util

import android.animation.ValueAnimator
import android.widget.TextView
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.julen.socios.R
import java.util.Locale
import kotlin.math.abs

object NumberAnimators {

    private val activeAnimators = mutableMapOf<TextView, ValueAnimator>()

    fun animateCurrency(
        textView: TextView,
        newValue: Double,
        prefix: String = "",
        suffix: String = " €",
        decimals: Int = 2,
        duration: Long = 350L
    ) {
        // Cancel any running animation on this TextView
        activeAnimators[textView]?.cancel()

        // Extract current value from tag or parse from current text
        val startValue = (textView.getTag(R.id.tag_numeric_value) as? Double) ?: 0.0
        textView.setTag(R.id.tag_numeric_value, newValue)

        if (abs(startValue - newValue) < 0.001) {
            textView.text = formatValue(newValue, prefix, suffix, decimals)
            return
        }

        val animator = ValueAnimator.ofFloat(startValue.toFloat(), newValue.toFloat()).apply {
            this.duration = duration
            interpolator = FastOutSlowInInterpolator()
            addUpdateListener { anim ->
                val current = (anim.animatedValue as Float).toDouble()
                textView.text = formatValue(current, prefix, suffix, decimals)
            }
        }

        activeAnimators[textView] = animator
        animator.start()
    }

    private fun formatValue(value: Double, prefix: String, suffix: String, decimals: Int): String {
        val formatStr = "%.${decimals}f"
        val formatted = String.format(Locale.getDefault(), formatStr, value)
        return "$prefix$formatted$suffix"
    }
}
