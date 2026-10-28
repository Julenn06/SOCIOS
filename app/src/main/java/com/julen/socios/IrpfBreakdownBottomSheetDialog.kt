package com.julen.socios

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.activityViewModels
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.julen.socios.databinding.BottomSheetIrpfBreakdownBinding
import com.julen.socios.databinding.DialogIrpfBinding
import com.julen.socios.databinding.ItemFilaDesgloseBinding
import com.julen.socios.util.CalculoComisiones
import com.julen.socios.util.DateUtils
import com.julen.socios.util.Movimiento
import com.julen.socios.util.NumberAnimators
import com.julen.socios.util.descontadoConSigno
import com.julen.socios.util.etiquetaDescontado
import com.julen.socios.util.parseImporte
import com.julen.socios.util.rangoSemanaConAnio
import com.julen.socios.util.setTextoAccesible
import com.julen.socios.util.toCurrencyString
import com.julen.socios.util.toEurosCompacto
import com.julen.socios.util.toImporteInput
import com.julen.socios.util.toPorcentajeString
import kotlinx.coroutines.launch

/** Desglose de la semana visible. Observa el ViewModel, así se actualiza solo y sobrevive a recreaciones. */
class IrpfBreakdownBottomSheetDialog :
    PanelInferior<BottomSheetIrpfBreakdownBinding>(BottomSheetIrpfBreakdownBinding::inflate) {

    private val viewModel: MainViewModel by activityViewModels { MainViewModel.Factory }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnCerrarBreakdown.setOnClickListener { dismiss() }
        // Los extremos redondos de la pista recortan también las partes de la barra del reparto.
        binding.barraReparto.clipToOutline = true
        binding.btnCambiarIrpf.configurar(
            R.drawable.ic_edit, Tono.AZUL, R.string.irpf_cambiar, R.string.irpf_cambiar_detalle
        ) { mostrarDialogoIrpf() }
        binding.btnCobroBreakdown.configurar(
            R.drawable.ic_cobro, Tono.VERDE, R.string.cobro_accion, R.string.cobro_accion_detalle
        ) { mostrarCobro() }
        binding.btnVerCambiosIrpf.configurar(
            R.drawable.ic_history, Tono.NARANJA, R.string.irpf_ver_cambios, R.string.irpf_ver_cambios_detalle
        ) { mostrarCambiosIrpf() }

        mientrasSeVea {
            launch { viewModel.uiState.collect(::render) }
            // Mantiene la lista de cambios cargada para mostrarla al instante.
            launch { viewModel.irpfVigencias.collect {} }
        }
    }

    private fun mostrarCambiosIrpf() {
        val vigencias = viewModel.irpfVigencias.value.sortedByDescending { it.desdeSemanaKey }
        val porDefecto = CalculoComisiones.IRPF_POR_DEFECTO.toPorcentajeString()
        if (vigencias.isEmpty()) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.irpf_ver_cambios)
                .setMessage(getString(R.string.irpf_sin_cambios, porDefecto))
                .setPositiveButton(R.string.action_accept, null)
                .show()
            return
        }

        val items = vigencias.map {
            getString(
                R.string.irpf_cambio_item,
                it.desdeSemanaKey,
                DateUtils.getRangoSemanaTextoFromKey(it.desdeSemanaKey),
                it.porcentaje.toPorcentajeString()
            )
        }.toTypedArray()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.irpf_cambios_titulo)
            .setItems(items) { _, which -> confirmarEliminarCambio(vigencias[which].desdeSemanaKey) }
            .setNegativeButton(R.string.action_close, null)
            .show()
    }

    private fun confirmarEliminarCambio(desdeSemanaKey: String) {
        // Porcentaje que volverá a aplicarse: el del cambio anterior a este, o el de por defecto.
        val restantes = viewModel.irpfVigencias.value.filter { it.desdeSemanaKey != desdeSemanaKey }
        val anterior = CalculoComisiones.irpfParaSemana(desdeSemanaKey, restantes).toPorcentajeString()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.irpf_eliminar_cambio_titulo)
            .setMessage(getString(R.string.irpf_eliminar_cambio_msg, desdeSemanaKey, anterior))
            .setPositiveButton(R.string.action_delete) { _, _ -> viewModel.eliminarIrpf(desdeSemanaKey) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun render(state: MainUiState) {
        val r = state.resumenSemana ?: return
        binding.tvBreakdownRangoSemana.text = requireContext().rangoSemanaConAnio(state.semanaKey)

        // "Neto cobrado" si se ha apuntado lo que llegó de verdad; si no, "Neto a cobrar".
        binding.tvBreakdownNetoBigLabel.setText(if (r.tieneCobroReal) R.string.irpf_neto_cobrado else R.string.irpf_neto_final)
        binding.tvBreakdownNetoLabel.setText(if (r.tieneCobroReal) R.string.irpf_neto_cobrado else R.string.irpf_total_neto)
        NumberAnimators.animateCurrency(binding.tvBreakdownNetoBig, r.totalNeto)
        binding.tvBreakdownNetoSmall.text = r.totalNeto.toCurrencyString()
        binding.tvBreakdownSociosCount.text = resources.getQuantityString(
            R.plurals.irpf_breakdown_count, r.totalSociosHechos, r.totalSociosHechos, r.totalSociosRegistrados
        )
        binding.tvBreakdownIrpfPastilla.text = getString(R.string.label_irpf_header, r.porcentajeIrpf.toPorcentajeString())
        renderReparto(r)

        binding.pasoSuma.mostrar(
            R.string.irpf_signo_suma, Tono.NEUTRO, getString(R.string.irpf_fila_suma), r.sumaColaboracionesBase.toCurrencyString(),
            detalle = resources.getQuantityString(R.plurals.irpf_suma_detalle, r.totalSociosHechos, r.totalSociosHechos)
        )
        binding.pasoBase.mostrar(
            R.string.irpf_signo_doble, Tono.AZUL, getString(R.string.irpf_fila_base), r.gananciasBaseX2.toCurrencyString(prefix = "+")
        )
        binding.pasoBonus.mostrar(
            R.string.irpf_signo_mas, Tono.NARANJA, etiquetaBonus(r), r.bonusSemanal.toCurrencyString(prefix = "+"),
            detalle = detalleBonus(r, state.bonoInfoSemana?.motivo)
        )
        binding.pasoBruto.mostrar(
            R.string.irpf_signo_igual, Tono.NEUTRO, getString(R.string.irpf_total_bruto), r.totalBruto.toCurrencyString(),
            destacado = true
        )
        binding.pasoIrpf.mostrar(
            R.string.irpf_signo_menos, Tono.ROJO,
            getString(R.string.irpf_retencion_label, r.porcentajeIrpf.toPorcentajeString()),
            r.retencionIrpf.toCurrencyString(prefix = "-")
        )
        renderCobro(r, state.cobroSemana?.motivo)
    }

    private fun etiquetaBonus(r: CalculoComisiones.ResumenCalculo): String = if (r.esBonoRegaloAplicado) {
        getString(R.string.irpf_fila_bonus_manual, r.bonusAutomatico.toCurrencyString())
    } else {
        resources.getQuantityString(R.plurals.irpf_fila_bonus_auto, r.totalSociosHechos, r.totalSociosHechos)
    }

    /** El motivo del bonus ajustado a mano, si se escribió; con el automático, cuánto falta para el siguiente tramo. */
    private fun detalleBonus(r: CalculoComisiones.ResumenCalculo, motivo: String?): String? = if (r.esBonoRegaloAplicado) {
        motivo?.let { getString(R.string.irpf_bonus_motivo, it) }
    } else {
        resources.getQuantityString(
            R.plurals.bonus_next_goal, r.sociosFaltantesParaSiguienteMeta,
            r.sociosFaltantesParaSiguienteMeta, r.siguienteMetaBonus.toEurosCompacto(prefix = "+")
        )
    }

    /** La barra del bruto: lo que te llevas (el neto, o lo cobrado), el IRPF y, si te han descontado algo, eso. */
    private fun renderReparto(r: CalculoComisiones.ResumenCalculo) {
        val neto = r.totalNeto.coerceAtLeast(0.0)
        val descontado = r.descontado.coerceAtLeast(0.0)
        val total = neto + r.retencionIrpf + descontado
        binding.contenedorReparto.isVisible = total > 0.0
        if (total <= 0.0) return
        Movimiento.animarCambios(binding.barraReparto)
        repartir(binding.segmentoNeto, neto)
        repartir(binding.segmentoIrpf, r.retencionIrpf)
        repartir(binding.segmentoDescontado, descontado)
        binding.tvLeyendaNeto.text = getString(R.string.irpf_leyenda_neto, r.totalNeto.toCurrencyString())
        binding.tvLeyendaIrpf.text = getString(R.string.irpf_amount_format, r.retencionIrpf.toCurrencyString())
        binding.tvLeyendaDescontado.isVisible = descontado > 0.0
        binding.tvLeyendaDescontado.text = getString(R.string.irpf_leyenda_descontado, descontado.toCurrencyString())
    }

    /** Cada parte de la barra ocupa lo que pesa su [importe]; sin importe, no se ve. */
    private fun repartir(segmento: View, importe: Double) {
        segmento.isVisible = importe > 0.0
        segmento.updateLayoutParams<LinearLayout.LayoutParams> { weight = importe.toFloat() }
    }

    /** Con lo cobrado apuntado: el neto calculado y la diferencia, en rojo si es un descuento y en verde si sobra. */
    private fun renderCobro(r: CalculoComisiones.ResumenCalculo, motivo: String?) {
        binding.contenedorBreakdownCobro.isVisible = r.tieneCobroReal
        val cobrado = r.netoCobrado
        if (cobrado == null) {
            binding.btnCobroBreakdown.tvFormato.setText(R.string.cobro_accion)
            binding.btnCobroBreakdown.tvFormatoDetalle.setText(R.string.cobro_accion_detalle)
            return
        }
        val descontado = r.descontado
        binding.pasoNetoCalculado.mostrar(
            R.string.irpf_signo_igual, Tono.NEUTRO, getString(R.string.irpf_neto_calculado), r.netoCalculado.toCurrencyString(),
            destacado = true
        )
        binding.pasoDescontado.mostrar(
            when {
                descontado > 0.0 -> R.string.irpf_signo_menos
                descontado < 0.0 -> R.string.irpf_signo_mas
                else -> R.string.irpf_signo_igual
            },
            if (descontado > 0.0) Tono.ROJO else Tono.VERDE,
            getString(etiquetaDescontado(descontado)),
            descontado.descontadoConSigno(),
            detalle = motivo?.let { getString(R.string.irpf_bonus_motivo, it) }
        )
        binding.btnCobroBreakdown.tvFormato.setText(R.string.cobro_accion_cambiar)
        binding.btnCobroBreakdown.tvFormatoDetalle.text = getString(R.string.cobro_accion_cambiar_detalle, cobrado.toCurrencyString())
    }

    /** Un paso de la cuenta: su operación en la placa, qué es, un detalle si lo hay y el importe, en su [tono]. */
    private fun ItemFilaDesgloseBinding.mostrar(
        @StringRes signo: Int,
        tono: Tono,
        titulo: String,
        importe: String,
        detalle: String? = null,
        destacado: Boolean = false
    ) {
        val contexto = root.context
        placaPaso.backgroundTintList = ContextCompat.getColorStateList(contexto, tono.fondo)
        tvSignoPaso.setText(signo)
        tvSignoPaso.setTextColor(ContextCompat.getColor(contexto, tono.color))
        tvTituloPaso.setTextoAccesible(titulo)
        tvTituloPaso.typeface = ResourcesCompat.getFont(contexto, if (destacado) R.font.geist_semibold else R.font.geist_medium)
        tvDetallePaso.isVisible = detalle != null
        tvDetallePaso.text = detalle
        tvImportePaso.text = importe
        tvImportePaso.setTextColor(ContextCompat.getColor(contexto, tono.color))
    }

    /** Encima del desglose, que se actualiza solo al guardarlo. */
    private fun mostrarCobro() = CobroBottomSheetDialog.mostrar(parentFragmentManager, viewModel.uiState.value)

    private fun mostrarDialogoIrpf() {
        val state = viewModel.uiState.value
        val porcentajeActual = state.resumenSemana?.porcentajeIrpf ?: return
        val dialogBinding = DialogIrpfBinding.inflate(layoutInflater)
        dialogBinding.tvIrpfAyuda.text = getString(R.string.irpf_dialogo_ayuda, state.semanaKey)
        dialogBinding.etIrpf.setText(porcentajeActual.toImporteInput())

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.irpf_dialogo_titulo)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.action_save, null)
            .setNegativeButton(R.string.action_cancel, null)
            .show()

        // Se valida antes de cerrar: el botón positivo por defecto cerraría el diálogo aunque el valor fuese inválido.
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val porcentaje = parseImporte(dialogBinding.etIrpf.text)
            if (porcentaje == null || porcentaje !in 0.0..100.0) {
                dialogBinding.tilIrpf.error = getString(R.string.msg_irpf_invalido)
            } else {
                viewModel.guardarIrpf(state.semanaKey, porcentaje)
                dialog.dismiss()
            }
        }
    }

    companion object {
        const val TAG = "IrpfBreakdownDialog"
    }
}
