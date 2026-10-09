package com.julen.socios

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.julen.socios.databinding.ItemSemanaHistoricaBinding
import com.julen.socios.util.CalculoComisiones
import com.julen.socios.util.toCurrencyString

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

        fun bind(item: CalculoComisiones.SemanaHistoricaItem) {
            val context = binding.root.context
            val r = item.resumenSemana

            binding.tvSemanaItemTitulo.text =
                context.getString(R.string.title_week_format, item.semanaKey)

            val bonusText = if (r.esBonoRegaloAplicado) {
                "${r.totalSociosHechos} socios hechos (+${r.bonusSemanal.toInt()}€ bonus 🎁)"
            } else {
                "${r.totalSociosHechos} socios hechos (+${r.bonusSemanal.toInt()}€ bonus)"
            }
            binding.tvSemanaItemSociosCount.text = bonusText

            binding.tvSemanaItemNeto.text = r.totalNeto.toCurrencyString(prefix = "+")
            binding.tvSemanaItemIrpf.text = context.getString(
                R.string.irpf_amount_format, r.retencionIrpf.toCurrencyString(prefix = "-")
            )

            binding.root.setOnClickListener {
                onSelectSemana(item.semanaKey)
            }
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
