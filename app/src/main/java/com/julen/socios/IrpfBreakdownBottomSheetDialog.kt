package com.julen.socios

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.julen.socios.databinding.BottomSheetIrpfBreakdownBinding
import com.julen.socios.databinding.DialogIrpfBinding
import com.julen.socios.util.CalculoComisiones
import com.julen.socios.util.DateUtils
import com.julen.socios.util.parseImporte
import com.julen.socios.util.toCurrencyString
import com.julen.socios.util.toFormattedEuros
import com.julen.socios.util.toImporteInput
import com.julen.socios.util.toPorcentajeString
import com.julen.socios.util.setTextoAccesible
import kotlinx.coroutines.launch

/** Desglose de la semana visible. Observa el ViewModel, así se actualiza solo y sobrevive a recreaciones. */
class IrpfBreakdownBottomSheetDialog : BottomSheetDialogFragment() {

    private val viewModel: MainViewModel by activityViewModels { MainViewModel.Factory }

    private var _binding: BottomSheetIrpfBreakdownBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetIrpfBreakdownBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnCerrarBreakdown.setOnClickListener { dismiss() }
        binding.btnCambiarIrpf.setOnClickListener { mostrarDialogoIrpf() }
        binding.btnVerCambiosIrpf.setOnClickListener { mostrarCambiosIrpf() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.uiState.collect(::render) }
                // Mantiene la lista de cambios cargada para mostrarla al instante.
                launch { viewModel.irpfVigencias.collect {} }
            }
        }
    }

    private fun mostrarCambiosIrpf() {
        val vigencias = viewModel.irpfVigencias.value.sortedByDescending { it.desdeSemanaKey }
        val porDefecto = CalculoComisiones.IRPF_POR_DEFECTO.toPorcentajeString()
        if (vigencias.isEmpty()) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.irpf_ver_cambios)
                .setMessage(getString(R.string.irpf_sin_cambios, porDefecto))
                .setPositiveButton(R.string.action_accept, null)
                .show()
            return
        }

        val items = vigencias.map {
            getString(
                R.string.irpf_cambio_item,
                it.desdeSemanaKey,
                DateUtils.getRangoSemanaTextoFromKey(it.desdeSemanaKey),
                it.porcentaje.toPorcentajeString()
            )
        }.toTypedArray()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.irpf_cambios_titulo)
            .setItems(items) { _, which -> confirmarEliminarCambio(vigencias[which].desdeSemanaKey) }
            .setNegativeButton(R.string.action_close, null)
            .show()
    }

    private fun confirmarEliminarCambio(desdeSemanaKey: String) {
        // Porcentaje que volverá a aplicarse: el del cambio anterior a este, o el de por defecto.
        val restantes = viewModel.irpfVigencias.value.filter { it.desdeSemanaKey != desdeSemanaKey }
        val anterior = CalculoComisiones.irpfParaSemana(desdeSemanaKey, restantes).toPorcentajeString()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.irpf_eliminar_cambio_titulo)
            .setMessage(getString(R.string.irpf_eliminar_cambio_msg, desdeSemanaKey, anterior))
            .setPositiveButton(R.string.action_delete) { _, _ -> viewModel.eliminarIrpf(desdeSemanaKey) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun render(state: MainUiState) {
        val resumen = state.resumenSemana ?: return
        binding.tvBreakdownRangoSemana.text = state.rangoTexto

        val netoFormatted = resumen.totalNeto.toFormattedEuros()
        binding.tvBreakdownNetoBig.text = netoFormatted
        binding.tvBreakdownNetoSmall.text = netoFormatted

        binding.tvBreakdownSociosCount.text = resources.getQuantityString(
            R.plurals.irpf_breakdown_count,
            resumen.totalSociosHechos,
            resumen.totalSociosHechos,
            resumen.totalSociosRegistrados
        )

        binding.tvBreakdownSumaCuotas.text = resumen.sumaColaboracionesBase.toFormattedEuros()
        binding.tvBreakdownBaseX2.text = resumen.gananciasBaseX2.toCurrencyString(prefix = "+")
        binding.tvBreakdownBonusValue.text = resumen.bonusSemanal.toCurrencyString(prefix = "+")
        val etiquetaBonus = if (resumen.esBonoRegaloAplicado) {
            getString(R.string.irpf_fila_bonus_manual, resumen.bonusAutomatico.toFormattedEuros())
        } else {
            resources.getQuantityString(
                R.plurals.irpf_fila_bonus_auto, resumen.totalSociosHechos, resumen.totalSociosHechos
            )
        }
        binding.tvBreakdownBonusLabel.setTextoAccesible(etiquetaBonus)
        val motivo = state.bonoInfoSemana?.motivo?.takeIf { resumen.esBonoRegaloAplicado }
        binding.tvBreakdownBonusNota.isVisible = motivo != null
        binding.tvBreakdownBonusNota.text = motivo?.let { getString(R.string.irpf_bonus_motivo, it) }

        binding.tvBreakdownBruto.text = resumen.totalBruto.toFormattedEuros()
        binding.tvBreakdownIrpfLabel.text =
            getString(R.string.irpf_retencion_label, resumen.porcentajeIrpf.toPorcentajeString())
        binding.tvBreakdownIrpf.text = resumen.retencionIrpf.toCurrencyString(prefix = "-")
    }

    private fun mostrarDialogoIrpf() {
        val state = viewModel.uiState.value
        val porcentajeActual = state.resumenSemana?.porcentajeIrpf ?: return
        val dialogBinding = DialogIrpfBinding.inflate(layoutInflater)
        dialogBinding.tvIrpfAyuda.text = getString(R.string.irpf_dialogo_ayuda, state.semanaKey)
        dialogBinding.etIrpf.setText(porcentajeActual.toImporteInput())

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.irpf_dialogo_titulo)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.action_save, null)
            .setNegativeButton(R.string.action_cancel, null)
            .show()

        // Se valida antes de cerrar: el botón positivo por defecto cerraría el diálogo aunque el valor fuese inválido.
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val porcentaje = parseImporte(dialogBinding.etIrpf.text)
            if (porcentaje == null || porcentaje !in 0.0..100.0) {
                dialogBinding.tilIrpf.error = getString(R.string.msg_irpf_invalido)
            } else {
                viewModel.guardarIrpf(state.semanaKey, porcentaje)
                dialog.dismiss()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "IrpfBreakdownDialog"
    }
}
