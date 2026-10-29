package com.julen.socios

import android.animation.ValueAnimator
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.julen.socios.databinding.ItemCabeceraDiaBinding
import com.julen.socios.databinding.ItemSocioBinding
import com.julen.socios.model.DiaSemana
import com.julen.socios.model.Socio
import com.julen.socios.util.CalculoComisiones
import com.julen.socios.util.DateUtils
import com.julen.socios.util.HapticUtils
import com.julen.socios.util.nombreVisible
import com.julen.socios.util.setTextoAccesible
import com.julen.socios.util.toEurosCompacto

/** Una fila de la lista de socios: la cabecera de un día o un socio. */
sealed interface FilaSocio {
    val id: String

    /** [ganancia]: lo que suman los socios hechos del día (cuota × 2; el bonus es de la semana, no de un día). */
    data class Cabecera(val dia: DiaSemana, val socios: Int, val hechos: Int, val ganancia: Double) : FilaSocio {
        override val id: String get() = "dia-${dia.id}"
    }

    data class Item(val socio: Socio) : FilaSocio {
        override val id: String get() = socio.id
    }

    companion object {
        /** De lunes a viernes, cada día con su cabecera y sus socios en el orden en que se apuntaron. */
        fun agrupadasPorDia(socios: List<Socio>): List<FilaSocio> =
            socios.groupBy { it.dia }.toSortedMap(compareBy { it.id }).flatMap { (dia, delDia) ->
                val hechos = delDia.filter { it.hecho }
                listOf(
                    Cabecera(
                        dia = dia,
                        socios = delDia.size,
                        hechos = hechos.size,
                        ganancia = CalculoComisiones.redondearCentimos(hechos.sumOf { CalculoComisiones.calcularBaseX2(it.colaboracion) })
                    )
                ) + delDia.sortedBy { it.timestamp }.map(::Item)
            }

        fun sinAgrupar(socios: List<Socio>): List<FilaSocio> = socios.map(::Item)
    }
}

/**
 * Socios con su interruptor. Tocar uno lo edita y mantenerlo pulsado llama a [onAcciones] (el panel con editar y
 * eliminar). En la pantalla principal van agrupados por día; en la búsqueda y en los pendientes ([mostrarSemana])
 * cada uno dice de qué semana es y tocarlo abre esa semana en lugar de editarlo.
 */
class SocioAdapter(
    private val onCambiarHecho: (Socio, Boolean) -> Unit,
    private val onEdit: (Socio) -> Unit,
    private val onDelete: (Socio) -> Unit,
    private val onAcciones: (Socio) -> Unit,
    private val mostrarSemana: Boolean = false,
    private val onAbrir: ((Socio) -> Unit)? = null
) : ListAdapter<FilaSocio, RecyclerView.ViewHolder>(FilaDiffCallback()) {

    companion object {
        private const val PAYLOAD_HECHO_CHANGED = "PAYLOAD_HECHO_CHANGED"
        private const val TIPO_CABECERA = 0
        private const val TIPO_SOCIO = 1
    }

    override fun getItemViewType(position: Int) = when (getItem(position)) {
        is FilaSocio.Cabecera -> TIPO_CABECERA
        is FilaSocio.Item -> TIPO_SOCIO
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TIPO_CABECERA) {
            CabeceraViewHolder(ItemCabeceraDiaBinding.inflate(inflater, parent, false))
        } else {
            SocioViewHolder(ItemSocioBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val fila = getItem(position)) {
            is FilaSocio.Cabecera -> (holder as CabeceraViewHolder).bind(fila)
            is FilaSocio.Item -> (holder as SocioViewHolder).bind(fila.socio)
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        val fila = getItem(position)
        if (PAYLOAD_HECHO_CHANGED in payloads && holder is SocioViewHolder && fila is FilaSocio.Item) {
            holder.updateHechoState(fila.socio)
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        (holder as? SocioViewHolder)?.cancelAnimation()
    }

    class CabeceraViewHolder(private val binding: ItemCabeceraDiaBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(cabecera: FilaSocio.Cabecera) {
            val context = binding.root.context
            val ganancia = cabecera.ganancia.toEurosCompacto(prefix = "+")
            binding.tvCabeceraDia.text = cabecera.dia.nombreCompleto
            binding.tvCabeceraDetalle.text =
                context.getString(R.string.cabecera_dia_detalle, cabecera.hechos, cabecera.socios, ganancia)
            binding.root.contentDescription = context.resources.getQuantityString(
                R.plurals.cabecera_dia_a11y, cabecera.socios, cabecera.dia.nombreCompleto, cabecera.hechos, cabecera.socios, ganancia
            )
        }
    }

    inner class SocioViewHolder(private val binding: ItemSocioBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private var textColorAnimator: ValueAnimator? = null
        private var fondoAnimator: ValueAnimator? = null

        /** Fondo de la placa del importe: verde si está conseguido, ámbar si está pendiente. */
        private val fondoPlaca = GradientDrawable().apply {
            orientation = GradientDrawable.Orientation.TL_BR
            cornerRadius = 18 * binding.root.resources.displayMetrics.density
        }

        init {
            binding.placaImporte.background = fondoPlaca

            // Los listeners se registran una sola vez y leen el socio actual de la lista en el momento
            // del clic. Si capturasen el socio del último bind, tras una actualización parcial (payload)
            // editarían una copia obsoleta y podrían deshacer el cambio de "hecho".
            binding.switchHecho.setOnClickListener { view ->
                HapticUtils.performToggle(view)
                socioActual()?.let { onCambiarHecho(it, binding.switchHecho.isChecked) }
            }

            // Tocar la tarjeta la edita (o, en búsqueda y pendientes, abre su semana); mantenerla pulsada
            // abre el panel con editar y eliminar. Así la fila solo lleva el interruptor y deja sitio al nombre.
            val alTocar = onAbrir ?: onEdit
            binding.root.setOnClickListener { view ->
                HapticUtils.performClick(view)
                socioActual()?.let(alTocar)
            }
            binding.root.setOnLongClickListener { view ->
                HapticUtils.performConfirm(view)
                socioActual()?.let(onAcciones)
                true
            }

            // Con TalkBack, las mismas acciones sin tener que abrir el panel.
            val recursos = binding.root.resources
            ViewCompat.replaceAccessibilityAction(
                binding.root, AccessibilityActionCompat.ACTION_CLICK,
                recursos.getString(if (onAbrir != null) R.string.socio_accion_abrir_semana else R.string.cd_editar_socio)
            ) { _, _ -> socioActual()?.let(alTocar); true }
            if (onAbrir != null) {
                ViewCompat.addAccessibilityAction(binding.root, recursos.getString(R.string.cd_editar_socio)) { _, _ ->
                    socioActual()?.let(onEdit); true
                }
            }
            ViewCompat.addAccessibilityAction(binding.root, recursos.getString(R.string.cd_eliminar_socio)) { _, _ ->
                socioActual()?.let(onDelete); true
            }
        }

        private fun socioActual(): Socio? =
            bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }
                ?.let { (getItem(it) as? FilaSocio.Item)?.socio }

        fun bind(socio: Socio) {
            val context = binding.root.context
            val baseX2 = CalculoComisiones.calcularBaseX2(socio.colaboracion)

            binding.tvColaboracionAmount.text = socio.colaboracion.toEurosCompacto()
            binding.tvBaseX2Amount.text = context.getString(R.string.socio_base_format, baseX2.toEurosCompacto())
            binding.tvDiaBadge.text = if (mostrarSemana) {
                // Día abreviado: con la semana al lado, el nombre completo no deja sitio al estado.
                context.getString(R.string.socio_dia_semana, socio.dia.nombreCorto, DateUtils.rangoCorto(socio.semanaKey))
            } else {
                socio.dia.nombreCompleto
            }

            binding.tvNombreSocio.text = socio.nombreVisible().resolver(context)

            binding.tvNotas.isVisible = socio.notas.isNotBlank()
            binding.tvNotas.text = socio.notas

            updateHechoState(socio, animar = false)
        }

        fun updateHechoState(socio: Socio, animar: Boolean = true) {
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

            if (animar) {
                animateTextColor(binding.tvEstadoTexto, targetColor)
            } else {
                // Al reutilizar la fila para otro socio, sin fundido desde el color del anterior.
                textColorAnimator?.cancel()
                binding.tvEstadoTexto.setTextColor(targetColor)
            }
            pintarPlaca(socio.hecho, animar)
        }

        private fun pintarPlaca(hecho: Boolean, animar: Boolean) {
            fondoAnimator?.cancel()
            fondoAnimator = fondoPlaca.fundirA(coloresDegradadoSocio(binding.root.context, hecho), animar) { duration = 300L }
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
            fondoAnimator?.cancel()
            fondoAnimator = null
        }
    }

    class FilaDiffCallback : DiffUtil.ItemCallback<FilaSocio>() {
        override fun areItemsTheSame(oldItem: FilaSocio, newItem: FilaSocio): Boolean = oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: FilaSocio, newItem: FilaSocio): Boolean = oldItem == newItem

        override fun getChangePayload(oldItem: FilaSocio, newItem: FilaSocio): Any? {
            if (oldItem is FilaSocio.Item && newItem is FilaSocio.Item &&
                oldItem.socio.copy(hecho = newItem.socio.hecho) == newItem.socio
            ) {
                return PAYLOAD_HECHO_CHANGED
            }
            return super.getChangePayload(oldItem, newItem)
        }
    }
}
