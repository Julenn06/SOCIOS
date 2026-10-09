package com.julen.socios

import android.app.Dialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.julen.socios.databinding.BottomSheetAddSocioBinding
import com.julen.socios.model.Socio
import com.julen.socios.util.CalculoComisiones
import com.julen.socios.util.HapticUtils
import com.julen.socios.util.toFormattedEuros

class AddSocioBottomSheetDialog(
    private val semanaKey: String,
    private val defaultDiaId: Int,
    private val socioToEdit: Socio? = null,
    private val onSave: (Socio) -> Unit,
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetAddSocioBinding? = null
    private val binding get() = _binding!!

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setOnShowListener { dialogInterface ->
            val bottomSheetDialog = dialogInterface as BottomSheetDialog
            val bottomSheet =
                bottomSheetDialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            if (bottomSheet != null) {
                val behavior = BottomSheetBehavior.from(bottomSheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true

                val layoutParams = bottomSheet.layoutParams
                layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
                bottomSheet.layoutParams = layoutParams
            }
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetAddSocioBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupListeners()

        if (socioToEdit != null) {
            binding.tvTitleBottomSheet.text = getString(R.string.title_edit_socio)
            binding.btnGuardarSocio.text = getString(R.string.action_edit)
            binding.btnGuardarSocio.setIconResource(R.drawable.ic_edit)

            binding.switchSocioHechoAdd.isChecked = socioToEdit.hecho
            binding.etNombreSocio.setText(socioToEdit.nombreSocio)
            binding.etNotas.setText(socioToEdit.notas)
            selectCuota(socioToEdit.colaboracion)
            selectDia(socioToEdit.diaSemanaId)
        } else {
            binding.tvTitleBottomSheet.text = getString(R.string.title_add_socio)
            binding.btnGuardarSocio.text = getString(R.string.action_save)
            binding.btnGuardarSocio.setIconResource(R.drawable.ic_add)
            selectDia(defaultDiaId)
        }

        updateLivePreview()

        binding.btnCancelarAdd.setOnClickListener { viewClick ->
            HapticUtils.performClick(viewClick)
            dismiss()
        }

        binding.btnGuardarSocio.setOnClickListener { viewClick ->
            HapticUtils.performConfirm(viewClick)
            saveSocio()
        }
    }

    private fun setupListeners() {
        binding.chipGroupCuotas.setOnCheckedStateChangeListener { _, checkedIds ->
            HapticUtils.performClick(binding.chipGroupCuotas)
            if (checkedIds.contains(R.id.chipOtro)) {
                binding.tilCustomAmount.visibility = View.VISIBLE
            } else {
                binding.tilCustomAmount.visibility = View.GONE
            }
            updateLivePreview()
        }

        binding.chipGroupDias.setOnCheckedStateChangeListener { _, _ ->
            HapticUtils.performClick(binding.chipGroupDias)
        }

        binding.switchSocioHechoAdd.setOnCheckedChangeListener { viewSwitch, _ ->
            HapticUtils.performToggle(viewSwitch)
            updateLivePreview()
        }

        binding.etCustomAmount.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateLivePreview()
            }

            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun updateLivePreview() {
        val cuota = getSelectedCuota() ?: 0.0
        val baseX2 = CalculoComisiones.calcularBaseX2(cuota)
        val isHecho = binding.switchSocioHechoAdd.isChecked

        val cuotaFormatted = cuota.toFormattedEuros(0)
        val baseX2Formatted = baseX2.toFormattedEuros(2)
        binding.tvPreviewBaseX2.text =
            getString(R.string.preview_base_format, baseX2Formatted, cuotaFormatted)

        val context = requireContext()
        if (isHecho) {
            binding.tvPreviewEstadoBadge.text = getString(R.string.socio_status_badge_conseguido)
            binding.tvPreviewEstadoBadge.setTextColor(
                ContextCompat.getColor(
                    context, R.color.primary
                )
            )
            binding.tvPreviewEstadoBadge.backgroundTintList =
                ContextCompat.getColorStateList(context, R.color.bg_light)
        } else {
            binding.tvPreviewEstadoBadge.text = getString(R.string.socio_status_badge_pendiente)
            binding.tvPreviewEstadoBadge.setTextColor(
                ContextCompat.getColor(
                    context, R.color.orange_pending
                )
            )
            binding.tvPreviewEstadoBadge.backgroundTintList =
                ContextCompat.getColorStateList(context, R.color.bg_light)
        }
    }

    private fun selectCuota(amount: Double) {
        when (amount.toInt()) {
            10 -> binding.chip10.isChecked = true
            12 -> binding.chip12.isChecked = true
            15 -> binding.chip15.isChecked = true
            18 -> binding.chip18.isChecked = true
            20 -> binding.chip20.isChecked = true
            25 -> binding.chip25.isChecked = true
            30 -> binding.chip30.isChecked = true
            else -> {
                binding.chipOtro.isChecked = true
                binding.tilCustomAmount.visibility = View.VISIBLE
                binding.etCustomAmount.setText(amount.toString())
            }
        }
    }

    private fun selectDia(diaId: Int) {
        when (diaId) {
            1 -> binding.chipDiaLun.isChecked = true
            2 -> binding.chipDiaMar.isChecked = true
            3 -> binding.chipDiaMie.isChecked = true
            4 -> binding.chipDiaJue.isChecked = true
            5 -> binding.chipDiaVie.isChecked = true
        }
    }

    private fun getSelectedCuota(): Double? {
        val checkedId = binding.chipGroupCuotas.checkedChipId
        if (checkedId == R.id.chipOtro) {
            val text = binding.etCustomAmount.text?.toString()?.trim()
            return text?.toDoubleOrNull()
        }
        return when (checkedId) {
            R.id.chip10 -> 10.0
            R.id.chip12 -> 12.0
            R.id.chip15 -> 15.0
            R.id.chip18 -> 18.0
            R.id.chip20 -> 20.0
            R.id.chip25 -> 25.0
            R.id.chip30 -> 30.0
            else -> null
        }
    }

    private fun getSelectedDiaId(): Int {
        return when (binding.chipGroupDias.checkedChipId) {
            R.id.chipDiaLun -> 1
            R.id.chipDiaMar -> 2
            R.id.chipDiaMie -> 3
            R.id.chipDiaJue -> 4
            R.id.chipDiaVie -> 5
            else -> 1
        }
    }

    private fun saveSocio() {
        val cuota = getSelectedCuota()
        if (cuota == null || cuota <= 0) {
            Toast.makeText(
                context, "Por favor introduce un importe de colaboración válido", Toast.LENGTH_SHORT
            ).show()
            return
        }

        val diaId = getSelectedDiaId()
        val esHecho = binding.switchSocioHechoAdd.isChecked
        val nombre = binding.etNombreSocio.text?.toString()?.trim().orEmpty()
        val notas = binding.etNotas.text?.toString()?.trim().orEmpty()

        val socio = socioToEdit?.copy(
            colaboracion = cuota,
            hecho = esHecho,
            diaSemanaId = diaId,
            nombreSocio = nombre,
            notas = notas
        ) ?: Socio(
            colaboracion = cuota,
            hecho = esHecho,
            diaSemanaId = diaId,
            semanaKey = semanaKey,
            nombreSocio = nombre,
            notas = notas
        )

        onSave(socio)
        dismiss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
