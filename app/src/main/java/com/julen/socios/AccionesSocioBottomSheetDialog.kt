package com.julen.socios

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.LifecycleOwner
import com.julen.socios.databinding.BottomSheetAccionesSocioBinding
import com.julen.socios.model.Socio
import com.julen.socios.util.CalculoComisiones
import com.julen.socios.util.DateUtils
import com.julen.socios.util.nombreVisible
import com.julen.socios.util.toEurosCompacto

/**
 * Lo que se puede hacer con un socio al mantenerlo pulsado: editarlo o eliminarlo. El panel solo dice qué se ha
 * elegido; lo hace quien lo abrió (ver [escuchar]), igual que antes con el menú.
 */
class AccionesSocioBottomSheetDialog :
    PanelInferior<BottomSheetAccionesSocioBinding>(BottomSheetAccionesSocioBinding::inflate) {

    private enum class Accion { EDITAR, ELIMINAR }

    private val socio: Socio by lazy { requireNotNull(requireArguments().getBundle(ARG_SOCIO)).toSocio() }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        mostrarSocio()

        binding.accionEditar.configurar(
            R.drawable.ic_edit, Tono.VERDE, R.string.cd_editar_socio, R.string.socio_acciones_editar_detalle
        ) { elegir(Accion.EDITAR) }
        binding.accionEliminar.configurar(
            R.drawable.ic_delete, Tono.ROJO, R.string.cd_eliminar_socio, R.string.socio_acciones_eliminar_detalle
        ) { elegir(Accion.ELIMINAR) }
        binding.accionEliminar.tvFormato.setTextColor(ContextCompat.getColor(requireContext(), R.color.red_text))
    }

    private fun mostrarSocio() {
        val contexto = requireContext()
        val base = CalculoComisiones.calcularBaseX2(socio.colaboracion)
        binding.placaAcciones.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR, coloresDegradadoSocio(contexto, socio.hecho)
        ).apply { cornerRadius = 20 * resources.displayMetrics.density }
        binding.tvImporteAcciones.text = socio.colaboracion.toEurosCompacto()
        binding.tvBaseAcciones.text = getString(R.string.socio_base_format, base.toEurosCompacto())

        binding.tvNombreAcciones.text = socio.nombreVisible().resolver(contexto)
        binding.tvSemanaAcciones.text =
            getString(R.string.socio_dia_semana, socio.dia.nombreCompleto, DateUtils.rangoCorto(socio.semanaKey))

        // El estado, como en su fila: lo que suma si está conseguido, o pendiente.
        val estado = binding.tvEstadoAcciones
        if (socio.hecho) {
            estado.text = getString(R.string.socio_status_conseguido, base.toEurosCompacto())
            estado.setTextColor(ContextCompat.getColor(contexto, R.color.on_primary_container))
            estado.backgroundTintList = null
        } else {
            estado.setText(R.string.socio_status_pendiente)
            estado.setTextColor(ContextCompat.getColor(contexto, R.color.orange_pending))
            estado.backgroundTintList = ContextCompat.getColorStateList(contexto, R.color.orange_container)
        }

        binding.tvNotasAcciones.isVisible = socio.notas.isNotBlank()
        binding.tvNotasAcciones.text = socio.notas
    }

    private fun elegir(accion: Accion) {
        setFragmentResult(RESULTADO, bundleOf(ARG_ACCION to accion.name, ARG_SOCIO to socio.toBundle()))
        dismiss()
    }

    companion object {
        private const val TAG = "AccionesSocio"
        private const val RESULTADO = "accionSocio"
        private const val ARG_SOCIO = "socio"
        private const val ARG_ACCION = "accion"

        /** Abre el panel de [socio] en [fragmentManager]; lo elegido llega a quien escuche en ese mismo gestor. */
        fun mostrar(fragmentManager: FragmentManager, socio: Socio) =
            fragmentManager.mostrarSiNoEsta(TAG) {
                AccionesSocioBottomSheetDialog().apply { arguments = bundleOf(ARG_SOCIO to socio.toBundle()) }
            }

        /** Lo que se elija en los paneles abiertos en [fragmentManager], mientras [owner] esté activo. */
        fun escuchar(fragmentManager: FragmentManager, owner: LifecycleOwner, alEditar: (Socio) -> Unit, alEliminar: (Socio) -> Unit) {
            fragmentManager.setFragmentResultListener(RESULTADO, owner) { _, resultado ->
                val socio = requireNotNull(resultado.getBundle(ARG_SOCIO)).toSocio()
                when (Accion.valueOf(requireNotNull(resultado.getString(ARG_ACCION)))) {
                    Accion.EDITAR -> alEditar(socio)
                    Accion.ELIMINAR -> alEliminar(socio)
                }
            }
        }
    }
}
