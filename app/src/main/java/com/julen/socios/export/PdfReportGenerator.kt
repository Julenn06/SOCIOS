package com.julen.socios.export

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.toColorInt
import com.julen.socios.R
import com.julen.socios.model.DatosBackup
import com.julen.socios.model.Socio
import com.julen.socios.util.CalculoComisiones
import com.julen.socios.util.CalculoComisiones.Periodo
import com.julen.socios.util.EscalaGrafico
import com.julen.socios.util.LOCALE_ES
import com.julen.socios.util.descontadoConSigno
import com.julen.socios.util.nombreVisible
import com.julen.socios.util.rangoSemanaConAnio
import com.julen.socios.util.toCurrencyString
import com.julen.socios.util.toEurosCompacto
import java.io.OutputStream
import java.time.Clock
import java.time.LocalDateTime
import java.time.Month
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Informe PDF A4 con el aspecto de la app: la cabecera verde con el neto, las cifras que lo forman, un gráfico
 * con la evolución del neto (si hay más de una semana) y los socios de cada semana, con su resumen.
 *
 * Los colores son los del tema claro, también con el móvil en modo oscuro: un informe se imprime en papel blanco.
 */
class PdfReportGenerator(private val context: Context) {

    private val fuenteNormal: Typeface = ResourcesCompat.getFont(context, R.font.geist_regular) ?: Typeface.DEFAULT
    private val fuenteMedia: Typeface = ResourcesCompat.getFont(context, R.font.geist_medium) ?: Typeface.DEFAULT
    private val fuenteFuerte: Typeface = ResourcesCompat.getFont(context, R.font.geist_semibold) ?: Typeface.DEFAULT_BOLD
    private val fuenteNegrita: Typeface = ResourcesCompat.getFont(context, R.font.geist_bold) ?: Typeface.DEFAULT_BOLD

    private val texto = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val relleno = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trazo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()

    fun generar(datos: DatosBackup, alcance: AlcanceExport, outputStream: OutputStream, clock: Clock) {
        val documento = PdfDocument()
        try {
            Informe(documento, datos, alcance, clock).dibujar()
            documento.writeTo(outputStream)
        } finally {
            documento.close()
        }
    }

    /** Un informe concreto: sus cuentas y las páginas que se van llenando de arriba abajo. */
    private inner class Informe(
        private val documento: PdfDocument,
        private val datos: DatosBackup,
        private val alcance: AlcanceExport,
        private val clock: Clock
    ) {
        private val historico = CalculoComisiones.calcularResumenHistoricoGlobal(
            datos.socios,
            datos.bonosRegalo.filter { it.activo }.associateBy { it.semanaKey },
            datos.irpfVigencias,
            datos.cobros.associateBy { it.semanaKey }
        )
        private val sociosPorSemana = datos.socios.groupBy { it.semanaKey }

        private var numeroPagina = 0
        private lateinit var pagina: PdfDocument.Page
        private val canvas: Canvas get() = pagina.canvas

        /** Por dónde va la página abierta. */
        private var y = 0f

        fun dibujar() {
            nuevaPagina()
            dibujarCabecera()
            dibujarCifras()
            if (historico.semanasHistoricas.size > 1) dibujarGrafico()
            dibujarDetalle()
            cerrarPagina()
        }

        private fun nuevaPagina() {
            numeroPagina++
            pagina = documento.startPage(PdfDocument.PageInfo.Builder(ANCHO.toInt(), ALTO.toInt(), numeroPagina).create())
            y = MARGEN
        }

        private fun cerrarPagina() {
            dibujarPie()
            documento.finishPage(pagina)
        }

        /** Si no caben [alto] puntos más en la página, sigue en otra y llama a [alEmpezarOtra] (para repetir una cabecera). */
        private fun asegurar(alto: Float, alEmpezarOtra: () -> Unit = {}) {
            if (y + alto <= ALTO - MARGEN_PIE) return
            cerrarPagina()
            nuevaPagina()
            alEmpezarOtra()
        }

        // --- Cabecera: qué es, de cuándo y el neto en grande ---

        private fun dibujarCabecera() {
            val alto = 158f
            rect.set(MARGEN, y, ANCHO - MARGEN, y + alto)
            relleno.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom, VERDE_CABECERA, VERDE_CABECERA_OSCURO, Shader.TileMode.CLAMP)
            canvas.drawRoundRect(rect, 18f, 18f, relleno)
            relleno.shader = null

            val izquierda = MARGEN + 22f
            val derecha = ANCHO - MARGEN - 22f
            escribir(context.getString(R.string.pdf_titulo).uppercase(LOCALE_ES), izquierda, y + 30f, 8.5f, ETIQUETA_NETO, fuenteFuerte, espaciado = 0.12f)
            val generado = DateTimeFormatter.ofPattern("d/M/yyyy, H:mm", LOCALE_ES).format(LocalDateTime.now(clock))
            escribir(context.getString(R.string.pdf_generado, generado), derecha, y + 30f, 8.5f, ETIQUETA_CLARA, fuenteNormal, Paint.Align.RIGHT)
            escribir(tituloPeriodo(), izquierda, y + 52f, 15f, BLANCO, fuenteFuerte, ancho = derecha - izquierda)

            escribir(context.getString(R.string.pdf_kpi_neto), izquierda, y + 82f, 9.5f, ETIQUETA_NETO, fuenteMedia)
            escribir(historico.totalNetoAcumulado.toCurrencyString(), izquierda, y + 114f, 30f, BLANCO, fuenteNegrita)

            var x = izquierda
            for (pastilla in pastillas()) x = dibujarPastilla(pastilla, x, y + 128f) + 6f
            y += alto + 14f
        }

        private fun tituloPeriodo(): String = when (alcance) {
            AlcanceExport.Historico -> context.getString(R.string.export_titulo_historico)
            is AlcanceExport.Semanas -> alcance.semanaKeys.singleOrNull()?.let { context.rangoSemanaConAnio(it) }
                ?: context.resources.getQuantityString(R.plurals.export_titulo_semanas, alcance.semanaKeys.size, alcance.semanaKeys.size)
        }

        private fun pastillas(): List<String> {
            val semanas = historico.semanasHistoricas.size
            return buildList {
                add(context.getString(R.string.pdf_pastilla_socios, historico.totalSociosHechosAcumulado, historico.totalSociosRegistradosAcumulado))
                if (semanas > 1) {
                    add(context.resources.getQuantityString(R.plurals.global_history_count, semanas, semanas))
                    val media = CalculoComisiones.redondearCentimos(historico.totalNetoAcumulado / semanas)
                    add(context.getString(R.string.historico_media_semana, media.toCurrencyString()))
                }
                val descontado = historico.totalDescontadoAcumulado
                if (descontado != 0.0) add(context.getString(R.string.pdf_pastilla_descontado, descontado.descontadoConSigno()))
            }
        }

        /** Pastilla translúcida sobre la cabecera; devuelve dónde termina. */
        private fun dibujarPastilla(contenido: String, x: Float, arriba: Float): Float {
            preparar(8.5f, BLANCO, fuenteFuerte)
            val ancho = texto.measureText(contenido) + 18f
            rect.set(x, arriba, x + ancho, arriba + 18f)
            relleno.color = PASTILLA
            canvas.drawRoundRect(rect, 9f, 9f, relleno)
            canvas.drawText(contenido, x + 9f, arriba + 12.5f, texto)
            return rect.right
        }

        // --- Las cifras que forman el neto ---

        private fun dibujarCifras() {
            val bonusManual = historico.semanasHistoricas.any { it.resumenSemana.esBonoRegaloAplicado }
            val cifras = listOf(
                Cifra(R.string.pdf_kpi_bruto, historico.totalBrutoAcumulado.toEurosCompacto(), GRIS_FONDO, TEXTO),
                Cifra(R.string.pdf_kpi_base, historico.totalBaseX2Acumulado.toEurosCompacto(prefix = "+"), AZUL_FONDO, AZUL),
                Cifra(
                    if (bonusManual) R.string.pdf_kpi_bonus_manual else R.string.pdf_kpi_bonus,
                    historico.totalBonusAcumulado.toEurosCompacto(prefix = "+"), AMBAR_FONDO, AMBAR
                ),
                Cifra(R.string.historico_irpf_label, historico.totalIrpfRetenidoAcumulado.toEurosCompacto(prefix = "-"), ROJO_FONDO, ROJO)
            )
            val separacion = 10f
            val ancho = (ANCHO - 2 * MARGEN - separacion * (cifras.size - 1)) / cifras.size
            cifras.forEachIndexed { i, cifra ->
                val x = MARGEN + i * (ancho + separacion)
                rect.set(x, y, x + ancho, y + 52f)
                relleno.color = cifra.fondo
                canvas.drawRoundRect(rect, 12f, 12f, relleno)
                escribir(context.getString(cifra.etiqueta), x + 12f, y + 19f, 8.5f, TEXTO_SUAVE, fuenteMedia, ancho = ancho - 24f)
                escribir(cifra.valor, x + 12f, y + 39f, 14f, cifra.color, fuenteNegrita, ancho = ancho - 24f)
            }
            y += 52f

            // De una sola semana con el bonus ajustado a mano, por qué.
            val motivo = historico.semanasHistoricas.singleOrNull()
                ?.let { semana -> datos.bonosRegalo.firstOrNull { it.semanaKey == semana.semanaKey && it.activo } }?.motivo
            if (motivo != null) {
                escribir(context.getString(R.string.pdf_detalle_motivo, motivo), MARGEN + 2f, y + 16f, 9f, TEXTO_SUAVE, fuenteNormal, ancho = ANCHO - 2 * MARGEN)
                y += 16f
            }
            y += 22f
        }

        // --- Evolución del neto ---

        /** Barras del neto por semana (o por mes, si son muchas semanas), seguidas y con 0 € en las vacías. */
        private fun dibujarGrafico() {
            val semanas = historico.semanasHistoricas
            val hasta = semanas.maxOf { it.semanaKey }
            val porSemanas = CalculoComisiones.evolucionSemanas(semanas, hasta, minimo = 1)
            val barras = if (porSemanas.size <= MAX_BARRAS_SEMANAS) {
                porSemanas.map { it.clave.substringAfter("-W").toIntOrNull()?.let { s -> "S$s" }.orEmpty() to it.neto }
            } else {
                val meses = CalculoComisiones.agruparPorPeriodo(semanas, Periodo.MES)
                CalculoComisiones.evolucionPeriodos(meses, Periodo.MES, hasta, minimo = 1).takeLast(MAX_BARRAS_MESES).map {
                    val mes = it.clave.substringAfter('-').toIntOrNull()?.let(Month::of)
                    (mes?.getDisplayName(TextStyle.SHORT, LOCALE_ES)?.removeSuffix(".") ?: it.clave) to it.neto
                }
            }

            val alto = 150f
            asegurar(alto + 40f)
            escribir(context.getString(R.string.historico_seccion_evolucion), MARGEN + 2f, y + 12f, 12f, TEXTO, fuenteFuerte)
            val subtitulo = if (porSemanas.size <= MAX_BARRAS_SEMANAS) R.string.pdf_por_semanas else R.string.pdf_por_meses
            escribir(context.getString(subtitulo), ANCHO - MARGEN - 2f, y + 12f, 9f, TEXTO_SUAVE, fuenteNormal, Paint.Align.RIGHT)
            y += 24f

            rect.set(MARGEN, y, ANCHO - MARGEN, y + alto)
            trazo.color = DIVISOR
            trazo.strokeWidth = 0.8f
            trazo.pathEffect = null
            canvas.drawRoundRect(rect, 14f, 14f, trazo)

            val escala = EscalaGrafico.para(barras.map { it.second })
            val izquierda = MARGEN + 14f
            val derechaBarras = ANCHO - MARGEN - 50f
            val arriba = y + 22f
            val abajo = y + alto - 24f
            val altoUtil = abajo - arriba
            fun yDe(valor: Double) = arriba + (altoUtil * ((escala.techo - valor) / escala.rango)).toFloat()

            // Líneas de guía con sus importes a la derecha; la del cero, continua.
            for (valor in escala.guias) {
                val yGuia = yDe(valor)
                trazo.color = DIVISOR
                trazo.pathEffect = if (escala.esCero(valor)) null else DashPathEffect(floatArrayOf(3f, 3f), 0f)
                val linea = Path().apply { moveTo(izquierda, yGuia); lineTo(derechaBarras, yGuia) }
                canvas.drawPath(linea, trazo)
                escribir(valor.toEurosCompacto(), ANCHO - MARGEN - 12f, yGuia + 3f, 7.5f, TEXTO_SUAVE, fuenteNormal, Paint.Align.RIGHT)
            }
            trazo.pathEffect = null

            val hueco = (derechaBarras - izquierda) / barras.size
            val anchoBarra = min(18f, hueco * 0.62f)
            val maximo = barras.indices.maxByOrNull { barras[it].second }?.takeIf { barras[it].second > 0.0 } ?: -1
            // Las etiquetas del eje que quepan, contando desde la última (la más reciente siempre va).
            preparar(7.5f, TEXTO_SUAVE, fuenteMedia)
            val anchoEtiqueta = barras.maxOf { texto.measureText(it.first) } + 6f
            val paso = max(1, ceil(anchoEtiqueta / hueco).toInt())
            barras.forEachIndexed { i, (etiqueta, neto) ->
                val centro = izquierda + hueco * (i + 0.5f)
                val base = yDe(0.0)
                val punta = yDe(neto)
                relleno.color = when {
                    neto < 0 -> ROJO
                    i == maximo || i == barras.lastIndex -> VERDE
                    else -> VERDE_SUAVE
                }
                if (abs(punta - base) > 0.5f) {
                    rect.set(centro - anchoBarra / 2, min(punta, base), centro + anchoBarra / 2, max(punta, base))
                    val r = min(3.5f, rect.height() / 2)
                    val radios = if (neto >= 0) floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) else floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
                    canvas.drawPath(Path().apply { addRoundRect(rect, radios, Path.Direction.CW) }, relleno)
                }
                if ((barras.lastIndex - i) % paso == 0) {
                    escribir(etiqueta, centro, abajo + 14f, 7.5f, if (i == barras.lastIndex) TEXTO else TEXTO_SUAVE, fuenteMedia, Paint.Align.CENTER)
                }
                if (i == maximo) {
                    escribir(neto.toEurosCompacto(), centro, punta - 5f, 8f, TEXTO, fuenteFuerte, Paint.Align.CENTER)
                }
            }
            y += alto + 26f
        }

        // --- Los socios de cada semana ---

        private fun dibujarDetalle() {
            asegurar(80f)
            escribir(context.getString(R.string.pdf_seccion_detalle), MARGEN + 2f, y + 12f, 12f, TEXTO, fuenteFuerte)
            y += 24f
            dibujarCabeceraTabla()
            for (semana in historico.semanasHistoricas) {
                val socios = sociosPorSemana[semana.semanaKey].orEmpty().sortedWith(compareBy<Socio> { it.dia.id }.thenBy { it.timestamp })
                // La semana no se queda sola al final de una página: con ella va al menos su primera fila.
                asegurar(ALTO_SEMANA + ALTO_FILA, ::dibujarCabeceraTabla)
                dibujarSemana(semana)
                if (socios.isEmpty()) {
                    escribir(context.getString(R.string.pdf_sin_socios), COL_SOCIO, y + 13f, 8.5f, TEXTO_SUAVE, fuenteNormal)
                    y += ALTO_FILA
                }
                socios.forEachIndexed { i, socio ->
                    // Si la semana sigue en otra página, allí se repite su banda: cada socio, con su semana a la vista.
                    asegurar(ALTO_FILA) {
                        dibujarCabeceraTabla()
                        dibujarSemana(semana, continuacion = true)
                    }
                    dibujarFila(socio, ultima = i == socios.lastIndex)
                }
                y += 8f
            }
        }

        private fun dibujarCabeceraTabla() {
            val linea = y + 12f
            listOf(
                R.string.pdf_col_nombre to COL_SOCIO, R.string.pdf_col_dia to COL_DIA, R.string.pdf_col_cuota to COL_CUOTA,
                R.string.pdf_col_estado to COL_ESTADO, R.string.pdf_col_notas to COL_NOTAS
            ).forEach { (columna, x) ->
                escribir(context.getString(columna).uppercase(LOCALE_ES), x, linea, 7.5f, TEXTO_SUAVE, fuenteFuerte, espaciado = 0.08f)
            }
            y += 20f
        }

        /** La banda de una semana: sus fechas y, a la derecha, cuántos se hicieron y su neto. */
        private fun dibujarSemana(semana: CalculoComisiones.SemanaHistoricaItem, continuacion: Boolean = false) {
            val r = semana.resumenSemana
            rect.set(MARGEN, y, ANCHO - MARGEN, y + ALTO_SEMANA - 4f)
            relleno.color = GRIS_FONDO
            canvas.drawRoundRect(rect, 8f, 8f, relleno)
            val fechas = context.rangoSemanaConAnio(semana.semanaKey)
            val titulo = if (continuacion) context.getString(R.string.pdf_semana_continuacion, fechas) else fechas
            escribir(titulo, MARGEN + 10f, y + 15f, 9.5f, TEXTO, fuenteFuerte)
            val resumen = context.getString(
                R.string.pdf_semana_resumen, r.totalSociosHechos, r.totalSociosRegistrados, r.totalNeto.toCurrencyString()
            )
            escribir(resumen, ANCHO - MARGEN - 10f, y + 15f, 8.5f, TEXTO_SUAVE, fuenteMedia, Paint.Align.RIGHT)
            y += ALTO_SEMANA
        }

        private fun dibujarFila(socio: Socio, ultima: Boolean) {
            val linea = y + 13f
            escribir(socio.nombreVisible().resolver(context), COL_SOCIO, linea, 9f, TEXTO, fuenteMedia, ancho = COL_DIA - COL_SOCIO - 8f)
            escribir(socio.dia.nombreCompleto, COL_DIA, linea, 9f, TEXTO_SUAVE, fuenteNormal, ancho = COL_CUOTA - COL_DIA - 8f)
            escribir(socio.colaboracion.toEurosCompacto(), COL_CUOTA, linea, 9f, TEXTO, fuenteFuerte)

            // El estado, en una pastilla de su color.
            val (estado, fondo, color) = if (socio.hecho) {
                Triple(R.string.pdf_estado_hecho, VERDE_FONDO, VERDE_TEXTO)
            } else {
                Triple(R.string.pdf_estado_pendiente, AMBAR_FONDO, AMBAR)
            }
            preparar(7.5f, color, fuenteFuerte)
            val textoEstado = context.getString(estado)
            rect.set(COL_ESTADO, y + 4f, COL_ESTADO + texto.measureText(textoEstado) + 14f, y + 16f)
            relleno.color = fondo
            canvas.drawRoundRect(rect, 6f, 6f, relleno)
            canvas.drawText(textoEstado, COL_ESTADO + 7f, y + 12.6f, texto)

            escribir(socio.notas.replace('\n', ' '), COL_NOTAS, linea, 8.5f, TEXTO_SUAVE, fuenteNormal, ancho = ANCHO - MARGEN - COL_NOTAS - 6f)
            if (!ultima) {
                trazo.color = DIVISOR
                trazo.strokeWidth = 0.5f
                canvas.drawLine(COL_SOCIO, y + ALTO_FILA, ANCHO - MARGEN - 6f, y + ALTO_FILA, trazo)
            }
            y += ALTO_FILA
        }

        private fun dibujarPie() {
            val linea = ALTO - 28f
            trazo.color = DIVISOR
            trazo.strokeWidth = 0.6f
            trazo.pathEffect = null
            canvas.drawLine(MARGEN, linea - 12f, ANCHO - MARGEN, linea - 12f, trazo)
            escribir(context.getString(R.string.pdf_pie), MARGEN, linea, 8f, TEXTO_SUAVE, fuenteNormal)
            escribir(context.getString(R.string.pdf_pagina, numeroPagina), ANCHO - MARGEN, linea, 8f, TEXTO_SUAVE, fuenteMedia, Paint.Align.RIGHT)
        }

        /** Escribe [contenido] en una línea; con [ancho], lo recorta con "…" si no cabe. */
        private fun escribir(
            contenido: String,
            x: Float,
            linea: Float,
            tamano: Float,
            color: Int,
            fuente: Typeface,
            alineacion: Paint.Align = Paint.Align.LEFT,
            ancho: Float? = null,
            espaciado: Float = 0f
        ) {
            preparar(tamano, color, fuente, alineacion, espaciado)
            val visible = ancho?.let { TextUtils.ellipsize(contenido, texto, it, TextUtils.TruncateAt.END).toString() } ?: contenido
            canvas.drawText(visible, x, linea, texto)
        }
    }

    private class Cifra(val etiqueta: Int, val valor: String, val fondo: Int, val color: Int)

    private fun preparar(
        tamano: Float,
        color: Int,
        fuente: Typeface,
        alineacion: Paint.Align = Paint.Align.LEFT,
        espaciado: Float = 0f
    ) {
        texto.textSize = tamano
        texto.color = color
        texto.typeface = fuente
        texto.textAlign = alineacion
        texto.letterSpacing = espaciado
    }

    private companion object {
        const val ANCHO = 595f // A4 en puntos
        const val ALTO = 842f
        const val MARGEN = 32f
        const val MARGEN_PIE = 52f
        const val ALTO_SEMANA = 26f
        const val ALTO_FILA = 20f

        /** Hasta medio año se dibujan semanas; con más, meses (y como mucho los tres últimos años). */
        const val MAX_BARRAS_SEMANAS = 26
        const val MAX_BARRAS_MESES = 36

        // Columnas de la tabla de socios.
        const val COL_SOCIO = MARGEN + 10f
        const val COL_DIA = 210f
        const val COL_CUOTA = 290f
        const val COL_ESTADO = 345f
        const val COL_NOTAS = 420f

        // Los colores del tema claro de la app (values/colors.xml).
        val VERDE_CABECERA = "#155E46".toColorInt()
        val VERDE_CABECERA_OSCURO = "#0A2D22".toColorInt()
        val ETIQUETA_NETO = "#9FD9C0".toColorInt()
        val ETIQUETA_CLARA = "#CDE8DC".toColorInt()
        val PASTILLA = "#33FFFFFF".toColorInt()
        val BLANCO = "#FFFFFF".toColorInt()
        val TEXTO = "#111814".toColorInt()
        val TEXTO_SUAVE = "#57625C".toColorInt()
        val DIVISOR = "#E3E7E1".toColorInt()
        val GRIS_FONDO = "#F6F7F4".toColorInt()
        val VERDE = "#0E7A55".toColorInt()
        val VERDE_SUAVE = "#9CCCB5".toColorInt()
        val VERDE_FONDO = "#E1F1E8".toColorInt()
        val VERDE_TEXTO = "#0B5A3E".toColorInt()
        val AZUL = "#1F6470".toColorInt()
        val AZUL_FONDO = "#E2EFEE".toColorInt()
        val AMBAR = "#8F5D00".toColorInt()
        val AMBAR_FONDO = "#FBF3DC".toColorInt()
        val ROJO = "#B4282C".toColorInt()
        val ROJO_FONDO = "#FBE9E8".toColorInt()
    }
}
