package com.julen.socios

import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import com.julen.socios.databinding.BottomSheetImportarBinding
import com.julen.socios.databinding.ItemResumenImportBinding
import com.julen.socios.model.ImportPreview
import com.julen.socios.util.AnimationExtensions
import com.julen.socios.util.DateUtils
import com.julen.socios.util.HapticUtils
import com.julen.socios.util.LOCALE_ES

/**
 * Revisión de una importación antes de guardarla: el archivo, cuánto trae, qué cambia en la app y qué se ignora.
 * Muestra la que el ViewModel tiene pendiente y se cierra sola cuando deja de haberla: al confirmar, al cancelar
 * o si la app se cerró mientras estaba abierta (lo leído solo se guarda en memoria).
 */
class ImportarBottomSheetDialog : PanelInferior<BottomSheetImportarBinding>(BottomSheetImportarBinding::inflate) {

    private val viewModel: MainViewModel by activityViewModels { MainViewModel.Factory }

    /** Una línea de lo que cambia: qué es, qué le pasará al importar y cuántos son. */
    private class Linea(
        @param:StringRes val titulo: Int,
        @param:StringRes val detalle: Int,
        val cantidad: Int,
        @param:DrawableRes val icono: Int,
        val tono: Tono
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnCerrarImport.setOnClickListener { viewModel.cancelarImport() }
        binding.btnCancelarImport.setOnClickListener {
            HapticUtils.performClick(it)
            viewModel.cancelarImport()
        }
        binding.btnConfirmarImport.setOnClickListener {
            HapticUtils.performConfirm(it)
            viewModel.confirmarImport()
        }
        prepararBotonesInferiores(binding.barraBotonesImport, binding.btnCancelarImport, binding.btnConfirmarImport)
        listOf(binding.btnCancelarImport, binding.btnConfirmarImport).forEach(AnimationExtensions::setupPressScaleAnimation)

        mientrasSeVea {
            viewModel.importPendiente.collect { preview -> if (preview == null) dismiss() else mostrar(preview) }
        }
    }

    /** Deslizarlo hacia abajo, tocar fuera o el botón de atrás también es cancelar. */
    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        viewModel.cancelarImport()
    }

    private fun mostrar(p: ImportPreview) {
        binding.tvArchivoImport.text = p.nombreArchivo ?: getString(R.string.import_archivo_sin_nombre)

        // Lo que más importa es cuántos socios entran; una copia sin socios (solo bonus o lo cobrado) dice cuántos registros.
        val registros = p.bonos + p.irpfVigencias + p.cobros
        binding.tvTotalImport.text = if (p.socios > 0 || registros == 0) {
            resources.getQuantityString(R.plurals.import_total_socios, p.socios, cantidad(p.socios))
        } else {
            resources.getQuantityString(R.plurals.import_total_registros, registros, cantidad(registros))
        }
        binding.tvSemanasImport.isVisible = p.semanas > 0
        binding.tvSemanasImport.text = resources.getQuantityString(R.plurals.global_history_count, p.semanas, p.semanas)
        val rango = rango(p)
        binding.tvRangoImport.isVisible = rango != null
        binding.tvRangoImport.text = rango
        binding.tvFechaCopiaImport.isVisible = p.fechaCopia != null
        binding.tvFechaCopiaImport.text = p.fechaCopia?.let { getString(R.string.import_copia_hecha, DateUtils.momentoRelativo(it)) }

        mostrarLineas(
            listOf(
                Linea(R.string.import_fila_nuevos, R.string.import_fila_nuevos_detalle, p.sociosNuevos, R.drawable.ic_person, Tono.VERDE),
                Linea(
                    R.string.import_fila_sustituidos, R.string.import_fila_sustituidos_detalle, p.sociosSobrescritos,
                    R.drawable.ic_edit, Tono.NARANJA
                ),
                Linea(R.string.import_fila_bonus, R.string.import_fila_bonus_detalle, p.bonos, R.drawable.ic_gift, Tono.AMBAR),
                Linea(R.string.import_fila_irpf, R.string.import_fila_irpf_detalle, p.irpfVigencias, R.drawable.ic_receipt, Tono.AZUL),
                Linea(R.string.import_fila_cobros, R.string.import_fila_cobros_detalle, p.cobros, R.drawable.ic_cobro, Tono.VERDE)
            ).filter { it.cantidad > 0 }
        )

        binding.tarjetaDescartadosImport.isVisible = p.descartados > 0
        binding.tvDescartadosImport.text =
            resources.getQuantityString(R.plurals.import_descartados, p.descartados, p.descartados)
    }

    private fun mostrarLineas(lineas: List<Linea>) {
        binding.filasImport.removeAllViews()
        lineas.forEachIndexed { i, linea ->
            val fila = ItemResumenImportBinding.inflate(layoutInflater, binding.filasImport, true)
            fila.separadorResumen.isVisible = i > 0
            pintarPlaca(fila.placaResumen, fila.ivResumen, linea.icono, linea.tono)
            fila.tvResumen.setText(linea.titulo)
            fila.tvResumenDetalle.setText(linea.detalle)
            fila.tvResumenCantidad.text = cantidad(linea.cantidad)
        }
    }

    /** "ene 2025 – oct 2026", o un solo mes si todo es del mismo; null si el archivo no trae semanas. */
    private fun rango(p: ImportPreview): String? {
        val desde = p.primeraSemana?.let(DateUtils::mesConAnio) ?: return null
        val hasta = p.ultimaSemana?.let(DateUtils::mesConAnio) ?: desde
        return if (desde == hasta) desde else getString(R.string.import_rango, desde, hasta)
    }

    /** Con separador de miles: "1.250". */
    private fun cantidad(n: Int): String = String.format(LOCALE_ES, "%,d", n)

    companion object {
        const val TAG = "ImportarDialog"
    }
}
