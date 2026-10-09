package com.julen.socios

import android.animation.ValueAnimator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.julen.socios.databinding.ItemSocioBinding
import com.julen.socios.model.DiaSemana
import com.julen.socios.model.Socio
import com.julen.socios.util.CalculoComisiones
import com.julen.socios.util.HapticUtils
import com.julen.socios.util.toFormattedEuros

class SocioAdapter(
    private val onToggleHecho: (Socio) -> Unit,
    private val onEdit: (Socio) -> Unit,
    private val onDelete: (Socio) -> Unit
) : ListAdapter<Socio, SocioAdapter.SocioViewHolder>(SocioDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SocioViewHolder {
        val binding = ItemSocioBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return SocioViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SocioViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: SocioViewHolder) {
        super.onViewRecycled(holder)
        holder.cancelAnimation()
    }

    inner class SocioViewHolder(private val binding: ItemSocioBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private var textColorAnimator: ValueAnimator? = null

        fun bind(socio: Socio) {
            val context = binding.root.context

            // Importes
            val colaboracionFormatted = socio.colaboracion.toFormattedEuros(0)
            val baseX2 = CalculoComisiones.calcularBaseX2(socio.colaboracion)
            val baseX2Formatted = context.getString(R.string.socio_base_format, baseX2.toInt())

            binding.tvColaboracionAmount.text = colaboracionFormatted
            binding.tvBaseX2Amount.text = baseX2Formatted

            // Día de la semana
            val diaSemana = DiaSemana.fromId(socio.diaSemanaId)
            binding.tvDiaBadge.text = diaSemana.nombreCompleto

            // Nombre
            if (socio.nombreSocio.isNotBlank()) {
                binding.tvNombreSocio.text = socio.nombreSocio
            } else {
                binding.tvNombreSocio.text =
                    context.getString(R.string.socio_default_name, socio.colaboracion.toInt())
            }

            // Notas
            if (socio.notas.isNotBlank()) {
                binding.tvNotas.visibility = View.VISIBLE
                binding.tvNotas.text = context.getString(R.string.socio_note_format, socio.notas)
            } else {
                binding.tvNotas.visibility = View.GONE
            }

            // Estado
            binding.switchHecho.setOnCheckedChangeListener(null)
            binding.switchHecho.isChecked = socio.hecho

            val targetColor = if (socio.hecho) {
                binding.tvEstadoTexto.text =
                    context.getString(R.string.socio_status_conseguido, baseX2.toInt())
                ContextCompat.getColor(context, R.color.primary)
            } else {
                binding.tvEstadoTexto.text = context.getString(R.string.socio_status_pendiente)
                ContextCompat.getColor(context, R.color.orange_pending)
            }

            animateTextColor(binding.tvEstadoTexto, targetColor)

            // Listeners
            binding.switchHecho.setOnClickListener { view ->
                HapticUtils.performToggle(view)
                onToggleHecho(socio)
            }

            binding.btnEditar.setOnClickListener { view ->
                HapticUtils.performClick(view)
                onEdit(socio)
            }

            binding.btnEliminar.setOnClickListener { view ->
                HapticUtils.performClick(view)
                onDelete(socio)
            }
        }

        private fun animateTextColor(textView: TextView, targetColor: Int) {
            textColorAnimator?.cancel()
            val currentColor = textView.currentTextColor
            if (currentColor == targetColor) return

            val animator = ValueAnimator.ofArgb(currentColor, targetColor)
            animator.duration = 300L
            animator.addUpdateListener { anim ->
                textView.setTextColor(anim.animatedValue as Int)
            }
            textColorAnimator = animator
            animator.start()
        }

        fun cancelAnimation() {
            textColorAnimator?.cancel()
            textColorAnimator = null
        }
    }

    class SocioDiffCallback : DiffUtil.ItemCallback<Socio>() {
        override fun areItemsTheSame(oldItem: Socio, newItem: Socio): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Socio, newItem: Socio): Boolean {
            return oldItem == newItem
        }
    }
}
