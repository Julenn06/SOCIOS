package com.julen.socios

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.julen.socios.databinding.BottomSheetIrpfBreakdownBinding
import com.julen.socios.util.CalculoComisiones
import com.julen.socios.util.toCurrencyString
import com.julen.socios.util.toFormattedEuros

class IrpfBreakdownBottomSheetDialog(
    private val rangoSemanaTexto: String, private val resumen: CalculoComisiones.ResumenCalculo
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetIrpfBreakdownBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetIrpfBreakdownBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.tvBreakdownRangoSemana.text = rangoSemanaTexto

        // Formatos de moneda
        val netoFormatted = resumen.totalNeto.toFormattedEuros()
        binding.tvBreakdownNetoBig.text = netoFormatted
        binding.tvBreakdownNetoSmall.text = netoFormatted

        binding.tvBreakdownSociosCount.text = getString(
            R.string.irpf_breakdown_count, resumen.totalSociosHechos, resumen.totalSociosRegistrados
        )

        binding.tvBreakdownSumaCuotas.text = resumen.sumaColaboracionesBase.toFormattedEuros()
        binding.tvBreakdownBaseX2.text = resumen.gananciasBaseX2.toCurrencyString(prefix = "+")
        binding.tvBreakdownBonusValue.text = resumen.bonusSemanal.toCurrencyString(prefix = "+")

        val bonusLabel = if (resumen.esBonoRegaloAplicado) {
            "3. Bonus semanal (${resumen.totalSociosHechos} hechos) 🎁 Bono de regalo"
        } else {
            "3. Bonus semanal por socios (${resumen.totalSociosHechos} hechos)"
        }
        binding.tvBreakdownBonusLabel.text = bonusLabel

        binding.tvBreakdownBruto.text = resumen.totalBruto.toFormattedEuros()
        binding.tvBreakdownIrpf.text = resumen.retencionIrpf.toCurrencyString(prefix = "-")

        binding.btnCerrarBreakdown.setOnClickListener {
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
