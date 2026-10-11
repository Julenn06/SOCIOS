package com.julen.socios.export

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.graphics.toColorInt
import com.julen.socios.R
import com.julen.socios.model.DatosBackup
import com.julen.socios.model.Socio
import com.julen.socios.util.CalculoComisiones
import com.julen.socios.util.DateUtils
import com.julen.socios.util.toEurosCompacto
import com.julen.socios.util.toFormattedEuros
import com.julen.socios.util.toPorcentajeString
import java.io.OutputStream
import java.time.Clock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Informe PDF A4 con un resumen económico y la tabla de socios del alcance exportado. */
class PdfReportGenerator(private val context: Context) {

    private data class Kpi(val label: String, val value: String, val colorHex: String)

    private data class Resumen(val kpis: List<Kpi>, val detalle: String)

    private data class Columna(val x: Float, val ancho: Float)

    fun generar(datos: DatosBackup, alcance: AlcanceExport, outputStream: OutputStream, clock: Clock) {
        val pdfDocument = PdfDocument()
        try {
            dibujar(pdfDocument, datos, alcance, clock)
            pdfDocument.writeTo(outputStream)
        } finally {
            pdfDocument.close()
        }
    }

    private fun dibujar(pdfDocument: PdfDocument, datos: DatosBackup, alcance: AlcanceExport, clock: Clock) {
        var pageNum = 1
        var page = pdfDocument.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH.toInt(), PAGE_HEIGHT.toInt(), pageNum).create())
        var canvas: Canvas = page.canvas

        val paint = TextPaint().apply { isAntiAlias = true }
        val fechaReporte = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.ROOT).format(LocalDateTime.now(clock))

        // --- ENCABEZADO PRINCIPAL ---
        val headerPaint = Paint().apply { color = "#0D47A1".toColorInt(); style = Paint.Style.FILL }
        canvas.drawRect(0f, 0f, PAGE_WIDTH.toFloat(), 85f, headerPaint)

        paint.color = Color.WHITE
        paint.textSize = 20f
        paint.isFakeBoldText = true
        canvas.drawText(context.getString(R.string.pdf_titulo), MARGEN + 2f, 40f, paint)

        paint.textSize = 12f
        paint.isFakeBoldText = false
        paint.color = "#E3F2FD".toColorInt()
        canvas.drawText(
            context.getString(R.string.pdf_subtitulo, titulo(alcance), fechaReporte), MARGEN + 2f, 65f, paint
        )

        // --- TARJETAS KPI ---
        var y = 105f
        val resumen = resumen(datos, alcance)
        val kpiBgPaint = Paint().apply { color = "#F4F6F9".toColorInt(); style = Paint.Style.FILL }
        val kpiBorderPaint = Paint().apply {
            color = "#E0E0E0".toColorInt(); style = Paint.Style.STROKE; strokeWidth = 1f
        }
        val cardWidth = 125f
        val cardHeight = 55f
        val gap = 11f

        resumen.kpis.forEachIndexed { idx, kpi ->
            val left = MARGEN + idx * (cardWidth + gap)
            val rect = RectF(left, y, left + cardWidth, y + cardHeight)
            canvas.drawRoundRect(rect, 8f, 8f, kpiBgPaint)
            canvas.drawRoundRect(rect, 8f, 8f, kpiBorderPaint)

            paint.color = "#616161".toColorInt()
            paint.textSize = 10f
            paint.isFakeBoldText = false
            canvas.drawText(ajustar(kpi.label, paint, cardWidth - 20f), left + 10f, y + 20f, paint)

            paint.color = kpi.colorHex.toColorInt()
            paint.textSize = 13f
            paint.isFakeBoldText = true
            canvas.drawText(ajustar(kpi.value, paint, cardWidth - 20f), left + 10f, y + 42f, paint)
        }
        y += cardHeight + 18f

        paint.color = "#475569".toColorInt()
        paint.textSize = 10f
        paint.isFakeBoldText = false
        canvas.drawText(resumen.detalle, MARGEN + 2f, y, paint)
        y += 14f

        // --- TABLA ---
        y = dibujarCabeceraTabla(canvas, y, paint)

        val rowBgEven = Paint().apply { color = "#FFFFFF".toColorInt(); style = Paint.Style.FILL }
        val rowBgOdd = Paint().apply { color = "#F8FAFC".toColorInt(); style = Paint.Style.FILL }
        val rowBorder = Paint().apply {
            color = "#E2E8F0".toColorInt(); style = Paint.Style.STROKE; strokeWidth = 0.8f
        }
        val rowHeight = 22f

        val socios = datos.socios.sortedWith(
            compareByDescending<Socio> { it.semanaKey }
                .thenBy { it.dia.id }
                .thenBy { it.timestamp }
        )

        socios.forEachIndexed { index, socio ->
            if (y > PAGE_HEIGHT - 60f) {
                dibujarPie(canvas, pageNum)
                pdfDocument.finishPage(page)

                pageNum++
                page = pdfDocument.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH.toInt(), PAGE_HEIGHT.toInt(), pageNum).create())
                canvas = page.canvas
                y = dibujarCabeceraTabla(canvas, 40f, paint)
            }

            val bgPaint = if (index % 2 == 0) rowBgEven else rowBgOdd
            canvas.drawRect(MARGEN, y, PAGE_WIDTH - MARGEN, y + rowHeight, bgPaint)
            canvas.drawRect(MARGEN, y, PAGE_WIDTH - MARGEN, y + rowHeight, rowBorder)

            val baseline = y + 15f
            paint.textSize = 10f
            paint.isFakeBoldText = false
            paint.color = "#1E293B".toColorInt()

            val nombre = socio.nombreSocio.ifBlank {
                context.getString(R.string.socio_default_name, socio.colaboracion.toEurosCompacto())
            }
            canvas.drawCelda(nombre, COL_NOMBRE, baseline, paint)

            paint.isFakeBoldText = true
            canvas.drawCelda(socio.colaboracion.toEurosCompacto(), COL_CUOTA, baseline, paint)
            paint.isFakeBoldText = false

            canvas.drawCelda(socio.dia.nombreCorto, COL_DIA, baseline, paint)

            if (socio.hecho) {
                paint.color = "#2E7D32".toColorInt()
                canvas.drawCelda(context.getString(R.string.pdf_estado_hecho), COL_ESTADO, baseline, paint)
            } else {
                paint.color = "#C62828".toColorInt()
                canvas.drawCelda(context.getString(R.string.pdf_estado_pendiente), COL_ESTADO, baseline, paint)
            }

            paint.color = "#64748B".toColorInt()
            canvas.drawCelda(socio.semanaKey, COL_SEMANA, baseline, paint)
            canvas.drawCelda(socio.notas.replace('\n', ' '), COL_NOTAS, baseline, paint)

            y += rowHeight
        }

        dibujarPie(canvas, pageNum)
        pdfDocument.finishPage(page)
    }

    private fun titulo(alcance: AlcanceExport): String = when (alcance) {
        AlcanceExport.Historico -> context.getString(R.string.export_titulo_historico)
        is AlcanceExport.Semanas -> alcance.semanaKeys.singleOrNull()
            ?.let(DateUtils::getRangoSemanaTextoFromKey)
            ?: context.resources.getQuantityString(
                R.plurals.export_titulo_semanas, alcance.semanaKeys.size, alcance.semanaKeys.size
            )
    }

    /**
     * Una sola semana muestra su resumen semanal. Varias semanas se calculan cada una por separado
     * (tramo de bonus, bono manual e IRPF propios) y se suman, igual que el histórico de la app.
     */
    private fun resumen(datos: DatosBackup, alcance: AlcanceExport): Resumen {
        val bonos = datos.bonosRegalo.filter { it.activo }.associateBy { it.semanaKey }
        val semanas = (datos.socios.map { it.semanaKey } + bonos.keys).toSet()
            .ifEmpty { (alcance as? AlcanceExport.Semanas)?.semanaKeys.orEmpty() }

        if (semanas.size <= 1) {
            val semanaKey = semanas.firstOrNull()
            val porcentaje = semanaKey?.let { CalculoComisiones.irpfParaSemana(it, datos.irpfVigencias) }
                ?: CalculoComisiones.IRPF_POR_DEFECTO
            val bono = semanaKey?.let(bonos::get)
            val r = CalculoComisiones.calcularResumenSemana(datos.socios, bono, porcentaje)
            val bonusLabel = if (r.esBonoRegaloAplicado) R.string.pdf_kpi_bonus_manual else R.string.pdf_kpi_bonus_semanal
            val detalle = context.getString(
                R.string.pdf_detalle_semana,
                r.totalBruto.toFormattedEuros(),
                r.porcentajeIrpf.toPorcentajeString(),
                r.retencionIrpf.toFormattedEuros()
            )
            val motivo = bono?.motivo?.let { context.getString(R.string.pdf_detalle_motivo, it) }.orEmpty()
            return Resumen(
                kpis = kpis(r.totalSociosHechos, r.totalSociosRegistrados, r.gananciasBaseX2, bonusLabel, r.bonusSemanal, r.totalNeto),
                detalle = detalle + motivo
            )
        }

        val g = CalculoComisiones.calcularResumenHistoricoGlobal(datos.socios, bonos, datos.irpfVigencias)
        return Resumen(
            kpis = kpis(
                g.totalSociosHechosAcumulado, g.totalSociosRegistradosAcumulado, g.totalBaseX2Acumulado,
                R.string.pdf_kpi_bonus_total, g.totalBonusAcumulado, g.totalNetoAcumulado
            ),
            detalle = context.resources.getQuantityString(
                R.plurals.pdf_detalle_varias_semanas,
                g.semanasHistoricas.size,
                g.totalBrutoAcumulado.toFormattedEuros(),
                g.totalIrpfRetenidoAcumulado.toFormattedEuros(),
                g.semanasHistoricas.size
            )
        )
    }

    private fun kpis(hechos: Int, registrados: Int, base: Double, bonusLabel: Int, bonus: Double, neto: Double) = listOf(
        Kpi(context.getString(R.string.pdf_kpi_socios_hechos), "$hechos / $registrados", "#1565C0"),
        Kpi(context.getString(R.string.pdf_kpi_base), base.toFormattedEuros(), "#2E7D32"),
        Kpi(context.getString(bonusLabel), bonus.toFormattedEuros(), "#F57F17"),
        Kpi(context.getString(R.string.pdf_kpi_neto), neto.toFormattedEuros(), "#0D47A1")
    )

    private fun dibujarCabeceraTabla(canvas: Canvas, y: Float, paint: TextPaint): Float {
        val tableHeaderPaint = Paint().apply { color = "#1565C0".toColorInt(); style = Paint.Style.FILL }
        canvas.drawRoundRect(RectF(MARGEN, y, PAGE_WIDTH - MARGEN, y + 26f), 4f, 4f, tableHeaderPaint)

        paint.color = Color.WHITE
        paint.textSize = 11f
        paint.isFakeBoldText = true
        val baseline = y + 17f
        canvas.drawCelda(context.getString(R.string.pdf_col_nombre), COL_NOMBRE, baseline, paint)
        canvas.drawCelda(context.getString(R.string.pdf_col_cuota), COL_CUOTA, baseline, paint)
        canvas.drawCelda(context.getString(R.string.pdf_col_dia), COL_DIA, baseline, paint)
        canvas.drawCelda(context.getString(R.string.pdf_col_estado), COL_ESTADO, baseline, paint)
        canvas.drawCelda(context.getString(R.string.pdf_col_semana), COL_SEMANA, baseline, paint)
        canvas.drawCelda(context.getString(R.string.pdf_col_notas), COL_NOTAS, baseline, paint)
        return y + 30f
    }

    private fun dibujarPie(canvas: Canvas, pageNum: Int) {
        val paint = TextPaint().apply {
            color = "#94A3B8".toColorInt()
            textSize = 9f
            isAntiAlias = true
        }
        canvas.drawLine(MARGEN, PAGE_HEIGHT - 35f, PAGE_WIDTH - MARGEN, PAGE_HEIGHT - 35f, paint)
        canvas.drawText(context.getString(R.string.pdf_pie), MARGEN, PAGE_HEIGHT - 20f, paint)
        canvas.drawText(context.getString(R.string.pdf_pagina, pageNum), PAGE_WIDTH - 70f, PAGE_HEIGHT - 20f, paint)
    }

    private fun Canvas.drawCelda(texto: String, columna: Columna, baseline: Float, paint: TextPaint) {
        drawText(ajustar(texto, paint, columna.ancho), columna.x, baseline, paint)
    }

    /** Recorta el texto con "…" según el ancho real que ocupa, no por número de caracteres. */
    private fun ajustar(texto: String, paint: TextPaint, ancho: Float): String =
        TextUtils.ellipsize(texto, paint, ancho, TextUtils.TruncateAt.END).toString()

    private companion object {
        const val PAGE_WIDTH = 595f // A4 en puntos
        const val PAGE_HEIGHT = 842f
        const val MARGEN = 30f

        val COL_NOMBRE = Columna(40f, 172f)
        val COL_CUOTA = Columna(220f, 58f)
        val COL_DIA = Columna(285f, 58f)
        val COL_ESTADO = Columna(350f, 64f)
        val COL_SEMANA = Columna(420f, 54f)
        val COL_NOTAS = Columna(480f, 80f)
    }
}
