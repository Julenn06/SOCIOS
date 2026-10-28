package com.julen.socios

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.activityViewModels
import com.julen.socios.ActualizacionesViewModel.EstadoVersion
import com.julen.socios.ajustes.Tema
import com.julen.socios.databinding.BottomSheetMasBinding
import com.julen.socios.databinding.ItemTemaBinding
import com.julen.socios.util.AnimationExtensions
import com.julen.socios.util.DateUtils
import com.julen.socios.util.HapticUtils

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

        mientrasSeVea { actualizaciones.estadoVersion.collect(::mostrarEstadoVersion) }
    }

    private fun configurarTemas() {
        val actual = preferenciasTema.tema
        val lado = (16 * resources.displayMetrics.density).toInt()
        val color = ContextCompat.getColorStateList(requireContext(), R.color.text_main)
        for ((tema, opcion) in opcionesTema) {
            opcion.muestraTema.tema = tema
            val icono = ContextCompat.getDrawable(requireContext(), tema.icono)?.mutate()
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

        // El botón está siempre: también al día (para volver a mirar) y mientras busca (desactivado), así el
        // panel no cambia de forma. Con una versión nueva se rellena de blanco para que destaque.
        val disponible = estado is EstadoVersion.Disponible
        b.btnEstadoVersion.setText(
            when (estado) {
                EstadoVersion.SinComprobar, EstadoVersion.AlDia, is EstadoVersion.UltimaPublicada -> R.string.action_buscar
                EstadoVersion.Buscando -> R.string.action_buscando
                EstadoVersion.Error -> R.string.action_reintentar
                is EstadoVersion.Disponible -> R.string.action_actualizar
            }
        )
        b.btnEstadoVersion.isEnabled = !buscando
        b.btnEstadoVersion.alpha = if (buscando) 0.6f else 1f
        b.btnEstadoVersion.setIconResource(if (disponible) R.drawable.ic_system_update else R.drawable.ic_refresh)
        val colorTexto = ContextCompat.getColor(requireContext(), if (disponible) R.color.header_button_text else android.R.color.white)
        b.btnEstadoVersion.setTextColor(colorTexto)
        b.btnEstadoVersion.iconTint = ColorStateList.valueOf(colorTexto)
        b.btnEstadoVersion.backgroundTintList = ColorStateList.valueOf(
            if (disponible) Color.WHITE else ContextCompat.getColor(requireContext(), R.color.header_filo)
        )
        b.btnEstadoVersion.strokeWidth = if (disponible) 0 else resources.displayMetrics.density.toInt().coerceAtLeast(1)
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
