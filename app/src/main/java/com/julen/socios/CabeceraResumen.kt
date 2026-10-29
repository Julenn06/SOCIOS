package com.julen.socios

import android.animation.ValueAnimator
import androidx.core.view.isVisible
import com.julen.socios.databinding.ActivityMainBinding
import com.julen.socios.model.CobroInfo
import com.julen.socios.util.CalculoComisiones
import com.julen.socios.util.Movimiento
import com.julen.socios.util.NumberAnimators
import com.julen.socios.util.decimalesCompactos
import com.julen.socios.util.etiquetaDescontado
import com.julen.socios.util.setTextoAccesible
import com.julen.socios.util.toCurrencyString
import com.julen.socios.util.toEurosCompacto
import com.julen.socios.util.toPorcentajeString
import kotlin.math.abs

/**
 * La cabecera verde (neto, base, bonus, IRPF y lo cobrado) y la tarjeta del bonus de la pantalla principal.
 * Recuerda el bonus de la última semana pintada para animar solo lo que cambia dentro de la misma semana.
 */
class CabeceraResumen(private val binding: ActivityMainBinding) {

    private val context get() = binding.root.context
    private val resources get() = context.resources

    private var bonusAnterior: Pair<String, Double>? = null

    fun mostrar(semanaKey: String, resumen: CalculoComisiones.ResumenCalculo, cobro: CobroInfo?) {
        NumberAnimators.animateCurrency(binding.tvNetoHeader, resumen.totalNeto, prefix = "")
        val etiquetaNeto = context.getString(
            if (resumen.tieneCobroReal) R.string.header_neto_cobrado else R.string.header_neto_semanal
        )
        binding.tvNetoHeaderLabel.text = etiquetaNeto
        binding.containerNetoHeader.contentDescription =
            context.getString(R.string.header_neto_a11y, etiquetaNeto, resumen.totalNeto.toCurrencyString())
        mostrarCobro(resumen, cobro)
        NumberAnimators.animateCurrency(
            binding.tvBaseHeader, resumen.gananciasBaseX2, prefix = "+", decimals = resumen.gananciasBaseX2.decimalesCompactos()
        )
        NumberAnimators.animateCurrency(
            binding.tvBonusHeader, resumen.bonusSemanal, prefix = "+", decimals = resumen.bonusSemanal.decimalesCompactos()
        )
        NumberAnimators.animateCurrency(binding.tvIrpfHeader, resumen.retencionIrpf, prefix = "-")

        binding.tvBonusHeaderLabel.setTextoAccesible(
            context.getString(if (resumen.esBonoRegaloAplicado) R.string.label_bonus_manual else R.string.label_bonus_auto)
        )
        binding.tvIrpfHeaderLabel.text =
            context.getString(R.string.label_irpf_header, resumen.porcentajeIrpf.toPorcentajeString())

        val hechos = resumen.totalSociosHechos
        val metaSocios = resumen.siguienteMetaSocios

        binding.tvBonusTitle.text =
            context.getString(R.string.bonus_titulo, resumen.bonusSemanal.toEurosCompacto(prefix = "+"))
        binding.tvBonusProgressCount.text = context.getString(R.string.bonus_progress_format, hechos, metaSocios)
        binding.tvBonusProgressCount.contentDescription =
            context.getString(R.string.bonus_progress_a11y, hechos, metaSocios)

        // La barra avanza dentro de la misma semana; al cambiar de semana se coloca sin animar.
        val anterior = bonusAnterior
        val mismaSemana = anterior != null && anterior.first == semanaKey
        binding.progressBonus.mostrar(hechos, animar = mismaSemana)
        // Un latido al subir de tramo dentro de la misma semana, en el título y en la caja del bonus.
        if (anterior != null && mismaSemana && anterior.second < resumen.bonusSemanal) {
            Movimiento.latido(binding.tvBonusTitle)
            Movimiento.latido(binding.containerBonusHeader)
        }
        bonusAnterior = semanaKey to resumen.bonusSemanal
    }

    /**
     * La fila de lo cobrado: solo con el neto ajustado a lo cobrado, y dice cuánto se ha descontado de lo
     * calculado (o cobrado de más, y por qué si había nota).
     */
    private fun mostrarCobro(resumen: CalculoComisiones.ResumenCalculo, cobro: CobroInfo?) {
        val valor = binding.tvCobroHeader
        binding.containerCobroHeader.isVisible = resumen.tieneCobroReal
        if (resumen.tieneCobroReal) {
            val descontado = resumen.descontado
            val etiqueta = context.getString(etiquetaDescontado(descontado))
            NumberAnimators.animateCurrency(valor, abs(descontado), prefix = if (descontado < 0.0) "+" else "-")
            binding.tvCobroHeaderLabel.text =
                cobro?.motivo?.let { context.getString(R.string.socio_dia_semana, etiqueta, it) } ?: etiqueta
            binding.containerCobroHeader.contentDescription = context.getString(
                R.string.header_cobro_a11y, etiqueta, abs(descontado).toCurrencyString(), resumen.netoCalculado.toCurrencyString()
            )
        } else {
            // Oculta: la próxima vez que salga, el importe crece desde 0 en lugar de desde el de otra semana.
            (valor.getTag(R.id.tag_numeric_animator) as? ValueAnimator)?.cancel()
            valor.setTag(R.id.tag_numeric_value, 0.0)
        }
    }
}
