package com.julen.socios

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
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
import kotlin.math.abs
import kotlinx.coroutines.flow.combine
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
    private var semanaActual: String = ""

    /** Agrupación con la que se dibujó el gráfico, para animarlo solo al cambiarla. */
    private var periodoDelGrafico: Periodo? = null
    private var graficoDibujado = false

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
        binding.graficoNeto.alSeleccionar = { columna ->
            binding.graficoNeto.announceForAccessibility(
                getString(R.string.grafico_seleccion, columna.etiquetaLarga, columna.neto.toFormattedEuros())
            )
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(viewModel.historicoGlobal.filterNotNull(), viewModel.semanaActual) { resumen, actual ->
                    resumen to actual
                }.collect { (resumen, actual) ->
                    resumenActual = resumen
                    semanaActual = actual
                    renderTotales(resumen)
                    renderComparativa(CalculoComisiones.comparar(actual, resumen.semanasHistoricas))
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
        binding.tvGlobalDescontadoTotal.text =
            importeConSigno(resumenGlobal.totalDescontadoAcumulado)
    }

    private fun renderComparativa(c: CalculoComisiones.Comparativa) {
        binding.tvComparativaNeto.text = getString(R.string.comparativa_neto_semana, c.neto.toFormattedEuros())
        mostrarDiferencia(binding.tvComparativaAnterior, c.diferenciaSemanaAnterior)
        binding.tvComparativaMediaLabel.text =
            resources.getQuantityString(R.plurals.comparativa_vs_media, c.semanasEnMedia, c.semanasEnMedia)
        mostrarDiferencia(binding.tvComparativaMedia, c.diferenciaMedia)

        val mejor = c.mejorSemana
        binding.tvMejorSemanaRango.text = when {
            mejor == null -> getString(R.string.comparativa_sin_datos)
            c.esLaMejor -> getString(R.string.comparativa_es_esta)
            else -> getString(R.string.rango_semana_anio, DateUtils.rangoCorto(mejor.semanaKey), mejor.semanaKey.take(4))
        }
        binding.tvMejorSemanaNeto.isVisible = mejor != null
        binding.tvMejorSemanaNeto.text = mejor?.resumenSemana?.totalNeto?.toFormattedEuros()
        val abrible = mejor?.takeIf { !c.esLaMejor }
        binding.ivMejorSemanaFlecha.isVisible = abrible != null
        binding.filaMejorSemana.isClickable = abrible != null
        binding.filaMejorSemana.setOnClickListener(
            if (abrible != null) View.OnClickListener {
                HapticUtils.performClick(it)
                viewModel.irASemana(abrible.semanaKey)
                dismiss()
            } else null
        )
    }

    /** "↑ 32,10 €" en verde si se gana más, "↓ 12,00 €" en rojo si menos; "Sin datos" si no hay con qué comparar. */
    private fun mostrarDiferencia(vista: TextView, diferencia: Double?) {
        val contexto = requireContext()
        when {
            diferencia == null -> {
                vista.text = getString(R.string.comparativa_sin_datos)
                vista.setTextColor(ContextCompat.getColor(contexto, R.color.text_sub))
            }

            diferencia >= 0.0 -> {
                vista.text = getString(R.string.comparativa_sube, diferencia.toFormattedEuros())
                vista.setTextColor(ContextCompat.getColor(contexto, R.color.green_text_soft))
            }

            else -> {
                vista.text = getString(R.string.comparativa_baja, (-diferencia).toFormattedEuros())
                vista.setTextColor(ContextCompat.getColor(contexto, R.color.red_text))
            }
        }
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
        val periodos = periodo?.let { CalculoComisiones.agruparPorPeriodo(semanas, it) }
        adapter.submitList(periodos?.map(::filaPeriodo) ?: semanas.map(::filaSemana))
        mostrarGrafico(semanas, periodo, periodos.orEmpty())
    }

    /** Las últimas 12 semanas, los últimos 12 meses o los años, hasta la semana actual (la destacada). */
    private fun mostrarGrafico(
        semanas: List<CalculoComisiones.SemanaHistoricaItem>,
        periodo: Periodo?,
        periodos: List<CalculoComisiones.ResumenPeriodo>
    ) {
        if (semanaActual.isEmpty()) return
        val columnas = if (periodo == null) {
            CalculoComisiones.ultimasSemanas(semanas, semanaActual).map { barra ->
                GraficoNetoView.Columna(
                    etiqueta = getString(R.string.historico_etiqueta_semana, barra.clave.substringAfter("-W").toIntOrNull() ?: 0),
                    etiquetaLarga = DateUtils.rangoCorto(barra.clave),
                    neto = barra.neto
                )
            }
        } else {
            CalculoComisiones.ultimosPeriodos(periodos, periodo, semanaActual).map { barra ->
                val anio = barra.clave.take(4)
                val mes = barra.clave.substringAfter('-', "").toIntOrNull()?.let(Month::of)
                GraficoNetoView.Columna(
                    etiqueta = mes?.getDisplayName(TextStyle.SHORT, LOCALE_ES)?.removeSuffix(".") ?: anio,
                    etiquetaLarga = mes?.let { "${nombreMes(it)} $anio" } ?: anio,
                    neto = barra.neto
                )
            }
        }

        val animar = !graficoDibujado || periodo != periodoDelGrafico
        binding.graficoNeto.mostrar(columnas, destacada = columnas.lastIndex, animar = animar)
        graficoDibujado = true
        periodoDelGrafico = periodo

        val mejor = columnas.maxByOrNull { it.neto }?.takeIf { it.neto > 0.0 }
        binding.graficoNeto.contentDescription = if (mejor == null || columnas.isEmpty()) {
            getString(R.string.grafico_a11y_vacio)
        } else {
            getString(
                R.string.grafico_a11y,
                columnas.size,
                columnas.first().etiquetaLarga,
                columnas.last().etiquetaLarga,
                mejor.neto.toFormattedEuros(),
                mejor.etiquetaLarga
            )
        }
    }

    private fun nombreMes(mes: Month) =
        mes.getDisplayName(TextStyle.FULL_STANDALONE, LOCALE_ES).replaceFirstChar { it.titlecase(LOCALE_ES) }

    private fun filaSemana(item: CalculoComisiones.SemanaHistoricaItem): FilaHistorico {
        val r = item.resumenSemana
        return FilaHistorico(
            id = item.semanaKey,
            etiqueta = getString(R.string.historico_etiqueta_semana, item.semanaKey.substringAfter("-W").toIntOrNull() ?: 0),
            titulo = getString(R.string.rango_semana_anio, DateUtils.rangoCorto(item.semanaKey), item.semanaKey.take(4)),
            detalle = conDescontado(
                resources.getQuantityString(
                    if (r.esBonoRegaloAplicado) R.plurals.historico_semana_resumen_manual else R.plurals.historico_semana_resumen,
                    r.totalSociosHechos,
                    r.totalSociosHechos,
                    r.bonusSemanal.toEurosCompacto(prefix = "+")
                ),
                r.descontado
            ),
            neto = r.totalNeto,
            irpf = r.retencionIrpf,
            semanaKey = item.semanaKey
        )
    }

    private fun filaPeriodo(p: CalculoComisiones.ResumenPeriodo): FilaHistorico {
        val titulo = p.mes?.let { mes -> "${nombreMes(Month.of(mes))} ${p.anio}" } ?: p.anio.toString()
        val etiqueta = p.mes?.let { mes ->
            Month.of(mes).getDisplayName(TextStyle.SHORT, LOCALE_ES).removeSuffix(".").uppercase(LOCALE_ES)
        } ?: getString(R.string.historico_etiqueta_anio)
        return FilaHistorico(
            id = "${p.anio}-${p.mes ?: 0}",
            etiqueta = etiqueta,
            titulo = titulo,
            detalle = conDescontado(
                resources.getQuantityString(
                    R.plurals.historico_periodo_resumen, p.sociosHechos, p.sociosHechos, p.bruto.toFormattedEuros()
                ),
                p.descontado
            ),
            neto = p.neto,
            irpf = p.irpf
        )
    }

    /** Añade "· -15,31 € desc." al resumen de una fila en la que se cobró menos (o más) de lo calculado. */
    private fun conDescontado(detalle: String, descontado: Double): String =
        if (descontado != 0.0) {
            getString(R.string.historico_detalle_descontado, detalle, importeConSigno(descontado, compacto = true))
        } else {
            detalle
        }

    /** Lo descontado con su signo: "-15,31 €" si se cobró menos de lo calculado, "+5 €" si más. */
    private fun importeConSigno(descontado: Double, compacto: Boolean = false): String {
        val signo = if (descontado < 0.0) "+" else if (descontado > 0.0) "-" else ""
        return if (compacto) abs(descontado).toEurosCompacto(prefix = signo) else abs(descontado).toCurrencyString(prefix = signo)
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
