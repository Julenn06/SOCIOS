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
    @ColorRes fondo: Int,
    @ColorRes color: Int,
    @StringRes titulo: Int,
    @StringRes detalle: Int,
    accion: () -> Unit
) {
    pintarPlaca(placaFormato, ivFormato, icono, fondo, color)
    tvFormato.setText(titulo)
    tvFormatoDetalle.setText(detalle)
    root.setOnClickListener {
        HapticUtils.performClick(it)
        accion()
    }
    AnimationExtensions.setupPressScaleAnimation(root)
}

/** Placa de color [fondo] con el [icono] en [color], como la de las filas, los avisos y el resumen de importación. */
fun pintarPlaca(placa: View, imagen: ImageView, @DrawableRes icono: Int, @ColorRes fondo: Int, @ColorRes color: Int) {
    val contexto = placa.context
    placa.backgroundTintList = ContextCompat.getColorStateList(contexto, fondo)
    imagen.setImageResource(icono)
    imagen.imageTintList = ContextCompat.getColorStateList(contexto, color)
}
