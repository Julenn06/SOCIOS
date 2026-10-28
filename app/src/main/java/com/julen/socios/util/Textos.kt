package com.julen.socios.util

import android.content.Context
import androidx.annotation.StringRes
import com.julen.socios.R
import com.julen.socios.model.Socio

/** El nombre del socio, o "Socio 20 €" si no tiene. */
fun Socio.nombreVisible(): UiText =
    nombreSocio.takeIf { it.isNotBlank() }?.let(UiText::Texto)
        ?: UiText.Res(R.string.socio_default_name, listOf(colaboracion.toEurosCompacto()))

/** Rango corto de la semana con su año: "12–16 oct 2026". */
fun Context.rangoSemanaConAnio(semanaKey: String): String =
    getString(R.string.rango_semana_anio, DateUtils.rangoCorto(semanaKey), semanaKey.take(4))

/** "Hace 2 semanas", "Dentro de 1 semana" o, a [distancia] 0, el texto [actual]. */
fun Context.semanaRelativa(distancia: Int, @StringRes actual: Int): String = when {
    distancia == 0 -> getString(actual)
    distancia < 0 -> resources.getQuantityString(R.plurals.semana_pasada, -distancia, -distancia)
    else -> resources.getQuantityString(R.plurals.semana_futura, distancia, distancia)
}

/** Cómo se llama la diferencia entre lo cobrado y lo calculado: descontado, cobrado de más o justo. */
@StringRes
fun etiquetaDescontado(descontado: Double): Int = when {
    descontado > 0.0 -> R.string.header_cobro_descontado
    descontado < 0.0 -> R.string.header_cobro_de_mas
    else -> R.string.header_cobro_coincide
}
