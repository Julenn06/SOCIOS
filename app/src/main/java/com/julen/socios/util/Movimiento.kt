package com.julen.socios.util

import android.animation.ValueAnimator
import android.content.Context
import android.transition.ChangeBounds
import android.transition.Fade
import android.transition.Transition
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.view.animation.Interpolator
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.julen.socios.R

/**
 * Cómo se mueve la app: una sola curva para todo lo que va de un sitio a otro, duraciones comunes y
 * muelles para lo que se toca (responden al dedo y se pueden interrumpir sin saltos). Si el usuario ha
 * quitado las animaciones en los ajustes de Android, todo cambia al instante.
 */
object Movimiento {

    const val CORTA_MS = 180L
    const val MEDIA_MS = 300L
    const val LARGA_MS = 450L

    /** La curva de la interfaz (res/interpolator/salida_suave): arranca con decisión y se posa despacio. */
    fun curva(context: Context): Interpolator = AnimationUtils.loadInterpolator(context, R.interpolator.salida_suave)

    /** false si el usuario ha quitado las animaciones del sistema. */
    fun activas(): Boolean = ValueAnimator.areAnimatorsEnabled()

    /**
     * Lo que cambia de tamaño, aparece o desaparece, todo a la vez. AutoTransition lo hace por turnos
     * (primero desvanece, luego redimensiona y al final muestra) y deja un instante vacío entre medias.
     */
    fun transicionSuave(context: Context, duracion: Long = MEDIA_MS): Transition =
        TransitionSet()
            .setOrdering(TransitionSet.ORDERING_TOGETHER)
            .addTransition(ChangeBounds())
            .addTransition(Fade())
            .setDuration(duracion)
            .setInterpolator(curva(context))

    /** Lleva un número de [desde] a [hasta] con la curva de la app (la altura de las barras, el avance del bonus…). */
    fun animarValor(context: Context, desde: Float, hasta: Float, duracion: Long, alCambiar: (Float) -> Unit): ValueAnimator =
        ValueAnimator.ofFloat(desde, hasta).apply {
            duration = duracion
            interpolator = curva(context)
            addUpdateListener { alCambiar(it.animatedValue as Float) }
            start()
        }

    /** Anima los cambios que se hagan a continuación dentro de [raiz] (mostrar, ocultar, cambiar de tamaño). */
    fun animarCambios(raiz: ViewGroup, duracion: Long = MEDIA_MS) {
        if (activas()) TransitionManager.beginDelayedTransition(raiz, transicionSuave(raiz.context, duracion))
    }

    /**
     * Lleva la escala de [vista] a [escala] con un muelle. Si ya se estaba moviendo, sigue desde donde iba
     * y con su velocidad: pulsar varias veces seguidas no da saltos.
     */
    fun escalarConMuelle(
        vista: View,
        escala: Float,
        rigidez: Float = SpringForce.STIFFNESS_MEDIUM,
        amortiguacion: Float = SpringForce.DAMPING_RATIO_NO_BOUNCY,
        velocidadInicial: Float = 0f
    ) {
        if (!activas()) {
            vista.scaleX = escala
            vista.scaleY = escala
            return
        }
        for (muelle in listOf(muelle(vista, DynamicAnimation.SCALE_X), muelle(vista, DynamicAnimation.SCALE_Y))) {
            muelle.spring.stiffness = rigidez
            muelle.spring.dampingRatio = amortiguacion
            if (velocidadInicial != 0f) muelle.setStartVelocity(velocidadInicial)
            muelle.animateToFinalPosition(escala)
        }
    }

    /** Hundirse al pulsar: rápido y sin rebote, para que se note enseguida bajo el dedo. */
    fun hundir(vista: View, escala: Float = ESCALA_PULSADO) =
        escalarConMuelle(vista, escala, SpringForce.STIFFNESS_HIGH, SpringForce.DAMPING_RATIO_NO_BOUNCY)

    /** Volver al soltar: con un rebote pequeño, como algo elástico. */
    fun soltar(vista: View) =
        escalarConMuelle(vista, 1f, RIGIDEZ_MEDIA_BAJA, AMORTIGUACION_REBOTE)

    /**
     * Un latido: la vista crece un momento y se posa en su tamaño con un muelle (para celebrar algo, como
     * subir de tramo del bonus). Con [hacia] negativo se encoge en lugar de crecer.
     */
    fun latido(vista: View, hacia: Float = 1f) =
        escalarConMuelle(vista, 1f, SpringForce.STIFFNESS_LOW, AMORTIGUACION_LATIDO, VELOCIDAD_LATIDO * hacia)

    private fun muelle(vista: View, propiedad: DynamicAnimation.ViewProperty): SpringAnimation {
        val tag = if (propiedad == DynamicAnimation.SCALE_X) R.id.tag_muelle_escala_x else R.id.tag_muelle_escala_y
        return vista.getTag(tag) as? SpringAnimation
            ?: SpringAnimation(vista, propiedad).setSpring(SpringForce(1f)).also { vista.setTag(tag, it) }
    }

    /** Entre STIFFNESS_LOW (200) y STIFFNESS_MEDIUM (1500): vuelve con cuerpo, sin hacerse lento. */
    private const val RIGIDEZ_MEDIA_BAJA = 400f
    private const val ESCALA_PULSADO = 0.96f
    private const val AMORTIGUACION_REBOTE = 0.5f
    private const val AMORTIGUACION_LATIDO = 0.35f
    private const val VELOCIDAD_LATIDO = 3f
}
