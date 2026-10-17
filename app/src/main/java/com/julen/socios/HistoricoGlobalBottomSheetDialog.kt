package com.julen.socios

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.julen.socios.databinding.BottomSheetHistoricoGlobalBinding
import com.julen.socios.util.CalculoComisiones
import com.julen.socios.util.CalculoComisiones.Periodo
import com.julen.socios.util.DateUtils
import com.julen.socios.util.HapticUtils
import com.julen.socios.util.toCurrencyString
import com.julen.socios.util.toEurosCompacto
import com.julen.socios.util.toFormattedEuros
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

class HistoricoGlobalBottomSheetDialog : BottomSheetDialogFragment() {

    private val viewModel: MainViewModel by activityViewModels { MainViewModel.Factory }

    private var _binding: BottomSheetHistoricoGlobalBinding? = null
    private val binding get() = _binding!!

    private val adapter = HistoricoAdapter { semanaKey ->
        viewModel.irASemana(semanaKey)
        dismiss()
    }

    private var resumenActual: CalculoComisiones.ResumenHistoricoGlobal? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        (super.onCreateDialog(savedInstanceState) as BottomSheetDialog).apply {
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetHistoricoGlobalBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.rvSemanasHistoricas.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSemanasHistoricas.adapter = adapter

        binding.toggleAgrupacion.addOnButtonCheckedListener { grupo, _, marcado ->
            if (!marcado) return@addOnButtonCheckedListener
            HapticUtils.performClick(grupo)
            mostrarFilas()
        }
        binding.btnCerrarHistorico.setOnClickListener { dismiss() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.historicoGlobal.filterNotNull().collect { resumen ->
                    resumenActual = resumen
                    renderTotales(resumen)
                    mostrarFilas()
                }
            }
        }
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        mostrarFilas()
    }

    private fun renderTotales(resumenGlobal: CalculoComisiones.ResumenHistoricoGlobal) {
        val countSemanas = resumenGlobal.semanasHistoricas.size
        binding.tvGlobalSemanasCount.text =
            resources.getQuantityString(R.plurals.global_history_count, countSemanas, countSemanas)

        binding.tvGlobalIrpfTotal.text =
            resumenGlobal.totalIrpfRetenidoAcumulado.toCurrencyString(prefix = "-")
        binding.tvGlobalNetoTotal.text =
            resumenGlobal.totalNetoAcumulado.toCurrencyString(prefix = "+")
        binding.tvGlobalBonusTotal.text =
            resumenGlobal.totalBonusAcumulado.toCurrencyString(prefix = "+")
    }

    private fun mostrarFilas() {
        val semanas = resumenActual?.semanasHistoricas ?: return
        // Se pregunta al grupo y no a cada botón: al elegir "Semanas", el grupo avisa antes de desmarcar el
        // botón de mes o año, y ese botón aún diría que está marcado.
        val periodo = when (binding.toggleAgrupacion.checkedButtonId) {
            R.id.btnPorMes -> Periodo.MES
            R.id.btnPorAnio -> Periodo.ANIO
            else -> null
        }
        binding.tvNotaAgrupacion.isVisible = periodo != null
        adapter.submitList(
            if (periodo == null) {
                semanas.map(::filaSemana)
            } else {
                CalculoComisiones.agruparPorPeriodo(semanas, periodo).map(::filaPeriodo)
            }
        )
    }

    private fun filaSemana(item: CalculoComisiones.SemanaHistoricaItem): FilaHistorico {
        val r = item.resumenSemana
        return FilaHistorico(
            id = item.semanaKey,
            etiqueta = getString(R.string.historico_etiqueta_semana, item.semanaKey.substringAfter("-W").toIntOrNull() ?: 0),
            titulo = getString(R.string.rango_semana_anio, DateUtils.rangoCorto(item.semanaKey), item.semanaKey.take(4)),
            detalle = resources.getQuantityString(
                if (r.esBonoRegaloAplicado) R.plurals.historico_semana_resumen_manual else R.plurals.historico_semana_resumen,
                r.totalSociosHechos,
                r.totalSociosHechos,
                r.bonusSemanal.toEurosCompacto(prefix = "+")
            ),
            neto = r.totalNeto,
            irpf = r.retencionIrpf,
            semanaKey = item.semanaKey
        )
    }

    private fun filaPeriodo(p: CalculoComisiones.ResumenPeriodo): FilaHistorico {
        val titulo = p.mes?.let { mes ->
            val nombre = Month.of(mes).getDisplayName(TextStyle.FULL_STANDALONE, LOCALE_ES)
            "${nombre.replaceFirstChar { it.titlecase(LOCALE_ES) }} ${p.anio}"
        } ?: p.anio.toString()
        val etiqueta = p.mes?.let { mes ->
            Month.of(mes).getDisplayName(TextStyle.SHORT, LOCALE_ES).removeSuffix(".").uppercase(LOCALE_ES)
        } ?: getString(R.string.historico_etiqueta_anio)
        return FilaHistorico(
            id = "${p.anio}-${p.mes ?: 0}",
            etiqueta = etiqueta,
            titulo = titulo,
            detalle = resources.getQuantityString(
                R.plurals.historico_periodo_resumen, p.sociosHechos, p.sociosHechos, p.bruto.toFormattedEuros()
            ),
            neto = p.neto,
            irpf = p.irpf
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "HistoricoGlobalDialog"
        private val LOCALE_ES: Locale = Locale.forLanguageTag("es-ES")
    }
}
