package com.julen.socios.vidrio

import android.animation.ValueAnimator
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withClip
import androidx.core.graphics.withTranslation
import com.julen.socios.util.Movimiento
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Fondo de vidrio líquido para [host]: lo que hay detrás en [fuente], desenfocado y más vivo, con el borde curvando la
 * luz como una lente, el grosor del vidrio (luz por dentro arriba, sombra abajo), un filo de luz arriba a la izquierda
 * y el tinte del [estilo]. Si es interactivo, brilla donde se pulsa y la lente se hunde.
 *
 * - Android 13 o más: todo en un shader AGSL sobre lo desenfocado. El tinte es adaptativo: poco donde detrás hay algo
 *   que ya deja leer el texto, y más solo donde haga falta para que el texto que menos contrasta llegue a 4,5:1.
 * - Android 12: desenfoque con RenderEffect, y un tinte fijo (que basta con cualquier fondo) y el filo pintados encima.
 * Antes de Android 12 no se usa: [Vidrio.aplicar] deja la vista como estaba.
 */
@RequiresApi(Build.VERSION_CODES.S)
class VidrioDrawable internal constructor(
    private val host: View,
    private val estilo: EstiloVidrio,
    private val fuente: FuenteVidrio
) : Drawable(), VidrioPuesto {

    private val densidad = host.resources.displayMetrics.density
    private val oscuro = (host.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    private val tinte = ContextCompat.getColor(host.context, estilo.tinte)
    private val brillo = if (oscuro) BRILLO_OSCURO else BRILLO_CLARO

    private val radioPx = estilo.radioDp * densidad
    private val desenfoquePx = estilo.desenfoqueDp * densidad

    /** Lo que se pinta fuera del vidrio para que el desenfoque del borde tenga de dónde tirar. */
    private val margen = ceil(desenfoquePx * 2).toInt()

    private val nodo = RenderNode("Vidrio")
    private val desenfoque = RenderEffect.createBlurEffect(desenfoquePx, desenfoquePx, Shader.TileMode.CLAMP)
    private val refraccion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Refraccion(estilo, densidad, tinte, brillo, Contraste.de(host, estilo, tinte))
    } else {
        null
    }

    private val pinturaSolida = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(host.context, estilo.solido)
    }

    // Solo para Android 12, sin shader.
    private val pinturaTinte = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(tinte, (estilo.fuerzaSinShader * 255).roundToInt())
    }
    private val pinturaFilo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = densidad
    }
    private val pinturaToque = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ruta = Path()
    private val caja = RectF()
    private val cajaFilo = RectF()

    private val posHost = IntArray(2)
    private val posFuente = IntArray(2)
    private var dibujadoX = Int.MIN_VALUE
    private var dibujadoY = Int.MIN_VALUE
    private var desplazamientoX = 0
    private var desplazamientoY = 0

    private var toqueX = 0f
    private var toqueY = 0f
    private var toque = 0f
    private var objetivoToque = 0f
    private var animacionToque: ValueAnimator? = null

    /** Cuánto se ve el borde (0–1): la barra en reposo no tiene nada debajo y no debe marcar una línea. */
    private var borde = 1f

    /**
     * Enganchado dentro de su propia fuente (por ejemplo, si el aviso acabara colgando de ella): se vería a sí mismo
     * en un ciclo sin fin, así que se pinta opaco y no se registra.
     */
    private var dentroDeLaFuente = false

    private var observador: ViewTreeObserver? = null
    private val alPreDibujar = ViewTreeObserver.OnPreDrawListener {
        // Movido sin volver a pintarse (una traslación animada, como el aviso al entrar): se repinta ya, en este
        // mismo fotograma, con lo que ahora tiene detrás. Sin nada detrás no hay nada que corregir.
        if (fuente.nodo != null && !dentroDeLaFuente) {
            calcularDesplazamiento()
            if (desplazamientoX != dibujadoX || desplazamientoY != dibujadoY) host.invalidate()
        }
        true
    }

    private val alEngancharse = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            dentroDeLaFuente = estaDentro(host, fuente)
            if (dentroDeLaFuente) return
            fuente.registrar(host)
            observador = host.viewTreeObserver.also { it.addOnPreDrawListener(alPreDibujar) }
        }

        override fun onViewDetachedFromWindow(v: View) {
            fuente.olvidar(host)
            observador?.takeIf { it.isAlive }?.removeOnPreDrawListener(alPreDibujar)
            observador = null
            animacionToque?.cancel()
        }
    }

    init {
        host.addOnAttachStateChangeListener(alEngancharse)
        if (host.isAttachedToWindow) alEngancharse.onViewAttachedToWindow(host)
    }

    override fun mostrarBorde(fraccion: Float) {
        val nuevo = fraccion.coerceIn(0f, 1f)
        if (nuevo == borde) return
        borde = nuevo
        invalidateSelf()
    }

    /** Dónde empieza el vidrio dentro de la fuente, en píxeles (en los campos, sin crear objetos en cada fotograma). */
    private fun calcularDesplazamiento() {
        host.getLocationInWindow(posHost)
        fuente.getLocationInWindow(posFuente)
        desplazamientoX = posHost[0] + (bounds.left * host.scaleX).roundToInt() - posFuente[0]
        desplazamientoY = posHost[1] + (bounds.top * host.scaleY).roundToInt() - posFuente[1]
    }

    private fun radio(): Float =
        if (estilo.soloBordeInferior) 0f else min(radioPx, min(bounds.width(), bounds.height()) / 2f)

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val radio = radio()
        caja.set(b)
        ruta.rewind()
        ruta.addRoundRect(caja, radio, radio, Path.Direction.CW)

        // Antes de cualquier salida: si no, el aviso de movimiento lo vería siempre distinto y repintaría sin parar.
        calcularDesplazamiento()
        val x = desplazamientoX
        val y = desplazamientoY
        dibujadoX = x
        dibujadoY = y

        val contenido = fuente.nodo
        if (!canvas.isHardwareAccelerated || contenido == null || dentroDeLaFuente) {
            // Sin lo de detrás (una captura por software): el color opaco de siempre.
            canvas.drawPath(ruta, pinturaSolida)
            return
        }

        // Al pulsar, el botón se encoge: lo de detrás se muestrea a la escala real y no se desplaza con él.
        val escalaX = host.scaleX.takeIf { it > 0f } ?: 1f
        val escalaY = host.scaleY.takeIf { it > 0f } ?: 1f
        // El margen solo donde hay algo detrás: más allá de la fuente (fuera de la pantalla) no hay nada.
        val izq = margen.coerceAtMost(x).coerceAtLeast(0)
        val arr = margen.coerceAtMost(y).coerceAtLeast(0)
        val der = margen.coerceAtMost(fuente.width - x - b.width()).coerceAtLeast(0)
        val aba = margen.coerceAtMost(fuente.height - y - b.height()).coerceAtLeast(0)
        val ancho = b.width() + izq + der
        val alto = b.height() + arr + aba
        nodo.setPosition(0, 0, ancho, alto)
        val grabacion = nodo.beginRecording(ancho, alto)
        try {
            // Lo que cae fuera de la fuente (un aviso que entra desde abajo o se desliza para cerrarlo) tiene el fondo
            // de la pantalla, no un hueco transparente que el desenfoque convertiría en una banda oscura.
            fuente.colorFondo?.let(grabacion::drawColor)
            grabacion.translate(izq.toFloat(), arr.toFloat())
            grabacion.scale(1f / escalaX, 1f / escalaY)
            grabacion.translate(-x.toFloat(), -y.toFloat())
            grabacion.drawRenderNode(contenido)
        } finally {
            nodo.endRecording()
        }

        val shader = refraccion
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && shader != null) {
            // La forma, en coordenadas del nodo. La barra se alarga por arriba y los lados: solo tiene borde abajo.
            val oculto = if (estilo.soloBordeInferior) EXTRA_BORDE_OCULTO * densidad else 0f
            nodo.setRenderEffect(
                shader.efecto(
                    desenfoque,
                    izquierda = izq - oculto,
                    arriba = arr - oculto,
                    ancho = b.width() + 2 * oculto,
                    alto = b.height() + oculto,
                    radio = radio,
                    toqueX = izq + toqueX,
                    toqueY = arr + toqueY,
                    toque = toque,
                    borde = borde
                )
            )
            canvas.withTranslation((b.left - izq).toFloat(), (b.top - arr).toFloat()) { drawRenderNode(nodo) }
        } else {
            nodo.setRenderEffect(desenfoque)
            canvas.withClip(ruta) {
                withTranslation((b.left - izq).toFloat(), (b.top - arr).toFloat()) { drawRenderNode(nodo) }
                drawPath(ruta, pinturaTinte)
                if (toque > 0f) pintarToque(this, b)
            }
            pintarFilo(canvas, radio)
        }
    }

    private fun pintarFilo(canvas: Canvas, radio: Float) {
        val blanco = (brillo * 255 * 1.6f * borde).roundToInt().coerceIn(0, 255)
        if (blanco == 0) return
        if (estilo.soloBordeInferior) {
            pinturaFilo.shader = null
            pinturaFilo.color = ColorUtils.setAlphaComponent(0xFFFFFFFF.toInt(), blanco / 2)
            canvas.drawLine(caja.left, caja.bottom - densidad / 2, caja.right, caja.bottom - densidad / 2, pinturaFilo)
            return
        }
        // El degradado solo se rehace al cambiar de tamaño, no en cada fotograma.
        if (pinturaFilo.shader == null || cajaFilo != caja) {
            cajaFilo.set(caja)
            pinturaFilo.shader = LinearGradient(
                caja.left, caja.top, caja.right, caja.bottom,
                ColorUtils.setAlphaComponent(0xFFFFFFFF.toInt(), blanco),
                ColorUtils.setAlphaComponent(0xFFFFFFFF.toInt(), blanco / 4),
                Shader.TileMode.CLAMP
            )
        }
        val mitad = densidad / 2
        caja.inset(mitad, mitad)
        canvas.drawRoundRect(caja, radio - mitad, radio - mitad, pinturaFilo)
        caja.inset(-mitad, -mitad)
    }

    private fun pintarToque(canvas: Canvas, b: Rect) {
        val alcance = maxOf(b.width(), b.height()) * 0.65f
        pinturaToque.shader = RadialGradient(
            b.left + toqueX, b.top + toqueY, alcance,
            ColorUtils.setAlphaComponent(0xFFFFFFFF.toInt(), (toque * 0.16f * 255).roundToInt()),
            0x00FFFFFF, Shader.TileMode.CLAMP
        )
        canvas.drawRect(b, pinturaToque)
    }

    override fun getOutline(outline: Outline) {
        if (estilo.soloBordeInferior) outline.setRect(bounds) else outline.setRoundRect(bounds, radio())
        // Translúcido: la sombra no se pinta debajo, solo alrededor, y es algo más suave.
        outline.alpha = ALFA_SOMBRA
    }

    override fun isStateful() = estilo.interactivo

    /**
     * Pulsado: brilla del todo donde está el dedo. Con el foco del teclado, del mando o del acceso por interruptores
     * (o el ratón encima), brilla a medias desde el centro: el vidrio sustituye al ripple de Material, que era el que
     * lo marcaba.
     */
    override fun onStateChange(state: IntArray): Boolean {
        if (!estilo.interactivo) return false
        val activo = android.R.attr.state_enabled in state
        val ahora = when {
            activo && android.R.attr.state_pressed in state -> 1f
            activo && (android.R.attr.state_focused in state || android.R.attr.state_hovered in state) -> BRILLO_FOCO
            else -> 0f
        }
        if (ahora == objetivoToque) return false
        if (ahora == BRILLO_FOCO && objetivoToque == 0f) {
            toqueX = bounds.width() / 2f
            toqueY = bounds.height() / 2f
        }
        objetivoToque = ahora
        animacionToque?.cancel()
        if (!Movimiento.activas()) {
            toque = ahora
            invalidateSelf()
        } else {
            val duracion = if (ahora > toque) Movimiento.CORTA_MS else Movimiento.LARGA_MS
            animacionToque = Movimiento.animarValor(host.context, toque, ahora, duracion) {
                toque = it
                invalidateSelf()
            }
        }
        return true
    }

    override fun setHotspot(x: Float, y: Float) {
        toqueX = x - bounds.left
        toqueY = y - bounds.top
        if (toque > 0f) invalidateSelf()
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    internal companion object {
        const val BRILLO_CLARO = 0.42f
        const val BRILLO_OSCURO = 0.16f
        const val ALFA_SOMBRA = 0.6f
        const val BRILLO_FOCO = 0.55f

        /** Cuánto se alarga la barra fuera de la pantalla para que sus bordes de arriba y de los lados no se vean. */
        const val EXTRA_BORDE_OCULTO = 48f
    }
}

/**
 * Lo que tiene que cumplir el fondo de un vidrio para que todos sus textos se lean (WCAG AA, 4,5:1): una luminancia
 * mínima si los textos son más oscuros que el tinte, o máxima si son más claros. Se calcula con el texto que menos
 * contrasta de los de [EstiloVidrio.textos].
 */
internal class Contraste(val limite: Float, val sentido: Float) {
    companion object {
        private const val MINIMO = 4.5

        fun de(host: View, estilo: EstiloVidrio, tinte: Int): Contraste {
            val luzTinte = ColorUtils.calculateLuminance(tinte)
            val luces = estilo.textos.map { ColorUtils.calculateLuminance(ContextCompat.getColor(host.context, it)) }
            val oscuros = luces.filter { it < luzTinte }
            return if (oscuros.size >= luces.size - oscuros.size) {
                // Textos oscuros sobre vidrio claro: el fondo tiene que ser al menos así de claro.
                Contraste((MINIMO * (oscuros.max() + 0.05) - 0.05).coerceIn(0.0, 1.0).toFloat(), 1f)
            } else {
                val claros = luces.filter { it >= luzTinte }
                Contraste(((claros.min() + 0.05) / MINIMO - 0.05).coerceIn(0.0, 1.0).toFloat(), -1f)
            }
        }
    }
}

/** La parte de Android 13: refracción, vibrancia, tinte adaptativo, grosor, filo y brillo en un solo shader. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class Refraccion(estilo: EstiloVidrio, densidad: Float, tinte: Int, brillo: Float, contraste: Contraste) {

    private val shader = RuntimeShader(AGSL_VIDRIO).apply {
        setFloatUniform("banda", estilo.bandaDp * densidad)
        setFloatUniform("refraccion", estilo.refraccionDp * densidad)
        setFloatUniform("dispersion", DISPERSION)
        setFloatUniform("tinte", ((tinte shr 16) and 0xFF) / 255f, ((tinte shr 8) and 0xFF) / 255f, (tinte and 0xFF) / 255f)
        setFloatUniform("fuerza", estilo.fuerzaTinte)
        setFloatUniform("saturacion", estilo.saturacion)
        setFloatUniform("limite", contraste.limite)
        setFloatUniform("sentido", contraste.sentido)
        setFloatUniform("brillo", brillo)
        setFloatUniform("brilloSuperior", estilo.brilloSuperior * brillo / VidrioDrawable.BRILLO_CLARO)
        setFloatUniform("grosorFilo", GROSOR_FILO_DP * densidad)
    }

    private var ultimo: RenderEffect? = null
    private val clave = FloatArray(9) { Float.NaN }

    fun efecto(
        desenfoque: RenderEffect,
        izquierda: Float, arriba: Float, ancho: Float, alto: Float, radio: Float,
        toqueX: Float, toqueY: Float, toque: Float, borde: Float
    ): RenderEffect {
        // Lo normal (el contenido se mueve por detrás, el vidrio no) es que nada cambie: se reutiliza el efecto.
        val igual = clave[0] == izquierda && clave[1] == arriba && clave[2] == ancho && clave[3] == alto &&
            clave[4] == radio && clave[5] == toqueX && clave[6] == toqueY && clave[7] == toque && clave[8] == borde
        ultimo?.let { if (igual) return it }
        clave[0] = izquierda; clave[1] = arriba; clave[2] = ancho; clave[3] = alto; clave[4] = radio
        clave[5] = toqueX; clave[6] = toqueY; clave[7] = toque; clave[8] = borde
        shader.setFloatUniform("origen", izquierda, arriba)
        shader.setFloatUniform("tamano", ancho, alto)
        shader.setFloatUniform("radio", radio)
        shader.setFloatUniform("toque", toqueX, toqueY, toque)
        shader.setFloatUniform("borde", borde)
        // El efecto toma los valores del shader al crearse: con otros valores hace falta uno nuevo.
        return RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(shader, "contenido"), desenfoque)
            .also { ultimo = it }
    }

    private companion object {
        const val DISPERSION = 0.14f
        const val GROSOR_FILO_DP = 1.5f
    }
}

/**
 * El vidrio, sobre lo de detrás ya desenfocado ("contenido"):
 * 1. Lente: en la franja del borde ([banda]) la imagen se curva hacia dentro con el perfil de una lente redonda (casi
 *    nada en el interior, mucho en el filo), separando un poco el rojo y el azul. Al pulsar, se hunde más.
 * 2. Vibrancia: lo de detrás, más saturado, como el vidrio de iOS.
 * 3. Tinte adaptativo: al menos [fuerza], y más donde haga falta para que la luminancia real (WCAG) quede del lado
 *    bueno de [limite] ([sentido] 1: al menos; -1: como mucho), que es lo que necesita el texto para leerse. Se mezcla
 *    en sRGB, que conserva el color del tinte, y la cantidad justa se busca por bisección.
 * 4. Grosor (luz por dentro arriba, sombra abajo), un brillo amplio arriba, el filo de luz que viene de arriba a la
 *    izquierda (con su reflejo abajo a la derecha) y el brillo donde se pulsa.
 * Fuera de la forma, transparente con el borde suavizado. [borde] (0–1) apaga la lente y el filo.
 */
private const val AGSL_VIDRIO = """
uniform shader contenido;
uniform float2 origen;
uniform float2 tamano;
uniform float radio;
uniform float banda;
uniform float refraccion;
uniform float dispersion;
uniform float3 tinte;
uniform float fuerza;
uniform float saturacion;
uniform float limite;
uniform float sentido;
uniform float brillo;
uniform float brilloSuperior;
uniform float grosorFilo;
uniform float borde;
uniform float3 toque;

const float3 PESOS = float3(0.2126, 0.7152, 0.0722);

float forma(float2 p, float2 mitad, float r) {
    float2 q = abs(p) - mitad + float2(r);
    return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - r;
}

float3 lineal(float3 c) {
    float3 bajo = c / 12.92;
    float3 alto = pow((c + 0.055) / 1.055, float3(2.4));
    return mix(alto, bajo, step(c, float3(0.04045)));
}

float luz(float3 c) {
    return dot(lineal(clamp(c, 0.0, 1.0)), PESOS);
}

half4 main(float2 coord) {
    float2 mitad = tamano * 0.5;
    float2 p = coord - origen - mitad;
    float d = forma(p, mitad, radio);
    if (d > 1.0) {
        return half4(0.0);
    }
    float2 ex = float2(0.5, 0.0);
    float2 ey = float2(0.0, 0.5);
    float2 n = float2(forma(p + ex, mitad, radio) - forma(p - ex, mitad, radio),
                      forma(p + ey, mitad, radio) - forma(p - ey, mitad, radio));
    float largo = length(n);
    n = largo > 0.0001 ? n / largo : float2(0.0);

    float t = clamp(1.0 + d / banda, 0.0, 1.0);
    float lente = (1.0 - sqrt(max(1.0 - t * t, 0.0))) * borde;
    float2 desvio = -n * lente * refraccion * (1.0 + 0.35 * toque.z);

    float3 c = float3(
        float(contenido.eval(coord + desvio * (1.0 + dispersion)).r),
        float(contenido.eval(coord + desvio).g),
        float(contenido.eval(coord + desvio * (1.0 - dispersion)).b));
    float gris = dot(c, PESOS);
    c = clamp(mix(float3(gris), c, saturacion), 0.0, 1.0);

    float3 color = mix(c, tinte, fuerza);
    if (sentido * (limite - luz(color)) > 0.0) {
        float bajo = fuerza;
        float alto = 1.0;
        for (int i = 0; i < 7; i++) {
            float medio = 0.5 * (bajo + alto);
            if (sentido * (limite - luz(mix(c, tinte, medio))) > 0.0) {
                bajo = medio;
            } else {
                alto = medio;
            }
        }
        color = mix(c, tinte, alto);
    }

    color += brillo * 0.30 * lente * clamp(-n.y, 0.0, 1.0);
    color *= 1.0 - 0.10 * lente * clamp(n.y, 0.0, 1.0);
    float altura = (coord.y - origen.y) / tamano.y;
    color += brilloSuperior * (1.0 - smoothstep(0.0, 0.6, altura));

    float2 luz = normalize(float2(-0.45, -1.0));
    float filo = (1.0 - smoothstep(0.0, grosorFilo, -d)) * borde;
    float cara = clamp(dot(n, luz), 0.0, 1.0);
    float contra = clamp(-dot(n, luz), 0.0, 1.0);
    color += brillo * filo * (0.35 + 0.65 * cara + 0.45 * contra);

    float alcance = max(mitad.x, mitad.y) * 1.3;
    color += toque.z * 0.16 * (1.0 - smoothstep(0.0, alcance, length(coord - toque.xy)));

    float alfa = 1.0 - smoothstep(-0.75, 0.75, d);
    float3 salida = clamp(color, 0.0, 1.0) * alfa;
    return half4(half3(salida), half(alfa));
}
"""
