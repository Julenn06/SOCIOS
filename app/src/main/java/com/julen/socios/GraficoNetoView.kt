package com.julen.socios

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.OverScroller
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withClip
import com.julen.socios.util.CalculoComisiones
import com.julen.socios.util.HapticUtils
import com.julen.socios.util.Movimiento
import com.julen.socios.util.toEurosCompacto
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Columnas del neto por semana, mes o año, de la más antigua a la más reciente. Si no caben todas, se desplazan
 * con el dedo (empieza mostrando las más recientes) y los bordes se difuminan para que se note que hay más.
 *
 * Una sola serie, sin leyenda: la barra [destacada] (la semana, el mes o el año actual) va en el verde de la
 * app y el resto en un paso más suave del mismo verde; un neto negativo baja de la línea base en rojo. A la
 * derecha, fija, la escala con unas líneas de guía de importes redondos, y una línea discontinua con la
 * [media] de las columnas con algo. Solo se rotulan el máximo y la destacada; el resto se lee tocando la barra.
 * Las columnas con [Columna.separador] (el primer mes o la primera semana de un año) llevan una marca con el año.
 */
class GraficoNetoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /**
     * Una columna: su etiqueta corta en el eje ("S42", "oct", "2026"), la larga para el aviso y su neto. Con
     * [separador], empieza en ella un año nuevo y se marca con ese texto.
     */
    data class Columna(val etiqueta: String, val etiquetaLarga: String, val neto: Double, val separador: String? = null)

    private val densidad = resources.displayMetrics.density
    private val huecoMin = 30 * densidad
    private val anchoMaxBarra = 22 * densidad
    private val separacionMin = 3 * densidad
    private val radio = 5 * densidad
    private val altoEje = 22 * densidad
    private val altoMarca = 18 * densidad
    private val altoRotulo = 18 * densidad
    private val altoAviso = 28 * densidad
    private val anchoDifuminado = 28 * densidad
    private val configuracion = ViewConfiguration.get(context)
    private val margenToque = configuracion.scaledTouchSlop

    private var columnas: List<Columna> = emptyList()
    private var destacada = -1
    private var seleccionada = -1
    private var progreso = 1f
    private var animacion: ValueAnimator? = null

    /** Media del neto de las columnas con algo, o null si hay menos de dos: la de la línea discontinua. */
    var media: Double? = null
        private set

    /** Se llama al tocar una columna, para que la pantalla la anuncie a TalkBack. */
    var alSeleccionar: ((Columna) -> Unit)? = null

    // Desplazamiento: cuánto contenido queda oculto por la izquierda. Mientras [pegadoAlFinal], se queda en el
    // máximo aunque cambie el ancho, para que siempre se vea lo más reciente.
    private var desplazamiento = 0f
    private var pegadoAlFinal = true
    private val scroller = OverScroller(context)
    private var rastreo: VelocityTracker? = null
    private var inicioX = 0f
    private var inicioY = 0f
    private var ultimoX = 0f
    private var arrastrando = false
    private var recorriendo = false

    private val colorBarra = ContextCompat.getColor(context, R.color.grafico_barra)
    private val colorBarraSuave = ContextCompat.getColor(context, R.color.grafico_barra_suave)
    private val colorNegativo = ContextCompat.getColor(context, R.color.red_text)
    private val colorEje = ContextCompat.getColor(context, R.color.divider)
    private val colorTexto = ContextCompat.getColor(context, R.color.text_main)
    private val colorTextoSuave = ContextCompat.getColor(context, R.color.text_sub)
    private val colorFondo = ContextCompat.getColor(context, R.color.surface_card)
    private val colorMedia = ContextCompat.getColor(context, R.color.green_text_soft)

    private val pintaBarra = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pintaEje = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorEje
        strokeWidth = max(1f, densidad)
    }
    private val pintaGuia = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorEje
        strokeWidth = max(1f, densidad)
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(3 * densidad, 4 * densidad), 0f)
    }
    private val pintaMedia = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorMedia
        strokeWidth = 1.5f * densidad
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(6 * densidad, 4 * densidad), 0f)
    }
    private val pintaEtiqueta = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = ResourcesCompat.getFont(context, R.font.geist_medium)
        textSize = sp(10.5f)
        color = colorTextoSuave
    }
    private val pintaEscala = pinturaCifra(context, sp(10f)).apply {
        textAlign = Paint.Align.RIGHT
        color = colorTextoSuave
    }
    private val pintaMarca = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = ResourcesCompat.getFont(context, R.font.geist_semibold)
        textSize = sp(10.5f)
        color = colorTextoSuave
    }
    private val pintaMarcaFondo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorFondo }
    private val pintaRotulo = pinturaCifra(context, sp(11f)).apply { color = colorTexto }

    /** Contorno del color del fondo bajo los rótulos: así se leen aunque la línea de la media pase por detrás. */
    private val pintaRotuloContorno = Paint(pintaRotulo).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4 * densidad
        strokeJoin = Paint.Join.ROUND
        color = colorFondo
    }
    private val pintaAvisoFondo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorTexto }
    private val pintaAvisoTexto = pinturaCifra(context, sp(12f)).apply { color = colorFondo }
    private val pintaDifuminado = Paint()
    private val rect = RectF()
    private val path = Path()

    private fun sp(valor: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, valor, resources.displayMetrics)

    /** Muestra [nuevas] columnas; con [animar], las barras crecen desde la línea base. */
    fun mostrar(nuevas: List<Columna>, destacada: Int, animar: Boolean) {
        val mismas = nuevas.map { it.etiquetaLarga } == columnas.map { it.etiquetaLarga }
        columnas = nuevas
        anchoEtiqueta = (nuevas.maxOfOrNull { pintaEtiqueta.measureText(it.etiqueta) } ?: 0f) + 6 * densidad
        this.destacada = destacada
        media = nuevas.filter { it.neto != 0.0 }.takeIf { it.size >= 2 }
            ?.let { conDatos -> CalculoComisiones.redondearCentimos(conDatos.sumOf { it.neto } / conDatos.size) }
        if (!mismas) {
            // Otras columnas (se ha cambiado de semanas a meses, por ejemplo): se vuelve a lo más reciente.
            seleccionada = -1
            scroller.forceFinished(true)
            pegadoAlFinal = true
        }
        animacion?.cancel()
        if (!animar) {
            progreso = 1f
            invalidate()
            return
        }
        animacion = Movimiento.animarValor(context, 0f, 1f, Movimiento.LARGA_MS) {
            progreso = it
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val alto = resolveSize((210 * densidad).toInt(), heightMeasureSpec)
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), alto)
    }

    override fun onDetachedFromWindow() {
        animacion?.cancel()
        scroller.forceFinished(true)
        rastreo?.recycle()
        rastreo = null
        super.onDetachedFromWindow()
    }

    // --- Geometría ---

    /** La escala de importes: líneas de guía de [paso] en [paso] desde [suelo] hasta [techo]. */
    private class Escala(val suelo: Double, val techo: Double, val paso: Double) {
        val rango: Double get() = techo - suelo
    }

    private fun escala(): Escala {
        val maximo = columnas.maxOfOrNull { it.neto }?.coerceAtLeast(0.0) ?: 0.0
        val minimo = columnas.minOfOrNull { it.neto }?.coerceAtMost(0.0) ?: 0.0
        val paso = pasoRedondo(max(maximo, -minimo) / 3)
        val techo = (ceil(maximo / paso) * paso).coerceAtLeast(paso)
        val suelo = floor(minimo / paso) * paso
        return Escala(suelo, techo, paso)
    }

    /** Un paso de 1, 2, 2,5 o 5 por una potencia de 10 (5 €, 25 €, 100 €, 250 €…) que no baje de [aprox]. */
    private fun pasoRedondo(aprox: Double): Double {
        if (aprox <= 0.0) return 100.0
        val potencia = 10.0.pow(floor(log10(aprox)))
        val factor = aprox / potencia
        return potencia * when {
            factor <= 1.0 -> 1.0
            factor <= 2.0 -> 2.0
            factor <= 2.5 -> 2.5
            factor <= 5.0 -> 5.0
            else -> 10.0
        }
    }

    /** Valores de las líneas de guía, de abajo arriba. */
    private fun guias(e: Escala): List<Double> =
        generateSequence(e.suelo) { it + e.paso }.takeWhile { it <= e.techo + e.paso / 1000 }.toList()

    /** Ancho de la escala fija de la derecha: el de su rótulo más largo y un margen. */
    private fun anchoEscala(e: Escala): Float =
        guias(e).maxOf { pintaEscala.measureText(it.toEurosCompacto()) } + 8 * densidad

    private var areaIzquierda = 0f
    private var areaDerecha = 0f

    private val anchoArea: Float get() = areaDerecha - areaIzquierda

    /**
     * Ancho de cada columna: el que tendrían [COLUMNAS_MIN] repartiéndose el sitio, sin bajar del mínimo. Así
     * unos pocos años van igual de juntos que las semanas o los meses; si no caben todas, se desplazan.
     */
    private val hueco: Float
        get() = if (columnas.isEmpty()) 0f else maxOf(huecoMin, anchoEtiqueta, anchoArea / max(columnas.size, COLUMNAS_MIN))

    /** La etiqueta más ancha del eje y un margen: ninguna columna es más estrecha, así salen todas ("2025"). */
    private var anchoEtiqueta = 0f

    private val maxDesplazamiento: Float get() = max(0f, columnas.size * hueco - anchoArea)

    /**
     * Si no caben todas las columnas. Antes de la primera medida (sin ancho aún) se supone que sí, en cuanto hay
     * más de las que se reparten el ancho.
     */
    val sePuedeDesplazar: Boolean
        get() = if (anchoArea > 0f) maxDesplazamiento > 0.5f else columnas.size > COLUMNAS_MIN

    /**
     * Dónde empieza la primera columna, ya desplazado. Si sobra sitio, las columnas se pegan a la derecha, con la
     * más reciente en el borde, como cuando se ven todas al final.
     */
    private val origen: Float
        get() = areaIzquierda + max(0f, anchoArea - columnas.size * hueco) - desplazamiento

    /** Centro horizontal de la columna [indice]. */
    private fun centroDe(indice: Int) = origen + hueco * (indice + 0.5f)

    /** Alto de la barra de [neto], hacia arriba o hacia abajo de la línea base. */
    private fun altoDe(neto: Double, altoUtil: Float, rango: Double) = (altoUtil * (abs(neto) / rango)).toFloat()

    private fun desplazarA(valor: Float) {
        desplazamiento = valor.coerceIn(0f, maxDesplazamiento)
        pegadoAlFinal = desplazamiento >= maxDesplazamiento - 0.5f
        invalidate()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            desplazarA(scroller.currX.toFloat())
            postInvalidateOnAnimation()
        }
    }

    // --- Dibujo ---

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (columnas.isEmpty()) return

        val e = escala()
        areaIzquierda = paddingLeft.toFloat()
        areaDerecha = width - paddingRight - anchoEscala(e)
        if (pegadoAlFinal) desplazamiento = maxDesplazamiento else desplazamiento = desplazamiento.coerceIn(0f, maxDesplazamiento)

        // Arriba, las marcas de año, el aviso de la columna tocada y los rótulos; abajo, las etiquetas.
        val arriba = paddingTop + altoMarca + altoAviso + altoRotulo
        val abajo = height - paddingBottom - altoEje
        val altoUtil = abajo - arriba
        val base = arriba + (altoUtil * (e.techo / e.rango)).toFloat()
        fun yDe(valor: Double) = base - (altoUtil * (valor / e.rango)).toFloat()

        dibujarEscala(canvas, e, ::yDe)

        canvas.withClip(areaIzquierda, 0f, areaDerecha, height.toFloat()) {
            dibujarColumnas(this, e, base, altoUtil, abajo)
            media?.let { raya(this, areaIzquierda, yDe(it), areaDerecha, yDe(it), pintaMedia) }
            dibujarRotulos(this, e, base, altoUtil, abajo)
            dibujarMarcas(this, base)
            dibujarDifuminados(this)
            if (seleccionada in columnas.indices) dibujarAviso(this, seleccionada, base, altoUtil, e.rango)
        }
    }

    /** Las rayas se dibujan como trazado: con drawLine, el discontinuo no se ve en todas las versiones. */
    private fun raya(canvas: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, pinta: Paint) {
        path.reset()
        path.moveTo(x0, y0)
        path.lineTo(x1, y1)
        canvas.drawPath(path, pinta)
    }

    /** Las líneas de guía (la del cero, continua) y sus importes en la columna fija de la derecha. */
    private fun dibujarEscala(canvas: Canvas, e: Escala, yDe: (Double) -> Float) {
        val ajusteTexto = -(pintaEscala.descent() + pintaEscala.ascent()) / 2
        for (valor in guias(e)) {
            val y = yDe(valor)
            if (abs(valor) < e.paso / 1000) {
                canvas.drawLine(areaIzquierda, y, areaDerecha, y, pintaEje)
            } else {
                raya(canvas, areaIzquierda, y, areaDerecha, y, pintaGuia)
            }
            canvas.drawText(valor.toEurosCompacto(), (width - paddingRight).toFloat(), y + ajusteTexto, pintaEscala)
        }
    }

    private fun dibujarColumnas(canvas: Canvas, e: Escala, base: Float, altoUtil: Float, abajo: Float) {
        val anchoBarra = min(anchoMaxBarra, hueco - separacionMin * 2)
        // Etiquetas del eje: todas si caben; si no, una de cada pocas contando desde la destacada, que siempre va.
        val paso = max(1, ceil(anchoEtiqueta / hueco).toInt())
        val anclaje = if (destacada in columnas.indices) destacada else columnas.lastIndex

        for (i in visibles()) {
            val columna = columnas[i]
            val centro = centroDe(i)
            val alto = altoDe(columna.neto, altoUtil, e.rango) * progreso
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
        }
    }

    /** Índices de las columnas que se ven (con una de margen a cada lado). */
    private fun visibles(): IntRange {
        if (hueco <= 0f) return IntRange.EMPTY
        val primera = floor((areaIzquierda - origen) / hueco).toInt() - 1
        val ultima = ceil((areaDerecha - origen) / hueco).toInt()
        return primera.coerceAtLeast(0)..ultima.coerceAtMost(columnas.lastIndex)
    }

    /**
     * Rótulos directos solo en la destacada y en el máximo, cuando la animación ha terminado y no hay una columna
     * tocada encima. Si los dos se pisarían, se queda el de la destacada.
     */
    private fun dibujarRotulos(canvas: Canvas, e: Escala, base: Float, altoUtil: Float, abajo: Float) {
        if (progreso < 1f) return
        val indiceMaximo = columnas.indices.maxByOrNull { columnas[it].neto }?.takeIf { columnas[it].neto > 0.0 } ?: -1
        val rotuloDestacada = rotulo(destacada, base, altoUtil, e.rango, abajo)
        val rotuloMaximo = rotulo(indiceMaximo, base, altoUtil, e.rango, abajo)?.takeIf { maximo ->
            indiceMaximo != destacada && rotuloDestacada?.let { RectF.intersects(it.caja, maximo.caja) } != true
        }
        for (r in listOfNotNull(rotuloDestacada, rotuloMaximo)) {
            canvas.drawText(r.texto, r.caja.centerX(), r.linea, pintaRotuloContorno)
            canvas.drawText(r.texto, r.caja.centerX(), r.linea, pintaRotulo)
        }
    }

    private class Rotulo(val texto: String, val caja: RectF, val linea: Float)

    /** El importe sobre la punta de la barra [indice] (bajo ella si es negativa), o null si no se rotula. */
    private fun rotulo(indice: Int, base: Float, altoUtil: Float, rango: Double, abajo: Float): Rotulo? {
        val columna = columnas.getOrNull(indice) ?: return null
        if (indice == seleccionada || columna.neto == 0.0) return null
        // Si su barra está fuera de la vista (por desplazar), el rótulo no se queda flotando en el borde.
        if (centroDe(indice) !in areaIzquierda..areaDerecha) return null
        val alto = altoDe(columna.neto, altoUtil, rango)
        val linea = (if (columna.neto >= 0) base - alto - 6 * densidad else base + alto + altoRotulo - 4 * densidad)
            .coerceAtMost(abajo)
        val texto = columna.neto.toEurosCompacto()
        // En las barras de los extremos el rótulo se mete hacia dentro en lugar de salirse del gráfico.
        val mitad = pintaRotulo.measureText(texto) / 2
        val centro = centroDe(indice).coerceIn(areaIzquierda + mitad, areaDerecha - mitad)
        return Rotulo(texto, RectF(centro - mitad, linea + pintaRotulo.ascent(), centro + mitad, linea + pintaRotulo.descent()), linea)
    }

    /**
     * Una raya discontinua donde empieza cada año, con el año arriba. El del año que se está viendo se queda
     * pegado a la izquierda al desplazar, hasta que el siguiente lo empuja.
     */
    private fun dibujarMarcas(canvas: Canvas, base: Float) {
        val lineaTexto = paddingTop - pintaMarca.ascent()
        val marcas = columnas.indices.filter { columnas[it].separador != null }
        for (i in marcas) {
            val x = origen + hueco * i
            if (i > 0 && x > areaIzquierda) raya(canvas, x, paddingTop + altoMarca, x, base, pintaGuia)
        }
        val margen = 4 * densidad
        val fija = marcas.lastOrNull { origen + hueco * it <= areaIzquierda + margen }
        for (i in marcas) {
            val texto = columnas[i].separador ?: continue
            val ancho = pintaMarca.measureText(texto)
            val x = if (i == fija) {
                // Pegada a la izquierda, salvo que la siguiente marca ya esté llegando: entonces la empuja.
                val siguiente = marcas.firstOrNull { it > i }?.let { origen + hueco * it }
                min(max(origen, areaIzquierda) + margen, (siguiente ?: Float.MAX_VALUE) - ancho - margen * 3)
            } else {
                origen + hueco * i + margen
            }
            if (i != fija && x < areaIzquierda + margen) continue
            rect.set(x - margen, paddingTop.toFloat(), x + ancho + margen, paddingTop + altoMarca)
            canvas.drawRect(rect, pintaMarcaFondo)
            canvas.drawText(texto, x, lineaTexto, pintaMarca)
        }
    }

    // Un degradado del fondo a transparente, de 0 a [anchoDifuminado]; se coloca en cada borde con una matriz.
    private val degradado = LinearGradient(
        0f, 0f, anchoDifuminado, 0f, colorFondo, ColorUtils.setAlphaComponent(colorFondo, 0), Shader.TileMode.CLAMP
    ).also { pintaDifuminado.shader = it }
    private val matrizDegradado = Matrix()

    /** Difumina los bordes cuando hay más columnas por ese lado: así se ve que el gráfico se desplaza. */
    private fun dibujarDifuminados(canvas: Canvas) {
        if (desplazamiento > 0.5f) difuminado(canvas, areaIzquierda, haciaLaDerecha = true)
        if (desplazamiento < maxDesplazamiento - 0.5f) difuminado(canvas, areaDerecha, haciaLaDerecha = false)
    }

    /** El degradado empieza opaco en [borde] y se desvanece hacia dentro del gráfico. */
    private fun difuminado(canvas: Canvas, borde: Float, haciaLaDerecha: Boolean) {
        matrizDegradado.reset()
        if (!haciaLaDerecha) matrizDegradado.setScale(-1f, 1f)
        matrizDegradado.postTranslate(borde, 0f)
        degradado.setLocalMatrix(matrizDegradado)
        val desde = if (haciaLaDerecha) borde else borde - anchoDifuminado
        canvas.drawRect(desde, paddingTop + altoMarca, desde + anchoDifuminado, height.toFloat(), pintaDifuminado)
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
        val izquierda = (centroDe(indice) - ancho / 2).coerceIn(areaIzquierda, max(areaIzquierda, areaDerecha - ancho))
        val cima = if (columna.neto >= 0) base - altoDe(columna.neto, altoUtil, rango) else base
        val y = (cima - alto - 8 * densidad).coerceAtLeast(paddingTop + altoMarca)
        rect.set(izquierda, y, izquierda + ancho, y + alto)
        canvas.drawRoundRect(rect, alto / 2, alto / 2, pintaAvisoFondo)
        val baseTexto = rect.centerY() - (pintaAvisoTexto.descent() + pintaAvisoTexto.ascent()) / 2
        canvas.drawText(texto, rect.centerX(), baseTexto, pintaAvisoTexto)
    }

    // --- Toques ---

    /**
     * Un toque elige la columna. Arrastrar en horizontal desplaza el gráfico (y, al soltar, sigue con inercia);
     * si caben todas las columnas, va recorriéndolas. Un arrastre vertical se deja al panel, para que el
     * histórico se pueda desplazar aunque el dedo empiece encima del gráfico.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (columnas.isEmpty()) return false
        val velocidad = rastreo ?: VelocityTracker.obtain().also { rastreo = it }
        velocidad.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                inicioX = event.x
                inicioY = event.y
                ultimoX = event.x
                arrastrando = false
                recorriendo = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = abs(event.x - inicioX)
                if (!arrastrando && !recorriendo && dx > margenToque && dx > abs(event.y - inicioY)) {
                    if (sePuedeDesplazar) arrastrando = true else recorriendo = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                when {
                    arrastrando -> desplazarA(desplazamiento + ultimoX - event.x)
                    recorriendo -> seleccionar(event.x)
                }
                ultimoX = event.x
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (arrastrando) {
                    velocidad.computeCurrentVelocity(1000, configuracion.scaledMaximumFlingVelocity.toFloat())
                    val vx = -velocidad.xVelocity.toInt()
                    if (abs(vx) > configuracion.scaledMinimumFlingVelocity) {
                        scroller.fling(desplazamiento.toInt(), 0, vx, 0, 0, maxDesplazamiento.toInt(), 0, 0)
                        postInvalidateOnAnimation()
                    }
                } else if (!recorriendo) {
                    seleccionar(event.x)
                    performClick()
                }
                terminarToque()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                terminarToque()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun terminarToque() {
        arrastrando = false
        recorriendo = false
        rastreo?.recycle()
        rastreo = null
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /** Toda la franja vertical de una columna es su zona táctil, no solo la barra. */
    private fun seleccionar(x: Float) {
        if (x < areaIzquierda || x > areaDerecha || hueco <= 0f) return
        // Tocar el hueco que queda a la izquierda de las columnas (con pocas) no elige ninguna.
        val indice = floor((x - origen) / hueco).toInt().takeIf { it in columnas.indices } ?: return
        if (indice == seleccionada) return
        seleccionada = indice
        HapticUtils.performClick(this)
        alSeleccionar?.invoke(columnas[indice])
        invalidate()
    }

    private companion object {
        /** Con menos columnas que estas, no se ensanchan para llenar el gráfico. */
        const val COLUMNAS_MIN = 12
    }

    // --- Accesibilidad: con TalkBack, el gráfico se desplaza con los gestos de desplazar de siempre ---

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isScrollable = sePuedeDesplazar
        if (desplazamiento > 0.5f) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        if (desplazamiento < maxDesplazamiento - 0.5f) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean = when (action) {
        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> {
            desplazarA(desplazamiento + anchoArea * 0.8f)
            true
        }

        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> {
            desplazarA(desplazamiento - anchoArea * 0.8f)
            true
        }

        else -> super.performAccessibilityAction(action, arguments)
    }
}
