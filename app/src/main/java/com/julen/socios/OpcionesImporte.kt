package com.julen.socios

import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout
import com.julen.socios.util.CalculoComisiones
import com.julen.socios.util.parseImporte
import com.julen.socios.util.toImporteInput

/**
 * Los importes predefinidos de un formulario (cuotas o bonus) y, al final, "Otro", con su [campo] para
 * escribirlo. Los crea [PanelInferior.crearOpcionesImporte].
 */
class OpcionesImporte(private val botones: List<MaterialButton>, private val campo: TextInputLayout) {

    val otro: MaterialButton get() = botones.last()

    /** Posición del importe marcado, o -1. Los botones se crean en código con ids nuevos: su selección se guarda a mano. */
    val indiceMarcado: Int get() = botones.indexOfFirst { it.isChecked }

    /** Marca un solo importe; volver a pulsar el elegido lo deja marcado. */
    fun marcar(elegido: MaterialButton) = botones.forEach { it.isChecked = it == elegido }

    fun restaurar(indice: Int) {
        botones.getOrNull(indice)?.let(::marcar)
    }

    /** Muestra el campo solo si está elegido "Otro". */
    fun ajustarCampo() {
        campo.isVisible = otro.isChecked
    }

    /** Marca el importe exacto; cualquier otro (p. ej. 10,50 €) va a "Otro" con su importe. */
    fun elegir(importe: Double) {
        val opcion = botones.firstOrNull { it.tag == importe }
        if (opcion != null) {
            marcar(opcion)
        } else {
            marcar(otro)
            campo.editText?.setText(importe.toImporteInput())
        }
    }

    /** El importe en euros; el escrito a mano, redondeado a céntimos (10,555 → 10,56). */
    fun elegido(): Double? {
        if (otro.isChecked) return parseImporte(campo.editText?.text)?.let(CalculoComisiones::redondearCentimos)
        return botones.firstOrNull { it.isChecked }?.tag as? Double
    }
}
