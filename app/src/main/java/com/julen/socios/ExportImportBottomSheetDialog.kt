package com.julen.socios

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.julen.socios.databinding.BottomSheetExportImportBinding
import com.julen.socios.export.AlcanceExport
import com.julen.socios.export.ExportFormat
import com.julen.socios.export.ImportFormat
import com.julen.socios.util.DateUtils
import com.julen.socios.util.setTextoAccesible
import kotlinx.coroutines.launch

class ExportImportBottomSheetDialog : BottomSheetDialogFragment() {

    private val viewModel: MainViewModel by activityViewModels { MainViewModel.Factory }

    private var _binding: BottomSheetExportImportBinding? = null
    private val binding get() = _binding!!

    private var semanasDisponibles: List<String> = emptyList()

    /** null hasta que se conocen las semanas disponibles y se elige la selección inicial. */
    private var semanasSeleccionadas: MutableSet<String>? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetExportImportBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        semanasSeleccionadas = savedInstanceState?.getStringArrayList(STATE_SEMANAS)?.toMutableSet()

        binding.chipGroupAlcance.setOnCheckedStateChangeListener { _, checkedIds ->
            val eligiendo = R.id.chipAlcanceSeleccion in checkedIds
            binding.btnElegirSemanas.isVisible = eligiendo
            if (eligiendo && semanasSeleccionadas.isNullOrEmpty()) {
                mostrarDialogoSeleccionarSemanas()
            }
        }

        binding.btnElegirSemanas.setOnClickListener { mostrarDialogoSeleccionarSemanas() }

        binding.btnExportPdf.setOnClickListener { exportar(ExportFormat.PDF) }
        binding.btnExportCsv.setOnClickListener { exportar(ExportFormat.CSV) }
        binding.btnExportJson.setOnClickListener { exportar(ExportFormat.JSON) }

        binding.btnImportJson.setOnClickListener { importar(ImportFormat.JSON) }
        binding.btnImportCsv.setOnClickListener { importar(ImportFormat.CSV) }

        binding.btnCerrar.setOnClickListener { dismiss() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.semanasDisponibles.collect { semanas ->
                    semanasDisponibles = semanas
                    if (semanasSeleccionadas == null && semanas.isNotEmpty()) {
                        // Por defecto, la semana visible si tiene datos; si no, la más reciente.
                        val activa = viewModel.uiState.value.semanaKey
                        semanasSeleccionadas = mutableSetOf(if (activa in semanas) activa else semanas.first())
                    }
                    actualizarBotonSemanas()
                }
            }
        }
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        binding.btnElegirSemanas.isVisible = binding.chipAlcanceSeleccion.isChecked
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        semanasSeleccionadas?.let { outState.putStringArrayList(STATE_SEMANAS, ArrayList(it)) }
    }

    private fun actualizarBotonSemanas() {
        val seleccion = semanasSeleccionadas.orEmpty()
        val texto = if (seleccion.isEmpty()) {
            getString(R.string.export_elegir_semanas_vacio)
        } else {
            resources.getQuantityString(
                R.plurals.export_semanas_seleccionadas, seleccion.size, seleccion.size, seleccion.first()
            )
        }
        binding.btnElegirSemanas.setTextoAccesible(texto)
    }

    private fun mostrarDialogoSeleccionarSemanas() {
        val semanas = semanasDisponibles
        if (semanas.isEmpty()) {
            Toast.makeText(requireContext(), R.string.msg_sin_semanas, Toast.LENGTH_SHORT).show()
            return
        }
        val seleccion = semanasSeleccionadas ?: mutableSetOf<String>().also { semanasSeleccionadas = it }

        val itemsText = semanas.map { key ->
            getString(R.string.export_semana_item, key, DateUtils.getRangoSemanaTextoFromKey(key))
        }.toTypedArray()
        val checkedItems = BooleanArray(semanas.size) { i -> semanas[i] in seleccion }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.export_dialogo_semanas_titulo)
            .setMultiChoiceItems(itemsText, checkedItems) { _, which, isChecked ->
                if (isChecked) seleccion.add(semanas[which]) else seleccion.remove(semanas[which])
                actualizarBotonSemanas()
            }
            .setPositiveButton(R.string.action_accept, null)
            .show()
    }

    private fun alcanceSeleccionado(): AlcanceExport {
        val seleccion = semanasSeleccionadas.orEmpty()
        return when {
            binding.chipAlcanceHistorico.isChecked -> AlcanceExport.Historico
            binding.chipAlcanceSeleccion.isChecked && seleccion.isNotEmpty() -> AlcanceExport.Semanas(seleccion.toSet())
            else -> AlcanceExport.Semanas(setOf(viewModel.uiState.value.semanaKey))
        }
    }

    private fun exportar(formato: ExportFormat) {
        viewModel.solicitarExport(formato, alcanceSeleccionado())
        dismiss()
    }

    private fun importar(formato: ImportFormat) {
        viewModel.solicitarImport(formato)
        dismiss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "ExportImportDialog"
        private const val STATE_SEMANAS = "semanasSeleccionadas"
    }
}
