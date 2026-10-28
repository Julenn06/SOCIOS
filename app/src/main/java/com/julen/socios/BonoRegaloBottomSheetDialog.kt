package com.julen.socios

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.activityViewModels
import com.julen.socios.databinding.BottomSheetBonoRegaloBinding
import com.julen.socios.model.BonoRegaloInfo
import com.julen.socios.model.ImportesPredefinidos
import com.julen.socios.util.AnimationExtensions
import com.julen.socios.util.HapticUtils
import com.julen.socios.util.rangoSemanaConAnio

/** Ajuste manual del bonus de una semana: sustituye al automático o se suma a él. */
class BonoRegaloBottomSheetDialog : PanelInferior<BottomSheetBonoRegaloBinding>(BottomSheetBonoRegaloBinding::inflate) {

    private val viewModel: MainViewModel by activityViewModels { MainViewModel.Factory }

    private val semanaKey: String by lazy { requireNotNull(requireArguments().getString(ARG_SEMANA)) }

    /** Los importes predefinidos y, al final, "Otro". */
    private lateinit var montos: OpcionesImporte

    private var montoFijo = true

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = requireArguments()

        binding.tvSubtituloBonoRegalo.text = requireContext().rangoSemanaConAnio(semanaKey)
        montos = crearOpcionesImporte(
            binding.gridMontos, ImportesPredefinidos.BONUS, binding.tilCustomMonto, binding.contenidoBono
        )

        val tieneBono = args.getBoolean(ARG_ACTIVO)
        binding.btnEliminarBono.isVisible = tieneBono

        if (savedInstanceState == null) {
            binding.switchActivarBono.isChecked = tieneBono
            montoFijo = args.getBoolean(ARG_MONTO_FIJO, true)
            // También un bonus de 0 €, que es válido: va a "Otro" con su importe.
            if (tieneBono) montos.elegir(args.getDouble(ARG_MONTO))
            binding.etNotaBono.setText(args.getString(ARG_MOTIVO).orEmpty())
        } else {
            montoFijo = savedInstanceState.getBoolean(STATE_MONTO_FIJO, true)
            montos.restaurar(savedInstanceState.getInt(STATE_MONTO_INDEX, -1))
        }

        configurarInterruptor(
            binding.cardActivarBono, binding.switchActivarBono, binding.contenidoBono, binding.containerBonoConfig
        )
        binding.cardModoFijo.setOnClickListener { elegirModo(fijo = true) }
        binding.cardModoSumar.setOnClickListener { elegirModo(fijo = false) }
        binding.etCustomMonto.doOnTextChanged { _, _, _, _ -> binding.tilCustomMonto.error = null }

        binding.btnGuardarBono.setOnClickListener {
            HapticUtils.performConfirm(it)
            saveBono()
        }
        binding.btnEliminarBono.setOnClickListener {
            HapticUtils.performClick(it)
            viewModel.eliminarBono(semanaKey)
            dismiss()
        }
        binding.btnCancelarBono.setOnClickListener { dismiss() }
        listOf(binding.cardModoFijo, binding.cardModoSumar, binding.btnGuardarBono, binding.btnEliminarBono)
            .forEach(AnimationExtensions::setupPressScaleAnimation)
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        binding.containerBonoConfig.isVisible = binding.switchActivarBono.isChecked
        montos.ajustarCampo()
        mostrarModo()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        bindingSiExiste?.let { outState.putInt(STATE_MONTO_INDEX, montos.indiceMarcado) }
        outState.putBoolean(STATE_MONTO_FIJO, montoFijo)
    }

    private fun elegirModo(fijo: Boolean) {
        if (fijo == montoFijo) return
        HapticUtils.performClick(binding.cardModoFijo)
        montoFijo = fijo
        mostrarModo()
    }

    private fun mostrarModo() {
        binding.cardModoFijo.isChecked = montoFijo
        binding.cardModoSumar.isChecked = !montoFijo
    }

    private fun saveBono() {
        if (!binding.switchActivarBono.isChecked) {
            viewModel.eliminarBono(semanaKey)
            dismiss()
            return
        }

        val monto = montos.elegido()
        if (monto == null || monto < 0) {
            if (montos.otro.isChecked) {
                binding.tilCustomMonto.error = getString(R.string.msg_bono_monto_invalido)
            } else {
                Avisos.mostrar(binding.root, getString(R.string.msg_bono_monto_invalido), TipoAviso.ERROR)
            }
            return
        }

        viewModel.guardarBono(
            BonoRegaloInfo(
                semanaKey = semanaKey,
                activo = true,
                esMontoFijo = montoFijo,
                monto = monto,
                nota = binding.etNotaBono.text?.toString()?.trim().orEmpty()
            )
        )
        dismiss()
    }

    companion object {
        const val TAG = "BonoRegaloDialog"

        private const val ARG_SEMANA = "semanaKey"
        private const val ARG_ACTIVO = "activo"
        private const val ARG_MONTO_FIJO = "esMontoFijo"
        private const val ARG_MONTO = "monto"
        private const val ARG_MOTIVO = "motivo"
        private const val STATE_MONTO_INDEX = "montoIndex"
        private const val STATE_MONTO_FIJO = "montoFijo"

        fun newInstance(semanaKey: String, bonoActual: BonoRegaloInfo?) =
            BonoRegaloBottomSheetDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_SEMANA, semanaKey)
                    putBoolean(ARG_ACTIVO, bonoActual?.activo == true)
                    putBoolean(ARG_MONTO_FIJO, bonoActual?.esMontoFijo ?: true)
                    putDouble(ARG_MONTO, bonoActual?.monto ?: 0.0)
                    putString(ARG_MOTIVO, bonoActual?.motivo)
                }
            }
    }
}
