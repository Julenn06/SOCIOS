package com.julen.socios

import android.animation.ValueAnimator
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.julen.socios.databinding.ItemSocioBinding
import com.julen.socios.model.Socio
import com.julen.socios.util.CalculoComisiones
import com.julen.socios.util.HapticUtils
import com.julen.socios.util.toEurosCompacto
import com.julen.socios.util.setTextoAccesible

class SocioAdapter(
    private val onCambiarHecho: (Socio, Boolean) -> Unit,
    private val onEdit: (Socio) -> Unit,
    private val onDelete: (Socio) -> Unit
) : ListAdapter<Socio, SocioAdapter.SocioViewHolder>(SocioDiffCallback()) {

    companion object {
        private const val PAYLOAD_HECHO_CHANGED = "PAYLOAD_HECHO_CHANGED"
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SocioViewHolder {
        val binding = ItemSocioBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return SocioViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SocioViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onBindViewHolder(
        holder: SocioViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (PAYLOAD_HECHO_CHANGED in payloads) {
            holder.updateHechoState(getItem(position))
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun onViewRecycled(holder: SocioViewHolder) {
        super.onViewRecycled(holder)
        holder.cancelAnimation()
    }

    inner class SocioViewHolder(private val binding: ItemSocioBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private var textColorAnimator: ValueAnimator? = null

        init {
            // Los listeners se registran una sola vez y leen el socio actual de la lista en el momento
            // del clic. Si capturasen el socio del último bind, tras una actualización parcial (payload)
            // editarían una copia obsoleta y podrían deshacer el cambio de "hecho".
            binding.switchHecho.setOnClickListener { view ->
                HapticUtils.performToggle(view)
                socioActual()?.let { onCambiarHecho(it, binding.switchHecho.isChecked) }
            }

            binding.btnEditar.setOnClickListener { view ->
                HapticUtils.performClick(view)
                socioActual()?.let(onEdit)
            }

            binding.btnEliminar.setOnClickListener { view ->
                HapticUtils.performClick(view)
                socioActual()?.let(onDelete)
            }
        }

        private fun socioActual(): Socio? =
            bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let(::getItem)

        fun bind(socio: Socio) {
            val context = binding.root.context
            val baseX2 = CalculoComisiones.calcularBaseX2(socio.colaboracion)

            binding.tvColaboracionAmount.text = socio.colaboracion.toEurosCompacto()
            binding.tvBaseX2Amount.text = context.getString(R.string.socio_base_format, baseX2.toEurosCompacto())
            binding.tvDiaBadge.text = socio.dia.nombreCompleto

            binding.tvNombreSocio.text = socio.nombreSocio.ifBlank {
                context.getString(R.string.socio_default_name, socio.colaboracion.toEurosCompacto())
            }

            binding.tvNotas.isVisible = socio.notas.isNotBlank()
            binding.tvNotas.text = context.getString(R.string.socio_note_format, socio.notas)

            updateHechoState(socio)
        }

        fun updateHechoState(socio: Socio) {
            val context = binding.root.context

            if (binding.switchHecho.isChecked != socio.hecho) {
                binding.switchHecho.isChecked = socio.hecho
            }

            val targetColor = if (socio.hecho) {
                val baseX2 = CalculoComisiones.calcularBaseX2(socio.colaboracion)
                binding.tvEstadoTexto.setTextoAccesible(
                    context.getString(R.string.socio_status_conseguido, baseX2.toEurosCompacto())
                )
                ContextCompat.getColor(context, R.color.primary)
            } else {
                binding.tvEstadoTexto.setTextoAccesible(context.getString(R.string.socio_status_pendiente))
                ContextCompat.getColor(context, R.color.orange_pending)
            }

            animateTextColor(binding.tvEstadoTexto, targetColor)
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

        override fun getChangePayload(oldItem: Socio, newItem: Socio): Any? {
            return if (oldItem.copy(hecho = newItem.hecho) == newItem) {
                PAYLOAD_HECHO_CHANGED
            } else {
                super.getChangePayload(oldItem, newItem)
            }
        }
    }
}
