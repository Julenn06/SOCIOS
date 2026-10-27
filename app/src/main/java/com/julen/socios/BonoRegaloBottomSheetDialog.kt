package com.julen.socios

import android.os.Bundle
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.View
import android.widget.GridLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.activityViewModels
import com.google.android.material.button.MaterialButton
import com.julen.socios.databinding.BottomSheetBonoRegaloBinding
import com.julen.socios.model.BonoRegaloInfo
import com.julen.socios.model.ImportesPredefinidos
import com.julen.socios.util.AnimationExtensions
import com.julen.socios.util.CalculoComisiones
import com.julen.socios.util.DateUtils
import com.julen.socios.util.HapticUtils
import com.julen.socios.util.parseImporte
import com.julen.socios.util.toEurosCompacto
import com.julen.socios.util.toImporteInput

/** Ajuste manual del bonus de una semana: sustituye al automático o se suma a él. */
class BonoRegaloBottomSheetDialog : PanelInferior<BottomSheetBonoRegaloBinding>(BottomSheetBonoRegaloBinding::inflate) {

    private val viewModel: MainViewModel by activityViewModels { MainViewModel.Factory }

    private val semanaKey: String by lazy { requireNotNull(requireArguments().getString(ARG_SEMANA)) }

    /** Los importes predefinidos y, al final, "Otro". */
    private var opcionesMonto: List<MaterialButton> = emptyList()
    private val opcionOtro get() = opcionesMonto.last()

    private var montoFijo = true

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = requireArguments()

        binding.tvSubtituloBonoRegalo.text =
            getString(R.string.rango_semana_anio, DateUtils.rangoCorto(semanaKey), semanaKey.take(4))
        crearOpcionesMonto()

        val tieneBono = args.getBoolean(ARG_ACTIVO)
        binding.btnEliminarBono.isVisible = tieneBono

        if (savedInstanceState == null) {
            binding.switchActivarBono.isChecked = tieneBono
            montoFijo = args.getBoolean(ARG_MONTO_FIJO, true)
            // También un bonus de 0 €, que es válido: va a "Otro" con su importe.
            if (tieneBono) elegirMonto(args.getDouble(ARG_MONTO))
            binding.etNotaBono.setText(args.getString(ARG_MOTIVO).orEmpty())
        } else {
            montoFijo = savedInstanceState.getBoolean(STATE_MONTO_FIJO, true)
            // Los importes se crean en código con ids nuevos: su selección se restaura a mano.
            opcionesMonto.getOrNull(savedInstanceState.getInt(STATE_MONTO_INDEX, -1))?.let(::marcarMonto)
        }

        binding.cardActivarBono.setOnClickListener {
            HapticUtils.performToggle(it)
            binding.switchActivarBono.toggle()
        }
        binding.switchActivarBono.setOnCheckedChangeListener { _, activado ->
            TransitionManager.beginDelayedTransition(binding.contenidoBono, AutoTransition().setDuration(DURACION_MS))
            binding.containerBonoConfig.isVisible = activado
            if (!activado) ocultarTeclado()
        }
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
        binding.tilCustomMonto.isVisible = opcionOtro.isChecked
        mostrarModo()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        bindingSiExiste?.let { outState.putInt(STATE_MONTO_INDEX, opcionesMonto.indexOfFirst { it.isChecked }) }
        outState.putBoolean(STATE_MONTO_FIJO, montoFijo)
    }

    private fun crearOpcionesMonto() {
        val margen = (4 * resources.displayMetrics.density).toInt()
        val textos = ImportesPredefinidos.BONUS.map { it.toEurosCompacto() } + getString(R.string.chip_otro)
        opcionesMonto = textos.mapIndexed { index, texto ->
            (layoutInflater.inflate(R.layout.item_opcion_cuota, binding.gridMontos, false) as MaterialButton).apply {
                id = View.generateViewId()
                text = texto
                tag = ImportesPredefinidos.BONUS.getOrNull(index)
                layoutParams = GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)
                ).apply {
                    width = 0
                    setMargins(margen, margen, margen, margen)
                }
                setOnClickListener { opcion ->
                    HapticUtils.performClick(opcion)
                    marcarMonto(this)
                    TransitionManager.beginDelayedTransition(binding.contenidoBono, AutoTransition().setDuration(DURACION_MS))
                    if (this == opcionOtro) {
                        binding.tilCustomMonto.isVisible = true
                        mostrarTeclado(binding.etCustomMonto)
                    } else {
                        if (binding.etCustomMonto.hasFocus()) ocultarTeclado()
                        binding.tilCustomMonto.isVisible = false
                    }
                }
                AnimationExtensions.setupPressScaleAnimation(this)
                binding.gridMontos.addView(this)
            }
        }
    }

    /** Marca un solo importe; volver a pulsar el elegido lo deja marcado. */
    private fun marcarMonto(elegido: MaterialButton) {
        opcionesMonto.forEach { it.isChecked = it == elegido }
    }

    /** Marca el importe exacto; cualquier otro va a "Otro". */
    private fun elegirMonto(monto: Double) {
        val opcion = opcionesMonto.firstOrNull { it.tag == monto }
        if (opcion != null) {
            marcarMonto(opcion)
        } else {
            marcarMonto(opcionOtro)
            binding.etCustomMonto.setText(monto.toImporteInput())
        }
    }

    /** El importe en euros; el escrito a mano, redondeado a céntimos. */
    private fun montoElegido(): Double? {
        if (opcionOtro.isChecked) return parseImporte(binding.etCustomMonto.text)?.let(CalculoComisiones::redondearCentimos)
        return opcionesMonto.firstOrNull { it.isChecked }?.tag as? Double
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

    private fun mostrarTeclado(campo: View) {
        campo.requestFocus()
        val ventana = dialog?.window ?: return
        WindowCompat.getInsetsController(ventana, campo).show(WindowInsetsCompat.Type.ime())
    }

    private fun ocultarTeclado() {
        val ventana = dialog?.window ?: return
        WindowCompat.getInsetsController(ventana, binding.root).hide(WindowInsetsCompat.Type.ime())
        ventana.currentFocus?.clearFocus()
    }

    private fun saveBono() {
        if (!binding.switchActivarBono.isChecked) {
            viewModel.eliminarBono(semanaKey)
            dismiss()
            return
        }

        val monto = montoElegido()
        if (monto == null || monto < 0) {
            if (opcionOtro.isChecked) {
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
        private const val DURACION_MS = 200L

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
