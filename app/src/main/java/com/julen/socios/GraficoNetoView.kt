package com.julen.socios

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.julen.socios.util.HapticUtils
import com.julen.socios.util.toEurosCompacto
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Columnas del neto por semana, mes o año, de la más antigua a la más reciente. Una sola serie, así que no
 * lleva leyenda: la barra [destacada] (la semana o el mes actual) va en el verde de la app y el resto en un
 * paso más suave del mismo verde. Las barras crecen desde una única línea base, con la punta redondeada y
 * la base recta; un neto negativo baja de la línea en rojo.
 *
 * Solo se rotulan el máximo y la destacada; el resto se lee tocando la barra, que muestra su importe.
 */
class GraficoNetoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** Una columna: su etiqueta corta en el eje ("S42", "oct", "2026"), la larga para el aviso y su neto. */
    data class Columna(val etiqueta: String, val etiquetaLarga: String, val neto: Double)

    private val densidad = resources.displayMetrics.density
    private val anchoMaxBarra = 24 * densidad
    private val separacionMin = 2 * densidad
    private val radio = 4 * densidad
    private val altoEje = 22 * densidad
    private val altoRotulo = 18 * densidad
    private val altoAviso = 28 * densidad

    private var columnas: List<Columna> = emptyList()
    private var destacada = -1
    private var seleccionada = -1
    private var progreso = 1f
    private var animacion: ValueAnimator? = null

    /** Se llama al tocar una columna, para que la pantalla la anuncie a TalkBack. */
    var alSeleccionar: ((Columna) -> Unit)? = null

    private val colorBarra = ContextCompat.getColor(context, R.color.grafico_barra)
    private val colorBarraSuave = ContextCompat.getColor(context, R.color.grafico_barra_suave)
    private val colorNegativo = ContextCompat.getColor(context, R.color.red_text)
    private val colorEje = ContextCompat.getColor(context, R.color.divider)
    private val colorTexto = ContextCompat.getColor(context, R.color.text_main)
    private val colorTextoSuave = ContextCompat.getColor(context, R.color.text_sub)
    private val colorAvisoFondo = ContextCompat.getColor(context, R.color.text_main)
    private val colorAvisoTexto = ContextCompat.getColor(context, R.color.surface_card)

    private val pintaBarra = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pintaEje = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorEje
        strokeWidth = max(1f, densidad)
    }
    private val pintaEtiqueta = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = ResourcesCompat.getFont(context, R.font.geist_medium)
        textSize = sp(10.5f)
        color = colorTextoSuave
    }
    private val pintaRotulo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = ResourcesCompat.getFont(context, R.font.geist_semibold)
        fontFeatureSettings = "tnum"
        textSize = sp(11f)
        color = colorTexto
    }
    private val pintaAvisoFondo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorAvisoFondo }
    private val pintaAvisoTexto = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = ResourcesCompat.getFont(context, R.font.geist_semibold)
        fontFeatureSettings = "tnum"
        textSize = sp(12f)
        color = colorAvisoTexto
    }
    private val rect = RectF()
    private val path = Path()

    private fun sp(valor: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, valor, resources.displayMetrics)

    /** Muestra [nuevas] columnas; con [animar], las barras crecen desde la línea base. */
    fun mostrar(nuevas: List<Columna>, destacada: Int, animar: Boolean) {
        val mismas = nuevas.map { it.etiquetaLarga } == columnas.map { it.etiquetaLarga }
        columnas = nuevas
        this.destacada = destacada
        if (!mismas) seleccionada = -1
        animacion?.cancel()
        if (!animar) {
            progreso = 1f
            invalidate()
            return
        }
        animacion = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 450L
            interpolator = FastOutSlowInInterpolator()
            addUpdateListener {
                progreso = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val alto = resolveSize((168 * densidad).toInt(), heightMeasureSpec)
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), alto)
    }

    override fun onDetachedFromWindow() {
        animacion?.cancel()
        super.onDetachedFromWindow()
    }

    private val anchoHueco: Float get() = (width - paddingLeft - paddingRight).toFloat() / max(1, columnas.size)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (columnas.isEmpty()) return

        val maximo = columnas.maxOf { it.neto }.coerceAtLeast(0.0)
        val minimo = columnas.minOf { it.neto }.coerceAtMost(0.0)
        val rango = (maximo - minimo).takeIf { it > 0.0 } ?: 1.0
        // Arriba queda sitio para el aviso de la columna tocada y los rótulos; abajo, para las etiquetas.
        val arriba = paddingTop + altoAviso + altoRotulo
        val abajo = height - paddingBottom - altoEje
        val altoUtil = abajo - arriba
        val base = arriba + (altoUtil * (maximo / rango)).toFloat()

        val hueco = anchoHueco
        val anchoBarra = min(anchoMaxBarra, hueco - separacionMin * 2)
        val indiceMaximo = columnas.indices.maxByOrNull { columnas[it].neto }?.takeIf { columnas[it].neto > 0.0 } ?: -1

        // Línea base: un pelo, en el gris de los separadores.
        canvas.drawLine(paddingLeft.toFloat(), base, (width - paddingRight).toFloat(), base, pintaEje)

        // Etiquetas del eje: todas si caben; si no, una de cada pocas contando desde la destacada, que siempre va.
        val anchoEtiqueta = columnas.maxOf { pintaEtiqueta.measureText(it.etiqueta) } + 6 * densidad
        val paso = max(1, ceil(anchoEtiqueta / hueco).toInt())
        val anclaje = if (destacada in columnas.indices) destacada else columnas.lastIndex

        columnas.forEachIndexed { i, columna ->
            val centro = paddingLeft + hueco * (i + 0.5f)
            val alto = (altoUtil * (abs(columna.neto) / rango) * progreso).toFloat()
            pintaBarra.color = when {
                columna.neto < 0 -> colorNegativo
                i == destacada || i == seleccionada -> colorBarra
                else -> colorBarraSuave
            }
            if (alto > 0.5f) {
                if (columna.neto >= 0) {
                    barra(canvas, centro - anchoBarra / 2, base - alto, centro + anchoBarra / 2, base, puntaArriba = true)
                } else {
                    barra(canvas, centro - anchoBarra / 2, base, centro + anchoBarra / 2, base + alto, puntaArriba = false)
                }
            }

            if ((anclaje - i) % paso == 0) {
                pintaEtiqueta.color = if (i == destacada) colorTexto else colorTextoSuave
                canvas.drawText(columna.etiqueta, centro, abajo + altoEje - 6 * densidad, pintaEtiqueta)
            }

            // Rótulos directos solo en el máximo y en la destacada (si no hay una columna tocada encima).
            if ((i == indiceMaximo || i == destacada) && i != seleccionada && columna.neto != 0.0 && progreso >= 1f) {
                val y = if (columna.neto >= 0) base - alto - 6 * densidad else base + alto + altoRotulo - 4 * densidad
                val rotulo = columna.neto.toEurosCompacto()
                // En las barras de los extremos el rótulo se mete hacia dentro en lugar de salirse del gráfico.
                val mitad = pintaRotulo.measureText(rotulo) / 2
                val x = centro.coerceIn(paddingLeft + mitad, width - paddingRight - mitad)
                canvas.drawText(rotulo, x, y.coerceAtMost(abajo), pintaRotulo)
            }
        }

        if (seleccionada in columnas.indices) dibujarAviso(canvas, seleccionada, base, altoUtil, rango)
    }

    /** Barra con la punta (el extremo del dato) redondeada y la base recta, pegada a la línea base. */
    private fun barra(canvas: Canvas, izquierda: Float, arriba: Float, derecha: Float, abajo: Float, puntaArriba: Boolean) {
        val r = min(radio, (abajo - arriba) / 2)
        val radios = if (puntaArriba) {
            floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        } else {
            floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
        }
        rect.set(izquierda, arriba, derecha, abajo)
        path.reset()
        path.addRoundRect(rect, radios, Path.Direction.CW)
        canvas.drawPath(path, pintaBarra)
    }

    /** Pastilla con la etiqueta y el importe de la columna tocada, encima de su barra. */
    private fun dibujarAviso(canvas: Canvas, indice: Int, base: Float, altoUtil: Float, rango: Double) {
        val columna = columnas[indice]
        val texto = context.getString(R.string.grafico_seleccion, columna.etiquetaLarga, columna.neto.toEurosCompacto())
        val ancho = pintaAvisoTexto.measureText(texto) + 20 * densidad
        val alto = 24 * densidad
        val centro = paddingLeft + anchoHueco * (indice + 0.5f)
        val izquierda = (centro - ancho / 2).coerceIn(paddingLeft.toFloat(), (width - paddingRight) - ancho)
        val cima = if (columna.neto >= 0) base - (altoUtil * (columna.neto / rango)).toFloat() else base
        val y = (cima - alto - 8 * densidad).coerceAtLeast(paddingTop.toFloat())
        rect.set(izquierda, y, izquierda + ancho, y + alto)
        canvas.drawRoundRect(rect, alto / 2, alto / 2, pintaAvisoFondo)
        val baseTexto = rect.centerY() - (pintaAvisoTexto.descent() + pintaAvisoTexto.ascent()) / 2
        canvas.drawText(texto, rect.centerX(), baseTexto, pintaAvisoTexto)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (columnas.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Que el panel no se arrastre mientras se recorren las barras con el dedo.
                parent?.requestDisallowInterceptTouchEvent(true)
                seleccionar(event.x)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                seleccionar(event.x)
                return true
            }

            MotionEvent.ACTION_UP -> {
                performClick()
                return true
            }

            MotionEvent.ACTION_CANCEL -> return true
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /** Toda la franja vertical de una columna es su zona táctil, no solo la barra. */
    private fun seleccionar(x: Float) {
        val indice = ((x - paddingLeft) / anchoHueco).toInt().coerceIn(0, columnas.lastIndex)
        if (indice == seleccionada) return
        seleccionada = indice
        HapticUtils.performClick(this)
        alSeleccionar?.invoke(columnas[indice])
        invalidate()
    }
}
