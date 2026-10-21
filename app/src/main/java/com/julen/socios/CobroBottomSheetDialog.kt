package com.julen.socios

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
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
import com.julen.socios.databinding.BottomSheetCobroBinding
import com.julen.socios.model.CobroInfo
import com.julen.socios.util.AnimationExtensions
import com.julen.socios.util.CalculoComisiones
import com.julen.socios.util.DateUtils
import com.julen.socios.util.HapticUtils
import com.julen.socios.util.parseImporte
import com.julen.socios.util.toCurrencyString
import com.julen.socios.util.toFormattedEuros
import com.julen.socios.util.toImporteInput
import kotlin.math.abs
import kotlinx.coroutines.launch

/**
 * Lo cobrado de verdad en la semana visible. Mientras se escribe, enseña la diferencia con el neto calculado:
 * lo que te han descontado (devoluciones u otros descuentos) o lo que has cobrado de más.
 */
class CobroBottomSheetDialog : BottomSheetDialogFragment() {

    private val viewModel: MainViewModel by activityViewModels { MainViewModel.Factory }

    private var _binding: BottomSheetCobroBinding? = null
    private val binding get() = _binding!!

    private val semanaKey: String by lazy { requireNotNull(requireArguments().getString(ARG_SEMANA)) }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        (super.onCreateDialog(savedInstanceState) as BottomSheetDialog).apply {
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetCobroBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = requireArguments()
        val tieneCobro = args.containsKey(ARG_NETO)

        binding.tvSubtituloCobro.text =
            getString(R.string.rango_semana_anio, DateUtils.rangoCorto(semanaKey), semanaKey.take(4))
        binding.btnQuitarCobro.isVisible = tieneCobro

        if (savedInstanceState == null) {
            if (tieneCobro) binding.etNetoCobrado.setText(args.getDouble(ARG_NETO).toImporteInput())
            binding.etNotaCobro.setText(args.getString(ARG_NOTA).orEmpty())
            // Sin nada apuntado todavía: directamente a escribirlo.
            if (!tieneCobro) mostrarTeclado()
        }

        binding.etNetoCobrado.doOnTextChanged { _, _, _, _ ->
            binding.tilNetoCobrado.error = null
            actualizarVistaPrevia()
        }
        binding.btnCerrarCobro.setOnClickListener { dismiss() }
        binding.btnGuardarCobro.setOnClickListener {
            HapticUtils.performConfirm(it)
            guardar()
        }
        binding.btnQuitarCobro.setOnClickListener {
            HapticUtils.performClick(it)
            viewModel.eliminarCobro(semanaKey)
            dismiss()
        }
        listOf(binding.btnGuardarCobro, binding.btnQuitarCobro).forEach(AnimationExtensions::setupPressScaleAnimation)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { actualizarVistaPrevia() }
            }
        }
    }

    /** Lo escrito, redondeado a céntimos; null si está vacío o no es un importe válido. */
    private fun netoEscrito(): Double? =
        parseImporte(binding.etNetoCobrado.text)?.takeIf { it >= 0.0 }?.let(CalculoComisiones::redondearCentimos)

    private fun actualizarVistaPrevia() {
        val b = _binding ?: return
        val state = viewModel.uiState.value
        // El panel se abre para la semana visible; hasta que llegan sus datos no hay nada que comparar.
        if (state.semanaKey != semanaKey) return
        val calculado = state.resumenSemana?.netoCalculado ?: return
        val cobrado = netoEscrito()

        b.tilNetoCobrado.helperText = getString(R.string.cobro_calculado_ayuda, calculado.toFormattedEuros())
        b.tvPreviewCalculado.text = calculado.toFormattedEuros()
        b.tvPreviewCobrado.text = cobrado?.toFormattedEuros() ?: "–"

        val descontado = cobrado?.let { CalculoComisiones.redondearCentimos(calculado - it) }
        val (etiqueta, fondo, color) = when {
            descontado == null || descontado == 0.0 ->
                Triple(R.string.header_cobro_coincide, R.color.green_container, R.color.green_text)
            descontado > 0.0 -> Triple(R.string.header_cobro_descontado, R.color.red_container, R.color.red_text)
            else -> Triple(R.string.header_cobro_de_mas, R.color.green_container, R.color.green_text)
        }
        val contexto = requireContext()
        b.placaPreviewDiferencia.isVisible = descontado != null
        b.placaPreviewDiferencia.backgroundTintList = ContextCompat.getColorStateList(contexto, fondo)
        b.tvPreviewDiferenciaLabel.setText(etiqueta)
        b.tvPreviewDiferenciaLabel.setTextColor(ContextCompat.getColor(contexto, color))
        b.tvPreviewDiferencia.setTextColor(ContextCompat.getColor(contexto, color))
        b.tvPreviewDiferencia.text = descontado?.let {
            abs(it).toCurrencyString(prefix = if (it > 0.0) "-" else if (it < 0.0) "+" else "")
        }
    }

    private fun guardar() {
        val cobrado = netoEscrito()
        if (cobrado == null) {
            binding.tilNetoCobrado.error = getString(R.string.msg_cobro_invalido)
            mostrarTeclado()
            return
        }
        viewModel.guardarCobro(semanaKey, cobrado, binding.etNotaCobro.text?.toString().orEmpty())
        dismiss()
    }

    private fun mostrarTeclado() {
        binding.etNetoCobrado.requestFocus()
        val ventana = dialog?.window ?: return
        WindowCompat.getInsetsController(ventana, binding.etNetoCobrado).show(WindowInsetsCompat.Type.ime())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "CobroDialog"

        private const val ARG_SEMANA = "semanaKey"
        private const val ARG_NETO = "netoCobrado"
        private const val ARG_NOTA = "nota"

        fun newInstance(semanaKey: String, actual: CobroInfo?) = CobroBottomSheetDialog().apply {
            arguments = Bundle().apply {
                putString(ARG_SEMANA, semanaKey)
                actual?.let {
                    putDouble(ARG_NETO, it.netoCobrado)
                    putString(ARG_NOTA, it.nota)
                }
            }
        }
    }
}
