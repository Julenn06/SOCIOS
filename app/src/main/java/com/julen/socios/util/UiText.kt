package com.julen.socios.util

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.julen.socios.R

/**
 * Texto que el ViewModel emite sin depender de un Context: la vista lo resuelve con sus recursos.
 * Los argumentos que sean a su vez un [UiText] se resuelven antes de sustituirlos.
 */
sealed interface UiText {
    data class Res(@param:StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
    data class Plural(@param:PluralsRes val id: Int, val cantidad: Int, val args: List<Any> = emptyList()) : UiText

    /** Texto ya escrito, como el mensaje de un error propio. */
    data class Texto(val texto: String) : UiText

    /** Varias partes en una enumeración: "12 socios, 2 bonus y 3 semanas con lo cobrado". */
    data class Lista(val partes: List<UiText>) : UiText

    fun resolver(context: Context): String = when (this) {
        is Res -> context.getString(id, *resolverArgs(args, context))
        is Plural -> context.resources.getQuantityString(id, cantidad, *resolverArgs(args, context))
        is Texto -> texto
        is Lista -> {
            val textos = partes.map { it.resolver(context) }
            if (textos.size < 2) {
                textos.firstOrNull().orEmpty()
            } else {
                context.getString(R.string.lista_ultimo, textos.dropLast(1).joinToString(", "), textos.last())
            }
        }
    }

    private fun resolverArgs(args: List<Any>, context: Context): Array<Any> =
        args.map { if (it is UiText) it.resolver(context) else it }.toTypedArray()
}
