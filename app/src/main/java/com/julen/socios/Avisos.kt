package com.julen.socios

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.view.animation.LinearInterpolator
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.view.isVisible
import androidx.dynamicanimation.animation.SpringForce
import com.google.android.material.snackbar.BaseTransientBottomBar
import com.google.android.material.snackbar.Snackbar
import com.julen.socios.databinding.ItemAvisoBinding
import com.julen.socios.util.HapticUtils
import com.julen.socios.util.Movimiento
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign

/**
 * Avisos de la app: una tarjeta flotante con el color de su tipo, un título y, si hace falta, un detalle.
 * Por debajo es un Snackbar (TalkBack lo lee y el sistema decide cuándo mostrarlo), con el aspecto propio.
 *
 * - Se quita tocándolo, con su ✕ o deslizándolo hacia cualquier lado, también dentro de los paneles.
 * - Cuánto dura depende de su tipo ([TipoAviso.duracionMs]); una barra fina muestra lo que le queda.
 * - Si ya hay uno a la vista, el nuevo lo sustituye en el sitio en lugar de cerrarlo y abrir otro.
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

    /** El aviso que se está viendo, para sustituirlo en el sitio. Débil: no debe retener la pantalla. */
    private class Visible(val snackbar: Snackbar, val aviso: ItemAvisoBinding, val contenedor: View) {
        /** Se ha pedido cerrarlo (✕, toque, deslizamiento o su acción): ya no sirve para el siguiente. */
        var cerrando = false

        /** Cuándo se irá solo, para no reutilizarlo justo cuando ya está saliendo. */
        var finPrevisto = 0L

        fun cerrar() {
            cerrando = true
            snackbar.dismiss()
        }

        /** Se puede cambiar su contenido: sigue a la vista y no está a punto de irse. */
        fun reutilizable(contenedor: View) = this.contenedor === contenedor && !cerrando &&
            snackbar.isShownOrQueued && SystemClock.uptimeMillis() < finPrevisto - MARGEN_SALIDA_MS
    }

    private var visible: WeakReference<Visible>? = null

    /**
     * Muestra [titulo] (y [detalle] debajo) con el aspecto de [tipo]. Con [anclaje], aparece encima de esa
     * vista (el botón de "Nuevo socio") en lugar de taparla; con [accion], añade un botón que la ejecuta y
     * cierra el aviso.
     */
    fun mostrar(
        vista: View,
        titulo: CharSequence,
        tipo: TipoAviso,
        detalle: CharSequence? = null,
        duracionMs: Int? = null,
        anclaje: View? = null,
        @StringRes accion: Int? = null,
        alPulsarAccion: (() -> Unit)? = null
    ): Snackbar {
        // Con detalle hay más que leer: un segundo más.
        val duracion = duracionMs ?: (tipo.duracionMs + if (detalle != null) TIEMPO_DETALLE_MS else 0)
        // Un aviso que ya se está yendo no se reutiliza: el nuevo se iría con él (pasaba al deshacer un borrado).
        val anterior = visible?.get()?.takeIf { it.reutilizable(vista) }
        val actual = anterior ?: crear(vista, anclaje)
        actual.finPrevisto = SystemClock.uptimeMillis() + duracion

        pintar(actual.aviso, titulo, detalle, tipo)
        val snackbar = actual.snackbar
        snackbar.duration = duracion
        actual.aviso.btnAccionAviso.isVisible = accion != null && alPulsarAccion != null
        if (accion != null && alPulsarAccion != null) {
            // También como acción del propio Snackbar, aunque su botón no se vea: así sabe que tiene una y,
            // con TalkBack, deja el aviso el tiempo suficiente para llegar al botón.
            snackbar.setAction(accion) { alPulsarAccion() }
            actual.aviso.btnAccionAviso.setText(accion)
            actual.aviso.btnAccionAviso.setOnClickListener {
                // Primero se cierra este, así el aviso que traiga la acción sale aparte.
                actual.cerrar()
                alPulsarAccion()
            }
        } else {
            snackbar.setAction(null as CharSequence?, null)
        }

        if (tipo == TipoAviso.ERROR) HapticUtils.performReject(actual.aviso.root)
        if (anterior != null) {
            // Ya estaba a la vista: se cambia el contenido con un latido del icono y vuelve a contar el tiempo.
            Movimiento.latido(actual.aviso.placaAviso)
            empezarCuentaAtras(actual.aviso, duracion)
        }
        // Si ya se estaba mostrando, show() solo vuelve a contar el tiempo desde ahora.
        snackbar.show()
        return snackbar
    }

    private fun crear(vista: View, anclaje: View?): Visible {
        val snackbar = Snackbar.make(vista, "", Snackbar.LENGTH_LONG)
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
        // Que no recorte la sombra de la tarjeta ni la tarjeta al deslizarla.
        capa.clipChildren = false
        capa.clipToPadding = false
        capa.getChildAt(0).visibility = View.GONE

        val aviso = ItemAvisoBinding.inflate(LayoutInflater.from(vista.context), capa, false)
        capa.addView(aviso.root)
        val visible = Visible(snackbar, aviso, vista)
        aviso.btnCerrarAviso.setOnClickListener { visible.cerrar() }
        aviso.root.setOnClickListener { visible.cerrar() }
        // El deslizamiento es el de la propia tarjeta (funciona igual en la pantalla y en los paneles), no el
        // del Snackbar, que solo existe fuera de los paneles.
        snackbar.behavior = object : BaseTransientBottomBar.Behavior() {
            override fun canSwipeDismissView(child: View) = false
        }
        permitirDeslizar(aviso.root) { visible.cerrar() }
        // Que deslizarlo para quitarlo no cambie además de semana (ver DeslizarEntreSemanas).
        aviso.root.setTag(R.id.tag_arrastre_horizontal, true)

        snackbar.animationMode = BaseTransientBottomBar.ANIMATION_MODE_SLIDE
        anclaje?.let { snackbar.anchorView = it }
        snackbar.addCallback(object : BaseTransientBottomBar.BaseCallback<Snackbar>() {
            override fun onShown(transientBottomBar: Snackbar) {
                // El icono entra con un muelle cuando la tarjeta ya ha llegado, y empieza a contar el tiempo.
                aviso.placaAviso.scaleX = ESCALA_ICONO_INICIAL
                aviso.placaAviso.scaleY = ESCALA_ICONO_INICIAL
                Movimiento.escalarConMuelle(aviso.placaAviso, 1f, SpringForce.STIFFNESS_LOW, AMORTIGUACION_ICONO)
                empezarCuentaAtras(aviso, transientBottomBar.duration)
            }

            override fun onDismissed(transientBottomBar: Snackbar, event: Int) {
                aviso.barraTiempoAviso.animate().cancel()
                if (this@Avisos.visible?.get() === visible) this@Avisos.visible = null
            }
        })
        this.visible = WeakReference(visible)
        return visible
    }

    private fun pintar(aviso: ItemAvisoBinding, titulo: CharSequence, detalle: CharSequence?, tipo: TipoAviso) {
        val contexto = aviso.root.context
        val estilo = estilo(tipo)
        val color = ContextCompat.getColor(contexto, estilo.color)
        aviso.franjaAviso.setBackgroundColor(color)
        aviso.barraTiempoAviso.setBackgroundColor(color)
        pintarPlaca(aviso.placaAviso, aviso.ivAviso, estilo.icono, estilo.fondo, estilo.color)
        aviso.tvAviso.text = titulo
        // Los motivos de los errores pueden venir en minúscula (pensados para ir detrás de otra frase).
        val detalleVisible = detalle?.toString()?.replaceFirstChar { it.uppercaseChar() }
        aviso.tvDetalleAviso.text = detalleVisible
        aviso.tvDetalleAviso.isVisible = !detalleVisible.isNullOrBlank()
        aviso.root.contentDescription = listOfNotNull(titulo, detalleVisible).joinToString(". ")
    }

    /**
     * La barra de abajo se vacía en lo que le queda al aviso. Con TalkBack el sistema lo deja más tiempo (o
     * hasta que se cierre), así que la barra no diría la verdad y no se muestra.
     */
    private fun empezarCuentaAtras(aviso: ItemAvisoBinding, duracion: Int) {
        val barra = aviso.barraTiempoAviso
        barra.animate().cancel()
        val conTalkBack = barra.context.getSystemService<AccessibilityManager>()?.isTouchExplorationEnabled == true
        barra.isVisible = !conTalkBack && duracion > 0 && Movimiento.activas()
        if (!barra.isVisible) return
        barra.pivotX = 0f
        barra.scaleX = 1f
        barra.animate().scaleX(0f).setDuration(duracion.toLong()).setInterpolator(LinearInterpolator()).start()
    }

    /**
     * La tarjeta se puede arrastrar hacia un lado: si se suelta lejos o se lanza con fuerza, sale de la
     * pantalla y se cierra; si no, vuelve a su sitio. Un toque sin arrastrar la cierra también.
     */
    @SuppressLint("ClickableViewAccessibility") // El toque sin arrastrar llama a performClick().
    private fun permitirDeslizar(tarjeta: View, alDescartar: () -> Unit) {
        val configuracion = ViewConfiguration.get(tarjeta.context)
        val umbralArrastre = configuracion.scaledTouchSlop
        val velocidadMinima = configuracion.scaledMinimumFlingVelocity * FACTOR_LANZAMIENTO
        var xInicial = 0f
        var arrastrando = false
        var seguimiento: VelocityTracker? = null

        tarjeta.setOnTouchListener { vista, evento ->
            when (evento.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    xInicial = evento.rawX
                    arrastrando = false
                    seguimiento?.recycle()
                    seguimiento = VelocityTracker.obtain().also { it.addMovement(evento) }
                    vista.animate().cancel()
                }

                MotionEvent.ACTION_MOVE -> {
                    seguimiento?.addMovement(evento)
                    val dx = evento.rawX - xInicial
                    if (!arrastrando && abs(dx) > umbralArrastre) {
                        arrastrando = true
                        vista.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (arrastrando) {
                        vista.translationX = dx
                        vista.alpha = 1f - min(1f, abs(dx) / vista.width) * 0.7f
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dx = evento.rawX - xInicial
                    val vx = seguimiento?.run { addMovement(evento); computeCurrentVelocity(1000); xVelocity } ?: 0f
                    seguimiento?.recycle()
                    seguimiento = null
                    val lanzada = abs(vx) > velocidadMinima && sign(vx) == sign(dx)
                    when {
                        arrastrando && (abs(dx) > vista.width * FRACCION_DESCARTE || lanzada) -> {
                            // Termina de salir por el lado hacia el que iba y entonces se cierra.
                            vista.animate()
                                .translationX(sign(dx) * vista.width)
                                .alpha(0f)
                                .setDuration(Movimiento.CORTA_MS)
                                .setInterpolator(Movimiento.curva(vista.context))
                                .withEndAction(alDescartar)
                                .start()
                        }

                        arrastrando -> vista.animate()
                            .translationX(0f)
                            .alpha(1f)
                            .setDuration(Movimiento.MEDIA_MS)
                            .setInterpolator(Movimiento.curva(vista.context))
                            .start()

                        evento.actionMasked == MotionEvent.ACTION_UP -> vista.performClick()
                    }
                    arrastrando = false
                }
            }
            true
        }
    }

    private const val TIEMPO_DETALLE_MS = 1000

    /** Lo que tarda en salir un aviso al acabarse su tiempo: en ese margen ya no se reutiliza. */
    private const val MARGEN_SALIDA_MS = 400L
    private const val ESCALA_ICONO_INICIAL = 0.4f
    private const val AMORTIGUACION_ICONO = 0.45f

    /** Qué parte de su ancho hay que arrastrar la tarjeta para que se cierre al soltarla. */
    private const val FRACCION_DESCARTE = 0.3f

    /** Un lanzamiento tiene que ser bastante más rápido que el mínimo del sistema para cerrar el aviso. */
    private const val FACTOR_LANZAMIENTO = 4
}
