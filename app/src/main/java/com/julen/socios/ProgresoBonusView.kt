package com.julen.socios

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withClip
import com.julen.socios.util.CalculoComisiones
import com.julen.socios.util.Movimiento
import com.julen.socios.util.toEurosCompacto
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Progreso hacia los tramos del bonus: una barra con un hito por tramo (5 socios → 50 €, 7 → 100 €…),
 * con el número de socios dentro de cada hito y el importe debajo. Con muchos socios muestra solo los tramos
 * cercanos.
 *
 * Los hitos conseguidos se rellenan con un halo, el siguiente se resalta en verde y la punta del relleno lleva un
 * tirador. Al avanzar, la barra crece, cada hito que se alcanza da un salto y, al terminar, un brillo recorre lo
 * conseguido mientras el siguiente hito late un par de veces. Sin animaciones del sistema, todo queda quieto.
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
    private val grosor = 10 * densidad

    private var inicio = 0
    private var fin = 10
    private var hitos = listOf(5, 7, 10)

    /** El bonus de cada hito ("+50 €"): se calcula al cambiar los hitos, no en cada fotograma de la animación. */
    private var importes = importesDe(hitos)

    /** Socios que dibuja la barra; persigue al valor real con una animación. */
    private var mostrado = 0f
    private var animacion: ValueAnimator? = null

    /** Brillo y latido del siguiente hito, de 0 a 1; null cuando no hay ninguno en marcha. */
    private var efecto: Float? = null
    private var animacionEfecto: ValueAnimator? = null

    /** Cuándo empezó el salto de cada hito cruzado (según SystemClock.uptimeMillis) y si fue al alcanzarlo. */
    private val saltos = mutableMapOf<Int, Pair<Long, Boolean>>()

    /** Lo primero que se muestra lleva el efecto; si llega antes de que la vista esté puesta, espera a estarlo. */
    private var primeraVez = true
    private var efectoPendiente = false

    private val colorPrincipal = ContextCompat.getColor(context, R.color.primary)
    private val colorInicio = ContextCompat.getColor(context, R.color.badge_gradient_start)
    private val colorPista = ContextCompat.getColor(context, R.color.divider)
    private val colorBorde = ContextCompat.getColor(context, R.color.stroke)
    private val colorFondo = ContextCompat.getColor(context, R.color.surface_card)
    private val colorTexto = ContextCompat.getColor(context, R.color.text_main)
    private val colorTextoSuave = ContextCompat.getColor(context, R.color.text_sub)

    private val pintaPista = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorPista }
    private val pintaRelleno = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // Sombra verde bajo lo conseguido (Android 9 en adelante; antes se ignora).
        setShadowLayer(5 * densidad, 0f, 2 * densidad, ColorUtils.setAlphaComponent(colorPrincipal, 90))
    }
    // El brillo es una franja blanca centrada en 0 que se desplaza con la matriz: no se crea nada al dibujar.
    private val anchoBrillo = 48 * densidad
    private val brillo = LinearGradient(
        -anchoBrillo / 2, 0f, anchoBrillo / 2, 0f,
        intArrayOf(Color.TRANSPARENT, ColorUtils.setAlphaComponent(Color.WHITE, 120), Color.TRANSPARENT),
        null, Shader.TileMode.CLAMP
    )
    private val matrizBrillo = Matrix()
    private val pintaBrillo = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = brillo }
    private val pintaHito = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pintaHalo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pintaBorde = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * densidad
    }
    private val pintaTirador = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        setShadowLayer(3 * densidad, 0f, 1 * densidad, ColorUtils.setAlphaComponent(Color.BLACK, 60))
    }
    private val pintaNumero = pinturaCifra(context, sp(11f))
    private val pintaImporte = pinturaCifra(context, sp(11.5f))
    private val tamanoImporte = pintaImporte.textSize

    /** 13dp, o más si el número de socios no cabe dentro con la letra grande. */
    private val radioHito = max(13 * densidad, pintaNumero.measureText("00") / 2 + 4.5f * densidad)

    /** Hueco alrededor de los hitos para su halo y su salto, que crecen más allá del círculo. */
    private val margenHalo = 6 * densidad

    private val rect = RectF()

    private fun sp(valor: Float) = valor * densidad * escalaTexto

    private fun importesDe(hitos: List<Int>) =
        hitos.map { CalculoComisiones.calcularBonusSemanal(it).toEurosCompacto(prefix = "+") }

    /**
     * Muestra [socios] hechos. Con [animar], la barra avanza (o retrocede) hasta ellos; con [cambioDeSemana], más
     * despacio y con un pequeño rebote al llegar, mientras los importes de la cabecera cuentan hasta los suyos.
     */
    fun mostrar(socios: Int, animar: Boolean, cambioDeSemana: Boolean = false) {
        val siguiente = CalculoComisiones.obtenerSiguienteMetaBonus(socios).first
        fin = max(TRAMO_FIJO_MAX, siguiente)
        inicio = if (fin <= TRAMO_FIJO_MAX) 0 else fin - VENTANA
        hitos = CalculoComisiones.tramosBonus().takeWhile { it <= fin }.filter { it > inicio }.toList()
        importes = importesDe(hitos)

        animacion?.cancel()
        val objetivo = socios.toFloat()
        if (!animar || !Movimiento.activas()) {
            mostrado = objetivo
            saltos.clear()
            invalidate()
            // Al abrir la pantalla, un brillo y un latido.
            if (primeraVez) empezarEfecto()
            primeraVez = false
            return
        }
        primeraVez = false
        val desde = mostrado
        if (desde == objetivo) return
        val sube = objetivo > desde
        // El rebote no puede cruzar un hito: no se encendería y apagaría uno sin haberlo alcanzado de verdad.
        val minimo = min(hitos.lastOrNull { it <= objetivo }?.toFloat() ?: inicio.toFloat(), desde)
        val maximo = max(hitos.firstOrNull { it > objetivo }?.let { it - 0.05f } ?: fin.toFloat(), desde)
        animacion = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (cambioDeSemana) DURACION_SEMANA_MS else DURACION_MS
            interpolator = if (cambioDeSemana) OvershootInterpolator(REBOTE) else Movimiento.curva(context)
            addUpdateListener {
                val valor = (desde + (objetivo - desde) * (it.animatedValue as Float)).coerceIn(minimo, maximo)
                // Los hitos que cruza dan un salto: hacia fuera al alcanzarlos y hacia dentro al dejarlos.
                val ahora = SystemClock.uptimeMillis()
                for (hito in hitos) {
                    val h = hito.toFloat()
                    if (sube && h > mostrado && h <= valor) saltos[hito] = ahora to true
                    if (!sube && h <= mostrado && h > valor) saltos[hito] = ahora to false
                }
                mostrado = valor
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (animation !== animacion) return
                    mostrado = objetivo
                    invalidate()
                    if (sube) empezarEfecto()
                }
            })
            start()
        }
    }

    private fun empezarEfecto() {
        animacionEfecto?.cancel()
        if (!Movimiento.activas()) return
        if (!isAttachedToWindow) {
            efectoPendiente = true
            return
        }
        efectoPendiente = false
        animacionEfecto = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DURACION_EFECTO_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                efecto = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    efecto = null
                    invalidate()
                }
            })
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val altoImporte = pintaImporte.descent() - pintaImporte.ascent()
        val alto = (margenHalo + radioHito * 2 + SEPARACION_IMPORTE * densidad + altoImporte + MARGEN_INFERIOR * densidad)
            .toInt() + paddingTop + paddingBottom
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), resolveSize(alto, heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        pintaRelleno.shader = LinearGradient(
            paddingLeft.toFloat(), 0f, (w - paddingRight).toFloat(), 0f, colorInicio, colorPrincipal, Shader.TileMode.CLAMP
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (efectoPendiente) empezarEfecto()
    }

    override fun onDetachedFromWindow() {
        animacion?.cancel()
        animacionEfecto?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        pintaImporte.textSize = tamanoImporte
        val izquierda = paddingLeft + radioHito + margenHalo / 2
        // Margen para que el importe del último hito no se corte.
        val anchoUltimo = importes.lastOrNull()?.let(pintaImporte::measureText) ?: 0f
        val derecha = width - paddingRight - max(radioHito * 1.6f, anchoUltimo / 2)
        val centroY = paddingTop + margenHalo / 2 + radioHito
        fun x(valor: Float) = izquierda + (valor - inicio) / (fin - inicio) * (derecha - izquierda)

        // Si dos importes vecinos no caben (hitos juntos o letra grande), se encogen lo justo para no pisarse.
        val factor = (1 until hitos.size).minOfOrNull { i ->
            val disponible = x(hitos[i].toFloat()) - x(hitos[i - 1].toFloat()) - 6 * densidad
            val necesario = (pintaImporte.measureText(importes[i - 1]) + pintaImporte.measureText(importes[i])) / 2
            min(1f, disponible / necesario)
        } ?: 1f
        if (factor < 1f) pintaImporte.textSize = tamanoImporte * factor

        // La pista y lo conseguido.
        rect.set(izquierda, centroY - grosor / 2, derecha, centroY + grosor / 2)
        canvas.drawRoundRect(rect, grosor, grosor, pintaPista)
        val hasta = x(mostrado.coerceIn(inicio.toFloat(), fin.toFloat()))
        if (hasta > izquierda) {
            rect.set(izquierda, centroY - grosor / 2, hasta, centroY + grosor / 2)
            canvas.drawRoundRect(rect, grosor, grosor, pintaRelleno)
            dibujarBrillo(canvas, izquierda, hasta, centroY)
        }

        // Cuánto está alcanzado cada hito, de 0 a 1: se enciende poco a poco según la barra llega a él.
        fun alcance(hito: Int) = ((mostrado - hito) / TRANSICION_HITO + 1f).coerceIn(0f, 1f)
        val siguiente = hitos.firstOrNull { alcance(it) < 1f }
        val baseNumero = centroY - (pintaNumero.descent() + pintaNumero.ascent()) / 2
        val baseImporte = centroY + radioHito + SEPARACION_IMPORTE * densidad - pintaImporte.ascent()
        val ahora = SystemClock.uptimeMillis()
        var saltando = false
        for ((indice, hito) in hitos.withIndex()) {
            val cx = x(hito.toFloat())
            val lleno = alcance(hito)
            val esSiguiente = hito == siguiente

            // El salto al cruzarlo: crece (al alcanzarlo) o se encoge (al dejarlo) un momento y vuelve a su tamaño.
            val escala = saltos[hito]?.let { (inicioSalto, haciaFuera) ->
                val t = (ahora - inicioSalto).toFloat() / DURACION_SALTO_MS
                if (t >= 1f) return@let 1f
                saltando = true
                1f + (if (haciaFuera) 0.28f else -0.18f) * sin(PI * t).toFloat()
            } ?: 1f
            val radio = radioHito * escala

            // Primero, el hito sin alcanzar (con el borde verde si es el siguiente) y, encima, el alcanzado.
            if (esSiguiente) dibujarLatido(canvas, cx, centroY)
            if (lleno < 1f) {
                pintaHito.color = colorFondo
                canvas.drawCircle(cx, centroY, radio, pintaHito)
                pintaBorde.color = if (esSiguiente) colorPrincipal else colorBorde
                canvas.drawCircle(cx, centroY, radio - pintaBorde.strokeWidth / 2, pintaBorde)
            }
            if (lleno > 0f) {
                pintaHalo.color = ColorUtils.setAlphaComponent(colorPrincipal, (40 * lleno).toInt())
                canvas.drawCircle(cx, centroY, radio + 4 * densidad, pintaHalo)
                pintaHito.color = ColorUtils.setAlphaComponent(colorPrincipal, (255 * lleno).toInt())
                canvas.drawCircle(cx, centroY, radio, pintaHito)
            }

            pintaNumero.color = ColorUtils.blendARGB(if (esSiguiente) colorPrincipal else colorTextoSuave, colorFondo, lleno)
            canvas.drawText(hito.toString(), cx, baseNumero, pintaNumero)
            pintaImporte.color = ColorUtils.blendARGB(if (esSiguiente) colorTexto else colorTextoSuave, colorPrincipal, lleno)
            canvas.drawText(importes[indice], cx, baseImporte, pintaImporte)
        }

        // El tirador en la punta del relleno, salvo encima de un hito (ya lo marca el hito).
        if (hasta > izquierda + grosor && hitos.none { abs(x(it.toFloat()) - hasta) < radioHito }) {
            canvas.drawCircle(hasta, centroY, grosor * 0.85f, pintaTirador)
            pintaHito.color = colorPrincipal
            canvas.drawCircle(hasta, centroY, grosor * 0.4f, pintaHito)
        }

        if (saltando) postInvalidateOnAnimation()
    }

    /** Un brillo blanco que recorre lo conseguido de izquierda a derecha (primera mitad del efecto). */
    private fun dibujarBrillo(canvas: Canvas, izquierda: Float, hasta: Float, centroY: Float) {
        val t = efecto?.let { it / FRACCION_BRILLO }?.takeIf { it < 1f } ?: return
        val centro = izquierda - anchoBrillo + (hasta - izquierda + 2 * anchoBrillo) * t
        matrizBrillo.setTranslate(centro, 0f)
        brillo.setLocalMatrix(matrizBrillo)
        rect.set(izquierda, centroY - grosor / 2, hasta, centroY + grosor / 2)
        canvas.withClip(rect) { drawRoundRect(rect, grosor, grosor, pintaBrillo) }
    }

    /** Dos ondas verdes que salen del siguiente hito y se apagan. */
    private fun dibujarLatido(canvas: Canvas, cx: Float, centroY: Float) {
        val t = efecto ?: return
        val onda = (t * LATIDOS) % 1f
        pintaHalo.color = ColorUtils.setAlphaComponent(colorPrincipal, ((1f - onda) * 70).toInt())
        canvas.drawCircle(cx, centroY, radioHito + margenHalo * 1.2f * onda, pintaHalo)
    }

    private companion object {
        /** Hasta 10 socios los tramos son 5, 7 y 10; después, uno cada 3. */
        const val TRAMO_FIJO_MAX = 10
        const val VENTANA = 9
        const val DURACION_MS = 650L
        const val DURACION_SEMANA_MS = 750L
        const val REBOTE = 1.4f

        /** Lo que tarda un hito en encenderse según la barra se le acerca, en socios. */
        const val TRANSICION_HITO = 0.35f
        const val DURACION_SALTO_MS = 380L
        const val DURACION_EFECTO_MS = 1800L

        /** El brillo ocupa este primer tramo del efecto; el latido, todo. */
        const val FRACCION_BRILLO = 0.55f
        const val LATIDOS = 2
        const val ESCALA_TEXTO_MAX = 1.3f

        /** En dp: del hito al importe y del importe al borde inferior (con la letra normal, como antes). */
        const val SEPARACION_IMPORTE = 5f
        const val MARGEN_INFERIOR = 3.5f
    }
}
