package com.julen.socios

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.julen.socios.util.CalculoComisiones
import com.julen.socios.util.toEurosCompacto
import kotlin.math.max

/**
 * Progreso hacia los tramos del bonus: una barra con un hito por tramo (5 socios → 50 €, 7 → 100 €…),
 * con el número de socios dentro de cada hito y el importe debajo. Los hitos alcanzados se rellenan y la
 * barra avanza animada. Con muchos socios muestra solo los tramos cercanos.
 */
class ProgresoBonusView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val densidad = resources.displayMetrics.density
    private val radioHito = 12 * densidad
    private val grosor = 8 * densidad

    private var inicio = 0
    private var fin = 10
    private var hitos = listOf(5, 7, 10)

    /** Socios que dibuja la barra; persigue al valor real con una animación. */
    private var mostrado = 0f
    private var animacion: ValueAnimator? = null

    private val colorPrincipal = ContextCompat.getColor(context, R.color.primary)
    private val colorInicio = ContextCompat.getColor(context, R.color.badge_gradient_start)
    private val colorPista = ContextCompat.getColor(context, R.color.divider)
    private val colorFondo = ContextCompat.getColor(context, R.color.surface_card)

    private val pintaPista = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorPista }
    private val pintaRelleno = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pintaHito = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pintaBorde = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * densidad
    }
    private val pintaNumero = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        textSize = sp(11f)
    }
    private val pintaImporte = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
        textSize = sp(11.5f)
    }
    private val colorTexto = ContextCompat.getColor(context, R.color.text_main)
    private val colorTextoSuave = ContextCompat.getColor(context, R.color.text_sub)
    private val rect = RectF()

    private fun sp(valor: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, valor, resources.displayMetrics)

    /** Muestra [socios] hechos; con [animar], la barra avanza (o retrocede) hasta ellos. */
    fun mostrar(socios: Int, animar: Boolean) {
        val siguiente = CalculoComisiones.obtenerSiguienteMetaBonus(socios).first
        fin = max(TRAMO_FIJO_MAX, siguiente)
        inicio = if (fin <= TRAMO_FIJO_MAX) 0 else fin - VENTANA
        hitos = umbrales().filter { it in (inicio + 1)..fin }

        animacion?.cancel()
        if (!animar) {
            mostrado = socios.toFloat()
            invalidate()
            return
        }
        animacion = ValueAnimator.ofFloat(mostrado, socios.toFloat()).apply {
            duration = DURACION_MS
            interpolator = FastOutSlowInInterpolator()
            addUpdateListener {
                mostrado = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** Socios con los que empieza cada tramo: 5, 7, 10 y luego cada 3 (13, 16…). */
    private fun umbrales(): List<Int> =
        listOf(5, 7) + generateSequence(10) { it + 3 }.takeWhile { it <= fin }.toList()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val alto = (radioHito * 2 + 22 * densidad).toInt() + paddingTop + paddingBottom
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), resolveSize(alto, heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        pintaRelleno.shader = LinearGradient(
            paddingLeft.toFloat(), 0f, (w - paddingRight).toFloat(), 0f, colorInicio, colorPrincipal, Shader.TileMode.CLAMP
        )
    }

    override fun onDetachedFromWindow() {
        animacion?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        // Margen para que el importe del último hito no se corte.
        val izquierda = paddingLeft + radioHito
        val derecha = width - paddingRight - radioHito * 1.6f
        val centroY = paddingTop + radioHito
        fun x(valor: Float) = izquierda + (valor - inicio) / (fin - inicio) * (derecha - izquierda)

        rect.set(izquierda, centroY - grosor / 2, derecha, centroY + grosor / 2)
        canvas.drawRoundRect(rect, grosor, grosor, pintaPista)
        val hasta = x(mostrado.coerceIn(inicio.toFloat(), fin.toFloat()))
        if (hasta > izquierda) {
            rect.set(izquierda, centroY - grosor / 2, hasta, centroY + grosor / 2)
            canvas.drawRoundRect(rect, grosor, grosor, pintaRelleno)
        }

        val baseNumero = centroY - (pintaNumero.descent() + pintaNumero.ascent()) / 2
        val baseImporte = centroY + radioHito + 16 * densidad
        for (hito in hitos) {
            val cx = x(hito.toFloat())
            val alcanzado = mostrado >= hito
            if (alcanzado) {
                pintaHito.color = colorPrincipal
                canvas.drawCircle(cx, centroY, radioHito, pintaHito)
            } else {
                pintaHito.color = colorFondo
                canvas.drawCircle(cx, centroY, radioHito, pintaHito)
                pintaBorde.color = colorPista
                canvas.drawCircle(cx, centroY, radioHito - pintaBorde.strokeWidth / 2, pintaBorde)
            }
            pintaNumero.color = if (alcanzado) colorFondo else colorTextoSuave
            canvas.drawText(hito.toString(), cx, baseNumero, pintaNumero)
            pintaImporte.color = if (alcanzado) colorTexto else colorTextoSuave
            canvas.drawText(
                CalculoComisiones.calcularBonusSemanal(hito).toEurosCompacto(prefix = "+"), cx, baseImporte, pintaImporte
            )
        }
    }

    private companion object {
        /** Hasta 10 socios los tramos son 5, 7 y 10; después, uno cada 3. */
        const val TRAMO_FIJO_MAX = 10
        const val VENTANA = 9
        const val DURACION_MS = 650L
    }
}
