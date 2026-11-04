package com.julen.socios

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
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

    private class Paleta(val fondo: Int, val linea: Int, val acento: Int, val tinta: Int)

    private val claro = Paleta(
        color(R.color.muestra_claro_fondo), color(R.color.muestra_claro_linea),
        color(R.color.muestra_claro_acento), color(R.color.muestra_claro_tinta)
    )
    private val oscuro = Paleta(
        color(R.color.muestra_oscuro_fondo), color(R.color.muestra_oscuro_linea),
        color(R.color.muestra_oscuro_acento), color(R.color.muestra_oscuro_tinta)
    )

    private val pinta = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val pantalla = Path()
    private val mitadOscura = Path()
    private val densidad = resources.displayMetrics.density
    private val radio = densidad * 10
    private val radioPequeno = densidad * 2
    private val pelo = densidad.coerceAtLeast(1f)

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

    /** Título, el neto en grande, sus tres columnas, dos socios en filas y el botón de añadir en tinta. */
    private fun dibujarPantalla(canvas: Canvas, paleta: Paleta) {
        val w = width.toFloat()
        val h = height.toFloat()
        val margen = w * 0.09f
        val ancho = w - 2 * margen
        canvas.drawColor(paleta.fondo)

        // Título de la barra
        pinta.color = paleta.tinta
        barra(canvas, margen, h * 0.07f, w * 0.52f, h * 0.105f)

        // El neto, grande, y sus tres columnas con una regla encima
        barra(canvas, margen, h * 0.18f, w * 0.58f, h * 0.27f)
        for (i in 0 until 3) {
            val izquierda = margen + i * ancho / 3
            val derecha = izquierda + ancho / 3 - margen * 0.4f
            pinta.color = paleta.linea
            canvas.drawRect(izquierda, h * 0.33f, derecha, h * 0.33f + pelo, pinta)
            pinta.color = if (i == 0) paleta.tinta else paleta.linea
            barra(canvas, izquierda, h * 0.37f, izquierda + (derecha - izquierda) * 0.7f, h * 0.40f)
        }

        // Dos socios: marca del estado, nombre, importe a la derecha y una línea fina debajo
        val altoFila = h * 0.13f
        for (i in 0 until 2) {
            val arriba = h * 0.49f + i * altoFila
            pinta.color = paleta.acento
            canvas.drawRect(margen, arriba + altoFila * 0.25f, margen + pelo * 2, arriba + altoFila * 0.75f, pinta)
            pinta.color = paleta.tinta
            barra(canvas, margen + ancho * 0.08f, arriba + altoFila * 0.4f, w * (0.5f - i * 0.08f), arriba + altoFila * 0.6f)
            barra(canvas, w - margen - ancho * 0.16f, arriba + altoFila * 0.4f, w - margen, arriba + altoFila * 0.6f)
            pinta.color = paleta.linea
            canvas.drawRect(margen, arriba + altoFila, w - margen, arriba + altoFila + pelo, pinta)
        }

        // Botón de "Nuevo socio", en tinta
        pinta.color = paleta.tinta
        rect.set(w - margen - w * 0.26f, h * 0.83f, w - margen, h * 0.94f)
        canvas.drawRoundRect(rect, radioPequeno * 3, radioPequeno * 3, pinta)
    }

    private fun barra(canvas: Canvas, izquierda: Float, arriba: Float, derecha: Float, abajo: Float) {
        rect.set(izquierda, arriba, derecha, abajo)
        canvas.drawRoundRect(rect, radioPequeno, radioPequeno, pinta)
    }
}
