package com.julen.socios.vidrio

import android.app.Dialog
import android.app.UiModeManager
import android.content.Context
import android.content.res.ColorStateList
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.annotation.ColorRes
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.julen.socios.R
import java.util.function.Consumer
import kotlin.math.roundToInt

/**
 * Cómo es un vidrio. El vidrio es para lo que flota sobre el contenido y se toca (barras, botones, avisos,
 * paneles), nunca para el contenido en sí: las tarjetas siguen siendo tarjetas.
 */
class EstiloVidrio(
    @param:ColorRes val tinte: Int,
    /** Cuánto tapa el tinte lo de detrás (0–1): más en lo que lleva texto, para que se lea siempre. */
    val fuerzaTinte: Float,
    /** El color opaco de siempre, para cuando no hay nada detrás que mostrar. */
    @param:ColorRes val solido: Int,
    val radioDp: Float,
    val desenfoqueDp: Float,
    /** Cuántos dp se desvía la imagen en el mismo filo. */
    val refraccionDp: Float,
    /** Anchura de la franja del borde que curva la luz. */
    val bandaDp: Float = 14f,
    /** Solo se ve el borde de abajo: la barra superior, que llega a los bordes de la pantalla. */
    val soloBordeInferior: Boolean = false,
    /** Brilla donde se pulsa. */
    val interactivo: Boolean = false
) {
    companion object {
        val BARRA = EstiloVidrio(
            R.color.vidrio_barra, fuerzaTinte = 0.62f, solido = R.color.bg_light,
            radioDp = 0f, desenfoqueDp = 14f, refraccionDp = 8f, bandaDp = 12f, soloBordeInferior = true
        )
        val BOTON = EstiloVidrio(
            R.color.vidrio_boton, fuerzaTinte = 0.88f, solido = R.color.primary,
            radioDp = 20f, desenfoqueDp = 6f, refraccionDp = 10f, interactivo = true
        )
        val AVISO = EstiloVidrio(
            R.color.vidrio_aviso, fuerzaTinte = 0.66f, solido = R.color.surface_card,
            radioDp = 20f, desenfoqueDp = 12f, refraccionDp = 10f
        )
    }
}

object Vidrio {

    /**
     * Si el usuario ha subido el contraste en los ajustes de Android (desde Android 14): entonces nada es
     * translúcido y todo se ve con sus colores opacos de siempre.
     */
    fun reducirTransparencia(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            context.getSystemService(UiModeManager::class.java).contrast > 0f

    /**
     * Pone el vidrio de [estilo] de fondo de [vista], con lo que se ve en la [FuenteVidrio] de la ventana de
     * [desde] (por defecto, la de la propia vista). Si no se puede (Android 11 o anterior, más contraste o una
     * ventana sin fuente, como un panel) no toca nada y devuelve false: la vista se queda como estaba.
     */
    fun aplicar(vista: View, estilo: EstiloVidrio, desde: View = vista): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || reducirTransparencia(vista.context)) return false
        val fuente = buscarFuente(desde.rootView) ?: return false
        if (estaDentro(vista, fuente)) return false
        val vidrio = VidrioDrawable(vista, estilo, fuente)
        // Los botones y tarjetas de Material avisan en el log de que su fondo se sustituye: es lo que se busca.
        // Su tinte y su borde ya no aplican; el filo de luz los sustituye.
        vista.background = vidrio
        when (vista) {
            is MaterialButton -> vista.backgroundTintList = null
            is MaterialCardView -> vista.strokeWidth = 0
        }
        // El contenido se recorta a la forma del vidrio (la franja de color de un aviso, por ejemplo).
        vista.clipToOutline = !estilo.soloBordeInferior
        return true
    }

    /**
     * Rehace [actividad] cuando el contraste de los ajustes pasa de normal a más alto o al revés (Android 14+): el
     * contraste no es parte de la configuración, así que Android no la rehace solo.
     */
    fun recrearAlCambiarContraste(actividad: ComponentActivity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ObservadorContraste.observar(actividad)
    }

    private fun buscarFuente(vista: View): FuenteVidrio? {
        if (vista is FuenteVidrio) return vista
        if (vista !is ViewGroup) return null
        for (i in 0 until vista.childCount) buscarFuente(vista.getChildAt(i))?.let { return it }
        return null
    }
}

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
private object ObservadorContraste {
    fun observar(actividad: ComponentActivity) {
        val modos = actividad.getSystemService(UiModeManager::class.java)
        val reducidoAlEmpezar = Vidrio.reducirTransparencia(actividad)
        // Solo si cambia de verdad lo que se ve: así nunca se rehace en bucle.
        val alCambiar = UiModeManager.ContrastChangeListener {
            if (Vidrio.reducirTransparencia(actividad) != reducidoAlEmpezar) actividad.recreate()
        }
        modos.addContrastChangeListener(actividad.mainExecutor, alCambiar)
        actividad.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) = modos.removeContrastChangeListener(alCambiar)
        })
    }
}

/**
 * Un vidrio dentro de su propia fuente se vería a sí mismo una y otra vez. Se mira al aplicarlo y otra vez al
 * engancharse a la ventana: un aviso aún no cuelga de nadie cuando se le pone el vidrio.
 */
internal fun estaDentro(vista: View, fuente: FuenteVidrio): Boolean {
    var padre = vista.parent
    while (padre != null) {
        if (padre === fuente) return true
        padre = padre.parent
    }
    return false
}

/**
 * Los paneles y diálogos son otras ventanas: no ven la [FuenteVidrio]. Desde Android 12 el sistema desenfoca lo que
 * queda detrás de la ventana, y entonces el panel se vuelve translúcido y se ve como vidrio esmerilado. El sistema
 * puede quitar ese desenfoque en cualquier momento (ahorro de batería, una GPU que no puede): entonces el panel
 * vuelve a ser opaco y la ventana de detrás se oscurece como siempre.
 */
object VidrioVentana {

    fun aplicar(dialogo: Dialog, hoja: View?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val ventana = dialogo.window ?: return
        if (Vidrio.reducirTransparencia(ventana.context)) return
        DesenfoqueDetras(ventana, hoja).empezar()
    }
}

@RequiresApi(Build.VERSION_CODES.S)
private class DesenfoqueDetras(private val ventana: Window, private val hoja: View?) : Consumer<Boolean> {

    private val contexto = ventana.context
    private val radio = (RADIO_DP * contexto.resources.displayMetrics.density).roundToInt()
    private val atenuadoNormal = ventana.attributes.dimAmount
    private val opaco = ColorStateList.valueOf(ContextCompat.getColor(contexto, R.color.surface_card))
    private val translucido = ColorStateList.valueOf(ContextCompat.getColor(contexto, R.color.vidrio_panel))

    fun empezar() {
        ventana.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        ventana.decorView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = escuchar()

            override fun onViewDetachedFromWindow(v: View) = ventana.windowManager.removeCrossWindowBlurEnabledListener(this@DesenfoqueDetras)
        })
        if (ventana.decorView.isAttachedToWindow) escuchar()
    }

    // Avisa enseguida con el estado actual y luego con cada cambio.
    private fun escuchar() = ventana.windowManager.addCrossWindowBlurEnabledListener(contexto.mainExecutor, this)

    override fun accept(activo: Boolean) {
        ventana.attributes = ventana.attributes.apply { blurBehindRadius = if (activo) radio else 0 }
        ventana.setDimAmount(if (activo) ATENUADO_CON_DESENFOQUE else atenuadoNormal)
        hoja?.backgroundTintList = if (activo) translucido else opaco
    }

    private companion object {
        const val RADIO_DP = 24f
        const val ATENUADO_CON_DESENFOQUE = 0.12f
    }
}
