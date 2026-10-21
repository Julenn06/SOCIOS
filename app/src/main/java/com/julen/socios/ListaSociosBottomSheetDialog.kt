package com.julen.socios

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.julen.socios.databinding.BottomSheetListaSociosBinding
import com.julen.socios.model.Socio
import com.julen.socios.util.BusquedaSocios
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Socios de varias semanas a la vez: los pendientes de semanas anteriores o los que coinciden con una
 * búsqueda. Desde aquí se pueden marcar como conseguidos, editar o borrar, y tocar uno abre su semana.
 */
class ListaSociosBottomSheetDialog : BottomSheetDialogFragment() {

    enum class Modo { PENDIENTES, BUSCAR }

    private val viewModel: MainViewModel by activityViewModels { MainViewModel.Factory }

    private var _binding: BottomSheetListaSociosBinding? = null
    private val binding get() = _binding!!

    private val modo: Modo by lazy { Modo.valueOf(requireNotNull(requireArguments().getString(ARG_MODO))) }

    /** Lo escrito en el buscador. El EditText ya conserva el texto al recrearse; esto solo lo hace observable. */
    private val textoBusqueda = MutableStateFlow("")

    private var dialogoEliminar: AlertDialog? = null

    private val adapter by lazy {
        SocioAdapter(
            onCambiarHecho = { socio, hecho -> viewModel.setSocioHecho(socio.id, hecho) },
            onEdit = ::editar,
            onDelete = { socio ->
                if (dialogoEliminar?.isShowing != true) {
                    dialogoEliminar = confirmarEliminarSocio(requireContext(), socio) { viewModel.eliminarSocio(socio) }
                }
            },
            mostrarSemana = true,
            onAbrir = { socio ->
                viewModel.irASemana(socio.semanaKey)
                dismiss()
            }
        )
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        (super.onCreateDialog(savedInstanceState) as BottomSheetDialog).apply {
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
            setOnShowListener {
                // A pantalla completa: la lista cambia de tamaño al escribir y el panel no debe dar saltos.
                findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { hoja ->
                    hoja.layoutParams = hoja.layoutParams.apply { height = ViewGroup.LayoutParams.MATCH_PARENT }
                }
            }
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetListaSociosBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.rvListaSocios.layoutManager = LinearLayoutManager(requireContext())
        binding.rvListaSocios.adapter = adapter
        binding.btnCerrarLista.setOnClickListener { dismiss() }

        val buscando = modo == Modo.BUSCAR
        binding.tilBuscar.isVisible = buscando
        binding.placaLista.setBackgroundResource(if (buscando) R.drawable.bg_icono_degradado else R.drawable.bg_icono_ambar)
        binding.ivLista.setImageResource(if (buscando) R.drawable.ic_search else R.drawable.ic_pendientes)
        binding.tvTituloLista.setText(if (buscando) R.string.buscar_titulo else R.string.pendientes_titulo)
        binding.tvSubtituloLista.setText(if (buscando) R.string.buscar_subtitulo else R.string.pendientes_subtitulo)
        binding.tvAyudaLista.setText(if (buscando) R.string.buscar_ayuda else R.string.pendientes_ayuda)

        if (buscando) {
            textoBusqueda.value = binding.etBuscar.text?.toString().orEmpty()
            binding.etBuscar.doOnTextChanged { texto, _, _, _ -> textoBusqueda.value = texto?.toString().orEmpty() }
            if (savedInstanceState == null) mostrarTeclado()
        }

        val socios: Flow<List<Socio>> = if (buscando) {
            combine(viewModel.todosLosSocios, textoBusqueda) { todos, texto -> BusquedaSocios.filtrar(todos, texto) }
        } else {
            viewModel.pendientesAnteriores
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                socios.collect(::mostrar)
            }
        }
    }

    private fun mostrar(socios: List<Socio>) {
        adapter.submitList(FilaSocio.sinAgrupar(socios))
        val texto = textoBusqueda.value.trim()
        val hayConsulta = modo == Modo.PENDIENTES || texto.isNotEmpty()

        binding.tvResultadosLista.isVisible = modo == Modo.BUSCAR && socios.isNotEmpty()
        binding.tvResultadosLista.text = resources.getQuantityString(R.plurals.buscar_resultados, socios.size, socios.size)
        // La ayuda se ve mientras no hay nada que mostrar; con resultados, la lista tiene todo el sitio.
        binding.tvAyudaLista.isVisible = socios.isEmpty() || modo == Modo.PENDIENTES
        binding.tvVacioLista.isVisible = hayConsulta && socios.isEmpty()
        binding.tvVacioLista.text = if (modo == Modo.BUSCAR) {
            getString(R.string.buscar_sin_resultados, texto)
        } else {
            getString(R.string.pendientes_vacio)
        }
    }

    private fun editar(socio: Socio) {
        val fragmentos = parentFragmentManager
        if (fragmentos.findFragmentByTag(AddSocioBottomSheetDialog.TAG) == null) {
            AddSocioBottomSheetDialog.editar(socio).show(fragmentos, AddSocioBottomSheetDialog.TAG)
        }
    }

    private fun mostrarTeclado() {
        binding.etBuscar.requestFocus()
        val ventana = dialog?.window ?: return
        WindowCompat.getInsetsController(ventana, binding.etBuscar).show(WindowInsetsCompat.Type.ime())
    }

    override fun onDestroyView() {
        dialogoEliminar?.dismiss()
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "ListaSociosDialog"
        private const val ARG_MODO = "modo"

        fun pendientes() = nueva(Modo.PENDIENTES)

        fun buscar() = nueva(Modo.BUSCAR)

        private fun nueva(modo: Modo) = ListaSociosBottomSheetDialog().apply {
            arguments = Bundle().apply { putString(ARG_MODO, modo.name) }
        }
    }
}
