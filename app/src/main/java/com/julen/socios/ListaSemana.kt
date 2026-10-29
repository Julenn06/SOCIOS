package com.julen.socios

import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.julen.socios.databinding.ActivityMainBinding
import com.julen.socios.model.DiaSemana
import com.julen.socios.model.Socio
import com.julen.socios.util.Movimiento

/** La lista de socios de la pantalla principal (de la semana o del día elegido), con su título y el estado vacío. */
class ListaSemana(
    private val binding: ActivityMainBinding,
    alCambiarHecho: (Socio, Boolean) -> Unit,
    alEditar: (Socio) -> Unit,
    alEliminar: (Socio) -> Unit
) {
    private val resources = binding.root.resources
    private val adapter = SocioAdapter(onCambiarHecho = alCambiarHecho, onEdit = alEditar, onDelete = alEliminar)

    /** La semana y el día que se pintaron la última vez, para animar solo lo que cambia. */
    private var vistaAnterior: Pair<String, DiaSemana?>? = null

    init {
        binding.rvSocios.layoutManager = LinearLayoutManager(binding.root.context)
        binding.rvSocios.adapter = adapter
    }

    fun mostrar(state: MainUiState) {
        val alTenerLaLista = prepararCambioDeVista(state)

        // Con todos los días, agrupada por día; con un día elegido, sin cabecera.
        adapter.submitList(
            if (state.diaFiltro == null) {
                FilaSocio.agrupadasPorDia(state.sociosFiltrados)
            } else {
                FilaSocio.sinAgrupar(state.sociosFiltrados.sortedBy { it.timestamp })
            }
        ) { alTenerLaLista?.invoke() }
        binding.containerEmptyState.isVisible = state.sociosFiltrados.isEmpty()
        binding.rvSocios.isVisible = state.sociosFiltrados.isNotEmpty()

        binding.tvListaTitulo.text = state.diaFiltro
            ?.let { resources.getString(R.string.title_socios_day_format, it.nombreCompleto) }
            ?: resources.getString(R.string.title_socios_week)
        binding.tvListaResumenDia.text = resources.getString(
            R.string.list_summary_day_format, state.sociosFiltrados.count { it.hecho }, state.sociosFiltrados.size
        )
    }

    /**
     * Al cambiar de día, los socios entran uno tras otro, cuando la lista nueva ya está puesta (se devuelve lo
     * que hay que hacer entonces). Al cambiar de semana no se anima la página: solo los importes, que cuentan
     * hasta su valor, y las filas, con la animación normal de la lista. Moverla entera daba un parpadeo.
     * La primera vez y al recrearse la pantalla no se anima nada.
     */
    private fun prepararCambioDeVista(state: MainUiState): (() -> Unit)? {
        val anterior = vistaAnterior
        vistaAnterior = state.semanaKey to state.diaFiltro
        if (anterior == null || !Movimiento.activas()) return null
        val (semanaAnterior, diaAnterior) = anterior
        if (semanaAnterior != state.semanaKey || diaAnterior == state.diaFiltro) return null
        sinAnimarFilas()
        return { binding.rvSocios.scheduleLayoutAnimation() }
    }

    /**
     * Quita un momento las animaciones de cada fila (desvanecer las que se van, hacer aparecer las nuevas):
     * al cambiar de día entran todas una tras otra, y las dos animaciones juntas parpadeaban.
     */
    private fun sinAnimarFilas() {
        val lista = binding.rvSocios
        val animador = lista.itemAnimator ?: return
        lista.itemAnimator = null
        lista.doOnNextLayout { lista.post { lista.itemAnimator = animador } }
    }
}
