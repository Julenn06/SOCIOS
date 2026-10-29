package com.julen.socios

import android.content.Context
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.TypedValue
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import kotlin.math.max

/** Los colores y las pinturas de [GraficoNetoView], creados una vez con el tema y la escala de letra de [context]. */
internal class PinturasGrafico(context: Context) {

    private val metricas = context.resources.displayMetrics
    private val densidad = metricas.density

    val colorBarra = ContextCompat.getColor(context, R.color.grafico_barra)
    val colorBarraSuave = ContextCompat.getColor(context, R.color.grafico_barra_suave)
    val colorNegativo = ContextCompat.getColor(context, R.color.red_text)
    val colorEje = ContextCompat.getColor(context, R.color.divider)
    val colorTexto = ContextCompat.getColor(context, R.color.text_main)
    val colorTextoSuave = ContextCompat.getColor(context, R.color.text_sub)
    val colorFondo = ContextCompat.getColor(context, R.color.surface_card)
    val colorMedia = ContextCompat.getColor(context, R.color.green_text_soft)

    val barra = Paint(Paint.ANTI_ALIAS_FLAG)
    val eje = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorEje
        strokeWidth = max(1f, densidad)
    }
    val guia = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorEje
        strokeWidth = max(1f, densidad)
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(3 * densidad, 4 * densidad), 0f)
    }
    val media = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorMedia
        strokeWidth = 1.5f * densidad
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(6 * densidad, 4 * densidad), 0f)
    }
    val etiqueta = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = ResourcesCompat.getFont(context, R.font.geist_medium)
        textSize = sp(10.5f)
        color = colorTextoSuave
    }
    val escala = pinturaCifra(context, sp(10f)).apply {
        textAlign = Paint.Align.RIGHT
        color = colorTextoSuave
    }
    val marca = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = ResourcesCompat.getFont(context, R.font.geist_semibold)
        textSize = sp(10.5f)
        color = colorTextoSuave
    }
    val marcaFondo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorFondo }
    val rotulo = pinturaCifra(context, sp(11f)).apply { color = colorTexto }

    /** Contorno del color del fondo bajo los rótulos: así se leen aunque la línea de la media pase por detrás. */
    val rotuloContorno = Paint(rotulo).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4 * densidad
        strokeJoin = Paint.Join.ROUND
        color = colorFondo
    }
    val avisoFondo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorTexto }
    val avisoTexto = pinturaCifra(context, sp(12f)).apply { color = colorFondo }
    val difuminado = Paint()

    private fun sp(valor: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, valor, metricas)
}
