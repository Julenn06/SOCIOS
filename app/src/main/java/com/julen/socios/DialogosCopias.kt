package com.julen.socios

import android.content.Context
import android.view.LayoutInflater
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.PluralsRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.julen.socios.databinding.DialogoConfirmarImportBinding
import com.julen.socios.databinding.DialogoDestinoExportBinding
import com.julen.socios.databinding.ItemResumenImportBinding
import com.julen.socios.export.ExportFormat
import com.julen.socios.model.ImportPreview

/**
 * Pregunta si compartir la exportación en [formato] con otra app o guardarla en un archivo. [alCerrar] se
 * llama al cerrarse de cualquier forma, también tras elegir.
 */
fun preguntarDestinoExport(
    context: Context,
    formato: ExportFormat,
    alCompartir: () -> Unit,
    alGuardar: () -> Unit,
    alCerrar: () -> Unit
): AlertDialog {
    val vista = DialogoDestinoExportBinding.inflate(LayoutInflater.from(context))
    vista.opcionCompartir.configurar(
        R.drawable.ic_share, R.color.blue_container, R.color.blue_text_soft,
        R.string.export_destino_compartir, R.string.export_destino_compartir_detalle, alCompartir
    )
    vista.opcionGuardar.configurar(
        R.drawable.ic_import, R.color.green_container, R.color.green_text_soft,
        R.string.export_destino_guardar, R.string.export_destino_guardar_detalle, alGuardar
    )
    return MaterialAlertDialogBuilder(context)
        .setTitle(context.getString(R.string.export_destino_titulo, formato.name))
        .setView(vista.root)
        .setNegativeButton(R.string.action_cancel, null)
        .setOnDismissListener { alCerrar() }
        .show()
}

/**
 * Resumen de lo que se va a importar (socios nuevos y sobrescritos, bonus, IRPF y lo cobrado) antes de
 * guardarlo. [alCerrar] se llama al cerrarse de cualquier forma, también tras confirmar.
 */
fun confirmarImportacion(
    context: Context,
    preview: ImportPreview,
    alConfirmar: () -> Unit,
    alCerrar: () -> Unit
): AlertDialog {
    val inflater = LayoutInflater.from(context)
    val vista = DialogoConfirmarImportBinding.inflate(inflater)
    listOf(
        FilaImport(R.plurals.import_linea_nuevos, preview.sociosNuevos, R.drawable.ic_person, R.color.green_container, R.color.green_text_soft),
        FilaImport(R.plurals.import_linea_sobrescritos, preview.sociosSobrescritos, R.drawable.ic_edit, R.color.orange_container, R.color.orange_text),
        FilaImport(R.plurals.import_linea_bonus, preview.bonos, R.drawable.ic_gift, R.color.amber_container, R.color.amber_text),
        FilaImport(R.plurals.import_linea_irpf, preview.irpfVigencias, R.drawable.ic_receipt, R.color.blue_container, R.color.blue_text_soft),
        FilaImport(R.plurals.import_linea_cobros, preview.cobros, R.drawable.ic_cobro, R.color.green_container, R.color.green_text_soft)
    ).forEach { fila ->
        val linea = ItemResumenImportBinding.inflate(inflater, vista.filasImport, true)
        linea.placaResumen.backgroundTintList = ContextCompat.getColorStateList(context, fila.fondo)
        linea.ivResumen.setImageResource(fila.icono)
        linea.ivResumen.imageTintList = ContextCompat.getColorStateList(context, fila.color)
        linea.tvResumen.text = context.resources.getQuantityString(fila.plural, fila.cantidad, fila.cantidad)
    }
    vista.tvDescartadosImport.isVisible = preview.descartados > 0
    vista.tvDescartadosImport.text =
        context.resources.getQuantityString(R.plurals.import_descartados, preview.descartados, preview.descartados)
    return MaterialAlertDialogBuilder(context)
        .setTitle(R.string.import_confirmar_titulo)
        .setView(vista.root)
        .setPositiveButton(R.string.action_importar) { _, _ -> alConfirmar() }
        .setNegativeButton(R.string.action_cancel, null)
        .setOnDismissListener { alCerrar() }
        .show()
}

/** Una línea del resumen de importación. */
private class FilaImport(
    @param:PluralsRes val plural: Int,
    val cantidad: Int,
    @param:DrawableRes val icono: Int,
    @param:ColorRes val fondo: Int,
    @param:ColorRes val color: Int
)
