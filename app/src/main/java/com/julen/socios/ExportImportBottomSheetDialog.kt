package com.julen.socios

import android.app.Dialog
import android.os.Bundle
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.julen.socios.databinding.BottomSheetExportImportBinding
import com.julen.socios.databinding.ItemAlcanceBinding
import com.julen.socios.export.AlcanceExport
import com.julen.socios.export.ExportFormat
import com.julen.socios.export.ImportFormat
import com.julen.socios.util.AnimationExtensions
import com.julen.socios.util.DateUtils
import com.julen.socios.util.HapticUtils
import kotlinx.coroutines.launch

/** Copias y exportación: exporta a PDF, CSV o JSON lo que se elija, o importa una copia. */
class ExportImportBottomSheetDialog : BottomSheetDialogFragment() {

    private enum class Alcance { SEMANA, ELEGIR, TODO }

    private val viewModel: MainViewModel by activityViewModels { MainViewModel.Factory }

    private var _binding: BottomSheetExportImportBinding? = null
    private val binding get() = _binding!!

    private val tarjetasAlcance: Map<Alcance, ItemAlcanceBinding> by lazy {
        mapOf(Alcance.SEMANA to binding.alcanceSemana, Alcance.ELEGIR to binding.alcanceElegir, Alcance.TODO to binding.alcanceTodo)
    }

    private var alcance = Alcance.SEMANA
    private var semanasDisponibles: List<String> = emptyList()

    /** null hasta que se conocen las semanas disponibles y se elige la selección inicial. */
    private var semanasSeleccionadas: MutableSet<String>? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        (super.onCreateDialog(savedInstanceState) as BottomSheetDialog).apply {
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetExportImportBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        semanasSeleccionadas = savedInstanceState?.getStringArrayList(STATE_SEMANAS)?.toMutableSet()
        alcance = savedInstanceState?.getString(STATE_ALCANCE)?.let(Alcance::valueOf) ?: Alcance.SEMANA

        binding.btnCerrar.setOnClickListener { dismiss() }
        binding.toggleModo.addOnButtonCheckedListener { _, id, marcado ->
            if (!marcado) return@addOnButtonCheckedListener
            HapticUtils.performClick(binding.toggleModo)
            TransitionManager.beginDelayedTransition(binding.contenidoExportImport, AutoTransition().setDuration(DURACION_MS))
            mostrarModo(importar = id == R.id.btnModoImportar)
        }

        configurarAlcances()

        binding.formatoPdf.configurar(
            R.drawable.ic_pdf, R.color.red_container, R.color.red_text, R.string.export_pdf, R.string.export_pdf_detalle
        ) { exportar(ExportFormat.PDF) }
        binding.formatoCsv.configurar(
            R.drawable.ic_table, R.color.green_container, R.color.green_text_soft, R.string.export_csv, R.string.export_csv_detalle
        ) { exportar(ExportFormat.CSV) }
        binding.formatoJson.configurar(
            R.drawable.ic_data_object, R.color.blue_container, R.color.blue_text_soft, R.string.export_json, R.string.export_json_detalle
        ) { exportar(ExportFormat.JSON) }
        binding.importarJson.configurar(
            R.drawable.ic_data_object, R.color.blue_container, R.color.blue_text_soft, R.string.import_json, R.string.import_json_detalle
        ) { importar(ImportFormat.JSON) }
        binding.importarCsv.configurar(
            R.drawable.ic_table, R.color.green_container, R.color.green_text_soft, R.string.import_csv, R.string.import_csv_detalle
        ) { importar(ImportFormat.CSV) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.semanasDisponibles.collect { semanas ->
                    semanasDisponibles = semanas
                    if (semanasSeleccionadas == null && semanas.isNotEmpty()) {
                        // Por defecto, la semana visible si tiene datos; si no, la más reciente.
                        val activa = viewModel.uiState.value.semanaKey
                        semanasSeleccionadas = mutableSetOf(if (activa in semanas) activa else semanas.first())
                    }
                    actualizarDetallesAlcance()
                }
            }
        }
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        // La pestaña la restaura el propio botón; los paneles se ajustan a ella sin animar.
        mostrarModo(importar = binding.toggleModo.checkedButtonId == R.id.btnModoImportar)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        semanasSeleccionadas?.let { outState.putStringArrayList(STATE_SEMANAS, ArrayList(it)) }
        outState.putString(STATE_ALCANCE, alcance.name)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun mostrarModo(importar: Boolean) {
        binding.contenedorExportar.isVisible = !importar
        binding.contenedorImportar.isVisible = importar
    }

    private fun configurarAlcances() {
        val nombres = mapOf(
            Alcance.SEMANA to (R.drawable.ic_today to R.string.export_alcance_semana),
            Alcance.ELEGIR to (R.drawable.ic_date_range to R.string.export_alcance_elegir),
            Alcance.TODO to (R.drawable.ic_history to R.string.export_alcance_historico)
        )
        for ((opcion, tarjeta) in tarjetasAlcance) {
            val (icono, nombre) = nombres.getValue(opcion)
            tarjeta.ivAlcance.setImageResource(icono)
            tarjeta.tvAlcance.setText(nombre)
            tarjeta.root.setOnClickListener {
                HapticUtils.performClick(it)
                elegirAlcance(opcion)
                if (opcion == Alcance.ELEGIR) mostrarDialogoSeleccionarSemanas()
            }
            AnimationExtensions.setupPressScaleAnimation(tarjeta.root)
        }
        elegirAlcance(alcance)
        actualizarDetallesAlcance()
    }

    private fun elegirAlcance(opcion: Alcance) {
        alcance = opcion
        tarjetasAlcance.forEach { (o, tarjeta) -> tarjeta.root.isChecked = o == opcion }
    }

    private fun actualizarDetallesAlcance() {
        binding.alcanceSemana.tvAlcanceDetalle.text = DateUtils.rangoCorto(viewModel.uiState.value.semanaKey)
        val seleccion = semanasSeleccionadas.orEmpty()
        binding.alcanceElegir.tvAlcanceDetalle.text = if (seleccion.isEmpty()) {
            getString(R.string.export_alcance_elegir_vacio)
        } else {
            resources.getQuantityString(R.plurals.export_semanas, seleccion.size, seleccion.size)
        }
        binding.alcanceTodo.tvAlcanceDetalle.text =
            resources.getQuantityString(R.plurals.export_semanas, semanasDisponibles.size, semanasDisponibles.size)
    }

    private fun mostrarDialogoSeleccionarSemanas() {
        val semanas = semanasDisponibles
        if (semanas.isEmpty()) {
            Avisos.mostrar(binding.root, getString(R.string.msg_sin_semanas), TipoAviso.INFO)
            return
        }
        val seleccion = semanasSeleccionadas ?: mutableSetOf<String>().also { semanasSeleccionadas = it }

        val itemsText = semanas.map { key ->
            getString(R.string.export_semana_item, key, DateUtils.getRangoSemanaTextoFromKey(key))
        }.toTypedArray()
        val checkedItems = BooleanArray(semanas.size) { i -> semanas[i] in seleccion }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.export_dialogo_semanas_titulo)
            .setMultiChoiceItems(itemsText, checkedItems) { _, which, isChecked ->
                if (isChecked) seleccion.add(semanas[which]) else seleccion.remove(semanas[which])
                actualizarDetallesAlcance()
            }
            .setPositiveButton(R.string.action_accept, null)
            .show()
    }

    private fun alcanceSeleccionado(): AlcanceExport = when (alcance) {
        Alcance.TODO -> AlcanceExport.Historico
        Alcance.ELEGIR -> AlcanceExport.Semanas(semanasSeleccionadas.orEmpty().toSet())
        Alcance.SEMANA -> AlcanceExport.Semanas(setOf(viewModel.uiState.value.semanaKey))
    }

    private fun exportar(formato: ExportFormat) {
        // Sin semanas elegidas no se exporta otra cosa en su lugar: se pide que se elija alguna.
        if (alcance == Alcance.ELEGIR && semanasSeleccionadas.isNullOrEmpty()) {
            if (semanasDisponibles.isNotEmpty()) {
                Avisos.mostrar(binding.root, getString(R.string.msg_elige_semanas), TipoAviso.INFO)
            }
            mostrarDialogoSeleccionarSemanas()
            return
        }
        viewModel.solicitarExport(formato, alcanceSeleccionado())
        dismiss()
    }

    private fun importar(formato: ImportFormat) {
        viewModel.solicitarImport(formato)
        dismiss()
    }

    companion object {
        const val TAG = "ExportImportDialog"
        private const val STATE_SEMANAS = "semanasSeleccionadas"
        private const val STATE_ALCANCE = "alcance"
        private const val DURACION_MS = 220L
    }
}
