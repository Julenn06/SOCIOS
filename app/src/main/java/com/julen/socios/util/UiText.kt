package com.julen.socios.util

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/**
 * Texto que el ViewModel emite sin depender de un Context: la vista lo resuelve con sus recursos.
 */
sealed interface UiText {
    data class Res(@param:StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
    data class Plural(@param:PluralsRes val id: Int, val cantidad: Int, val args: List<Any> = emptyList()) : UiText

    fun resolver(context: Context): String = when (this) {
        is Res -> context.getString(id, *args.toTypedArray())
        is Plural -> context.resources.getQuantityString(id, cantidad, *args.toTypedArray())
    }
}
