package com.julen.socios

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.julen.socios.databinding.BottomSheetBonoRegaloBinding
import com.julen.socios.model.BonoRegaloInfo

class BonoRegaloBottomSheetDialog(
    private val semanaTexto: String,
    private val currentBono: BonoRegaloInfo,
    private val onSave: (BonoRegaloInfo) -> Unit,
    private val onDelete: () -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetBonoRegaloBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetBonoRegaloBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tvSubtituloBonoRegalo.text = semanaTexto

        // Estado inicial
        val isActivo = currentBono.activo
        binding.switchActivarBono.isChecked = isActivo
        binding.containerBonoConfig.visibility = if (isActivo) View.VISIBLE else View.GONE
        binding.btnEliminarBono.visibility = if (isActivo) View.VISIBLE else View.GONE

        // Configurar selección de importe: Si no hay activo previo, no seleccionar ninguno por defecto
        if (isActivo && currentBono.monto > 0) {
            selectMontoInChips(currentBono.monto)
        } else {
            binding.chipGroupMontos.clearCheck()
            binding.tilCustomMonto.visibility = View.GONE
        }

        binding.switchActivarBono.setOnCheckedChangeListener { _, isChecked ->
            binding.containerBonoConfig.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        binding.chipGroupMontos.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.contains(R.id.chipBonusOtro)) {
                binding.tilCustomMonto.visibility = View.VISIBLE
            } else {
                binding.tilCustomMonto.visibility = View.GONE
            }
        }

        binding.btnGuardarBono.setOnClickListener {
            saveBono()
        }

        binding.btnEliminarBono.setOnClickListener {
            onDelete()
            dismiss()
        }

        binding.btnCancelarBono.setOnClickListener {
            dismiss()
        }
    }

    private fun selectMontoInChips(monto: Double) {
        when (monto.toInt()) {
            50 -> binding.chipBonus50.isChecked = true
            100 -> binding.chipBonus100.isChecked = true
            140 -> binding.chipBonus140.isChecked = true
            else -> {
                binding.chipBonusOtro.isChecked = true
                binding.tilCustomMonto.visibility = View.VISIBLE
                binding.etCustomMonto.setText(monto.toString())
            }
        }
    }

    private fun getSelectedMonto(): Double? {
        val checkedId = binding.chipGroupMontos.checkedChipId
        if (checkedId == View.NO_ID) {
            return null
        }
        if (checkedId == R.id.chipBonusOtro) {
            val text = binding.etCustomMonto.text?.toString()?.trim()
            return text?.toDoubleOrNull()
        }
        return when (checkedId) {
            R.id.chipBonus50 -> 50.0
            R.id.chipBonus100 -> 100.0
            R.id.chipBonus140 -> 140.0
            else -> null
        }
    }

    private fun saveBono() {
        val isActivo = binding.switchActivarBono.isChecked
        if (!isActivo) {
            onDelete()
            dismiss()
            return
        }

        val monto = getSelectedMonto()
        if (monto == null || monto < 0) {
            Toast.makeText(
                context, "Por favor selecciona o introduce un importe de bonus", Toast.LENGTH_SHORT
            ).show()
            return
        }

        val nuevoBono = BonoRegaloInfo(
            semanaKey = currentBono.semanaKey,
            activo = true,
            monto = monto,
            nota = "Ajustado a mano"
        )

        onSave(nuevoBono)
        dismiss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
