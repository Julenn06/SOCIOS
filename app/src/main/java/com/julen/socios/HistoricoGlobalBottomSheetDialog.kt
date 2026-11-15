package com.julen.socios

import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.annotation.StringRes
import com.julen.socios.databinding.BottomSheetHistoricoGlobalBinding
import com.julen.socios.databinding.ItemEstadisticaBinding
import com.julen.socios.util.CalculoComisiones
import com.julen.socios.util.CalculoComisiones.Periodo
import com.julen.socios.util.HapticUtils
import com.julen.socios.util.LOCALE_ES
import com.julen.socios.util.Movimiento
import com.julen.socios.util.NumberAnimators
import com.julen.socios.util.descontadoConSigno
import com.julen.socios.util.DateUtils
import com.julen.socios.util.ESPACIO_CIFRA
import com.julen.socios.util.EstadisticasHistorico
import com.julen.socios.util.toCurrencyString
import com.julen.socios.util.toEurosCompacto
import java.time.Month
import java.time.format.TextStyle
import kotlin.math.roundToInt
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

    /**
     * Al abrir el panel, lo que va debajo de la cabecera espera oculto a que lleguen los datos y entonces entra bloque
     * a bloque. Sin esto, el panel subía vacío y todo aparecía de golpe un momento después.
     */
    private var entradaPendiente = false

    /** Agrupación con la que se dibujó el gráfico, para animarlo solo al cambiarla. */
    private var periodoDelGrafico: Periodo? = null
    private var graficoDibujado = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Al recrearse (girar la pantalla) ya estaba a la vista: no vuelve a entrar.
        entradaPendiente = savedInstanceState == null && Movimiento.activas()
        if (entradaPendiente) {
            val desplazamiento = DESPLAZAMIENTO_ENTRADA_DP * resources.displayMetrics.density
            bloques().forEach {
                it.alpha = 0f
                it.translationY = desplazamiento
            }
        }

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
                if (entradaPendiente) entrar()
            }
        }
        mientrasSeVea {
            viewModel.estadisticas.filterNotNull().collect(::renderEstadisticas)
        }
    }

    /** Lo que va debajo de la cabecera: el total, la mejor semana, el desglose, el gráfico y la lista. */
    private fun bloques(): List<View> {
        val contenido = binding.contenidoHistorico
        val desde = contenido.indexOfChild(binding.encabezadoHistorico) + 1
        return (desde until contenido.childCount).map(contenido::getChildAt)
    }

    /** Los bloques suben a su sitio mientras aparecen, uno tras otro de arriba abajo. */
    private fun entrar() {
        entradaPendiente = false
        val curva = Movimiento.curva(requireContext())
        var orden = 0
        for (bloque in bloques()) {
            val retraso = if (bloque.isVisible) RETRASO_ENTRADA_MS + ESCALON_ENTRADA_MS * orden++ else 0L
            bloque.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(retraso)
                .setDuration(Movimiento.LARGA_MS)
                .setInterpolator(curva)
                .start()
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
        binding.tvMejorSemanaRango.text = DateUtils.rangoCortoConAnio(mejor.semanaKey)
        binding.tvMejorSemanaDetalle.text = resumenDeSemana(mejor.resumenSemana)
        binding.tvMejorSemanaNeto.text = mejor.resumenSemana.totalNeto.toCurrencyString()
        binding.filaMejorSemana.setOnClickListener {
            HapticUtils.performClick(it)
            viewModel.irASemana(mejor.semanaKey)
            dismiss()
        }
    }

    /** Cómo trabajas: cada dato, solo si ya hay con qué calcularlo; sin ninguno, la sección entera se oculta. */
    private fun renderEstadisticas(e: EstadisticasHistorico.Estadisticas) {
        mostrarEstadistica(binding.estadisticaMejorDia, R.string.estadistica_mejor_dia, e.mejorDia, { it.dia.nombreCompleto }) {
            resources.getQuantityString(R.plurals.estadistica_mejor_dia_detalle, it.hechos, it.hechos, porcentaje(it.proporcion))
        }
        mostrarEstadistica(binding.estadisticaCuota, R.string.estadistica_cuota, e.cuota, { it.media.toCurrencyString() }) { cuota ->
            cuota.mediaReciente?.let { getString(R.string.estadistica_cuota_reciente, it.toCurrencyString()) }
                ?: resources.getQuantityString(R.plurals.estadistica_cuota_detalle, cuota.hechos, cuota.hechos)
        }
        mostrarEstadistica(binding.estadisticaApuntados, R.string.estadistica_apuntados, e.apuntados, { porcentaje(it.proporcionHechos) }) {
            if (it.pendientes == 0) {
                getString(R.string.estadistica_apuntados_todos, it.total)
            } else {
                resources.getQuantityString(R.plurals.estadistica_apuntados_detalle, it.pendientes, it.total, it.pendientes)
            }
        }
        mostrarEstadistica(binding.estadisticaDescontado, R.string.estadistica_descontado, e.descontado, { it.media.descontadoConSigno() }) {
            val reciente = it.mediaReciente?.descontadoConSigno()
            when (it.tendencia) {
                EstadisticasHistorico.Tendencia.SUBE -> getString(R.string.estadistica_descontado_sube, reciente)
                EstadisticasHistorico.Tendencia.BAJA -> getString(R.string.estadistica_descontado_baja, reciente)
                EstadisticasHistorico.Tendencia.IGUAL -> getString(R.string.estadistica_descontado_igual, reciente)
                EstadisticasHistorico.Tendencia.SIN_DATOS ->
                    resources.getQuantityString(R.plurals.estadistica_descontado_detalle, it.meses, it.meses)
            }
        }

        val alguna = listOf(e.mejorDia, e.cuota, e.apuntados, e.descontado).any { it != null }
        binding.tvSeccionEstadisticas.isVisible = alguna
        binding.tarjetaEstadisticas.isVisible = alguna
    }

    /** Una fila de [renderEstadisticas]: oculta si aún no hay [dato]; si no, su [titulo], su [valor] y su [detalle]. */
    private inline fun <T : Any> mostrarEstadistica(
        fila: ItemEstadisticaBinding,
        @StringRes titulo: Int,
        dato: T?,
        valor: (T) -> String,
        detalle: (T) -> String
    ) {
        fila.root.isVisible = dato != null
        if (dato == null) return
        fila.tvEstadisticaTitulo.setText(titulo)
        fila.tvEstadisticaValor.text = valor(dato)
        fila.tvEstadisticaDetalle.text = detalle(dato)
    }

    /** 0,284 → "28 %". */
    private fun porcentaje(proporcion: Double) = "${(proporcion * 100).roundToInt()}$ESPACIO_CIFRA%"

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
                    etiquetaLarga = DateUtils.rangoCortoConAnio(barra.clave),
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
        // Cuando el gráfico ya tiene su ancho (al abrir el panel aún no lo tiene): solo así se sabe si caben todas.
        binding.graficoNeto.doOnLayout {
            binding.tvGraficoAyuda.setText(
                if (binding.graficoNeto.sePuedeDesplazar) R.string.grafico_ayuda_desplazar else R.string.grafico_ayuda
            )
        }
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
            titulo = DateUtils.rangoCortoConAnio(item.semanaKey),
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

        /** La entrada de los bloques: cuánto suben, cuándo empieza el primero y cuánto espera cada siguiente. */
        private const val DESPLAZAMIENTO_ENTRADA_DP = 28f
        private const val RETRASO_ENTRADA_MS = 90L
        private const val ESCALON_ENTRADA_MS = 70L
    }
}
