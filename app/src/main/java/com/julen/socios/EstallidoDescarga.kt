package com.julen.socios

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** Las dos ondas y las partículas que salen del anillo de [AnimacionDescargaView] al terminar la descarga. */
internal class EstallidoDescarga(context: Context) {

    private class Particula(val angulo: Float, val distancia: Float, val tamano: Float, val color: Int, val retraso: Float)

    private val colorOnda = ContextCompat.getColor(context, R.color.primary)
    private val colores = intArrayOf(
        colorOnda,
        ContextCompat.getColor(context, R.color.accent_blue),
        ContextCompat.getColor(context, R.color.amber_text),
        ContextCompat.getColor(context, R.color.green_text_soft),
        ContextCompat.getColor(context, R.color.orange_pending)
    )
    private val pintaOnda = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pintaParticula = Paint(Paint.ANTI_ALIAS_FLAG)
    private val frenada = DecelerateInterpolator(1.6f)

    private var particulas: List<Particula> = emptyList()

    /** Reparte las partículas alrededor de un anillo de [radio] y [grosor]; siempre igual, con la misma semilla. */
    fun preparar(radio: Float, grosor: Float) {
        val azar = Random(SEMILLA)
        particulas = List(N_PARTICULAS) { i ->
            Particula(
                angulo = (i * 2 * PI / N_PARTICULAS).toFloat() + azar.nextFloat() * 0.4f - 0.2f,
                distancia = radio * (0.30f + azar.nextFloat() * 0.25f),
                tamano = grosor * (0.26f + azar.nextFloat() * 0.24f),
                color = colores[i % colores.size],
                retraso = azar.nextFloat() * 90f
            )
        }
    }

    /** Dibuja el estallido [t] ms después de empezar, alrededor del anillo centrado en ([cx], [cy]). */
    fun dibujar(canvas: Canvas, t: Float, cx: Float, cy: Float, radio: Float, grosor: Float) {
        // Dos ondas que se alejan del anillo.
        for (i in 0 until 2) {
            val p = ((t - 120f - i * 220f) / 700f).coerceIn(0f, 1f)
            if (p <= 0f || p >= 1f) continue
            val q = frenada.getInterpolation(p)
            pintaOnda.color = colorOnda
            pintaOnda.alpha = ((1 - p) * 140).roundToInt()
            pintaOnda.strokeWidth = grosor * 0.5f * (1 - q) + 1f
            canvas.drawCircle(cx, cy, radio + grosor / 2 + radio * 0.55f * q, pintaOnda)
        }

        // Partículas que salen disparadas y se apagan.
        for (particula in particulas) {
            val p = ((t - 140f - particula.retraso) / 760f).coerceIn(0f, 1f)
            if (p <= 0f || p >= 1f) continue
            val distancia = radio + grosor + particula.distancia * frenada.getInterpolation(p)
            pintaParticula.color = particula.color
            pintaParticula.alpha = ((1 - p * p) * 255).roundToInt()
            canvas.drawCircle(
                cx + distancia * cos(particula.angulo),
                cy + distancia * sin(particula.angulo),
                particula.tamano * (1 - p * 0.7f),
                pintaParticula
            )
        }
    }

    private companion object {
        const val N_PARTICULAS = 16
        const val SEMILLA = 7
    }
}
