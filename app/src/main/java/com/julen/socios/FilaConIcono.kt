package com.julen.socios

import android.view.View
import android.widget.ImageView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.julen.socios.databinding.ItemFormatoBinding
import com.julen.socios.util.AnimationExtensions
import com.julen.socios.util.HapticUtils

/** Rellena una fila de item_formato: placa de color con icono, título, descripción y qué hace al tocarla. */
fun ItemFormatoBinding.configurar(
    @DrawableRes icono: Int,
    tono: Tono,
    @StringRes titulo: Int,
    @StringRes detalle: Int,
    accion: () -> Unit
) {
    pintarPlaca(placaFormato, ivFormato, icono, tono)
    tvFormato.setText(titulo)
    tvFormatoDetalle.setText(detalle)
    root.setOnClickListener {
        HapticUtils.performClick(it)
        accion()
    }
    AnimationExtensions.setupPressScaleAnimation(root)
}

/** Los colores de las placas y los avisos: un fondo suave y su color intenso para el icono y el texto. */
enum class Tono(@param:ColorRes val fondo: Int, @param:ColorRes val color: Int) {
    VERDE(R.color.green_container, R.color.green_text_soft),
    AZUL(R.color.blue_container, R.color.blue_text_soft),
    ROJO(R.color.red_container, R.color.red_text),
    NARANJA(R.color.orange_container, R.color.orange_text),
    PENDIENTE(R.color.orange_container, R.color.orange_pending),
    AMBAR(R.color.amber_container, R.color.amber_text),

    /** Sin color propio: los pasos de una cuenta que solo suman lo anterior (la suma de cuotas, el bruto). */
    NEUTRO(R.color.surface_card, R.color.text_main)
}

/** Placa de color con su [icono], como la de las filas, los avisos y el resumen de importación. */
fun pintarPlaca(placa: View, imagen: ImageView, @DrawableRes icono: Int, tono: Tono) {
    val contexto = placa.context
    placa.backgroundTintList = ContextCompat.getColorStateList(contexto, tono.fondo)
    imagen.setImageResource(icono)
    imagen.imageTintList = ContextCompat.getColorStateList(contexto, tono.color)
}
