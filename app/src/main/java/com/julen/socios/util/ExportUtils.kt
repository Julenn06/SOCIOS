package com.julen.socios.util

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import androidx.core.graphics.toColorInt
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.julen.socios.model.BackupData
import com.julen.socios.model.BackupImportResult
import com.julen.socios.model.BonoRegaloInfo
import com.julen.socios.model.DiaSemana
import com.julen.socios.model.Socio
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

object ExportUtils {

    private const val PAGE_WIDTH = 595 // A4 width in points
    private const val PAGE_HEIGHT = 842 // A4 height in points

    // ==========================================
    // EXPORT & IMPORT JSON (INCLUYE SOCIOS Y BONOS)
    // ==========================================

    fun exportToJson(
        socios: List<Socio>,
        bonosRegalo: List<BonoRegaloInfo>,
        outputStream: OutputStream
    ) {
        val backupData = BackupData(
            version = 1,
            timestamp = System.currentTimeMillis(),
            socios = socios,
            bonosRegalo = bonosRegalo
        )
        val gson = GsonBuilder().setPrettyPrinting().create()
        val jsonString = gson.toJson(backupData)
        outputStream.write(jsonString.toByteArray(Charsets.UTF_8))
    }

    fun exportToJsonFile(
        context: Context,
        socios: List<Socio>,
        bonosRegalo: List<BonoRegaloInfo>
    ): File? {
        return try {
            val fileName = "socios_export_${
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            }.json"
            val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), fileName)
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { out ->
                exportToJson(socios, bonosRegalo, out)
            }
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun importFromJson(inputStream: InputStream): BackupImportResult {
        val jsonString = inputStream.bufferedReader().use { it.readText() }
        val gson = GsonBuilder().create()

        // 1. Intentar cargar como BackupData completo (socios + bonos)
        try {
            val backupData = gson.fromJson(jsonString, BackupData::class.java)
            if (backupData != null && (backupData.socios.isNotEmpty() || backupData.bonosRegalo.isNotEmpty())) {
                return BackupImportResult(
                    socios = backupData.socios,
                    bonosRegalo = backupData.bonosRegalo
                )
            }
        } catch (_: Exception) {
            // Ignorar y probar formato antiguo de lista
        }

        // 2. Fallback: Deserializar como List<Socio> de versiones anteriores
        try {
            val listType = object : TypeToken<List<Socio>>() {}.type
            val sociosList: List<Socio>? = gson.fromJson(jsonString, listType)
            if (sociosList != null) {
                return BackupImportResult(socios = sociosList, bonosRegalo = emptyList())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return BackupImportResult()
    }

    // ==========================================
    // EXPORT & IMPORT CSV (INCLUYE SOCIOS Y BONOS)
    // ==========================================

    fun exportToCsv(
        socios: List<Socio>,
        bonosRegalo: List<BonoRegaloInfo>,
        outputStream: OutputStream
    ) {
        outputStream.bufferedWriter(Charsets.UTF_8).use { out ->
            // Sección 1: Socios
            out.write("# SOCIOS\n")
            out.write("ID,Colaboracion,Hecho,DiaSemanaId,SemanaKey,NombreSocio,Notas,Timestamp\n")
            for ((id, colaboracion, hecho, diaSemanaId, semanaKey, nombreSocio, notas, timestamp) in socios) {
                val nombreEscaped = escapeCsvField(nombreSocio)
                val notasEscaped = escapeCsvField(notas)
                out.write("$id,$colaboracion,$hecho,$diaSemanaId,$semanaKey,$nombreEscaped,$notasEscaped,$timestamp\n")
            }

            // Sección 2: Bonos Regalo / Ajustes Manuales
            if (bonosRegalo.isNotEmpty()) {
                out.write("# BONOS_REGALO\n")
                out.write("SemanaKey,Activo,EsMontoFijo,Monto,Nota\n")
                for ((semanaKey, activo, esMontoFijo, monto, nota) in bonosRegalo) {
                    val notaEscaped = escapeCsvField(nota)
                    out.write("$semanaKey,$activo,$esMontoFijo,$monto,$notaEscaped\n")
                }
            }
        }
    }

    fun exportToCsvFile(
        context: Context,
        socios: List<Socio>,
        bonosRegalo: List<BonoRegaloInfo>
    ): File? {
        return try {
            val fileName = "socios_export_${
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            }.csv"
            val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), fileName)
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { out ->
                exportToCsv(socios, bonosRegalo, out)
            }
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun importFromCsv(inputStream: InputStream): BackupImportResult {
        val socios = mutableListOf<Socio>()
        val bonosRegalo = mutableListOf<BonoRegaloInfo>()

        val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
        val lines = reader.readLines()

        if (lines.isEmpty()) return BackupImportResult()

        var currentSection = "SOCIOS"

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isBlank()) continue

            if (trimmed.startsWith("# SOCIOS", ignoreCase = true)) {
                currentSection = "SOCIOS"
                continue
            }
            if (trimmed.startsWith("# BONOS_REGALO", ignoreCase = true)) {
                currentSection = "BONOS_REGALO"
                continue
            }

            // Saltar cabeceras
            if (trimmed.startsWith("ID,Colaboracion", ignoreCase = true) ||
                trimmed.startsWith("SemanaKey,Activo", ignoreCase = true)
            ) {
                continue
            }

            val tokens = parseCsvLine(trimmed)

            if (currentSection == "SOCIOS" && tokens.size >= 5) {
                try {
                    val id = if (tokens.getOrNull(0).isNullOrBlank()) UUID.randomUUID().toString() else tokens[0]
                    val colaboracion = tokens.getOrNull(1)?.toDoubleOrNull() ?: 0.0
                    val hecho = tokens.getOrNull(2)?.toBooleanStrictOrNull() ?: (tokens.getOrNull(2) == "1")
                    val diaSemanaId = tokens.getOrNull(3)?.toIntOrNull() ?: 1
                    val semanaKey = tokens.getOrNull(4) ?: DateUtils.getSemanaKey(0)
                    val nombreSocio = tokens.getOrNull(5) ?: ""
                    val notas = tokens.getOrNull(6) ?: ""
                    val timestamp = tokens.getOrNull(7)?.toLongOrNull() ?: System.currentTimeMillis()

                    socios.add(
                        Socio(
                            id = id,
                            colaboracion = colaboracion,
                            hecho = hecho,
                            diaSemanaId = diaSemanaId,
                            semanaKey = semanaKey,
                            nombreSocio = nombreSocio,
                            notas = notas,
                            timestamp = timestamp
                        )
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } else if (currentSection == "BONOS_REGALO" && tokens.size >= 4) {
                try {
                    val semanaKey = tokens[0]
                    val activo = tokens.getOrNull(1)?.toBooleanStrictOrNull() ?: true
                    val esMontoFijo = tokens.getOrNull(2)?.toBooleanStrictOrNull() ?: true
                    val monto = tokens.getOrNull(3)?.toDoubleOrNull() ?: 0.0
                    val nota = tokens.getOrNull(4) ?: "Ajustado a mano"

                    bonosRegalo.add(
                        BonoRegaloInfo(
                            semanaKey = semanaKey,
                            activo = activo,
                            esMontoFijo = esMontoFijo,
                            monto = monto,
                            nota = nota
                        )
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        return BackupImportResult(socios = socios, bonosRegalo = bonosRegalo)
    }

    private fun escapeCsvField(field: String): String {
        if (field.contains(",") || field.contains("\"") || field.contains("\n")) {
            val escaped = field.replace("\"", "\"\"")
            return "\"$escaped\""
        }
        return field
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false

        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '"') {
                if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                    sb.append('"')
                    i++
                } else {
                    inQuotes = !inQuotes
                }
            } else if (c == ',' && !inQuotes) {
                result.add(sb.toString().trim())
                sb.clear()
            } else {
                sb.append(c)
            }
            i++
        }
        result.add(sb.toString().trim())
        return result
    }

    // ==========================================
    // EXPORT PDF WITH PROFESSIONAL DESIGN
    // ==========================================

    fun exportToPdf(
        socios: List<Socio>,
        titulo: String,
        outputStream: OutputStream,
        bonoRegalo: BonoRegaloInfo? = null
    ) {
        val pdfDocument = PdfDocument()

        var pageNum = 1
        var pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNum).create()
        var page = pdfDocument.startPage(pageInfo)
        var canvas: Canvas = page.canvas

        val paint = Paint()
        val resumen = CalculoComisiones.calcularResumenSemana(socios, bonoRegalo)
        val fechaReporte = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())

        // --- DIBUJAR ENCABEZADO PRINCIPAL ---
        val headerPaint = Paint().apply {
            color = "#0D47A1".toColorInt() // Dark Blue
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, 0f, PAGE_WIDTH.toFloat(), 85f, headerPaint)

        // Texto Encabezado
        paint.color = Color.WHITE
        paint.textSize = 20f
        paint.isFakeBoldText = true
        canvas.drawText("GESTIÓN DE SOCIOS - REPORTE", 30f, 40f, paint)

        paint.textSize = 12f
        paint.isFakeBoldText = false
        paint.color = "#E3F2FD".toColorInt()
        canvas.drawText("Periodo: $titulo  •  Generado: $fechaReporte", 30f, 65f, paint)

        // --- TARJETAS KPI RESUMEN ---
        var y = 105f

        val kpiBgPaint = Paint().apply {
            color = "#F4F6F9".toColorInt()
            style = Paint.Style.FILL
        }
        val kpiBorderPaint = Paint().apply {
            color = "#E0E0E0".toColorInt()
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }

        val cardWidth = 125f
        val cardHeight = 55f
        val startX = 30f
        val gap = 11f

        data class KpiData(val label: String, val value: String, val colorHex: String)

        val bonusLabel = if (resumen.esBonoRegaloAplicado) "Bonus (Manual)" else "Bonus Semanal"

        val kpis = listOf(
            KpiData(
                "Socios Hechos",
                "${resumen.totalSociosHechos} / ${resumen.totalSociosRegistrados}",
                "#1565C0"
            ), KpiData(
                "Base (Cuotas x2)",
                String.format(Locale.getDefault(), "%.2f €", resumen.gananciasBaseX2),
                "#2E7D32"
            ), KpiData(
                bonusLabel,
                String.format(Locale.getDefault(), "%.2f €", resumen.bonusSemanal),
                "#F57F17"
            ), KpiData(
                "Ganancia Neta",
                String.format(Locale.getDefault(), "%.2f €", resumen.totalNeto),
                "#0D47A1"
            )
        )

        for (idx in kpis.indices) {
            val left = startX + idx * (cardWidth + gap)
            val rect = RectF(left, y, left + cardWidth, y + cardHeight)
            canvas.drawRoundRect(rect, 8f, 8f, kpiBgPaint)
            canvas.drawRoundRect(rect, 8f, 8f, kpiBorderPaint)

            paint.color = "#616161".toColorInt()
            paint.textSize = 10f
            paint.isFakeBoldText = false
            canvas.drawText(kpis[idx].label, left + 10f, y + 20f, paint)

            paint.color = kpis[idx].colorHex.toColorInt()
            paint.textSize = 13f
            paint.isFakeBoldText = true
            canvas.drawText(kpis[idx].value, left + 10f, y + 42f, paint)
        }

        y += cardHeight + 25f

        // --- FUNCIÓN CABECERA DE TABLA ---
        fun drawTableHeader(c: Canvas, currentY: Float): Float {
            val tableHeaderPaint = Paint().apply {
                color = "#1565C0".toColorInt()
                style = Paint.Style.FILL
            }
            c.drawRoundRect(
                RectF(30f, currentY, PAGE_WIDTH.toFloat() - 30f, currentY + 26f),
                4f,
                4f,
                tableHeaderPaint
            )

            paint.color = Color.WHITE
            paint.textSize = 11f
            paint.isFakeBoldText = true

            c.drawText("Nombre / Socio", 40f, currentY + 17f, paint)
            c.drawText("Cuota", 220f, currentY + 17f, paint)
            c.drawText("Día", 285f, currentY + 17f, paint)
            c.drawText("Estado", 350f, currentY + 17f, paint)
            c.drawText("Semana", 420f, currentY + 17f, paint)
            c.drawText("Notas", 480f, currentY + 17f, paint)

            return currentY + 30f
        }

        y = drawTableHeader(canvas, y)

        // --- FILAS DE LA TABLA ---
        val rowBgEven = Paint().apply { color = "#FFFFFF".toColorInt(); style = Paint.Style.FILL }
        val rowBgOdd = Paint().apply { color = "#F8FAFC".toColorInt(); style = Paint.Style.FILL }
        val rowBorder = Paint().apply {
            color = "#E2E8F0".toColorInt(); style = Paint.Style.STROKE; strokeWidth = 0.8f
        }

        val rowHeight = 22f

        for (index in socios.indices) {
            if (y > PAGE_HEIGHT.toFloat() - 60f) {
                drawFooter(canvas, pageNum)
                pdfDocument.finishPage(page)

                pageNum++
                pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNum).create()
                page = pdfDocument.startPage(pageInfo)
                canvas = page.canvas

                y = 40f
                y = drawTableHeader(canvas, y)
            }

            val socio = socios[index]
            val bgPaint = if (index % 2 == 0) rowBgEven else rowBgOdd

            canvas.drawRect(30f, y, PAGE_WIDTH.toFloat() - 30f, y + rowHeight, bgPaint)
            canvas.drawRect(30f, y, PAGE_WIDTH.toFloat() - 30f, y + rowHeight, rowBorder)

            paint.textSize = 10f
            paint.isFakeBoldText = false
            paint.color = "#1E293B".toColorInt()

            val nombreText = socio.nombreSocio.ifBlank { "Socio ${socio.colaboracion.toInt()}€" }
            val nombreCorto =
                if (nombreText.length > 25) nombreText.substring(0, 23) + "..." else nombreText
            canvas.drawText(nombreCorto, 40f, y + 15f, paint)

            paint.isFakeBoldText = true
            canvas.drawText(
                String.format(Locale.getDefault(), "%.0f €", socio.colaboracion),
                220f,
                y + 15f,
                paint
            )
            paint.isFakeBoldText = false

            val diaNombre = DiaSemana.fromId(socio.diaSemanaId).nombreCorto
            canvas.drawText(diaNombre, 285f, y + 15f, paint)

            if (socio.hecho) {
                paint.color = "#2E7D32".toColorInt()
                canvas.drawText("✓ Hecho", 350f, y + 15f, paint)
            } else {
                paint.color = "#C62828".toColorInt()
                canvas.drawText("⏳ Pendiente", 350f, y + 15f, paint)
            }

            paint.color = "#64748B".toColorInt()
            canvas.drawText(socio.semanaKey, 420f, y + 15f, paint)

            val notasText =
                if (socio.notas.length > 15) socio.notas.substring(0, 13) + "..." else socio.notas
            canvas.drawText(notasText, 480f, y + 15f, paint)

            y += rowHeight
        }

        drawFooter(canvas, pageNum)
        pdfDocument.finishPage(page)

        pdfDocument.writeTo(outputStream)
        pdfDocument.close()
    }

    private fun drawFooter(canvas: Canvas, pageNum: Int) {
        val paint = Paint().apply {
            color = "#94A3B8".toColorInt()
            textSize = 9f
            isAntiAlias = true
        }
        canvas.drawLine(
            30f,
            PAGE_HEIGHT.toFloat() - 35f,
            PAGE_WIDTH.toFloat() - 30f,
            PAGE_HEIGHT.toFloat() - 35f,
            paint
        )
        canvas.drawText(
            "Gestión de Socios • Documento Oficial", 30f, PAGE_HEIGHT.toFloat() - 20f, paint
        )
        canvas.drawText(
            "Página $pageNum", PAGE_WIDTH.toFloat() - 70f, PAGE_HEIGHT.toFloat() - 20f, paint
        )
    }

    fun exportToPdfFile(
        context: Context, socios: List<Socio>, titulo: String, bonoRegalo: BonoRegaloInfo? = null
    ): File? {
        return try {
            val fileName = "socios_report_${
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            }.pdf"
            val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), fileName)
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { out ->
                exportToPdf(socios, titulo, out, bonoRegalo)
            }
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // ==========================================
    // COMPARTIR ARCHIVOS CON OTRAS APPS (INTENT)
    // ==========================================

    fun shareFile(context: Context, file: File, mimeType: String, title: String) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, title))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
