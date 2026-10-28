package com.julen.socios

import android.content.Context
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.julen.socios.databinding.DialogoDestinoExportBinding
import com.julen.socios.export.ExportFormat

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
        R.drawable.ic_share, Tono.AZUL,
        R.string.export_destino_compartir, R.string.export_destino_compartir_detalle, alCompartir
    )
    vista.opcionGuardar.configurar(
        R.drawable.ic_import, Tono.VERDE,
        R.string.export_destino_guardar, R.string.export_destino_guardar_detalle, alGuardar
    )
    return MaterialAlertDialogBuilder(context)
        .setTitle(context.getString(R.string.export_destino_titulo, formato.name))
        .setView(vista.root)
        .setNegativeButton(R.string.action_cancel, null)
        .setOnDismissListener { alCerrar() }
        .show()
}
