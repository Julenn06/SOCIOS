package com.julen.socios

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.julen.socios.databinding.BottomSheetCobroBinding
import com.julen.socios.model.CobroInfo
import com.julen.socios.util.AnimationExtensions
import com.julen.socios.util.CalculoComisiones
import com.julen.socios.util.DateUtils
import com.julen.socios.util.HapticUtils
import com.julen.socios.util.Movimiento
import com.julen.socios.util.parseImporte
import com.julen.socios.util.toCurrencyString
import com.julen.socios.util.toFormattedEuros
import com.julen.socios.util.toImporteInput
import kotlin.math.abs
import kotlinx.coroutines.launch

/**
 * Ajuste del neto de la semana visible a lo cobrado de verdad, como el del bonus: con el interruptor encendido
 * se escribe el importe y se ve cuánto se ha descontado de lo calculado; apagado y guardado, la semana vuelve
 * al neto calculado. Se abre con un doble toque en el neto de la cabecera.
 */
class CobroBottomSheetDialog : PanelInferior<BottomSheetCobroBinding>(BottomSheetCobroBinding::inflate) {

    private val viewModel: MainViewModel by activityViewModels { MainViewModel.Factory }

    private val semanaKey: String by lazy { requireNotNull(requireArguments().getString(ARG_SEMANA)) }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        super.onCreateDialog(savedInstanceState).apply {
            // Aunque el campo del importe se vea, el teclado espera a que se toque.
            window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = requireArguments()
        val tieneCobro = args.containsKey(ARG_NETO)

        binding.tvSubtituloCobro.text =
            getString(R.string.rango_semana_anio, DateUtils.rangoCorto(semanaKey), semanaKey.take(4))

        // El teclado no sale al abrir: solo al tocar el campo.
        if (savedInstanceState == null) {
            binding.switchActivarCobro.isChecked = tieneCobro
            if (tieneCobro) binding.etNetoCobrado.setText(args.getDouble(ARG_NETO).toImporteInput())
        }

        binding.cardActivarCobro.setOnClickListener {
            HapticUtils.performToggle(it)
            binding.switchActivarCobro.toggle()
        }
        binding.switchActivarCobro.setOnCheckedChangeListener { _, activado ->
            Movimiento.animarCambios(binding.contenidoCobro)
            binding.containerCobroConfig.isVisible = activado
            binding.tilNetoCobrado.error = null
            if (!activado) ocultarTeclado()
        }
        binding.etNetoCobrado.doOnTextChanged { _, _, _, _ ->
            binding.tilNetoCobrado.error = null
            actualizarVistaPrevia()
        }
        binding.btnCerrarCobro.setOnClickListener { dismiss() }
        binding.btnGuardarCobro.setOnClickListener {
            HapticUtils.performConfirm(it)
            guardar(tieneCobro)
        }
        AnimationExtensions.setupPressScaleAnimation(binding.btnGuardarCobro)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { actualizarVistaPrevia() }
            }
        }
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        binding.containerCobroConfig.isVisible = binding.switchActivarCobro.isChecked
    }

    /** Lo escrito, redondeado a céntimos; null si está vacío o no es un importe válido. */
    private fun netoEscrito(): Double? =
        parseImporte(binding.etNetoCobrado.text)?.takeIf { it >= 0.0 }?.let(CalculoComisiones::redondearCentimos)

    private fun actualizarVistaPrevia() {
        val b = bindingSiExiste ?: return
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

    /** Apagado: vuelve al neto calculado (si había algo apuntado). Encendido: guarda lo escrito. */
    private fun guardar(teniaCobro: Boolean) {
        if (!binding.switchActivarCobro.isChecked) {
            if (teniaCobro) viewModel.eliminarCobro(semanaKey)
            dismiss()
            return
        }
        val cobrado = netoEscrito()
        if (cobrado == null) {
            binding.tilNetoCobrado.error = getString(R.string.msg_cobro_invalido)
            return
        }
        // La nota ya no se escribe aquí, pero se conserva la que tuviera (por ejemplo, de la versión 1.9.0).
        viewModel.guardarCobro(semanaKey, cobrado, requireArguments().getString(ARG_NOTA).orEmpty())
        dismiss()
    }

    private fun ocultarTeclado() {
        val ventana = dialog?.window ?: return
        WindowCompat.getInsetsController(ventana, binding.root).hide(WindowInsetsCompat.Type.ime())
        ventana.currentFocus?.clearFocus()
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
