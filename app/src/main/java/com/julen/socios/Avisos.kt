package com.julen.socios

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.snackbar.BaseTransientBottomBar
import com.google.android.material.snackbar.Snackbar
import com.julen.socios.databinding.ItemAvisoBinding

/**
 * Avisos de la app: una tarjeta flotante con una franja y un icono del color de su tipo, en lugar de la
 * barra oscura estándar. Por debajo es un Snackbar, así que se quita deslizándolo y TalkBack lo lee.
 */
object Avisos {

    private class Estilo(
        @param:DrawableRes val icono: Int,
        @param:ColorRes val fondo: Int,
        @param:ColorRes val color: Int
    )

    private fun estilo(tipo: TipoAviso) = when (tipo) {
        TipoAviso.EXITO -> Estilo(R.drawable.ic_check_circle, R.color.green_container, R.color.green_text_soft)
        TipoAviso.INFO -> Estilo(R.drawable.ic_info, R.color.blue_container, R.color.blue_text_soft)
        TipoAviso.PENDIENTE -> Estilo(R.drawable.ic_schedule, R.color.orange_container, R.color.orange_pending)
        TipoAviso.BORRADO -> Estilo(R.drawable.ic_delete, R.color.red_container, R.color.red_text)
        TipoAviso.ERROR -> Estilo(R.drawable.ic_error, R.color.red_container, R.color.red_text)
        TipoAviso.NOVEDAD -> Estilo(R.drawable.ic_system_update, R.color.blue_container, R.color.blue_text_soft)
    }

    /**
     * Muestra [texto] con el aspecto de [tipo]. Con [anclaje], aparece encima de esa vista (el botón de
     * "Nuevo socio") en lugar de taparla; con [accion], añade un botón que la ejecuta y cierra el aviso.
     */
    fun mostrar(
        vista: View,
        texto: CharSequence,
        tipo: TipoAviso,
        duracion: Int = Snackbar.LENGTH_LONG,
        anclaje: View? = null,
        @StringRes accion: Int? = null,
        alPulsarAccion: (() -> Unit)? = null
    ): Snackbar {
        val snackbar = Snackbar.make(vista, texto, duracion)
        val capa = snackbar.view as ViewGroup
        // Se oculta el contenido estándar (texto y botón) y se usa la tarjeta propia.
        // Fondo transparente (el Snackbar se lo vuelve a poner si solo se quita) y sin sombra propia: sin
        // contorno no la proyecta, pero conserva una elevación alta para quedar encima del resto de vistas de
        // su ventana. Un panel abierto es otra ventana: en ese caso hay que mostrar el aviso dentro de él.
        snackbar.setBackgroundTint(Color.TRANSPARENT)
        val densidad = vista.resources.displayMetrics.density
        capa.outlineProvider = null
        capa.elevation = 24 * densidad
        // Hueco para la sombra de la tarjeta; como relleno del contenedor, porque el margen derecho de la
        // tarjeta se pierde dentro del Snackbar.
        capa.setPadding((4 * densidad).toInt(), (8 * densidad).toInt(), (4 * densidad).toInt(), (8 * densidad).toInt())
        // Que no recorte la sombra de la tarjeta.
        capa.clipChildren = false
        capa.clipToPadding = false
        capa.getChildAt(0).visibility = View.GONE

        val aviso = ItemAvisoBinding.inflate(LayoutInflater.from(vista.context), capa, false)
        val contexto = vista.context
        val estilo = estilo(tipo)
        aviso.franjaAviso.setBackgroundColor(ContextCompat.getColor(contexto, estilo.color))
        aviso.placaAviso.backgroundTintList = ContextCompat.getColorStateList(contexto, estilo.fondo)
        aviso.ivAviso.setImageResource(estilo.icono)
        aviso.ivAviso.imageTintList = ContextCompat.getColorStateList(contexto, estilo.color)
        aviso.tvAviso.text = texto
        if (accion != null && alPulsarAccion != null) {
            // También como acción del propio Snackbar, aunque su botón no se vea: así sabe que tiene una y,
            // con TalkBack, deja el aviso el tiempo suficiente para llegar al botón.
            snackbar.setAction(accion) { alPulsarAccion() }
            aviso.btnAccionAviso.setText(accion)
            aviso.btnAccionAviso.isVisible = true
            aviso.btnAccionAviso.setOnClickListener {
                alPulsarAccion()
                snackbar.dismiss()
            }
        }
        capa.addView(aviso.root)

        snackbar.animationMode = BaseTransientBottomBar.ANIMATION_MODE_SLIDE
        anclaje?.let { snackbar.anchorView = it }
        snackbar.addCallback(object : BaseTransientBottomBar.BaseCallback<Snackbar>() {
            override fun onShown(transientBottomBar: Snackbar) {
                // El icono aparece con un pequeño rebote cuando la tarjeta ya ha entrado.
                aviso.placaAviso.scaleX = 0.4f
                aviso.placaAviso.scaleY = 0.4f
                aviso.placaAviso.animate().scaleX(1f).scaleY(1f).setDuration(DURACION_REBOTE_MS)
                    .setInterpolator(OvershootInterpolator(3f)).start()
            }
        })
        snackbar.show()
        return snackbar
    }

    private const val DURACION_REBOTE_MS = 350L
}
