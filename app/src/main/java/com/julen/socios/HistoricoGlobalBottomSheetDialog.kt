package com.julen.socios

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.julen.socios.databinding.BottomSheetHistoricoGlobalBinding
import com.julen.socios.util.CalculoComisiones
import com.julen.socios.util.toCurrencyString
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

class HistoricoGlobalBottomSheetDialog : BottomSheetDialogFragment() {

    private val viewModel: MainViewModel by activityViewModels { MainViewModel.Factory }

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

        val adapter = SemanaHistoricaAdapter { semanaKey ->
            viewModel.irASemana(semanaKey)
            dismiss()
        }
        binding.rvSemanasHistoricas.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSemanasHistoricas.adapter = adapter

        binding.btnCerrarHistorico.setOnClickListener { dismiss() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.historicoGlobal.filterNotNull().collect { resumen ->
                    render(resumen)
                    adapter.submitList(resumen.semanasHistoricas)
                }
            }
        }
    }

    private fun render(resumenGlobal: CalculoComisiones.ResumenHistoricoGlobal) {
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "HistoricoGlobalDialog"
    }
}
