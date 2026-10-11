package com.julen.socios

import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Versión instalada y botón para buscar actualizaciones. Es un fragment para sobrevivir a los giros. */
class AcercaDeDialogFragment : DialogFragment() {

    private val viewModel: ActualizacionesViewModel by activityViewModels { ActualizacionesViewModel.Factory }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.title_app_bar)
            .setMessage(getString(R.string.acerca_de_mensaje, viewModel.versionInstalada))
            .setPositiveButton(R.string.action_buscar_actualizaciones) { _, _ -> viewModel.buscarActualizaciones() }
            .setNegativeButton(R.string.action_close, null)
            .create()

    companion object {
        const val TAG = "AcercaDeDialog"
    }
}
