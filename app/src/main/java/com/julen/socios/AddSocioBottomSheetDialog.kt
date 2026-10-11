package com.julen.socios

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.activityViewModels
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.julen.socios.databinding.BottomSheetAddSocioBinding
import com.julen.socios.model.DiaSemana
import com.julen.socios.model.ImportesPredefinidos
import com.julen.socios.model.Socio
import com.julen.socios.util.CalculoComisiones
import com.julen.socios.util.HapticUtils
import com.julen.socios.util.parseImporte
import com.julen.socios.util.toEurosCompacto
import com.julen.socios.util.toFormattedEuros
import com.julen.socios.util.toImporteInput

/**
 * Alta o edición de un socio. Recibe sus datos por argumentos (no por constructor) para que el sistema
 * pueda recrearlo tras un giro de pantalla o la muerte del proceso, y guarda a través del ViewModel.
 */
class AddSocioBottomSheetDialog : BottomSheetDialogFragment() {

    private val viewModel: MainViewModel by activityViewModels { MainViewModel.Factory }

    private var _binding: BottomSheetAddSocioBinding? = null
    private val binding get() = _binding!!

    private val socioToEdit: Socio? by lazy { requireArguments().getBundle(ARG_SOCIO)?.toSocio() }
    private val semanaKey: String by lazy { requireNotNull(requireArguments().getString(ARG_SEMANA)) }
    private val defaultDia: DiaSemana by lazy { DiaSemana.fromId(requireArguments().getInt(ARG_DIA)) }

    private var chipsCuota: List<Chip> = emptyList()
    private val chipsDia: Map<DiaSemana, Chip> by lazy {
        DiaSemana.entries.zip(
            listOf(binding.chipDiaLun, binding.chipDiaMar, binding.chipDiaMie, binding.chipDiaJue, binding.chipDiaVie)
        ).toMap()
    }

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

        crearChipsCuota()
        setupListeners()

        val socio = socioToEdit
        if (socio != null) {
            binding.tvTitleBottomSheet.text = getString(R.string.title_edit_socio)
            binding.btnGuardarSocio.text = getString(R.string.action_edit)
            binding.btnGuardarSocio.setIconResource(R.drawable.ic_edit)
        } else {
            binding.tvTitleBottomSheet.text = getString(R.string.title_add_socio)
            binding.btnGuardarSocio.text = getString(R.string.action_save)
            binding.btnGuardarSocio.setIconResource(R.drawable.ic_add)
        }

        if (savedInstanceState == null) {
            if (socio != null) {
                binding.switchSocioHechoAdd.isChecked = socio.hecho
                binding.etNombreSocio.setText(socio.nombreSocio)
                binding.etNotas.setText(socio.notas)
                selectCuota(socio.colaboracion)
                chipsDia[socio.dia]?.isChecked = true
            } else {
                selectCuota(ImportesPredefinidos.CUOTA_POR_DEFECTO)
                chipsDia[defaultDia]?.isChecked = true
            }
        } else {
            // Los chips de cuota se crean en código con ids nuevos: su selección se restaura a mano.
            chipsCuota.getOrNull(savedInstanceState.getInt(STATE_CUOTA_INDEX, -1))?.isChecked = true
        }

        binding.btnCancelarAdd.setOnClickListener { viewClick ->
            HapticUtils.performClick(viewClick)
            dismiss()
        }

        binding.btnGuardarSocio.setOnClickListener { viewClick ->
            HapticUtils.performConfirm(viewClick)
            saveSocio()
        }
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        binding.tilCustomAmount.isVisible = binding.chipOtro.isChecked
        updateLivePreview()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        _binding?.let { outState.putInt(STATE_CUOTA_INDEX, chipsCuota.indexOfFirst { it.isChecked }) }
    }

    private fun crearChipsCuota() {
        chipsCuota = ImportesPredefinidos.CUOTAS.mapIndexed { index, cuota ->
            (layoutInflater.inflate(R.layout.chip_importe, binding.chipGroupCuotas, false) as Chip).apply {
                id = View.generateViewId()
                text = cuota.toEurosCompacto()
                tag = cuota
                binding.chipGroupCuotas.addView(this, index)
            }
        }
    }

    private fun setupListeners() {
        binding.chipGroupCuotas.setOnCheckedStateChangeListener { _, checkedIds ->
            HapticUtils.performClick(binding.chipGroupCuotas)
            binding.tilCustomAmount.isVisible = R.id.chipOtro in checkedIds
            updateLivePreview()
        }

        binding.chipGroupDias.setOnCheckedStateChangeListener { _, _ ->
            HapticUtils.performClick(binding.chipGroupDias)
        }

        binding.switchSocioHechoAdd.setOnCheckedChangeListener { viewSwitch, _ ->
            HapticUtils.performToggle(viewSwitch)
            updateLivePreview()
        }

        binding.etCustomAmount.doOnTextChanged { _, _, _, _ ->
            binding.tilCustomAmount.error = null
            updateLivePreview()
        }
    }

    private fun updateLivePreview() {
        val cuota = getSelectedCuota() ?: 0.0
        val baseX2 = CalculoComisiones.calcularBaseX2(cuota)

        binding.tvPreviewBaseX2.text =
            getString(R.string.preview_base_format, baseX2.toFormattedEuros(2), cuota.toEurosCompacto())

        val context = requireContext()
        if (binding.switchSocioHechoAdd.isChecked) {
            binding.tvPreviewEstadoBadge.text = getString(R.string.socio_status_badge_conseguido)
            binding.tvPreviewEstadoBadge.setTextColor(ContextCompat.getColor(context, R.color.primary))
        } else {
            binding.tvPreviewEstadoBadge.text = getString(R.string.socio_status_badge_pendiente)
            binding.tvPreviewEstadoBadge.setTextColor(ContextCompat.getColor(context, R.color.orange_pending))
        }
        binding.tvPreviewEstadoBadge.backgroundTintList = ContextCompat.getColorStateList(context, R.color.bg_light)
    }

    /** Selecciona el chip con el importe exacto; cualquier otro importe (p. ej. 10,50 €) va a "Otro". */
    private fun selectCuota(amount: Double) {
        val chip = chipsCuota.firstOrNull { it.tag == amount }
        if (chip != null) {
            chip.isChecked = true
        } else {
            binding.chipOtro.isChecked = true
            binding.etCustomAmount.setText(amount.toImporteInput())
        }
    }

    private fun getSelectedCuota(): Double? {
        if (binding.chipOtro.isChecked) return parseImporte(binding.etCustomAmount.text)
        return chipsCuota.firstOrNull { it.isChecked }?.tag as? Double
    }

    private fun saveSocio() {
        val cuota = getSelectedCuota()
        if (cuota == null || cuota <= 0) {
            binding.tilCustomAmount.isVisible = true
            binding.chipOtro.isChecked = true
            binding.tilCustomAmount.error = getString(R.string.msg_cuota_invalida)
            return
        }

        val dia = chipsDia.entries.firstOrNull { it.value.isChecked }?.key ?: defaultDia
        val nombre = binding.etNombreSocio.text?.toString()?.trim().orEmpty()
        val notas = binding.etNotas.text?.toString()?.trim().orEmpty()
        val esHecho = binding.switchSocioHechoAdd.isChecked

        val original = socioToEdit
        val socio = original?.copy(
            colaboracion = cuota,
            hecho = esHecho,
            dia = dia,
            nombreSocio = nombre,
            notas = notas
        ) ?: Socio(
            colaboracion = cuota,
            hecho = esHecho,
            dia = dia,
            semanaKey = semanaKey,
            nombreSocio = nombre,
            notas = notas
        )

        viewModel.guardarSocio(socio, esNuevo = original == null)
        dismiss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "AddSocioDialog"

        private const val ARG_SOCIO = "socio"
        private const val ARG_SEMANA = "semanaKey"
        private const val ARG_DIA = "diaId"
        private const val STATE_CUOTA_INDEX = "cuotaIndex"

        fun nuevo(semanaKey: String, dia: DiaSemana) = AddSocioBottomSheetDialog().apply {
            arguments = Bundle().apply {
                putString(ARG_SEMANA, semanaKey)
                putInt(ARG_DIA, dia.id)
            }
        }

        fun editar(socio: Socio) = AddSocioBottomSheetDialog().apply {
            arguments = Bundle().apply {
                putString(ARG_SEMANA, socio.semanaKey)
                putInt(ARG_DIA, socio.dia.id)
                putBundle(ARG_SOCIO, socio.toBundle())
            }
        }

        private fun Socio.toBundle() = Bundle().apply {
            putString("id", id)
            putDouble("colaboracion", colaboracion)
            putBoolean("hecho", hecho)
            putInt("diaId", dia.id)
            putString("semanaKey", semanaKey)
            putString("nombreSocio", nombreSocio)
            putString("notas", notas)
            putLong("timestamp", timestamp)
        }

        private fun Bundle.toSocio() = Socio(
            id = requireNotNull(getString("id")),
            colaboracion = getDouble("colaboracion"),
            hecho = getBoolean("hecho"),
            dia = DiaSemana.fromId(getInt("diaId")),
            semanaKey = requireNotNull(getString("semanaKey")),
            nombreSocio = getString("nombreSocio").orEmpty(),
            notas = getString("notas").orEmpty(),
            timestamp = getLong("timestamp")
        )
    }
}
