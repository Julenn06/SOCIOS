package com.julen.socios

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
    val contexto = root.context
    placaFormato.backgroundTintList = ContextCompat.getColorStateList(contexto, fondo)
    ivFormato.setImageResource(icono)
    ivFormato.imageTintList = ContextCompat.getColorStateList(contexto, color)
    tvFormato.setText(titulo)
    tvFormatoDetalle.setText(detalle)
    root.setOnClickListener {
        HapticUtils.performClick(it)
        accion()
    }
    AnimationExtensions.setupPressScaleAnimation(root)
}
