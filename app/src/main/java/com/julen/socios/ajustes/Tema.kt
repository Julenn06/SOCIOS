package com.julen.socios.ajustes

import android.content.Context
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import com.julen.socios.R

/** Tema de la app elegido por el usuario. Por defecto sigue al del sistema. */
enum class Tema(@param:StringRes val nombre: Int, val modoNoche: Int) {
    SISTEMA(R.string.tema_sistema, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
    CLARO(R.string.tema_claro, AppCompatDelegate.MODE_NIGHT_NO),
    OSCURO(R.string.tema_oscuro, AppCompatDelegate.MODE_NIGHT_YES);

    /** Cambia el tema de toda la app; las pantallas abiertas se recrean con los colores nuevos. */
    fun aplicar() = AppCompatDelegate.setDefaultNightMode(modoNoche)
}

/** Guarda el tema elegido, que AppCompat no recuerda entre arranques. */
class PreferenciasTema(context: Context) {
    private val preferencias = context.getSharedPreferences("ajustes", Context.MODE_PRIVATE)

    var tema: Tema
        get() = preferencias.getString(CLAVE, null)?.let { nombre -> Tema.entries.find { it.name == nombre } }
            ?: Tema.SISTEMA
        set(valor) = preferencias.edit { putString(CLAVE, valor.name) }

    private companion object {
        const val CLAVE = "tema"
    }
}
