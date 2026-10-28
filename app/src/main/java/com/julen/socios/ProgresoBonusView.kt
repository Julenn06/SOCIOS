package com.julen.socios

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.julen.socios.util.CalculoComisiones
import com.julen.socios.util.Movimiento
import com.julen.socios.util.toEurosCompacto
import kotlin.math.max
import kotlin.math.min

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

    // Los textos van dentro de los hitos y entre hitos cercanos: crecen con la letra del sistema, pero solo
    // hasta ESCALA_TEXTO_MAX para no pisarse. TalkBack ya lee el progreso completo (ver MainActivity).
    private val escalaTexto = min(resources.configuration.fontScale, ESCALA_TEXTO_MAX)
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
    private val pintaNumero = pinturaCifra(context, sp(11f))
    private val pintaImporte = pinturaCifra(context, sp(11.5f))
    private val tamanoImporte = pintaImporte.textSize

    /** 12dp, o más si el número de socios no cabe dentro con la letra grande. */
    private val radioHito = max(12 * densidad, pintaNumero.measureText("00") / 2 + 4 * densidad)

    private val colorTexto = ContextCompat.getColor(context, R.color.text_main)
    private val colorTextoSuave = ContextCompat.getColor(context, R.color.text_sub)
    private val rect = RectF()

    private fun sp(valor: Float) = valor * densidad * escalaTexto

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
        animacion = Movimiento.animarValor(context, mostrado, socios.toFloat(), DURACION_MS) {
            mostrado = it
            invalidate()
        }
    }

    /** Socios con los que empieza cada tramo: 5, 7, 10 y luego cada 3 (13, 16…). */
    private fun umbrales(): List<Int> =
        listOf(5, 7) + generateSequence(10) { it + 3 }.takeWhile { it <= fin }.toList()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val altoImporte = pintaImporte.descent() - pintaImporte.ascent()
        val alto = (radioHito * 2 + SEPARACION_IMPORTE * densidad + altoImporte + MARGEN_INFERIOR * densidad).toInt() +
            paddingTop + paddingBottom
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
        pintaImporte.textSize = tamanoImporte
        val importes = hitos.map { CalculoComisiones.calcularBonusSemanal(it).toEurosCompacto(prefix = "+") }
        val izquierda = paddingLeft + radioHito
        // Margen para que el importe del último hito no se corte.
        val anchoUltimo = importes.lastOrNull()?.let(pintaImporte::measureText) ?: 0f
        val derecha = width - paddingRight - max(radioHito * 1.6f, anchoUltimo / 2)
        val centroY = paddingTop + radioHito
        fun x(valor: Float) = izquierda + (valor - inicio) / (fin - inicio) * (derecha - izquierda)

        // Si dos importes vecinos no caben (hitos juntos o letra grande), se encogen lo justo para no pisarse.
        val factor = (1 until hitos.size).minOfOrNull { i ->
            val disponible = x(hitos[i].toFloat()) - x(hitos[i - 1].toFloat()) - 6 * densidad
            val necesario = (pintaImporte.measureText(importes[i - 1]) + pintaImporte.measureText(importes[i])) / 2
            min(1f, disponible / necesario)
        } ?: 1f
        if (factor < 1f) pintaImporte.textSize = tamanoImporte * factor

        rect.set(izquierda, centroY - grosor / 2, derecha, centroY + grosor / 2)
        canvas.drawRoundRect(rect, grosor, grosor, pintaPista)
        val hasta = x(mostrado.coerceIn(inicio.toFloat(), fin.toFloat()))
        if (hasta > izquierda) {
            rect.set(izquierda, centroY - grosor / 2, hasta, centroY + grosor / 2)
            canvas.drawRoundRect(rect, grosor, grosor, pintaRelleno)
        }

        val baseNumero = centroY - (pintaNumero.descent() + pintaNumero.ascent()) / 2
        val baseImporte = centroY + radioHito + SEPARACION_IMPORTE * densidad - pintaImporte.ascent()
        for ((indice, hito) in hitos.withIndex()) {
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
            canvas.drawText(importes[indice], cx, baseImporte, pintaImporte)
        }
    }

    private companion object {
        /** Hasta 10 socios los tramos son 5, 7 y 10; después, uno cada 3. */
        const val TRAMO_FIJO_MAX = 10
        const val VENTANA = 9
        const val DURACION_MS = 650L
        const val ESCALA_TEXTO_MAX = 1.3f

        /** En dp: del hito al importe y del importe al borde inferior (con la letra normal, como antes). */
        const val SEPARACION_IMPORTE = 5f
        const val MARGEN_INFERIOR = 3.5f
    }
}
