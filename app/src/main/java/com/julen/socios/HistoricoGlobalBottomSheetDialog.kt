package com.julen.socios

import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.julen.socios.databinding.BottomSheetHistoricoGlobalBinding
import com.julen.socios.util.CalculoComisiones
import com.julen.socios.util.CalculoComisiones.Periodo
import com.julen.socios.util.HapticUtils
import com.julen.socios.util.LOCALE_ES
import com.julen.socios.util.NumberAnimators
import com.julen.socios.util.descontadoConSigno
import com.julen.socios.util.rangoSemanaConAnio
import com.julen.socios.util.toCurrencyString
import com.julen.socios.util.toEurosCompacto
import java.time.Month
import java.time.format.TextStyle
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull

class HistoricoGlobalBottomSheetDialog :
    PanelInferior<BottomSheetHistoricoGlobalBinding>(BottomSheetHistoricoGlobalBinding::inflate) {

    private val viewModel: MainViewModel by activityViewModels { MainViewModel.Factory }

    private val adapter = HistoricoAdapter { semanaKey ->
        viewModel.irASemana(semanaKey)
        dismiss()
    }

    private var resumenActual: CalculoComisiones.ResumenHistoricoGlobal? = null
    private var semanaActual: String = ""

    /** Agrupación con la que se dibujó el gráfico, para animarlo solo al cambiarla. */
    private var periodoDelGrafico: Periodo? = null
    private var graficoDibujado = false

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
        // La barra elegida como estado del gráfico: TalkBack lo lee al cambiar (announceForAccessibility
        // está obsoleto desde Android 16).
        binding.graficoNeto.alSeleccionar = { columna ->
            ViewCompat.setStateDescription(
                binding.graficoNeto,
                getString(R.string.grafico_seleccion, columna.etiquetaLarga, columna.neto.toCurrencyString())
            )
        }

        mientrasSeVea {
            combine(viewModel.historicoGlobal.filterNotNull(), viewModel.semanaActual) { resumen, actual ->
                resumen to actual
            }.collect { (resumen, actual) ->
                resumenActual = resumen
                semanaActual = actual
                renderTotales(resumen)
                renderMejorSemana(CalculoComisiones.mejorSemana(resumen.semanasHistoricas))
                mostrarFilas()
            }
        }
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        mostrarFilas()
    }

    /** Lo cobrado en grande (cuenta hasta su valor), su media por semana y de dónde sale. */
    private fun renderTotales(r: CalculoComisiones.ResumenHistoricoGlobal) {
        val semanas = r.semanasHistoricas.size
        binding.tvGlobalSemanasCount.text = resources.getQuantityString(R.plurals.global_history_count, semanas, semanas)
        NumberAnimators.animateCurrency(binding.tvGlobalNetoTotal, r.totalNetoAcumulado)
        binding.tvGlobalMediaSemana.isVisible = semanas > 0
        if (semanas > 0) {
            val media = CalculoComisiones.redondearCentimos(r.totalNetoAcumulado / semanas)
            binding.tvGlobalMediaSemana.text = getString(R.string.historico_media_semana, media.toCurrencyString())
        }
        binding.tvGlobalBaseTotal.text = r.totalBaseX2Acumulado.toEurosCompacto(prefix = "+")
        binding.tvGlobalBonusTotal.text = r.totalBonusAcumulado.toEurosCompacto(prefix = "+")
        binding.tvGlobalIrpfTotal.text = r.totalIrpfRetenidoAcumulado.toEurosCompacto(prefix = "-")
    }

    /** La mejor semana, con su resumen; no se muestra hasta que alguna haya dado neto. Al tocarla se abre. */
    private fun renderMejorSemana(mejor: CalculoComisiones.SemanaHistoricaItem?) {
        binding.tarjetaMejorSemana.isVisible = mejor != null
        if (mejor == null) return
        binding.tvMejorSemanaRango.text = requireContext().rangoSemanaConAnio(mejor.semanaKey)
        binding.tvMejorSemanaDetalle.text = resumenDeSemana(mejor.resumenSemana)
        binding.tvMejorSemanaNeto.text = mejor.resumenSemana.totalNeto.toCurrencyString()
        binding.filaMejorSemana.setOnClickListener {
            HapticUtils.performClick(it)
            viewModel.irASemana(mejor.semanaKey)
            dismiss()
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
        val filas = if (periodo != null && periodos != null) {
            // El mes o el año de la semana actual, el mismo que el gráfico destaca.
            val actual = CalculoComisiones.ultimosPeriodos(periodos, periodo, semanaActual, cantidad = 1).firstOrNull()?.clave
            periodos.map { filaPeriodo(it, destacada = it.clave == actual) }
        } else {
            semanas.map(::filaSemana)
        }
        // Cada fila con su barra: lo que supone su neto frente al mejor de la lista.
        val mejor = filas.maxOfOrNull { it.neto }?.takeIf { it > 0.0 }
        adapter.submitList(filas.map { fila -> fila.copy(proporcion = mejor?.let { (fila.neto / it).coerceIn(0.0, 1.0) } ?: 0.0) })
        mostrarGrafico(semanas, periodo, periodos.orEmpty())
    }

    /**
     * Todas las semanas, meses o años desde el primero con datos hasta el actual (el destacado), que se ven
     * desplazando el gráfico. Cada año nuevo lleva su marca.
     */
    private fun mostrarGrafico(
        semanas: List<CalculoComisiones.SemanaHistoricaItem>,
        periodo: Periodo?,
        periodos: List<CalculoComisiones.ResumenPeriodo>
    ) {
        if (semanaActual.isEmpty()) return
        val columnas = if (periodo == null) {
            val barras = CalculoComisiones.evolucionSemanas(semanas, semanaActual)
            barras.mapIndexed { i, barra ->
                GraficoNetoView.Columna(
                    etiqueta = etiquetaSemana(barra.clave),
                    etiquetaLarga = requireContext().rangoSemanaConAnio(barra.clave),
                    neto = barra.neto,
                    separador = marcaDeAnio(barras, i)
                )
            }
        } else {
            val barras = CalculoComisiones.evolucionPeriodos(periodos, periodo, semanaActual)
            barras.mapIndexed { i, barra ->
                val anio = barra.clave.take(4)
                val mes = barra.clave.substringAfter('-', "").toIntOrNull()?.let(Month::of)
                GraficoNetoView.Columna(
                    etiqueta = mes?.let(::mesCorto) ?: anio,
                    etiquetaLarga = mes?.let { nombreMes(it, anio) } ?: anio,
                    neto = barra.neto,
                    // Por años no hace falta: cada columna ya es uno.
                    separador = if (periodo == Periodo.MES) marcaDeAnio(barras, i) else null
                )
            }
        }

        val animar = !graficoDibujado || periodo != periodoDelGrafico
        binding.graficoNeto.mostrar(columnas, destacada = columnas.lastIndex, animar = animar)
        graficoDibujado = true
        periodoDelGrafico = periodo
        val media = binding.graficoNeto.media
        binding.tvGraficoMedia.isVisible = media != null
        binding.tvGraficoMedia.text = media?.let { getString(R.string.grafico_media, it.toEurosCompacto()) }

        val mejor = columnas.maxByOrNull { it.neto }?.takeIf { it.neto > 0.0 }
        binding.graficoNeto.contentDescription = if (mejor == null || columnas.isEmpty()) {
            getString(R.string.grafico_a11y_vacio)
        } else {
            getString(
                R.string.grafico_a11y,
                columnas.size,
                columnas.first().etiquetaLarga,
                columnas.last().etiquetaLarga,
                mejor.neto.toCurrencyString(),
                mejor.etiquetaLarga
            )
        }
    }

    /**
     * El año de la barra [i] si es la primera o con ella empieza un año nuevo, para marcarlo en el gráfico; las
     * claves de semana y de mes empiezan por el año ("2026-W01", "2026-01").
     */
    private fun marcaDeAnio(barras: List<CalculoComisiones.Barra>, i: Int): String? {
        val anio = barras[i].clave.take(4)
        return anio.takeIf { i == 0 || barras[i - 1].clave.take(4) != anio }
    }

    /** "Octubre 2026". */
    private fun nombreMes(mes: Month, anio: String) =
        "${mes.getDisplayName(TextStyle.FULL_STANDALONE, LOCALE_ES).replaceFirstChar { it.titlecase(LOCALE_ES) }} $anio"

    /** "oct". */
    private fun mesCorto(mes: Month) = mes.getDisplayName(TextStyle.SHORT, LOCALE_ES).removeSuffix(".")

    /** "S42". */
    private fun etiquetaSemana(semanaKey: String) =
        getString(R.string.historico_etiqueta_semana, semanaKey.substringAfter("-W").toIntOrNull() ?: 0)

    private fun filaSemana(item: CalculoComisiones.SemanaHistoricaItem): FilaHistorico {
        val r = item.resumenSemana
        return FilaHistorico(
            id = item.semanaKey,
            etiqueta = etiquetaSemana(item.semanaKey),
            titulo = requireContext().rangoSemanaConAnio(item.semanaKey),
            detalle = conDescontado(resumenDeSemana(r), r.descontado),
            neto = r.totalNeto,
            irpf = r.retencionIrpf,
            semanaKey = item.semanaKey,
            destacada = item.semanaKey == semanaActual
        )
    }

    /** "13 socios hechos · +220 € bonus" (con 🎁 si el bonus se ajustó a mano). */
    private fun resumenDeSemana(r: CalculoComisiones.ResumenCalculo): String = resources.getQuantityString(
        if (r.esBonoRegaloAplicado) R.plurals.historico_semana_resumen_manual else R.plurals.historico_semana_resumen,
        r.totalSociosHechos,
        r.totalSociosHechos,
        r.bonusSemanal.toEurosCompacto(prefix = "+")
    )

    private fun filaPeriodo(p: CalculoComisiones.ResumenPeriodo, destacada: Boolean): FilaHistorico {
        val titulo = p.mes?.let { mes -> nombreMes(Month.of(mes), p.anio.toString()) } ?: p.anio.toString()
        val etiqueta = p.mes?.let { mes -> mesCorto(Month.of(mes)).uppercase(LOCALE_ES) }
            ?: getString(R.string.historico_etiqueta_anio)
        return FilaHistorico(
            id = "${p.anio}-${p.mes ?: 0}",
            etiqueta = etiqueta,
            titulo = titulo,
            detalle = conDescontado(
                resources.getQuantityString(
                    R.plurals.historico_periodo_resumen, p.sociosHechos, p.sociosHechos, p.bruto.toCurrencyString()
                ),
                p.descontado
            ),
            neto = p.neto,
            irpf = p.irpf,
            destacada = destacada
        )
    }

    /** Añade "· -15,31 € desc." al resumen de una fila en la que se cobró menos (o más) de lo calculado. */
    private fun conDescontado(detalle: String, descontado: Double): String =
        if (descontado != 0.0) {
            getString(R.string.historico_detalle_descontado, detalle, descontado.descontadoConSigno(compacto = true))
        } else {
            detalle
        }

    companion object {
        const val TAG = "HistoricoGlobalDialog"
    }
}
