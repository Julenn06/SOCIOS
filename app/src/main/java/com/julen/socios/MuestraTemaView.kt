package com.julen.socios

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withClip
import com.julen.socios.ajustes.Tema

/**
 * Miniatura de la pantalla principal en un tema, para el selector del panel "Más": claro, oscuro o, para
 * "según el sistema", partida en diagonal entre los dos. Usa siempre los colores de ese tema, no los del
 * tema que tenga la app en ese momento.
 */
class MuestraTemaView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var tema: Tema = Tema.SISTEMA
        set(valor) {
            field = valor
            invalidate()
        }

    private class Paleta(val fondo: Int, val tarjeta: Int, val linea: Int, val acento: Int)

    private val claro = Paleta(
        color(R.color.muestra_claro_fondo), color(R.color.muestra_claro_tarjeta),
        color(R.color.muestra_claro_linea), color(R.color.muestra_claro_acento)
    )
    private val oscuro = Paleta(
        color(R.color.muestra_oscuro_fondo), color(R.color.muestra_oscuro_tarjeta),
        color(R.color.muestra_oscuro_linea), color(R.color.muestra_oscuro_acento)
    )
    private val textoCabecera = ColorUtils.setAlphaComponent(color(R.color.muestra_claro_tarjeta), 170)

    private val pinta = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pintaCabecera = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val pantalla = Path()
    private val mitadOscura = Path()
    private val radio = resources.displayMetrics.density * 10
    private val radioPequeno = resources.displayMetrics.density * 4

    private fun color(id: Int) = ContextCompat.getColor(context, id)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        pantalla.reset()
        pantalla.addRoundRect(0f, 0f, w.toFloat(), h.toFloat(), radio, radio, Path.Direction.CW)
        // Triángulo inferior derecho: la diagonal va de la esquina superior derecha a la inferior izquierda.
        mitadOscura.reset()
        mitadOscura.moveTo(w.toFloat(), 0f)
        mitadOscura.lineTo(w.toFloat(), h.toFloat())
        mitadOscura.lineTo(0f, h.toFloat())
        mitadOscura.close()
        pintaCabecera.shader = LinearGradient(
            0f, h * 0.17f, w.toFloat(), h * 0.42f,
            color(R.color.header_gradient_start), color(R.color.header_gradient_end), Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        canvas.withClip(pantalla) {
            when (tema) {
                Tema.CLARO -> dibujarPantalla(this, claro)
                Tema.OSCURO -> dibujarPantalla(this, oscuro)
                Tema.SISTEMA -> {
                    dibujarPantalla(this, claro)
                    withClip(mitadOscura) { dibujarPantalla(this, oscuro) }
                }
            }
        }
    }

    /** Título, tarjeta azul del resumen, dos socios y el botón de añadir. */
    private fun dibujarPantalla(canvas: Canvas, paleta: Paleta) {
        val w = width.toFloat()
        val h = height.toFloat()
        val margen = w * 0.09f
        canvas.drawColor(paleta.fondo)

        pinta.color = paleta.linea
        rect.set(margen, h * 0.07f, w * 0.55f, h * 0.115f)
        canvas.drawRoundRect(rect, radioPequeno, radioPequeno, pinta)

        rect.set(margen, h * 0.17f, w - margen, h * 0.42f)
        canvas.drawRoundRect(rect, radioPequeno * 1.5f, radioPequeno * 1.5f, pintaCabecera)
        pinta.color = textoCabecera
        rect.set(margen * 1.8f, h * 0.23f, w * 0.45f, h * 0.26f)
        canvas.drawRoundRect(rect, radioPequeno, radioPequeno, pinta)
        rect.set(margen * 1.8f, h * 0.30f, w * 0.62f, h * 0.36f)
        canvas.drawRoundRect(rect, radioPequeno, radioPequeno, pinta)

        val altoFila = h * 0.13f
        for (i in 0 until 2) {
            val arriba = h * 0.48f + i * h * 0.17f
            pinta.color = paleta.tarjeta
            rect.set(margen, arriba, w - margen, arriba + altoFila)
            canvas.drawRoundRect(rect, radioPequeno, radioPequeno, pinta)
            pinta.color = paleta.acento
            canvas.drawCircle(margen + altoFila / 2, arriba + altoFila / 2, altoFila * 0.22f, pinta)
            pinta.color = paleta.linea
            rect.set(margen + altoFila, arriba + altoFila * 0.4f, w * (0.62f - i * 0.12f), arriba + altoFila * 0.6f)
            canvas.drawRoundRect(rect, radioPequeno, radioPequeno, pinta)
        }

        pinta.color = paleta.acento
        rect.set(w - margen - w * 0.26f, h * 0.84f, w - margen, h * 0.94f)
        canvas.drawRoundRect(rect, h, h, pinta)
    }
}
