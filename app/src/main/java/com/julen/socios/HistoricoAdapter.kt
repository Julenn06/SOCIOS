package com.julen.socios

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.julen.socios.databinding.ItemSemanaHistoricaBinding
import com.julen.socios.util.toCurrencyString

/** Una fila del histórico: una semana, un mes o un año. Solo las semanas ([semanaKey]) se pueden abrir. */
data class FilaHistorico(
    val id: String,
    val titulo: String,
    val detalle: String,
    val neto: Double,
    val irpf: Double,
    val semanaKey: String? = null
)

class HistoricoAdapter(
    private val onSelectSemana: (String) -> Unit
) : ListAdapter<FilaHistorico, HistoricoAdapter.FilaViewHolder>(FilaDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FilaViewHolder {
        val binding =
            ItemSemanaHistoricaBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return FilaViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FilaViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class FilaViewHolder(private val binding: ItemSemanaHistoricaBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }
                    ?.let { getItem(it).semanaKey }
                    ?.let(onSelectSemana)
            }
        }

        fun bind(fila: FilaHistorico) {
            binding.tvSemanaItemTitulo.text = fila.titulo
            binding.tvSemanaItemSociosCount.text = fila.detalle
            binding.tvSemanaItemNeto.text = fila.neto.toCurrencyString(prefix = "+")
            binding.tvSemanaItemIrpf.text = binding.root.context.getString(
                R.string.irpf_amount_format, fila.irpf.toCurrencyString(prefix = "-")
            )
            binding.root.isClickable = fila.semanaKey != null
        }
    }

    class FilaDiffCallback : DiffUtil.ItemCallback<FilaHistorico>() {
        override fun areItemsTheSame(oldItem: FilaHistorico, newItem: FilaHistorico) = oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: FilaHistorico, newItem: FilaHistorico) = oldItem == newItem
    }
}
