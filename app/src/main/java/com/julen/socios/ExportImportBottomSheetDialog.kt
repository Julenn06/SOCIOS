package com.julen.socios

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.julen.socios.databinding.BottomSheetExportImportBinding
import com.julen.socios.util.DateUtils

sealed class ExportScope {
    object SemanaActiva : ExportScope()
    data class SemanasEspecificas(val semanaKeys: Set<String>) : ExportScope()
    object HistoricoGlobal : ExportScope()
}

class ExportImportBottomSheetDialog(
    private val semanaKeyActiva: String,
    private val todasLasSemanasKeys: List<String>,
    private val onExportPdf: (scope: ExportScope) -> Unit,
    private val onExportCsv: (scope: ExportScope) -> Unit,
    private val onExportJson: (scope: ExportScope) -> Unit,
    private val onImportJson: () -> Unit,
    private val onImportCsv: () -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetExportImportBinding? = null
    private val binding get() = _binding!!

    private val selectedSemanasKeys = mutableSetOf<String>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetExportImportBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Inicialmente añadir la semana activa si está disponible
        if (semanaKeyActiva.isNotBlank()) {
            selectedSemanasKeys.add(semanaKeyActiva)
        } else if (todasLasSemanasKeys.isNotEmpty()) {
            selectedSemanasKeys.add(todasLasSemanasKeys.first())
        }

        updateBotonSemanasTexto()

        binding.chipGroupAlcance.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.contains(R.id.chipAlcanceSeleccion)) {
                binding.btnElegirSemanas.visibility = View.VISIBLE
                if (selectedSemanasKeys.isEmpty()) {
                    mostrarDialogoSeleccionarSemanas()
                }
            } else {
                binding.btnElegirSemanas.visibility = View.GONE
            }
        }

        binding.btnElegirSemanas.setOnClickListener {
            mostrarDialogoSeleccionarSemanas()
        }

        binding.btnExportPdf.setOnClickListener {
            onExportPdf(getCurrentExportScope())
            dismiss()
        }

        binding.btnExportCsv.setOnClickListener {
            onExportCsv(getCurrentExportScope())
            dismiss()
        }

        binding.btnExportJson.setOnClickListener {
            onExportJson(getCurrentExportScope())
            dismiss()
        }

        binding.btnImportJson.setOnClickListener {
            onImportJson()
            dismiss()
        }

        binding.btnImportCsv.setOnClickListener {
            onImportCsv()
            dismiss()
        }

        binding.btnCerrar.setOnClickListener {
            dismiss()
        }
    }

    private fun updateBotonSemanasTexto() {
        val count = selectedSemanasKeys.size
        binding.btnElegirSemanas.text = when (count) {
            0 -> {
                "📅 Pulsar para seleccionar semanas..."
            }

            1 -> {
                "📅 1 semana seleccionada (${selectedSemanasKeys.first()})"
            }

            else -> {
                "📅 $count semanas seleccionadas"
            }
        }
    }

    private fun mostrarDialogoSeleccionarSemanas() {
        if (todasLasSemanasKeys.isEmpty()) {
            Toast.makeText(
                context, "No hay semanas registradas para seleccionar", Toast.LENGTH_SHORT
            ).show()
            return
        }

        val itemsText = todasLasSemanasKeys.map { key ->
            val rango = DateUtils.getRangoSemanaTextoFromKey(key)
            "Semana $key ($rango)"
        }.toTypedArray()

        val checkedItems = BooleanArray(todasLasSemanasKeys.size) { i ->
            selectedSemanasKeys.contains(todasLasSemanasKeys[i])
        }

        AlertDialog.Builder(requireContext()).setTitle("Seleccionar semanas a exportar")
            .setMultiChoiceItems(itemsText, checkedItems) { _, which, isChecked ->
                val key = todasLasSemanasKeys[which]
                if (isChecked) {
                    selectedSemanasKeys.add(key)
                } else {
                    selectedSemanasKeys.remove(key)
                }
                updateBotonSemanasTexto()
            }.setPositiveButton("Aceptar") { _, _ ->
                updateBotonSemanasTexto()
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun getCurrentExportScope(): ExportScope {
        return when {
            binding.chipAlcanceSeleccion.isChecked -> {
                if (selectedSemanasKeys.isEmpty()) {
                    ExportScope.SemanaActiva
                } else {
                    ExportScope.SemanasEspecificas(selectedSemanasKeys)
                }
            }

            binding.chipAlcanceHistorico.isChecked -> ExportScope.HistoricoGlobal
            else -> ExportScope.SemanaActiva
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
