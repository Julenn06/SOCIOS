package com.julen.socios

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.activityViewModels
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.julen.socios.databinding.BottomSheetBonoRegaloBinding
import com.julen.socios.model.BonoRegaloInfo
import com.julen.socios.model.ImportesPredefinidos
import com.julen.socios.util.parseImporte
import com.julen.socios.util.toEurosCompacto
import com.julen.socios.util.toImporteInput

class BonoRegaloBottomSheetDialog : BottomSheetDialogFragment() {

    private val viewModel: MainViewModel by activityViewModels { MainViewModel.Factory }

    private var _binding: BottomSheetBonoRegaloBinding? = null
    private val binding get() = _binding!!

    private val semanaKey: String by lazy { requireNotNull(requireArguments().getString(ARG_SEMANA)) }

    private var chipsMonto: List<Chip> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetBonoRegaloBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = requireArguments()

        binding.tvSubtituloBonoRegalo.text = args.getString(ARG_RANGO)
        crearChipsMonto()

        val tieneBono = args.getBoolean(ARG_ACTIVO)
        binding.btnEliminarBono.isVisible = tieneBono

        if (savedInstanceState == null) {
            binding.switchActivarBono.isChecked = tieneBono
            binding.chipModoFijo.isChecked = args.getBoolean(ARG_MONTO_FIJO, true)
            binding.chipModoSumar.isChecked = !binding.chipModoFijo.isChecked
            val monto = args.getDouble(ARG_MONTO)
            if (tieneBono && monto > 0) selectMonto(monto)
            binding.etNotaBono.setText(args.getString(ARG_MOTIVO).orEmpty())
        } else {
            // Los chips de importe se crean en código con ids nuevos: su selección se restaura a mano.
            chipsMonto.getOrNull(savedInstanceState.getInt(STATE_MONTO_INDEX, -1))?.isChecked = true
        }

        binding.switchActivarBono.setOnCheckedChangeListener { _, isChecked ->
            binding.containerBonoConfig.isVisible = isChecked
        }

        binding.chipGroupMontos.setOnCheckedStateChangeListener { _, checkedIds ->
            binding.tilCustomMonto.isVisible = R.id.chipBonusOtro in checkedIds
        }

        binding.etCustomMonto.doOnTextChanged { _, _, _, _ -> binding.tilCustomMonto.error = null }

        binding.btnGuardarBono.setOnClickListener { saveBono() }

        binding.btnEliminarBono.setOnClickListener {
            viewModel.eliminarBono(semanaKey)
            dismiss()
        }

        binding.btnCancelarBono.setOnClickListener { dismiss() }
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        binding.containerBonoConfig.isVisible = binding.switchActivarBono.isChecked
        binding.tilCustomMonto.isVisible = binding.chipBonusOtro.isChecked
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        _binding?.let { outState.putInt(STATE_MONTO_INDEX, chipsMonto.indexOfFirst { it.isChecked }) }
    }

    private fun crearChipsMonto() {
        chipsMonto = ImportesPredefinidos.BONUS.mapIndexed { index, monto ->
            (layoutInflater.inflate(R.layout.chip_importe, binding.chipGroupMontos, false) as Chip).apply {
                id = View.generateViewId()
                text = monto.toEurosCompacto()
                tag = monto
                binding.chipGroupMontos.addView(this, index)
            }
        }
    }

    /** Selecciona el chip con el importe exacto; cualquier otro importe va a "Otros". */
    private fun selectMonto(monto: Double) {
        val chip = chipsMonto.firstOrNull { it.tag == monto }
        if (chip != null) {
            chip.isChecked = true
        } else {
            binding.chipBonusOtro.isChecked = true
            binding.etCustomMonto.setText(monto.toImporteInput())
        }
    }

    private fun getSelectedMonto(): Double? {
        if (binding.chipBonusOtro.isChecked) return parseImporte(binding.etCustomMonto.text)
        return chipsMonto.firstOrNull { it.isChecked }?.tag as? Double
    }

    private fun saveBono() {
        if (!binding.switchActivarBono.isChecked) {
            viewModel.eliminarBono(semanaKey)
            dismiss()
            return
        }

        val monto = getSelectedMonto()
        if (monto == null || monto < 0) {
            if (binding.chipBonusOtro.isChecked) {
                binding.tilCustomMonto.error = getString(R.string.msg_bono_monto_invalido)
            } else {
                Toast.makeText(requireContext(), R.string.msg_bono_monto_invalido, Toast.LENGTH_SHORT).show()
            }
            return
        }

        viewModel.guardarBono(
            BonoRegaloInfo(
                semanaKey = semanaKey,
                activo = true,
                esMontoFijo = binding.chipModoFijo.isChecked,
                monto = monto,
                nota = binding.etNotaBono.text?.toString()?.trim().orEmpty()
            )
        )
        dismiss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "BonoRegaloDialog"

        private const val ARG_SEMANA = "semanaKey"
        private const val ARG_RANGO = "rango"
        private const val ARG_ACTIVO = "activo"
        private const val ARG_MONTO_FIJO = "esMontoFijo"
        private const val ARG_MONTO = "monto"
        private const val ARG_MOTIVO = "motivo"
        private const val STATE_MONTO_INDEX = "montoIndex"

        fun newInstance(semanaKey: String, rangoTexto: String, bonoActual: BonoRegaloInfo?) =
            BonoRegaloBottomSheetDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_SEMANA, semanaKey)
                    putString(ARG_RANGO, rangoTexto)
                    putBoolean(ARG_ACTIVO, bonoActual?.activo == true)
                    putBoolean(ARG_MONTO_FIJO, bonoActual?.esMontoFijo ?: true)
                    putDouble(ARG_MONTO, bonoActual?.monto ?: 0.0)
                    putString(ARG_MOTIVO, bonoActual?.motivo)
                }
            }
    }
}
