package com.julen.socios

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.OverScroller
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withClip
import com.julen.socios.util.CalculoComisiones
import com.julen.socios.util.EscalaGrafico
import com.julen.socios.util.HapticUtils
import com.julen.socios.util.Movimiento
import com.julen.socios.util.toEurosCompacto
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

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

    private val pinturas = PinturasGrafico(context)
    private val rect = RectF()
    private val path = Path()

    // Lo que solo depende de las columnas: se prepara en [mostrar] y no en cada fotograma, porque al desplazar o
    // durante la animación se dibuja 60 veces por segundo (y con años de histórico hay muchas columnas).
    private var escala: EscalaGrafico = EscalaGrafico.para(emptyList())
    private var guias: List<Double> = emptyList()
    private var textosGuias: List<String> = emptyList()
    private var anchoEscala = 0f
    private var textosNeto: List<String> = emptyList()
    private var indiceMaximo = -1
    private var marcas = IntArray(0)
    private var anchosMarca = FloatArray(0)

    // El aviso de la columna tocada: su texto se forma una vez por columna, no en cada fotograma.
    private var avisoIndice = -1
    private var avisoTexto = ""
    private var avisoAncho = 0f

    // Piezas que se reutilizan al dibujar, para no crear objetos en cada fotograma.
    private val radiosBarra = FloatArray(8)
    private val rotuloDestacada = Rotulo()
    private val rotuloMaximo = Rotulo()

    /** Muestra [nuevas] columnas; con [animar], las barras crecen desde la línea base. */
    fun mostrar(nuevas: List<Columna>, destacada: Int, animar: Boolean) {
        val mismas = nuevas.map { it.etiquetaLarga } == columnas.map { it.etiquetaLarga }
        columnas = nuevas
        anchoEtiqueta = (nuevas.maxOfOrNull { pinturas.etiqueta.measureText(it.etiqueta) } ?: 0f) + 6 * densidad
        this.destacada = destacada
        prepararDatos()
        medirArea()
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        medirArea()
    }

    override fun onDetachedFromWindow() {
        animacion?.cancel()
        scroller.forceFinished(true)
        rastreo?.recycle()
        rastreo = null
        super.onDetachedFromWindow()
    }

    // --- Geometría ---

    /**
     * La escala, sus líneas de guía con sus textos, el ancho de la escala fija de la derecha (el de su rótulo más
     * largo y un margen), los importes, la columna máxima y las marcas de año.
     */
    private fun prepararDatos() {
        escala = EscalaGrafico.para(columnas.map { it.neto })
        guias = escala.guias
        textosGuias = guias.map { it.toEurosCompacto() }
        anchoEscala = (textosGuias.maxOfOrNull { pinturas.escala.measureText(it) } ?: 0f) + 8 * densidad
        textosNeto = columnas.map { it.neto.toEurosCompacto() }
        indiceMaximo = columnas.indices.maxByOrNull { columnas[it].neto }?.takeIf { columnas[it].neto > 0.0 } ?: -1
        marcas = columnas.indices.filter { columnas[it].separador != null }.toIntArray()
        anchosMarca = FloatArray(marcas.size) { pinturas.marca.measureText(columnas[marcas[it]].separador.orEmpty()) }
        avisoIndice = -1
    }

    private var areaIzquierda = 0f
    private var areaDerecha = 0f

    /**
     * Dónde van las columnas: todo el ancho menos la escala de la derecha. Se calcula al cambiar de tamaño o de
     * datos, no solo al dibujar: así [sePuedeDesplazar] acierta antes del primer dibujo.
     */
    private fun medirArea() {
        if (width == 0 || columnas.isEmpty()) return
        areaIzquierda = paddingLeft.toFloat()
        areaDerecha = width - paddingRight - anchoEscala
    }

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

        val e = escala
        areaIzquierda = paddingLeft.toFloat()
        areaDerecha = width - paddingRight - anchoEscala
        if (pegadoAlFinal) desplazamiento = maxDesplazamiento else desplazamiento = desplazamiento.coerceIn(0f, maxDesplazamiento)

        // Arriba, las marcas de año, el aviso de la columna tocada y los rótulos; abajo, las etiquetas.
        val arriba = paddingTop + altoMarca + altoAviso + altoRotulo
        val abajo = height - paddingBottom - altoEje
        val altoUtil = abajo - arriba
        val base = arriba + (altoUtil * (e.techo / e.rango)).toFloat()

        dibujarEscala(canvas, e, base, altoUtil)

        canvas.withClip(areaIzquierda, 0f, areaDerecha, height.toFloat()) {
            dibujarColumnas(this, e, base, altoUtil, abajo)
            media?.let {
                val y = yDe(it, base, altoUtil, e.rango)
                raya(this, areaIzquierda, y, areaDerecha, y, pinturas.media)
            }
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

    /** Altura en la vista del importe [valor], con la línea base en [base]. */
    private fun yDe(valor: Double, base: Float, altoUtil: Float, rango: Double) = base - (altoUtil * (valor / rango)).toFloat()

    /** Las líneas de guía (la del cero, continua) y sus importes en la columna fija de la derecha. */
    private fun dibujarEscala(canvas: Canvas, e: EscalaGrafico, base: Float, altoUtil: Float) {
        val ajusteTexto = -(pinturas.escala.descent() + pinturas.escala.ascent()) / 2
        for (g in guias.indices) {
            val valor = guias[g]
            val y = yDe(valor, base, altoUtil, e.rango)
            if (e.esCero(valor)) {
                canvas.drawLine(areaIzquierda, y, areaDerecha, y, pinturas.eje)
            } else {
                raya(canvas, areaIzquierda, y, areaDerecha, y, pinturas.guia)
            }
            canvas.drawText(textosGuias[g], (width - paddingRight).toFloat(), y + ajusteTexto, pinturas.escala)
        }
    }

    private fun dibujarColumnas(canvas: Canvas, e: EscalaGrafico, base: Float, altoUtil: Float, abajo: Float) {
        val anchoBarra = min(anchoMaxBarra, hueco - separacionMin * 2)
        // Etiquetas del eje: todas si caben; si no, una de cada pocas contando desde la destacada, que siempre va.
        val paso = max(1, ceil(anchoEtiqueta / hueco).toInt())
        val anclaje = if (destacada in columnas.indices) destacada else columnas.lastIndex

        if (hueco <= 0f) return
        // Solo las columnas que se ven (con una de margen a cada lado).
        val primera = (floor((areaIzquierda - origen) / hueco).toInt() - 1).coerceAtLeast(0)
        val ultima = ceil((areaDerecha - origen) / hueco).toInt().coerceAtMost(columnas.lastIndex)
        for (i in primera..ultima) {
            val columna = columnas[i]
            val centro = centroDe(i)
            val alto = altoDe(columna.neto, altoUtil, e.rango) * progreso
            pinturas.barra.color = when {
                columna.neto < 0 -> pinturas.colorNegativo
                i == destacada || i == seleccionada -> pinturas.colorBarra
                else -> pinturas.colorBarraSuave
            }
            if (alto > 0.5f) {
                if (columna.neto >= 0) {
                    barra(canvas, centro - anchoBarra / 2, base - alto, centro + anchoBarra / 2, base, puntaArriba = true)
                } else {
                    barra(canvas, centro - anchoBarra / 2, base, centro + anchoBarra / 2, base + alto, puntaArriba = false)
                }
            }
            if ((anclaje - i) % paso == 0) {
                pinturas.etiqueta.color = if (i == destacada) pinturas.colorTexto else pinturas.colorTextoSuave
                canvas.drawText(columna.etiqueta, centro, abajo + altoEje - 6 * densidad, pinturas.etiqueta)
            }
        }
    }

    /**
     * Rótulos directos solo en la destacada y en el máximo, cuando la animación ha terminado y no hay una columna
     * tocada encima. Si los dos se pisarían, se queda el de la destacada.
     */
    private fun dibujarRotulos(canvas: Canvas, e: EscalaGrafico, base: Float, altoUtil: Float, abajo: Float) {
        if (progreso < 1f) return
        val hayDestacada = colocarRotulo(rotuloDestacada, destacada, base, altoUtil, e.rango, abajo)
        val hayMaximo = indiceMaximo != destacada &&
            colocarRotulo(rotuloMaximo, indiceMaximo, base, altoUtil, e.rango, abajo) &&
            !(hayDestacada && RectF.intersects(rotuloDestacada.caja, rotuloMaximo.caja))
        if (hayDestacada) pintarRotulo(canvas, rotuloDestacada)
        if (hayMaximo) pintarRotulo(canvas, rotuloMaximo)
    }

    private fun pintarRotulo(canvas: Canvas, r: Rotulo) {
        canvas.drawText(r.texto, r.caja.centerX(), r.linea, pinturas.rotuloContorno)
        canvas.drawText(r.texto, r.caja.centerX(), r.linea, pinturas.rotulo)
    }

    /** Dónde va un rótulo: se reutiliza en cada fotograma. */
    private class Rotulo {
        var texto = ""
        val caja = RectF()
        var linea = 0f
    }

    /**
     * Coloca en [r] el importe sobre la punta de la barra [indice] (bajo ella si es negativa). Devuelve false si
     * no se rotula.
     */
    private fun colocarRotulo(r: Rotulo, indice: Int, base: Float, altoUtil: Float, rango: Double, abajo: Float): Boolean {
        val columna = columnas.getOrNull(indice) ?: return false
        if (indice == seleccionada || columna.neto == 0.0) return false
        // Si su barra está fuera de la vista (por desplazar), el rótulo no se queda flotando en el borde.
        val centroBarra = centroDe(indice)
        if (centroBarra < areaIzquierda || centroBarra > areaDerecha) return false
        val alto = altoDe(columna.neto, altoUtil, rango)
        val linea = (if (columna.neto >= 0) base - alto - 6 * densidad else base + alto + altoRotulo - 4 * densidad)
            .coerceAtMost(abajo)
        val texto = textosNeto[indice]
        // En las barras de los extremos el rótulo se mete hacia dentro en lugar de salirse del gráfico.
        val mitad = pinturas.rotulo.measureText(texto) / 2
        val centro = centroBarra.coerceIn(areaIzquierda + mitad, areaDerecha - mitad)
        r.texto = texto
        r.linea = linea
        r.caja.set(centro - mitad, linea + pinturas.rotulo.ascent(), centro + mitad, linea + pinturas.rotulo.descent())
        return true
    }

    /**
     * Una raya discontinua donde empieza cada año, con el año arriba. El del año que se está viendo se queda
     * pegado a la izquierda al desplazar, hasta que el siguiente lo empuja.
     */
    private fun dibujarMarcas(canvas: Canvas, base: Float) {
        val lineaTexto = paddingTop - pinturas.marca.ascent()
        for (i in marcas) {
            val x = origen + hueco * i
            if (i > 0 && x > areaIzquierda) raya(canvas, x, paddingTop + altoMarca, x, base, pinturas.guia)
        }
        val margen = 4 * densidad
        // La última marca que ya ha pasado el borde izquierdo: la del año que se está viendo.
        var fija = -1
        for (k in marcas.indices) if (origen + hueco * marcas[k] <= areaIzquierda + margen) fija = k
        for (k in marcas.indices) {
            val i = marcas[k]
            val texto = columnas[i].separador ?: continue
            val ancho = anchosMarca[k]
            val x = if (k == fija) {
                // Pegada a la izquierda, salvo que la siguiente marca ya esté llegando: entonces la empuja.
                val siguiente = if (k + 1 < marcas.size) origen + hueco * marcas[k + 1] else Float.MAX_VALUE
                min(max(origen, areaIzquierda) + margen, siguiente - ancho - margen * 3)
            } else {
                origen + hueco * i + margen
            }
            if (k != fija && x < areaIzquierda + margen) continue
            rect.set(x - margen, paddingTop.toFloat(), x + ancho + margen, paddingTop + altoMarca)
            canvas.drawRect(rect, pinturas.marcaFondo)
            canvas.drawText(texto, x, lineaTexto, pinturas.marca)
        }
    }

    // Un degradado del fondo a transparente, de 0 a [anchoDifuminado]; se coloca en cada borde con una matriz.
    private val degradado = LinearGradient(
        0f, 0f, anchoDifuminado, 0f, pinturas.colorFondo, ColorUtils.setAlphaComponent(pinturas.colorFondo, 0), Shader.TileMode.CLAMP
    ).also { pinturas.difuminado.shader = it }
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
        canvas.drawRect(desde, paddingTop + altoMarca, desde + anchoDifuminado, height.toFloat(), pinturas.difuminado)
    }

    /** Barra con la punta (el extremo del dato) redondeada y la base recta, pegada a la línea base. */
    private fun barra(canvas: Canvas, izquierda: Float, arriba: Float, derecha: Float, abajo: Float, puntaArriba: Boolean) {
        val r = min(radio, (abajo - arriba) / 2)
        // Las cuatro primeras son las esquinas de arriba y las cuatro últimas las de abajo.
        radiosBarra.fill(if (puntaArriba) r else 0f, 0, 4)
        radiosBarra.fill(if (puntaArriba) 0f else r, 4, 8)
        rect.set(izquierda, arriba, derecha, abajo)
        path.reset()
        path.addRoundRect(rect, radiosBarra, Path.Direction.CW)
        canvas.drawPath(path, pinturas.barra)
    }

    /** Pastilla con la etiqueta y el importe de la columna tocada, encima de su barra. */
    private fun dibujarAviso(canvas: Canvas, indice: Int, base: Float, altoUtil: Float, rango: Double) {
        val columna = columnas[indice]
        if (avisoIndice != indice) {
            avisoTexto = context.getString(R.string.grafico_seleccion, columna.etiquetaLarga, textosNeto[indice])
            avisoAncho = pinturas.avisoTexto.measureText(avisoTexto) + 20 * densidad
            avisoIndice = indice
        }
        val texto = avisoTexto
        val ancho = avisoAncho
        val alto = 24 * densidad
        val izquierda = (centroDe(indice) - ancho / 2).coerceIn(areaIzquierda, max(areaIzquierda, areaDerecha - ancho))
        val cima = if (columna.neto >= 0) base - altoDe(columna.neto, altoUtil, rango) else base
        val y = (cima - alto - 8 * densidad).coerceAtLeast(paddingTop + altoMarca)
        rect.set(izquierda, y, izquierda + ancho, y + alto)
        canvas.drawRoundRect(rect, alto / 2, alto / 2, pinturas.avisoFondo)
        val baseTexto = rect.centerY() - (pinturas.avisoTexto.descent() + pinturas.avisoTexto.ascent()) / 2
        canvas.drawText(texto, rect.centerX(), baseTexto, pinturas.avisoTexto)
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
