package com.julen.socios.ajustes

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Lo que la app recuerda del uso: separado en una interfaz para poder sustituirlo en tests. */
interface PreferenciasUsuario {
    /** Cuota del último socio añadido, para proponerla en el siguiente. Null si aún no se ha añadido ninguno. */
    var ultimaCuota: Double?

    /** Pendientes de semanas pasadas cuyo aviso ya se ha cerrado: no vuelve a salir hasta que haya uno nuevo. */
    val pendientesOcultos: StateFlow<Set<String>>

    fun ocultarPendientes(ids: Set<String>)
}

class PreferenciasUsuarioCompartidas(context: Context) : PreferenciasUsuario {
    private val preferencias = context.getSharedPreferences("ajustes", Context.MODE_PRIVATE)

    override var ultimaCuota: Double?
        get() = preferencias.getString(CLAVE_ULTIMA_CUOTA, null)?.toDoubleOrNull()
        set(valor) = preferencias.edit { putString(CLAVE_ULTIMA_CUOTA, valor?.toString()) }

    private val _pendientesOcultos =
        MutableStateFlow(preferencias.getStringSet(CLAVE_PENDIENTES_OCULTOS, null)?.toSet().orEmpty())
    override val pendientesOcultos: StateFlow<Set<String>> = _pendientesOcultos.asStateFlow()

    // Se guarda solo lo que se acaba de cerrar: los que ya no están pendientes se olvidan solos.
    override fun ocultarPendientes(ids: Set<String>) {
        preferencias.edit { putStringSet(CLAVE_PENDIENTES_OCULTOS, ids) }
        _pendientesOcultos.value = ids
    }

    private companion object {
        const val CLAVE_ULTIMA_CUOTA = "ultimaCuota"
        const val CLAVE_PENDIENTES_OCULTOS = "pendientesOcultos"
    }
}
