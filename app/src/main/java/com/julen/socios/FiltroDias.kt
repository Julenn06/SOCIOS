package com.julen.socios

import android.view.View
import androidx.core.text.buildSpannedString
import androidx.core.text.scale
import com.google.android.material.button.MaterialButton
import com.julen.socios.databinding.ActivityMainBinding
import com.julen.socios.model.DiaSemana
import com.julen.socios.util.HapticUtils
import com.julen.socios.util.Movimiento

/**
 * Los botones de la pantalla principal para ver los socios de toda la semana o de un día, cada uno con cuántos
 * socios hay. [alElegir] recibe el día que toca el usuario (null: todos).
 */
class FiltroDias(private val binding: ActivityMainBinding, alElegir: (DiaSemana?) -> Unit) {

    private val resources = binding.root.resources
    private val botones: Map<DiaSemana, MaterialButton> = DiaSemana.entries.zip(
        listOf(binding.btnFiltroLun, binding.btnFiltroMar, binding.btnFiltroMie, binding.btnFiltroJue, binding.btnFiltroVie)
    ).toMap()

    /** Mientras se marca el botón del estado, para no tomarlo por un toque del usuario. */
    private var sincronizando = false

    init {
        // Dos líneas: el día y, debajo, cuántos socios hay (los botones de Material traen una sola).
        (botones.values + binding.btnFiltroTodos).forEach {
            it.isSingleLine = false
            it.maxLines = 2
        }
        binding.toggleFiltroDias.addOnButtonCheckedListener { grupo, id, marcado ->
            if (!marcado || sincronizando) return@addOnButtonCheckedListener
            HapticUtils.performClick(grupo)
            grupo.findViewById<View>(id)?.let { Movimiento.latido(it, hacia = 0.6f) }
            alElegir(botones.entries.firstOrNull { it.value.id == id }?.key)
        }
    }

    fun mostrar(state: MainUiState) {
        mostrarBoton(
            binding.btnFiltroTodos,
            resources.getString(R.string.filtro_todos),
            resources.getString(R.string.filtro_todos_a11y),
            state.todosSociosSemana.size
        )
        botones.forEach { (dia, boton) ->
            mostrarBoton(boton, dia.nombreCorto, dia.nombreCompleto, state.conteoPorDia[dia] ?: 0)
        }
        sincronizar(state.diaFiltro)
    }

    /** El nombre en una línea y, debajo, cuántos socios hay. */
    private fun mostrarBoton(boton: MaterialButton, nombre: String, nombreLeido: String, socios: Int) {
        boton.text = buildSpannedString {
            append(nombre)
            append('\n')
            scale(0.9f) { append(socios.toString()) }
        }
        boton.contentDescription = resources.getQuantityString(R.plurals.filtro_dia_a11y, socios, nombreLeido, socios)
    }

    private fun sincronizar(dia: DiaSemana?) {
        val id = dia?.let { botones[it]?.id } ?: R.id.btnFiltroTodos
        if (binding.toggleFiltroDias.checkedButtonId != id) {
            sincronizando = true
            binding.toggleFiltroDias.check(id)
            sincronizando = false
        }
    }
}
