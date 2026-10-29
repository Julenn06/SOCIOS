package com.julen.socios

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.julen.socios.util.Movimiento
import kotlin.math.min

/**
 * Los cinco días laborables de la semana elegida, como cinco rayitas bajo la fecha del selector. En la semana
 * actual, los días ya pasados van en verde suave y hoy en verde, más largo; en las pasadas, todos en verde suave;
 * en las futuras, todos vacíos. Al cambiar, los días se rellenan o se vacían uno tras otro.
 */
class FranjaSemanaView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Momento { PASADA, ACTUAL, FUTURA }

    private val densidad = resources.displayMetrics.density
    private val ancho = 14 * densidad
    private val anchoHoy = 24 * densidad
    private val alto = 4 * densidad
    private val altoHoy = 5 * densidad
    private val separacion = 5 * densidad

    /** Días rellenos (con decimales mientras se anima) y cuánto se ve la marca de hoy, de 0 a 1. */
    private var llenos = 0f
    private var marcaHoy = 0f
    private var hoy = -1
    private var animacion: ValueAnimator? = null

    private val colorPrincipal = ContextCompat.getColor(context, R.color.primary)
    private val colorLleno = ColorUtils.setAlphaComponent(colorPrincipal, 120)
    private val colorVacio = ContextCompat.getColor(context, R.color.divider)
    private val pinta = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val anchos = FloatArray(DIAS)

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** [diaHoy]: el de hoy, de 0 (lunes) a 6 (domingo); solo cuenta en la semana [Momento.ACTUAL]. */
    fun mostrar(momento: Momento, diaHoy: Int, animar: Boolean) {
        val nuevoHoy = if (momento == Momento.ACTUAL && diaHoy < DIAS) diaHoy else -1
        val objetivo = when (momento) {
            Momento.PASADA -> DIAS
            Momento.FUTURA -> 0
            // El fin de semana, la semana ya está entera.
            Momento.ACTUAL -> if (nuevoHoy >= 0) nuevoHoy else DIAS
        }.toFloat()
        val marca = if (nuevoHoy >= 0) 1f else 0f
        if (llenos == objetivo && marcaHoy == marca && (hoy == nuevoHoy || marca == 0f)) return

        animacion?.cancel()
        if (!animar || !Movimiento.activas()) {
            llenos = objetivo
            hoy = nuevoHoy
            marcaHoy = marca
            invalidate()
            return
        }
        // Al salir de la semana actual, la marca de hoy se apaga; al volver, se enciende. A la vez se rellenan los días.
        val llenosDesde = llenos
        val marcaDesde = if (nuevoHoy < 0 || hoy == nuevoHoy) marcaHoy else 0f
        if (nuevoHoy >= 0) hoy = nuevoHoy
        animacion = Movimiento.animarValor(context, 0f, 1f, Movimiento.LARGA_MS) { t ->
            llenos = llenosDesde + (objetivo - llenosDesde) * t
            marcaHoy = marcaDesde + (marca - marcaDesde) * t
            if (t >= 1f) hoy = nuevoHoy
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val anchoTotal = (ancho * (DIAS - 1) + anchoHoy + separacion * (DIAS - 1)).toInt() + paddingLeft + paddingRight
        val altoTotal = altoHoy.toInt() + paddingTop + paddingBottom
        setMeasuredDimension(resolveSize(anchoTotal, widthMeasureSpec), resolveSize(altoTotal, heightMeasureSpec))
    }

    override fun onDetachedFromWindow() {
        animacion?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        // Los días ocupan siempre lo mismo; el de hoy se ensancha según se ve su marca. Si no caben (pantalla
        // estrecha o letra grande), se estrechan todos a la vez.
        for (i in 0 until DIAS) anchos[i] = if (i == hoy) ancho + (anchoHoy - ancho) * marcaHoy else ancho
        val disponible = (width - paddingLeft - paddingRight).toFloat()
        val escala = min(1f, disponible / (anchos.sum() + separacion * (DIAS - 1)))
        val hueco = separacion * escala
        for (i in 0 until DIAS) anchos[i] *= escala
        val total = anchos.sum() + hueco * (DIAS - 1)
        var x = paddingLeft + (disponible - total) / 2
        val centroY = height / 2f
        for (i in 0 until DIAS) {
            val esHoy = i == hoy && marcaHoy > 0f
            val altoDia = if (esHoy) alto + (altoHoy - alto) * marcaHoy else alto
            val relleno = (llenos - i).coerceIn(0f, 1f)
            pinta.color = when {
                esHoy -> ColorUtils.blendARGB(ColorUtils.blendARGB(colorVacio, colorLleno, relleno), colorPrincipal, marcaHoy)
                else -> ColorUtils.blendARGB(colorVacio, colorLleno, relleno)
            }
            rect.set(x, centroY - altoDia / 2, x + anchos[i], centroY + altoDia / 2)
            canvas.drawRoundRect(rect, altoDia, altoDia, pinta)
            x += anchos[i] + hueco
        }
    }

    private companion object {
        const val DIAS = 5
    }
}
