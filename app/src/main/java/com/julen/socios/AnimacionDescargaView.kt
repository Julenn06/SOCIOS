package com.julen.socios

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withScale
import androidx.core.graphics.withTranslation
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.julen.socios.util.HapticUtils
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Animación de la descarga de una actualización. Mientras descarga, un anillo con degradado avanza con un
 * destello en la punta, un halo que respira y una flecha que baja; al terminar, el centro se rellena con un
 * rebote, se dibuja una marca de verificación y salen ondas y partículas. Si falla, se vuelve rojo y tiembla.
 *
 * El anillo no salta al progreso real: lo persigue sin dar la vuelta en menos de [VUELTA_RAPIDA_MS], para
 * que la animación se vea aunque la descarga (unos 2 MB) dure un instante. Sigue la escala de animación del
 * sistema, como los ValueAnimator: con las animaciones desactivadas muestra cada estado directamente.
 */
class AnimacionDescargaView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private enum class Fase { DESCARGANDO, COMPLETANDO, COMPLETADA, FALLIDA }

    /** Cuando el anillo llega al 100 % y empieza la animación final. */
    var alCompletar: (() -> Unit)? = null

    /** Cuando termina la animación final. */
    var alTerminar: (() -> Unit)? = null

    /** Cada vez que cambia el porcentaje que muestra el anillo, para que el texto vaya a la par. */
    var alMostrarProgreso: ((Float) -> Unit)? = null

    private var fase = Fase.DESCARGANDO

    /** Progreso real, de 0 a 1. */
    private var objetivo = 0f

    /** Progreso que dibuja el anillo, que persigue a [objetivo]. */
    private var mostrado = 0f
    private var completarAlLlegar = false
    private var porcentajeAvisado = -1

    /** Reloj de las animaciones en ms; va más despacio o más deprisa según la escala de animación. */
    private var reloj = 0f
    private var ultimoFotograma = 0L

    /** Escala de animación del sistema (0: desactivadas). */
    private var escala = 1f

    /** Cuándo empezó la animación final, de éxito o de error, según [reloj]. */
    private var inicioFinal = 0f

    private val colorPrincipal = ContextCompat.getColor(context, R.color.primary)
    private val colorAcento = ContextCompat.getColor(context, R.color.accent_blue)
    private val colorError = ContextCompat.getColor(context, R.color.red_text)
    private val coloresParticulas = intArrayOf(
        colorPrincipal,
        colorAcento,
        ContextCompat.getColor(context, R.color.amber_text),
        ContextCompat.getColor(context, R.color.green_text_soft),
        ContextCompat.getColor(context, R.color.orange_pending)
    )

    private val pintaPista = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = ContextCompat.getColor(context, R.color.divider)
    }
    private val pintaArco = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val pintaTrazo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val pintaRelleno = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pintaHalo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pintaNumero = pinturaCifra(context, centrada = false).apply {
        color = ContextCompat.getColor(context, R.color.text_main)
    }
    private val pintaSimbolo = pinturaCifra(context, centrada = false).apply {
        color = ContextCompat.getColor(context, R.color.text_sub)
    }
    private val colorCheck = ContextCompat.getColor(context, R.color.on_primary)

    private var cx = 0f
    private var cy = 0f
    private var radio = 0f
    private var grosor = 0f
    private val ovalo = RectF()
    private val flecha = Path()
    private val check = Path()
    private val tramoCheck = Path()
    private val medidaCheck = PathMeasure()

    private class Particula(val angulo: Float, val distancia: Float, val tamano: Float, val color: Int, val retraso: Float)

    private var particulas: List<Particula> = emptyList()

    private val rebote = OvershootInterpolator(1.4f)
    private val suave = FastOutSlowInInterpolator()
    private val frenada = DecelerateInterpolator(1.6f)

    private var temblor: ObjectAnimator? = null

    private val avisoProgreso = Runnable { alMostrarProgreso?.invoke(mostrado) }

    /** El progreso real de la descarga, de 0 a 1. [inmediato] lo muestra sin animar. */
    fun mostrarProgreso(fraccion: Float, inmediato: Boolean = false) {
        if (fase != Fase.DESCARGANDO) {
            // Reintento después de un fallo.
            fase = Fase.DESCARGANDO
            mostrado = 0f
            completarAlLlegar = false
            pararTemblor()
        }
        objetivo = fraccion
        if (inmediato) mostrado = fraccion
        invalidate()
    }

    /**
     * Completa el anillo y celebra la descarga; avisa con [alCompletar] y [alTerminar]. Sin [animar] (por
     * ejemplo, al girar la pantalla) salta al final y avisa enseguida.
     */
    fun completar(animar: Boolean) {
        if (fase == Fase.COMPLETANDO || fase == Fase.COMPLETADA) return
        pararTemblor()
        objetivo = 1f
        escala = leerEscala()
        if (!animar || escala == 0f) {
            fase = Fase.COMPLETADA
            mostrado = 1f
            completarAlLlegar = false
            invalidate()
            // Sin pasar por avisarProgreso(): llegaría después de alCompletar y pisaría sus textos.
            removeCallbacks(avisoProgreso)
            porcentajeAvisado = 100
            alMostrarProgreso?.invoke(1f)
            alCompletar?.invoke()
            alTerminar?.invoke()
            return
        }
        fase = Fase.DESCARGANDO
        completarAlLlegar = true
        invalidate()
    }

    /** Muestra el error. Con [animar], el anillo tiembla. */
    fun fallar(animar: Boolean) {
        if (fase == Fase.FALLIDA) return
        fase = Fase.FALLIDA
        completarAlLlegar = false
        escala = leerEscala()
        val animado = animar && escala > 0f
        // Sin animar, como si la entrada ya hubiera terminado.
        inicioFinal = if (animado) reloj else reloj - DURACION_ERROR_MS
        if (animado) {
            val d = grosor * 0.9f
            temblor = ObjectAnimator.ofFloat(this, TRANSLATION_X, 0f, -d, d, -d * 0.7f, d * 0.7f, -d * 0.35f, 0f)
                .setDuration(DURACION_ERROR_MS.toLong())
                .also { it.start() }
            HapticUtils.performReject(this)
        }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val ancho = w - paddingLeft - paddingRight
        val alto = h - paddingTop - paddingBottom
        val lado = min(ancho, alto).toFloat()
        cx = paddingLeft + ancho / 2f
        cy = paddingTop + alto / 2f
        // Deja sitio alrededor del anillo para el halo, las ondas y las partículas.
        radio = lado * 0.28f
        grosor = lado * 0.05f
        ovalo.set(cx - radio, cy - radio, cx + radio, cy + radio)

        pintaPista.strokeWidth = grosor
        pintaArco.strokeWidth = grosor
        // Simétrico (verde, azul, verde) para que no se note la costura arriba, donde empieza el anillo.
        pintaArco.shader = SweepGradient(
            cx, cy, intArrayOf(colorPrincipal, colorAcento, colorPrincipal), floatArrayOf(0f, 0.5f, 1f)
        ).apply { setLocalMatrix(Matrix().apply { setRotate(-90f, cx, cy) }) }

        val radioHalo = radio + grosor * 3f
        pintaHalo.shader = RadialGradient(
            cx, cy, radioHalo,
            intArrayOf(
                ColorUtils.setAlphaComponent(colorPrincipal, 20),
                ColorUtils.setAlphaComponent(colorPrincipal, 80),
                ColorUtils.setAlphaComponent(colorPrincipal, 0)
            ),
            floatArrayOf(0f, radio / radioHalo, 1f),
            Shader.TileMode.CLAMP
        )

        pintaNumero.textSize = radio * 0.44f
        pintaSimbolo.textSize = radio * 0.24f

        val a = radio * 0.15f
        flecha.reset()
        flecha.moveTo(0f, -a)
        flecha.lineTo(0f, a)
        flecha.moveTo(-a * 0.62f, a * 0.38f)
        flecha.lineTo(0f, a)
        flecha.lineTo(a * 0.62f, a * 0.38f)

        val r = radioRelleno()
        check.reset()
        check.moveTo(cx - r * 0.42f, cy + r * 0.02f)
        check.lineTo(cx - r * 0.12f, cy + r * 0.32f)
        check.lineTo(cx + r * 0.45f, cy - r * 0.28f)
        medidaCheck.setPath(check, false)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        escala = leerEscala()
    }

    override fun onDetachedFromWindow() {
        temblor?.cancel()
        removeCallbacks(avisoProgreso)
        ultimoFotograma = 0L
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val ahora = SystemClock.uptimeMillis()
        val animado = escala > 0f
        // Tras una pausa (vista oculta), sigue donde lo dejó en lugar de saltar.
        val dt = if (!animado || ultimoFotograma == 0L) 0f else (ahora - ultimoFotograma).coerceIn(0, 64) / escala
        ultimoFotograma = ahora
        reloj += dt

        if (fase == Fase.DESCARGANDO) {
            avanzar(dt, animado)
            if (completarAlLlegar && mostrado >= 1f) empezarCelebracion()
        }

        when (fase) {
            Fase.DESCARGANDO -> dibujarDescarga(canvas, animado)
            Fase.COMPLETANDO -> dibujarCelebracion(canvas, reloj - inicioFinal)
            Fase.COMPLETADA -> dibujarCompletada(canvas)
            Fase.FALLIDA -> dibujarFallo(canvas, reloj - inicioFinal)
        }

        val seguir = when (fase) {
            Fase.DESCARGANDO -> animado || mostrado != objetivo
            Fase.COMPLETANDO -> true
            Fase.COMPLETADA -> false
            Fase.FALLIDA -> reloj - inicioFinal < DURACION_ERROR_MS
        }
        if (seguir) postInvalidateOnAnimation() else ultimoFotograma = 0L
    }

    private fun avanzar(dt: Float, animado: Boolean) {
        val restante = objetivo - mostrado
        mostrado = when {
            !animado || restante <= 0f -> objetivo
            // Se acerca con suavidad al progreso real, con un mínimo para llegar y un máximo de velocidad.
            else -> mostrado + (restante * dt / SUAVIZADO_MS)
                .coerceIn(min(restante, dt / VUELTA_LENTA_MS), dt / VUELTA_RAPIDA_MS)
                .coerceAtMost(restante)
        }
        avisarProgreso()
    }

    private fun avisarProgreso() {
        val porcentaje = (mostrado * 100).toInt()
        if (porcentaje == porcentajeAvisado) return
        porcentajeAvisado = porcentaje
        // Fuera del dibujado: quien escucha cambia textos y eso pide un nuevo layout.
        removeCallbacks(avisoProgreso)
        post(avisoProgreso)
    }

    private fun empezarCelebracion() {
        fase = Fase.COMPLETANDO
        inicioFinal = reloj
        completarAlLlegar = false
        val azar = Random(SEMILLA)
        particulas = List(N_PARTICULAS) { i ->
            Particula(
                angulo = (i * 2 * PI / N_PARTICULAS).toFloat() + azar.nextFloat() * 0.4f - 0.2f,
                distancia = radio * (0.30f + azar.nextFloat() * 0.25f),
                tamano = grosor * (0.26f + azar.nextFloat() * 0.24f),
                color = coloresParticulas[i % coloresParticulas.size],
                retraso = azar.nextFloat() * 90f
            )
        }
        HapticUtils.performConfirm(this)
        post { alCompletar?.invoke() }
    }

    private fun dibujarDescarga(canvas: Canvas, animado: Boolean) {
        // Halo que respira detrás del anillo.
        val pulso = if (animado) 0.5f + 0.5f * sin(reloj / PULSO_MS * 2 * PI).toFloat() else 0.5f
        pintaHalo.alpha = (150 + 105 * pulso).roundToInt()
        canvas.withScale(1f + 0.06f * pulso, 1f + 0.06f * pulso, cx, cy) {
            drawCircle(cx, cy, radio + grosor * 3f, pintaHalo)
        }

        canvas.drawCircle(cx, cy, radio, pintaPista)

        if (mostrado > 0f) {
            val barrido = 360f * mostrado
            canvas.drawArc(ovalo, -90f, barrido, false, pintaArco)
            dibujarDestello(canvas, -90f + barrido, animado)
        }
        dibujarFlecha(canvas, animado, 255)
        dibujarPorcentaje(canvas, 255)
    }

    /** Brillo en la punta del arco, del color que tiene el degradado en ese punto. */
    private fun dibujarDestello(canvas: Canvas, angulo: Float, animado: Boolean) {
        val rad = Math.toRadians(angulo.toDouble())
        val x = cx + radio * cos(rad).toFloat()
        val y = cy + radio * sin(rad).toFloat()
        val color = if (mostrado < 0.5f) {
            ColorUtils.blendARGB(colorPrincipal, colorAcento, mostrado * 2)
        } else {
            ColorUtils.blendARGB(colorAcento, colorPrincipal, (mostrado - 0.5f) * 2)
        }
        val brillo = if (animado) 0.85f + 0.15f * sin(reloj / 140f) else 1f
        pintaRelleno.color = ColorUtils.setAlphaComponent(color, 45)
        canvas.drawCircle(x, y, grosor * 1.25f * brillo, pintaRelleno)
        pintaRelleno.color = ColorUtils.setAlphaComponent(color, 90)
        canvas.drawCircle(x, y, grosor * 0.85f, pintaRelleno)
        pintaRelleno.color = ColorUtils.setAlphaComponent(Color.WHITE, 235)
        canvas.drawCircle(x, y, grosor * 0.28f * brillo, pintaRelleno)
    }

    /** Flecha que baja y se desvanece una y otra vez, encima del porcentaje. */
    private fun dibujarFlecha(canvas: Canvas, animado: Boolean, opacidad: Int) {
        val ciclo = if (animado) reloj % FLECHA_MS / FLECHA_MS else 0.5f
        val desplazamiento = radio * (-0.09f + 0.18f * suave.getInterpolation(ciclo))
        val visibilidad = if (animado) sin(PI * ciclo).toFloat() else 1f
        pintaTrazo.color = colorPrincipal
        pintaTrazo.alpha = (opacidad * visibilidad).roundToInt()
        pintaTrazo.strokeWidth = grosor * 0.55f
        canvas.withTranslation(cx, cy - radio * 0.34f + desplazamiento) { drawPath(flecha, pintaTrazo) }
    }

    /** "42 %", con el número grande y el símbolo más pequeño. */
    private fun dibujarPorcentaje(canvas: Canvas, opacidad: Int) {
        val numero = (mostrado * 100).toInt().toString()
        val anchoNumero = pintaNumero.measureText(numero)
        val separacion = radio * 0.04f
        val inicio = cx - (anchoNumero + separacion + pintaSimbolo.measureText("%")) / 2f
        val base = cy + radio * 0.36f
        pintaNumero.alpha = opacidad
        pintaSimbolo.alpha = opacidad
        canvas.drawText(numero, inicio, base, pintaNumero)
        canvas.drawText("%", inicio + anchoNumero + separacion, base, pintaSimbolo)
    }

    private fun dibujarCelebracion(canvas: Canvas, t: Float) {
        canvas.drawCircle(cx, cy, radio, pintaArco)

        // Dos ondas que se alejan del anillo.
        for (i in 0 until 2) {
            val p = ((t - 120f - i * 220f) / 700f).coerceIn(0f, 1f)
            if (p <= 0f || p >= 1f) continue
            val q = frenada.getInterpolation(p)
            pintaTrazo.color = colorPrincipal
            pintaTrazo.alpha = ((1 - p) * 140).roundToInt()
            pintaTrazo.strokeWidth = grosor * 0.5f * (1 - q) + 1f
            canvas.drawCircle(cx, cy, radio + grosor / 2 + radio * 0.55f * q, pintaTrazo)
        }

        // Partículas que salen disparadas y se apagan.
        for (particula in particulas) {
            val p = ((t - 140f - particula.retraso) / 760f).coerceIn(0f, 1f)
            if (p <= 0f || p >= 1f) continue
            val distancia = radio + grosor + particula.distancia * frenada.getInterpolation(p)
            pintaRelleno.color = particula.color
            pintaRelleno.alpha = ((1 - p * p) * 255).roundToInt()
            canvas.drawCircle(
                cx + distancia * cos(particula.angulo),
                cy + distancia * sin(particula.angulo),
                particula.tamano * (1 - p * 0.7f),
                pintaRelleno
            )
        }

        // El porcentaje se desvanece mientras el centro se rellena con un rebote.
        val desvanecido = (1 - t / 160f).coerceIn(0f, 1f)
        if (desvanecido > 0f) {
            dibujarFlecha(canvas, false, (255 * desvanecido).roundToInt())
            dibujarPorcentaje(canvas, (255 * desvanecido).roundToInt())
        }
        val relleno = rebote.getInterpolation((t / 380f).coerceIn(0f, 1f))
        pintaRelleno.color = colorPrincipal
        canvas.drawCircle(cx, cy, radioRelleno() * relleno, pintaRelleno)

        dibujarCheck(canvas, suave.getInterpolation(((t - 220f) / 340f).coerceIn(0f, 1f)))

        if (t >= DURACION_EXITO_MS) {
            fase = Fase.COMPLETADA
            post { alTerminar?.invoke() }
        }
    }

    private fun dibujarCompletada(canvas: Canvas) {
        canvas.drawCircle(cx, cy, radio, pintaArco)
        pintaRelleno.color = colorPrincipal
        canvas.drawCircle(cx, cy, radioRelleno(), pintaRelleno)
        dibujarCheck(canvas, 1f)
    }

    private fun dibujarCheck(canvas: Canvas, fraccion: Float) {
        if (fraccion <= 0f) return
        tramoCheck.reset()
        medidaCheck.getSegment(0f, medidaCheck.length * fraccion, tramoCheck, true)
        pintaTrazo.color = colorCheck
        pintaTrazo.strokeWidth = grosor * 0.95f
        canvas.drawPath(tramoCheck, pintaTrazo)
    }

    private fun dibujarFallo(canvas: Canvas, t: Float) {
        val entrada = rebote.getInterpolation((t / 300f).coerceIn(0f, 1f))

        pintaTrazo.color = ColorUtils.setAlphaComponent(colorError, 70)
        pintaTrazo.strokeWidth = grosor
        canvas.drawCircle(cx, cy, radio, pintaTrazo)
        // El arco se queda donde iba la descarga.
        if (mostrado > 0f) {
            pintaTrazo.color = colorError
            canvas.drawArc(ovalo, -90f, 360f * mostrado, false, pintaTrazo)
        }

        pintaRelleno.color = ColorUtils.setAlphaComponent(colorError, 36)
        canvas.drawCircle(cx, cy, radioRelleno() * entrada, pintaRelleno)

        // Signo de exclamación.
        canvas.withScale(entrada, entrada, cx, cy) {
            pintaTrazo.color = colorError
            pintaTrazo.strokeWidth = grosor * 0.8f
            drawLine(cx, cy - radio * 0.34f, cx, cy + radio * 0.06f, pintaTrazo)
            pintaRelleno.color = colorError
            drawCircle(cx, cy + radio * 0.30f, grosor * 0.5f, pintaRelleno)
        }
    }

    private fun pararTemblor() {
        temblor?.cancel()
        translationX = 0f
    }

    private fun radioRelleno() = radio - grosor * 0.95f

    private fun leerEscala(): Float =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ValueAnimator.getDurationScale()
        } else {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }

    private companion object {
        const val VUELTA_RAPIDA_MS = 1100f
        const val VUELTA_LENTA_MS = 9000f
        const val SUAVIZADO_MS = 110f
        const val PULSO_MS = 1600f
        const val FLECHA_MS = 1150f
        const val DURACION_EXITO_MS = 1150f
        const val DURACION_ERROR_MS = 450f
        const val N_PARTICULAS = 16
        const val SEMILLA = 7
    }
}
