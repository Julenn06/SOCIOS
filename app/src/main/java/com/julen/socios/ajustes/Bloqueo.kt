package com.julen.socios.ajustes

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.core.content.edit

/**
 * El bloqueo de la app con la huella, la cara o el PIN del móvil: si está puesto y cuándo hay que volver a pedirlo.
 *
 * Se pide al abrir la app (un proceso nuevo siempre empieza bloqueado) y al volver a ella tras pasar más de
 * [ESPERA_MS] fuera. Con menos, no: así elegir un archivo para exportar o confirmar el PIN no la vuelve a bloquear.
 */
class Bloqueo(
    private val leerActivo: () -> Boolean,
    private val guardarActivo: (Boolean) -> Unit,
    private val reloj: () -> Long = SystemClock::elapsedRealtime
) {
    private var desbloqueada = false
    private var fueraDesde: Long? = null

    var activo: Boolean
        get() = leerActivo()
        set(valor) {
            guardarActivo(valor)
            // Quien lo activa ya ha confirmado que es él: no se le pide otra vez enseguida.
            desbloqueada = true
        }

    /** Si ahora mismo hay que tapar la app y pedir la huella o el PIN. */
    val bloqueada: Boolean get() = activo && !desbloqueada

    fun desbloquear() {
        desbloqueada = true
    }

    /** La app deja de verse (no al girar la pantalla ni al cambiar de tema, que solo la recrean). */
    fun alSalir() {
        fueraDesde = reloj()
    }

    /** La app vuelve a verse: si ha estado fuera demasiado, se bloquea. */
    fun alVolver() {
        val desde = fueraDesde ?: return
        fueraDesde = null
        if (reloj() - desde >= ESPERA_MS) desbloqueada = false
    }

    companion object {
        const val ESPERA_MS = 60_000L

        /** Huella o cara (de cualquier nivel) o, si no, el PIN, patrón o contraseña del móvil. */
        const val AUTENTICADORES = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

        private const val CLAVE = "bloqueo"

        fun conPreferencias(context: Context): Bloqueo {
            val preferencias = context.getSharedPreferences("ajustes", Context.MODE_PRIVATE)
            return Bloqueo(
                leerActivo = { preferencias.getBoolean(CLAVE, false) },
                guardarActivo = { valor -> preferencias.edit { putBoolean(CLAVE, valor) } }
            )
        }

        /** Si el móvil puede pedir la huella o el PIN; si no, [BiometricManager.canAuthenticate] dice por qué. */
        fun disponibilidad(context: Context): Int = BiometricManager.from(context).canAuthenticate(AUTENTICADORES)
    }
}

/**
 * Con el bloqueo puesto, la miniatura de la app en "Recientes" sale en blanco: si no, enseñaría las cuentas sin
 * pedir nada. Solo desde Android 13; antes, la única forma era impedir también las capturas de pantalla.
 */
fun Activity.ocultarEnRecientesSiHaceFalta(bloqueo: Bloqueo) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(!bloqueo.activo)
}
