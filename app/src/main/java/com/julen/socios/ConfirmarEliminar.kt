package com.julen.socios

import android.content.Context
import android.content.DialogInterface
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.julen.socios.model.Socio
import com.julen.socios.util.nombreVisible

/** Pide confirmación antes de borrar un socio; después aún se puede deshacer desde el aviso. */
fun confirmarEliminarSocio(context: Context, socio: Socio, alConfirmar: () -> Unit): AlertDialog {
    val nombre = socio.nombreVisible().resolver(context)
    val icono = ContextCompat.getDrawable(context, R.drawable.ic_delete)?.mutate()?.apply {
        setTint(ContextCompat.getColor(context, R.color.red_text))
    }
    val dialogo = MaterialAlertDialogBuilder(context)
        .setIcon(icono)
        .setTitle(context.getString(R.string.eliminar_socio_titulo, nombre))
        .setMessage(R.string.eliminar_socio_mensaje)
        .setPositiveButton(R.string.action_delete) { _, _ -> alConfirmar() }
        .setNegativeButton(R.string.action_cancel, null)
        .show()
    dialogo.getButton(DialogInterface.BUTTON_POSITIVE).setTextColor(ContextCompat.getColor(context, R.color.red_text))
    return dialogo
}
