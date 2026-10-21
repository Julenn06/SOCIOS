package com.julen.socios

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.julen.socios.databinding.BottomSheetDevolucionesBinding
import com.julen.socios.model.DevolucionInfo
import com.julen.socios.util.AnimationExtensions
import com.julen.socios.util.CalculoComisiones
import com.julen.socios.util.DateUtils
import com.julen.socios.util.HapticUtils
import com.julen.socios.util.parseImporte
import com.julen.socios.util.toCurrencyString
import com.julen.socios.util.toFormattedEuros
import com.julen.socios.util.toImporteInput
import kotlinx.coroutines.launch

/**
 * Devoluciones de la semana visible: lo que la empresa descuenta del pago por cuotas devueltas. Mientras se
 * escribe el importe, enseña cómo queda el neto (la devolución se resta antes del IRPF).
 */
class DevolucionesBottomSheetDialog : BottomSheetDialogFragment() {

    private val viewModel: MainViewModel by activityViewModels { MainViewModel.Factory }

    private var _binding: BottomSheetDevolucionesBinding? = null
    private val binding get() = _binding!!

    private val semanaKey: String by lazy { requireNotNull(requireArguments().getString(ARG_SEMANA)) }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        (super.onCreateDialog(savedInstanceState) as BottomSheetDialog).apply {
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetDevolucionesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = requireArguments()
        val montoActual = args.getDouble(ARG_MONTO)

        binding.tvSubtituloDevoluciones.text =
            getString(R.string.rango_semana_anio, DateUtils.rangoCorto(semanaKey), semanaKey.take(4))
        binding.btnQuitarDevoluciones.isVisible = montoActual > 0.0

        if (savedInstanceState == null) {
            if (montoActual > 0.0) binding.etMontoDevolucion.setText(montoActual.toImporteInput())
            binding.etNotaDevolucion.setText(args.getString(ARG_NOTA).orEmpty())
            // Sin importe todavía: directamente a escribirlo.
            if (montoActual <= 0.0) mostrarTeclado()
        }

        binding.etMontoDevolucion.doOnTextChanged { _, _, _, _ ->
            binding.tilMontoDevolucion.error = null
            actualizarVistaPrevia()
        }
        binding.btnCerrarDevoluciones.setOnClickListener { dismiss() }
        binding.btnGuardarDevoluciones.setOnClickListener {
            HapticUtils.performConfirm(it)
            guardar()
        }
        binding.btnQuitarDevoluciones.setOnClickListener {
            HapticUtils.performClick(it)
            viewModel.eliminarDevolucion(semanaKey)
            dismiss()
        }
        listOf(binding.btnGuardarDevoluciones, binding.btnQuitarDevoluciones)
            .forEach(AnimationExtensions::setupPressScaleAnimation)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { actualizarVistaPrevia() }
            }
        }
    }

    /** El importe escrito, redondeado a céntimos; 0 si está vacío y null si no es un número válido. */
    private fun montoEscrito(): Double? {
        val texto = binding.etMontoDevolucion.text?.toString().orEmpty()
        if (texto.isBlank()) return 0.0
        return parseImporte(texto)?.takeIf { it >= 0.0 }?.let(CalculoComisiones::redondearCentimos)
    }

    private fun actualizarVistaPrevia() {
        val b = _binding ?: return
        val state = viewModel.uiState.value
        // El panel se abre para la semana visible; hasta que llegan sus datos no hay nada que previsualizar.
        if (state.semanaKey != semanaKey) return
        val porcentaje = state.resumenSemana?.porcentajeIrpf ?: return
        val monto = montoEscrito() ?: 0.0

        val sin = CalculoComisiones.calcularResumenSemana(state.todosSociosSemana, state.bonoInfoSemana, porcentaje)
        val con = CalculoComisiones.calcularResumenSemana(
            state.todosSociosSemana, state.bonoInfoSemana, porcentaje, DevolucionInfo(semanaKey, monto)
        )
        b.tvPreviewNetoSin.text = sin.totalNeto.toFormattedEuros()
        b.tvPreviewDescuento.text = con.devoluciones.toCurrencyString(prefix = "-")
        b.tvPreviewIrpfAhorrado.text =
            CalculoComisiones.redondearCentimos(sin.retencionIrpf - con.retencionIrpf).toCurrencyString(prefix = "+")
        b.tvPreviewNetoCon.text = con.totalNeto.toFormattedEuros()
    }

    private fun guardar() {
        val monto = montoEscrito()
        if (monto == null) {
            binding.tilMontoDevolucion.error = getString(R.string.msg_devolucion_invalida)
            return
        }
        if (monto <= 0.0 && !binding.btnQuitarDevoluciones.isVisible) {
            // Nada que guardar ni que quitar.
            binding.tilMontoDevolucion.error = getString(R.string.msg_devolucion_invalida)
            mostrarTeclado()
            return
        }
        viewModel.guardarDevolucion(semanaKey, monto, binding.etNotaDevolucion.text?.toString().orEmpty())
        dismiss()
    }

    private fun mostrarTeclado() {
        binding.etMontoDevolucion.requestFocus()
        val ventana = dialog?.window ?: return
        WindowCompat.getInsetsController(ventana, binding.etMontoDevolucion).show(WindowInsetsCompat.Type.ime())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "DevolucionesDialog"

        private const val ARG_SEMANA = "semanaKey"
        private const val ARG_MONTO = "monto"
        private const val ARG_NOTA = "nota"

        fun newInstance(semanaKey: String, actual: DevolucionInfo?) = DevolucionesBottomSheetDialog().apply {
            arguments = Bundle().apply {
                putString(ARG_SEMANA, semanaKey)
                putDouble(ARG_MONTO, actual?.monto ?: 0.0)
                putString(ARG_NOTA, actual?.nota)
            }
        }
    }
}
