package com.julen.socios

import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.julen.socios.ajustes.Tema

/** Elegir entre el tema del sistema, claro u oscuro. Se aplica al momento y se recuerda. */
class TemaDialogFragment : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val preferencias = (requireActivity().application as SociosApp).container.preferenciasTema
        val temas = Tema.entries
        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.tema_titulo)
            .setSingleChoiceItems(
                temas.map { getString(it.nombre) }.toTypedArray(), temas.indexOf(preferencias.tema)
            ) { dialogo, posicion ->
                val elegido = temas[posicion]
                dialogo.dismiss()
                if (elegido != preferencias.tema) {
                    preferencias.tema = elegido
                    elegido.aplicar()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .create()
    }

    companion object {
        const val TAG = "TemaDialog"
    }
}
