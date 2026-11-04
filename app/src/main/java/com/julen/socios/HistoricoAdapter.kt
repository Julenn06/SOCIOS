package com.julen.socios

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.julen.socios.databinding.ItemSemanaHistoricaBinding
import com.julen.socios.util.setTextoAccesible
import com.julen.socios.util.toCurrencyString
import kotlin.math.roundToInt

/**
 * Una fila del histórico: una semana, un mes o un año, con una [etiqueta] corta para su placa ("S42", "OCT",
 * "AÑO"). Solo las semanas ([semanaKey]) se pueden abrir. [proporcion] (de 0 a 1) es su neto frente al mejor de
 * la lista, para su barra; [destacada], la semana (o el mes o el año) actual.
 */
data class FilaHistorico(
    val id: String,
    val etiqueta: String,
    val titulo: String,
    val detalle: String,
    val neto: Double,
    val irpf: Double,
    val semanaKey: String? = null,
    val proporcion: Double = 0.0,
    val destacada: Boolean = false
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
            binding.tvSemanaItemEtiqueta.text = fila.etiqueta
            binding.tvSemanaItemTitulo.text = fila.titulo
            binding.tvSemanaItemSociosCount.setTextoAccesible(fila.detalle)
            val contexto = binding.root.context
            binding.placaSemanaItem.backgroundTintList = ContextCompat.getColorStateList(
                contexto, if (fila.destacada) R.color.green_container else R.color.blue_container
            )
            binding.tvSemanaItemEtiqueta.setTextColor(
                ContextCompat.getColor(contexto, if (fila.destacada) R.color.green_text else R.color.blue_text_soft)
            )
            // Sin "+" delante de un neto negativo (quedaba "+-5,00 €").
            binding.tvSemanaItemNeto.text = fila.neto.toCurrencyString(prefix = if (fila.neto > 0.0) "+" else "")
            binding.barraSemanaItem.progress = (fila.proporcion * binding.barraSemanaItem.max).roundToInt()
            binding.tvSemanaItemIrpf.text = contexto.getString(
                R.string.label_irpf_header, fila.irpf.toCurrencyString(prefix = "-")
            )
            binding.root.isClickable = fila.semanaKey != null
            binding.ivSemanaItemFlecha.isVisible = fila.semanaKey != null
        }
    }

    class FilaDiffCallback : DiffUtil.ItemCallback<FilaHistorico>() {
        override fun areItemsTheSame(oldItem: FilaHistorico, newItem: FilaHistorico) = oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: FilaHistorico, newItem: FilaHistorico) = oldItem == newItem
    }
}
