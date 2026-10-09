package com.julen.socios

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.julen.socios.databinding.BottomSheetHistoricoGlobalBinding
import com.julen.socios.util.CalculoComisiones
import com.julen.socios.util.toCurrencyString

class HistoricoGlobalBottomSheetDialog(
    private val resumenGlobal: CalculoComisiones.ResumenHistoricoGlobal,
    private val onSelectSemana: (String) -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetHistoricoGlobalBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetHistoricoGlobalBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val countSemanas = resumenGlobal.semanasHistoricas.size
        binding.tvGlobalSemanasCount.text = if (countSemanas == 1) {
            getString(R.string.global_history_count_singular)
        } else {
            getString(R.string.global_history_count_plural, countSemanas)
        }

        binding.tvGlobalIrpfTotal.text =
            resumenGlobal.totalIrpfRetenidoAcumulado.toCurrencyString(prefix = "-")
        binding.tvGlobalNetoTotal.text =
            resumenGlobal.totalNetoAcumulado.toCurrencyString(prefix = "+")
        binding.tvGlobalBonusTotal.text =
            resumenGlobal.totalBonusAcumulado.toCurrencyString(prefix = "+")

        // Adapter para la lista de semanas
        val adapter = SemanaHistoricaAdapter { semanaKey ->
            onSelectSemana(semanaKey)
            dismiss()
        }

        binding.rvSemanasHistoricas.layoutManager = LinearLayoutManager(context)
        binding.rvSemanasHistoricas.adapter = adapter
        adapter.submitList(resumenGlobal.semanasHistoricas)

        binding.btnCerrarHistorico.setOnClickListener {
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
