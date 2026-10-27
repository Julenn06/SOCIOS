package com.julen.socios

import android.os.Bundle
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.julen.socios.ActualizacionesViewModel.EstadoVersion
import com.julen.socios.ajustes.Tema
import com.julen.socios.databinding.BottomSheetMasBinding
import com.julen.socios.databinding.ItemTemaBinding
import com.julen.socios.util.AnimationExtensions
import com.julen.socios.util.DateUtils
import com.julen.socios.util.HapticUtils
import kotlinx.coroutines.launch

/**
 * Panel "Más", en lugar del menú ⋮: versión de la app y búsqueda de actualizaciones, acceso a copias y
 * exportación y selector de tema.
 */
class MasBottomSheetDialog : PanelInferior<BottomSheetMasBinding>(BottomSheetMasBinding::inflate) {

    private val actualizaciones: ActualizacionesViewModel by activityViewModels { ActualizacionesViewModel.Factory }

    private val preferenciasTema by lazy { (requireActivity().application as SociosApp).container.preferenciasTema }

    private val opcionesTema: Map<Tema, ItemTemaBinding> by lazy {
        mapOf(Tema.SISTEMA to binding.temaSistema, Tema.CLARO to binding.temaClaro, Tema.OSCURO to binding.temaOscuro)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.tvVersionMas.text = getString(R.string.mas_version, actualizaciones.versionInstalada)

        binding.cardExportarImportar.setOnClickListener {
            dismiss()
            parentFragmentManager.mostrarSiNoEsta(ExportImportBottomSheetDialog.TAG, ::ExportImportBottomSheetDialog)
        }
        listOf(binding.cardExportarImportar, binding.btnEstadoVersion)
            .forEach(AnimationExtensions::setupPressScaleAnimation)

        configurarTemas()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                actualizaciones.estadoVersion.collect(::mostrarEstadoVersion)
            }
        }
    }

    private fun configurarTemas() {
        val iconos = mapOf(Tema.SISTEMA to R.drawable.ic_contrast, Tema.CLARO to R.drawable.ic_light_mode, Tema.OSCURO to R.drawable.ic_dark_mode)
        val actual = preferenciasTema.tema
        val lado = (16 * resources.displayMetrics.density).toInt()
        val color = ContextCompat.getColorStateList(requireContext(), R.color.text_main)
        for ((tema, opcion) in opcionesTema) {
            opcion.muestraTema.tema = tema
            val icono = ContextCompat.getDrawable(requireContext(), iconos.getValue(tema))?.mutate()
            icono?.setBounds(0, 0, lado, lado)
            opcion.tvTema.setCompoundDrawablesRelative(icono, null, null, null)
            TextViewCompat.setCompoundDrawableTintList(opcion.tvTema, color)
            opcion.tvTema.setText(tema.nombre)
            opcion.root.contentDescription = getString(R.string.tema_a11y, getString(tema.nombre))
            opcion.root.isChecked = tema == actual
            opcion.root.setOnClickListener { elegirTema(tema) }
            AnimationExtensions.setupPressScaleAnimation(opcion.root)
        }
    }

    private fun elegirTema(tema: Tema) {
        if (tema == preferenciasTema.tema) return
        HapticUtils.performClick(binding.root)
        opcionesTema.forEach { (t, opcion) -> opcion.root.isChecked = t == tema }
        preferenciasTema.tema = tema
        // Un instante para que se vea la marca antes de que la pantalla se recree con los colores nuevos.
        binding.root.postDelayed({ if (bindingSiExiste != null) tema.aplicar() }, RETRASO_TEMA_MS)
    }

    private fun mostrarEstadoVersion(estado: EstadoVersion) {
        val b = binding
        val buscando = estado == EstadoVersion.Buscando
        b.progresoEstadoVersion.isVisible = buscando
        b.ivEstadoVersion.isVisible = !buscando

        val (icono, titulo, detalle) = when (estado) {
            EstadoVersion.SinComprobar -> Triple(
                R.drawable.ic_system_update,
                getString(R.string.version_sin_comprobar),
                actualizaciones.ultimaComprobacion.takeIf { it > 0 }
                    ?.let { getString(R.string.version_comprobada, DateUtils.momentoRelativo(it)) }
                    ?: getString(R.string.version_nunca_comprobada)
            )
            EstadoVersion.Buscando -> Triple(
                R.drawable.ic_system_update, getString(R.string.version_buscando), getString(R.string.version_buscando_detalle)
            )
            EstadoVersion.AlDia -> Triple(
                R.drawable.ic_check_circle,
                getString(R.string.version_al_dia),
                getString(R.string.version_comprobada, DateUtils.momentoRelativo(actualizaciones.ultimaComprobacion))
            )
            is EstadoVersion.Disponible -> Triple(
                R.drawable.ic_new_releases,
                getString(R.string.version_disponible, estado.publicada.version.toString()),
                getString(R.string.version_disponible_detalle)
            )
            is EstadoVersion.UltimaPublicada -> Triple(
                R.drawable.ic_system_update,
                getString(R.string.version_ultima_publicada, estado.version.toString()),
                getString(R.string.version_desarrollo)
            )
            EstadoVersion.Error -> Triple(
                R.drawable.ic_error, getString(R.string.version_error), getString(R.string.version_error_detalle)
            )
        }
        b.ivEstadoVersion.setImageResource(icono)
        b.tvEstadoVersion.text = titulo
        b.tvDetalleEstadoVersion.text = detalle

        val boton = when (estado) {
            EstadoVersion.SinComprobar, is EstadoVersion.UltimaPublicada -> R.string.action_buscar
            EstadoVersion.Error -> R.string.action_reintentar
            is EstadoVersion.Disponible -> R.string.action_actualizar
            EstadoVersion.Buscando, EstadoVersion.AlDia -> null
        }
        b.btnEstadoVersion.isVisible = boton != null
        boton?.let(b.btnEstadoVersion::setText)
        b.btnEstadoVersion.setOnClickListener {
            if (estado is EstadoVersion.Disponible) {
                val actividad = requireActivity()
                dismiss()
                if (!ActualizacionBottomSheetDialog.empezar(actividad, actualizaciones, estado.publicada)) {
                    // El panel ya se ha cerrado: el aviso va en la pantalla principal.
                    Avisos.mostrar(
                        actividad.findViewById(android.R.id.content), getString(R.string.msg_sin_navegador), TipoAviso.ERROR
                    )
                }
            } else {
                actualizaciones.buscarActualizaciones()
            }
        }
    }

    companion object {
        const val TAG = "MasBottomSheet"
        private const val RETRASO_TEMA_MS = 250L
    }
}
