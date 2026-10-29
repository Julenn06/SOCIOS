package com.julen.socios

import android.content.res.ColorStateList
import android.transition.ChangeBounds
import android.transition.Fade
import android.transition.Transition
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.view.View
import android.view.animation.AccelerateInterpolator
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.julen.socios.databinding.ActivityMainBinding
import com.julen.socios.util.DateUtils
import com.julen.socios.util.Movimiento
import com.julen.socios.util.rangoSemanaConAnio
import com.julen.socios.util.semanaRelativa
import java.time.LocalDate
import kotlin.math.sign

/**
 * El selector de semana de la pantalla principal: flechas, fecha, a cuánto está de hoy (con un punto de color) y los
 * días de la semana, más el botón "Hoy" cuando se mira otra. Al cambiar de semana se desliza solo su texto, hacia el
 * lado al que se va; la página no se mueve (ver la nota de [ListaSemana]).
 */
class SelectorSemana(
    private val binding: ActivityMainBinding,
    alCambiar: (Int) -> Unit,
    alIrAHoy: () -> Unit
) {
    private val context = binding.root.context
    private val desplazamiento = DESPLAZAMIENTO_DP * context.resources.displayMetrics.density
    private val curva = Movimiento.curva(context)

    /** La semana mostrada la última vez y a cuántas semanas de la actual estaba; null antes de mostrar nada. */
    private var anterior: Pair<String, Int>? = null

    init {
        binding.btnSemanaAnterior.setOnClickListener { alCambiar(-1) }
        binding.btnSemanaSiguiente.setOnClickListener { alCambiar(1) }
        val irAHoy = View.OnClickListener { alIrAHoy() }
        binding.btnHoyLeft.setOnClickListener(irAHoy)
        binding.btnHoyRight.setOnClickListener(irAHoy)
    }

    fun mostrar(state: MainUiState) {
        val distancia = state.weekOffset
        val previa = anterior
        val primeraVez = previa == null
        anterior = state.semanaKey to distancia

        // Los días se comprueban siempre: hoy puede cambiar con la app abierta.
        val momento = when {
            distancia < 0 -> FranjaSemanaView.Momento.PASADA
            distancia > 0 -> FranjaSemanaView.Momento.FUTURA
            else -> FranjaSemanaView.Momento.ACTUAL
        }
        binding.franjaSemana.mostrar(momento, LocalDate.now().dayOfWeek.value - 1, animar = !primeraVez)
        if (previa == state.semanaKey to distancia) return

        // Al cambiar de semana sin moverse (el lunes, con la app abierta) no hay hacia dónde deslizar.
        deslizarTextos(if (previa == null) 0 else (distancia - previa.second).sign) {
            // El año solo si no es el de ahora: "19–23 oct" cabe hasta en pantallas estrechas con "Hoy" al lado.
            val delAnioActual = state.semanaKey.take(4) == DateUtils.getSemanaKey(0).take(4)
            binding.tvRangoSemana.text =
                if (delAnioActual) DateUtils.rangoCorto(state.semanaKey) else context.rangoSemanaConAnio(state.semanaKey)
            binding.tvSemanaSubtitulo.text = context.semanaRelativa(distancia, R.string.subtitle_current_week)
            pintarMomento(distancia)
            // A la vez que el texto: mientras la tarjeta cambia de tamaño, Android no recoloca lo de dentro, y un
            // texto nuevo más largo se quedaría cortado hasta el final.
            mostrarHoy(distancia, animar = !primeraVez)
        }
    }

    /** "Hoy" aparece del lado de la semana actual; la tarjeta se estrecha o se ensancha con suavidad. */
    private fun mostrarHoy(distancia: Int, animar: Boolean) {
        val izquierda = distancia > 0
        val derecha = distancia < 0
        if (binding.btnHoyLeft.isVisible == izquierda && binding.btnHoyRight.isVisible == derecha) return
        if (animar && Movimiento.activas()) {
            TransitionManager.beginDelayedTransition(binding.filaSemana, transicionHoy(aparece = izquierda || derecha))
        }
        binding.btnHoyLeft.isVisible = izquierda
        binding.btnHoyRight.isVisible = derecha
    }

    /**
     * La tarjeta se estrecha (o se ensancha) mientras "Hoy" se desvanece; al aparecer, "Hoy" espera a que la
     * tarjeta le haya dejado sitio, para no verse un momento encima de la flecha.
     */
    private fun transicionHoy(aparece: Boolean): Transition = TransitionSet()
        .setOrdering(TransitionSet.ORDERING_TOGETHER)
        .addTransition(ChangeBounds().setDuration(Movimiento.MEDIA_MS))
        .addTransition(
            Fade(if (aparece) Fade.IN else Fade.OUT)
                .setDuration(if (aparece) Movimiento.CORTA_MS else Movimiento.CORTA_MS / 2)
                .setStartDelay(if (aparece) Movimiento.MEDIA_MS / 2 else 0)
        )
        .setInterpolator(curva)

    /** El punto y el texto del color del momento: verde la semana actual, gris las pasadas y azul las futuras. */
    private fun pintarMomento(distancia: Int) {
        val (punto, texto) = when {
            distancia == 0 -> R.color.primary to R.color.primary
            distancia < 0 -> R.color.stroke to R.color.text_sub
            else -> R.color.blue_text to R.color.blue_text
        }
        binding.puntoSemana.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, punto))
        binding.tvSemanaSubtitulo.setTextColor(ContextCompat.getColor(context, texto))
    }

    /**
     * Saca el texto hacia el lado contrario a [direccion] (1: semana siguiente; -1: anterior), lo [cambia] y lo
     * trae desde el otro lado. Con [direccion] 0 o sin animaciones, lo cambia sin más. Si se pulsa otra vez a
     * medias, la animación anterior se corta y manda la última.
     */
    private fun deslizarTextos(direccion: Int, cambiar: () -> Unit) {
        val vistas = listOf(binding.tvRangoSemana, binding.filaSubtituloSemana)
        vistas.forEach { it.animate().cancel() }
        if (direccion == 0 || !Movimiento.activas()) {
            vistas.forEach {
                it.translationX = 0f
                it.alpha = 1f
            }
            cambiar()
            return
        }
        val hacia = desplazamiento * direccion
        vistas.forEachIndexed { i, vista ->
            vista.animate()
                .translationX(-hacia)
                .alpha(0f)
                .setDuration(SALIDA_MS)
                .setStartDelay(0)
                .setInterpolator(AccelerateInterpolator())
                .withEndAction {
                    if (i == 0) cambiar()
                    vista.translationX = hacia
                    vista.animate()
                        .translationX(0f)
                        .alpha(1f)
                        .setDuration(ENTRADA_MS)
                        .setStartDelay(i * ESCALONADO_MS)
                        .setInterpolator(curva)
                        .start()
                }
                .start()
        }
    }

    private companion object {
        const val DESPLAZAMIENTO_DP = 18f
        const val SALIDA_MS = 110L
        const val ENTRADA_MS = 260L
        const val ESCALONADO_MS = 40L
    }
}
