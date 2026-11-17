package com.julen.socios

import android.os.Bundle
import android.view.View
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.julen.socios.databinding.BottomSheetExportImportBinding
import com.julen.socios.databinding.ItemAlcanceBinding
import com.julen.socios.export.AlcanceExport
import com.julen.socios.export.ExportFormat
import com.julen.socios.export.ImportFormat
import com.julen.socios.util.AnimationExtensions
import com.julen.socios.util.DateUtils
import com.julen.socios.util.HapticUtils
import com.julen.socios.util.Movimiento

/** Copias y exportación: exporta a PDF, CSV o JSON lo que se elija, o importa una copia. */
class ExportImportBottomSheetDialog :
    PanelInferior<BottomSheetExportImportBinding>(BottomSheetExportImportBinding::inflate) {

    private enum class Alcance(@param:DrawableRes val icono: Int, @param:StringRes val nombre: Int) {
        SEMANA(R.drawable.ic_today, R.string.export_alcance_semana),
        ELEGIR(R.drawable.ic_date_range, R.string.export_alcance_elegir),
        ANIO(R.drawable.ic_calendar_year, R.string.export_alcance_anio),
        TODO(R.drawable.ic_history, R.string.export_alcance_historico)
    }

    private val viewModel: MainViewModel by activityViewModels { MainViewModel.Factory }

    private val tarjetasAlcance: Map<Alcance, ItemAlcanceBinding> by lazy {
        mapOf(
            Alcance.SEMANA to binding.alcanceSemana,
            Alcance.ELEGIR to binding.alcanceElegir,
            Alcance.ANIO to binding.alcanceAnio,
            Alcance.TODO to binding.alcanceTodo
        )
    }

    private var alcance = Alcance.SEMANA
    private var semanasDisponibles: List<String> = emptyList()

    /** null hasta que se conocen las semanas disponibles y se elige la selección inicial. */
    private var semanasSeleccionadas: MutableSet<String>? = null

    /** null hasta que se conocen las semanas disponibles y se elige el año inicial. */
    private var anioSeleccionado: Int? = null

    /** La lista para elegir semanas (o el año) mientras está abierta: una sola a la vez, y se cierra con el panel. */
    private var dialogoSemanas: AlertDialog? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        semanasSeleccionadas = savedInstanceState?.getStringArrayList(STATE_SEMANAS)?.toMutableSet()
        anioSeleccionado = savedInstanceState?.getInt(STATE_ANIO)?.takeIf { it > 0 }
        alcance = savedInstanceState?.getString(STATE_ALCANCE)?.let(Alcance::valueOf) ?: Alcance.SEMANA

        binding.btnCerrar.setOnClickListener { dismiss() }
        binding.toggleModo.addOnButtonCheckedListener { _, id, marcado ->
            if (!marcado) return@addOnButtonCheckedListener
            HapticUtils.performClick(binding.toggleModo)
            mostrarModo(importar = id == R.id.btnModoImportar, animar = true)
        }

        configurarAlcances()

        binding.formatoPdf.configurar(
            R.drawable.ic_pdf, Tono.ROJO, R.string.export_pdf, R.string.export_pdf_detalle
        ) { exportar(ExportFormat.PDF) }
        binding.formatoCsv.configurar(
            R.drawable.ic_table, Tono.VERDE, R.string.export_csv, R.string.export_csv_detalle
        ) { exportar(ExportFormat.CSV) }
        binding.formatoJson.configurar(
            R.drawable.ic_data_object, Tono.AZUL, R.string.export_json, R.string.export_json_detalle
        ) { exportar(ExportFormat.JSON) }
        binding.importarJson.configurar(
            R.drawable.ic_data_object, Tono.AZUL, R.string.import_json, R.string.import_json_detalle
        ) { importar(ImportFormat.JSON) }
        binding.importarCsv.configurar(
            R.drawable.ic_table, Tono.VERDE, R.string.import_csv, R.string.import_csv_detalle
        ) { importar(ImportFormat.CSV) }

        mientrasSeVea {
            viewModel.semanasDisponibles.collect { semanas ->
                semanasDisponibles = semanas
                if (semanasSeleccionadas == null && semanas.isNotEmpty()) {
                    // Por defecto, la semana visible si tiene datos; si no, la más reciente.
                    val activa = viewModel.uiState.value.semanaKey
                    semanasSeleccionadas = mutableSetOf(if (activa in semanas) activa else semanas.first())
                }
                if (anioSeleccionado == null && semanas.isNotEmpty()) {
                    // El año de la semana visible si tiene datos; si no, el más reciente.
                    val anios = aniosDisponibles()
                    anioSeleccionado = anioDe(viewModel.uiState.value.semanaKey)?.takeIf { it in anios } ?: anios.firstOrNull()
                }
                actualizarDetallesAlcance()
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
        anioSeleccionado?.let { outState.putInt(STATE_ANIO, it) }
    }

    /**
     * Exportar e importar están uno encima del otro (ver el layout): el que entra aparece mientras el que sale
     * se desvanece, con un pequeño desplazamiento hacia el lado de su pestaña. Nunca queda el panel vacío.
     */
    private fun mostrarModo(importar: Boolean, animar: Boolean = false) {
        val entra = if (importar) binding.contenedorImportar else binding.contenedorExportar
        val sale = if (importar) binding.contenedorExportar else binding.contenedorImportar
        entra.animate().cancel()
        sale.animate().cancel()
        // Delante el que entra, para que reciba los toques aunque el otro aún se esté desvaneciendo.
        entra.bringToFront()
        entra.isVisible = true
        if (!animar) {
            entra.alpha = 1f
            entra.translationX = 0f
            sale.isInvisible = true
            return
        }
        val desplazamiento = DESPLAZAMIENTO_DP * resources.displayMetrics.density * (if (importar) 1 else -1)
        entra.alpha = 0f
        entra.translationX = desplazamiento
        val curva = Movimiento.curva(requireContext())
        entra.animate().alpha(1f).translationX(0f).setDuration(Movimiento.MEDIA_MS).setInterpolator(curva).start()
        sale.animate().alpha(0f).translationX(-desplazamiento).setDuration(Movimiento.MEDIA_MS).setInterpolator(curva)
            .withEndAction {
                sale.isInvisible = true
                sale.alpha = 1f
                sale.translationX = 0f
            }.start()
    }

    private fun configurarAlcances() {
        for ((opcion, tarjeta) in tarjetasAlcance) {
            tarjeta.ivAlcance.setImageResource(opcion.icono)
            tarjeta.tvAlcance.setText(opcion.nombre)
            tarjeta.root.setOnClickListener {
                HapticUtils.performClick(it)
                elegirAlcance(opcion)
                when (opcion) {
                    Alcance.ELEGIR -> mostrarDialogoSeleccionarSemanas()
                    Alcance.ANIO -> mostrarDialogoSeleccionarAnio()
                    else -> Unit
                }
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
        binding.alcanceAnio.tvAlcanceDetalle.text =
            anioSeleccionado?.toString() ?: getString(R.string.export_alcance_elegir_vacio)
        binding.alcanceTodo.tvAlcanceDetalle.text =
            resources.getQuantityString(R.plurals.export_semanas, semanasDisponibles.size, semanasDisponibles.size)
    }

    override fun onDestroyView() {
        dialogoSemanas?.dismiss()
        dialogoSemanas = null
        super.onDestroyView()
    }

    private fun mostrarDialogoSeleccionarSemanas() {
        if (dialogoSemanas?.isShowing == true) return
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

        dialogoSemanas = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.export_dialogo_semanas_titulo)
            .setMultiChoiceItems(itemsText, checkedItems) { _, which, isChecked ->
                if (isChecked) seleccion.add(semanas[which]) else seleccion.remove(semanas[which])
                actualizarDetallesAlcance()
            }
            .setPositiveButton(R.string.action_accept, null)
            .setOnDismissListener { dialogoSemanas = null }
            .show()
    }

    /** Los años con algo apuntado, del más reciente al más antiguo. */
    private fun aniosDisponibles(): List<Int> = semanasDisponibles.mapNotNull(::anioDe).distinct().sortedDescending()

    /** El año de una semana: el de su clave ("2026-W01" → 2026), que es el de su jueves. */
    private fun anioDe(semanaKey: String): Int? = semanaKey.substringBefore("-W").toIntOrNull()

    private fun mostrarDialogoSeleccionarAnio() {
        if (dialogoSemanas?.isShowing == true) return
        val anios = aniosDisponibles()
        if (anios.isEmpty()) {
            Avisos.mostrar(binding.root, getString(R.string.msg_sin_semanas), TipoAviso.INFO)
            return
        }
        dialogoSemanas = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.export_dialogo_anio_titulo)
            .setSingleChoiceItems(anios.map(Int::toString).toTypedArray(), anios.indexOf(anioSeleccionado)) { dialogo, cual ->
                anioSeleccionado = anios[cual]
                actualizarDetallesAlcance()
                dialogo.dismiss()
            }
            .setOnDismissListener { dialogoSemanas = null }
            .show()
    }

    private fun alcanceSeleccionado(): AlcanceExport = when (alcance) {
        Alcance.TODO -> AlcanceExport.Historico
        Alcance.ANIO -> AlcanceExport.Anio(checkNotNull(anioSeleccionado))
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
        if (alcance == Alcance.ANIO && anioSeleccionado == null) {
            mostrarDialogoSeleccionarAnio()
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
        private const val STATE_ANIO = "anio"
        private const val DESPLAZAMIENTO_DP = 24f
    }
}
