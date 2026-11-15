package com.julen.socios

import android.app.Dialog
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.activity.addCallback
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.julen.socios.ajustes.Bloqueo
import com.julen.socios.databinding.PantallaBloqueoBinding

/**
 * Tapa la app entera (también los paneles que estuvieran abiertos, que quedan debajo) hasta que el usuario se
 * identifica con la huella, la cara o el PIN del móvil. Atrás no la quita: deja la app en segundo plano.
 */
class PantallaBloqueo : DialogFragment() {

    private val bloqueo: Bloqueo by lazy { (requireActivity().application as SociosApp).container.bloqueo }

    /** El cuadro de la huella sale solo una vez al aparecer; si se cancela, queda el botón. */
    private var pedidoAlAparecer = false

    private lateinit var identificacion: Identificacion

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isCancelable = false
        pedidoAlAparecer = savedInstanceState?.getBoolean(STATE_PEDIDO) ?: false
        identificacion = Identificacion(this) {
            bloqueo.desbloquear()
            dismissAllowingStateLoss()
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        ComponentDialog(requireContext(), R.style.Theme_Socios).apply {
            onBackPressedDispatcher.addCallback(this@PantallaBloqueo) { requireActivity().moveTaskToBack(true) }
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val binding = PantallaBloqueoBinding.inflate(inflater, container, false)
        binding.btnDesbloquear.setOnClickListener { pedir() }
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        val ventana = dialog?.window ?: return
        ventana.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        // Su ventana no hereda de la Activity los iconos oscuros de las barras del sistema: sobre el fondo claro
        // quedaban en blanco y casi no se veían.
        val claro = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(ventana, ventana.decorView).apply {
            isAppearanceLightStatusBars = claro
            isAppearanceLightNavigationBars = claro
        }
    }

    override fun onResume() {
        super.onResume()
        // Ya desbloqueada (por ejemplo, se quitó el bloqueo de pantalla del móvil, ver pedir()) o aún por pedir.
        if (!bloqueo.bloqueada) {
            dismissAllowingStateLoss()
        } else if (!pedidoAlAparecer) {
            pedidoAlAparecer = true
            pedir()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_PEDIDO, pedidoAlAparecer)
    }

    private fun pedir() {
        // Si el móvil ya no tiene bloqueo de pantalla (se quitó después de activar esto), no hay con qué
        // identificarse: dejarla bloqueada impediría entrar para siempre. Quien lo quitó ya sabía el PIN.
        if (Bloqueo.disponibilidad(requireContext()) != BiometricManager.BIOMETRIC_SUCCESS) {
            bloqueo.desbloquear()
            dismissAllowingStateLoss()
            return
        }
        identificacion.pedir(getString(R.string.bloqueo_prompt_titulo))
    }

    companion object {
        const val TAG = "PantallaBloqueo"
        private const val STATE_PEDIDO = "pedido"

        /** La pone encima de todo si hace falta y no está ya. */
        fun mostrarSiHaceFalta(bloqueo: Bloqueo, fragmentManager: FragmentManager) {
            if (!bloqueo.bloqueada || fragmentManager.isStateSaved) return
            if (fragmentManager.findFragmentByTag(TAG) != null) return
            PantallaBloqueo().showNow(fragmentManager, TAG)
        }
    }
}

/**
 * Pide la huella, la cara o el PIN del móvil con el cuadro del sistema y llama a [alAcertar] si es el dueño.
 * Cancelarlo o fallar no hace nada: el cuadro del sistema ya explica lo que ha pasado. Se crea en el onCreate del
 * [fragment], como pide BiometricPrompt para no perder la respuesta si la pantalla se recrea mientras tanto.
 */
class Identificacion(fragment: Fragment, alAcertar: () -> Unit) {
    private val prompt = BiometricPrompt(
        fragment,
        ContextCompat.getMainExecutor(fragment.requireContext()),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = alAcertar()
        }
    )

    fun pedir(titulo: String, subtitulo: String? = null) {
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(titulo)
            .apply { subtitulo?.let(::setSubtitle) }
            .setAllowedAuthenticators(Bloqueo.AUTENTICADORES)
            .build()
        prompt.authenticate(info)
    }
}
