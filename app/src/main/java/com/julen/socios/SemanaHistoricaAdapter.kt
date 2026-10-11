package com.julen.socios

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.julen.socios.databinding.ItemSemanaHistoricaBinding
import com.julen.socios.util.CalculoComisiones
import com.julen.socios.util.toCurrencyString
import com.julen.socios.util.toEurosCompacto

class SemanaHistoricaAdapter(
    private val onSelectSemana: (String) -> Unit
) : ListAdapter<CalculoComisiones.SemanaHistoricaItem, SemanaHistoricaAdapter.SemanaViewHolder>(
    SemanaDiffCallback()
) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SemanaViewHolder {
        val binding =
            ItemSemanaHistoricaBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return SemanaViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SemanaViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class SemanaViewHolder(private val binding: ItemSemanaHistoricaBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }
                    ?.let { onSelectSemana(getItem(it).semanaKey) }
            }
        }

        fun bind(item: CalculoComisiones.SemanaHistoricaItem) {
            val context = binding.root.context
            val r = item.resumenSemana

            binding.tvSemanaItemTitulo.text = context.getString(R.string.title_week_format, item.semanaKey)

            val bonus = r.bonusSemanal.toEurosCompacto(prefix = "+")
            binding.tvSemanaItemSociosCount.text = context.resources.getQuantityString(
                if (r.esBonoRegaloAplicado) R.plurals.historico_semana_resumen_manual else R.plurals.historico_semana_resumen,
                r.totalSociosHechos,
                r.totalSociosHechos,
                bonus
            )

            binding.tvSemanaItemNeto.text = r.totalNeto.toCurrencyString(prefix = "+")
            binding.tvSemanaItemIrpf.text = context.getString(
                R.string.irpf_amount_format, r.retencionIrpf.toCurrencyString(prefix = "-")
            )
        }
    }

    class SemanaDiffCallback : DiffUtil.ItemCallback<CalculoComisiones.SemanaHistoricaItem>() {
        override fun areItemsTheSame(
            oldItem: CalculoComisiones.SemanaHistoricaItem,
            newItem: CalculoComisiones.SemanaHistoricaItem
        ): Boolean {
            return oldItem.semanaKey == newItem.semanaKey
        }

        override fun areContentsTheSame(
            oldItem: CalculoComisiones.SemanaHistoricaItem,
            newItem: CalculoComisiones.SemanaHistoricaItem
        ): Boolean {
            return oldItem == newItem
        }
    }
}
