package com.julen.socios.vidrio

import android.app.Dialog
import android.app.UiModeManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.annotation.ColorRes
import androidx.appcompat.app.AlertDialog
import androidx.annotation.RequiresApi
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.shape.MaterialShapeDrawable
import com.julen.socios.R
import java.util.function.Consumer
import kotlin.math.roundToInt

/**
 * Cómo es un vidrio. El vidrio es para lo que flota sobre el contenido y se toca (barras, botones, avisos,
 * paneles), nunca para el contenido en sí: las tarjetas siguen siendo tarjetas.
 */
class EstiloVidrio(
    @param:ColorRes val tinte: Int,
    /** Tinte mínimo con el shader (Android 13+): lo sube solo donde hace falta para que el texto se lea. */
    val fuerzaTinte: Float,
    /** Tinte fijo sin shader (Android 12): tiene que bastar con cualquier fondo detrás. */
    val fuerzaSinShader: Float,
    /** Los colores de texto que van encima: el tinte se ajusta al que menos contrasta para que llegue a 4,5:1. */
    val textos: List<Int>,
    /** El color opaco de siempre, para cuando no hay nada detrás que mostrar. */
    @param:ColorRes val solido: Int,
    val radioDp: Float,
    val desenfoqueDp: Float,
    /** Cuántos dp se desvía la imagen en el mismo filo. */
    val refraccionDp: Float,
    /** Anchura de la franja del borde que curva la luz. */
    val bandaDp: Float = 18f,
    /** Cuánto más vivo se ve lo de detrás (1 = igual). */
    val saturacion: Float = 1.5f,
    /** Brillo amplio en la mitad de arriba, como el reflejo de una superficie curva. */
    val brilloSuperior: Float = 0f,
    /** Solo se ve el borde de abajo: la barra superior, que llega a los bordes de la pantalla. */
    val soloBordeInferior: Boolean = false,
    /** Brilla donde se pulsa y la lente se hunde. */
    val interactivo: Boolean = false
) {
    init {
        require(textos.isNotEmpty()) { "Un vidrio necesita saber qué texto lleva encima" }
    }

    companion object {
        val BARRA = EstiloVidrio(
            R.color.vidrio_barra, fuerzaTinte = 0.3f, fuerzaSinShader = 0.62f,
            // Solo el título y los iconos: el selector de semana va en su propia cápsula de vidrio.
            textos = listOf(R.color.text_main),
            solido = R.color.bg_light,
            radioDp = 0f, desenfoqueDp = 16f, refraccionDp = 10f, bandaDp = 14f, saturacion = 1.6f,
            soloBordeInferior = true
        )
        /** El selector de semana, una cápsula sobre la barra que se tiñe según sus textos. */
        val CAPSULA = EstiloVidrio(
            R.color.vidrio_aviso, fuerzaTinte = 0.3f, fuerzaSinShader = 0.7f,
            // El subtítulo de la semana cambia de color según sea la actual, una pasada o una futura.
            textos = listOf(R.color.text_main, R.color.primary, R.color.text_sub, R.color.blue_text),
            solido = R.color.surface_card,
            radioDp = 20f, desenfoqueDp = 12f, refraccionDp = 10f, bandaDp = 16f, saturacion = 1.6f,
            brilloSuperior = 0.05f
        )
        val BOTON = EstiloVidrio(
            R.color.vidrio_boton, fuerzaTinte = 0.6f, fuerzaSinShader = 0.88f,
            textos = listOf(R.color.on_primary), solido = R.color.primary,
            radioDp = 20f, desenfoqueDp = 6f, refraccionDp = 14f, bandaDp = 20f, saturacion = 1.3f,
            brilloSuperior = 0.08f, interactivo = true
        )
        val AVISO = EstiloVidrio(
            R.color.vidrio_aviso, fuerzaTinte = 0.4f, fuerzaSinShader = 0.66f,
            textos = listOf(R.color.text_main, R.color.text_sub, R.color.on_primary_container),
            solido = R.color.surface_card,
            radioDp = 20f, desenfoqueDp = 14f, refraccionDp = 12f, bandaDp = 18f, saturacion = 1.6f,
            brilloSuperior = 0.05f
        )
    }
}

/** Un vidrio ya puesto en una vista (solo existe desde Android 12). */
interface VidrioPuesto {
    /** Muestra el borde en la medida [fraccion] (0–1): la barra lo enciende al pasar contenido por debajo. */
    fun mostrarBorde(fraccion: Float)
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
     * [desde] (por defecto, la de la propia vista), y lo devuelve. Si no se puede (Android 11 o anterior, más contraste
     * o una ventana sin fuente, como un panel) no toca nada y devuelve null: la vista se queda como estaba.
     */
    fun aplicar(vista: View, estilo: EstiloVidrio, desde: View = vista): VidrioPuesto? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || reducirTransparencia(vista.context)) return null
        val fuente = buscarFuente(desde.rootView) ?: return null
        if (estaDentro(vista, fuente)) return null
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
        return vidrio
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

/**
 * Para la [FuenteVidrio] de la pantalla principal, que ocupa toda la pantalla: su [scroll] empieza justo debajo de la
 * barra. El relleno se pone al medir (la barra se mide antes, porque depende de ella), no después de colocar la barra:
 * así no hace falta un segundo pase de layout.
 */
class DebajoDeLaBarra(private val scroll: View) : CoordinatorLayout.Behavior<View>() {

    override fun layoutDependsOn(parent: CoordinatorLayout, child: View, dependency: View) = dependency is AppBarLayout

    override fun onMeasureChild(
        parent: CoordinatorLayout,
        child: View,
        parentWidthMeasureSpec: Int,
        widthUsed: Int,
        parentHeightMeasureSpec: Int,
        heightUsed: Int
    ): Boolean {
        val barra = parent.getDependencies(child).firstOrNull { it is AppBarLayout }
        if (barra != null && scroll.paddingTop != barra.measuredHeight) scroll.updatePadding(top = barra.measuredHeight)
        return false
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
 * queda detrás de la ventana, y entonces el panel o el diálogo se vuelven translúcidos, con un filo de luz, y se ven
 * como vidrio esmerilado. El sistema puede quitar ese desenfoque en cualquier momento (ahorro de batería, una GPU
 * que no puede): entonces vuelven a ser opacos y la ventana de detrás se oscurece como siempre.
 */
object VidrioVentana {

    /** Un panel inferior: [hoja] es la hoja del BottomSheet. */
    fun aplicar(dialogo: Dialog, hoja: View?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val ventana = dialogo.window ?: return
        if (Vidrio.reducirTransparencia(ventana.context)) return
        DesenfoqueDetras(ventana, hoja, flotante = false).empezar()
    }

    /** Un diálogo, ya mostrado. Su fondo es la ventana entera (con sus márgenes y esquinas). */
    fun aplicarDialogo(dialogo: Dialog) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val ventana = dialogo.window ?: return
        if (Vidrio.reducirTransparencia(ventana.context)) return
        DesenfoqueDetras(ventana, hoja = null, flotante = true).empezar()
    }
}

@RequiresApi(Build.VERSION_CODES.S)
private class DesenfoqueDetras(
    private val ventana: Window,
    private val hoja: View?,
    /** Un diálogo flotante: además de lo de detrás, se desenfoca lo que queda bajo su propio fondo. */
    private val flotante: Boolean
) : Consumer<Boolean> {

    private val contexto = ventana.context
    private val densidad = contexto.resources.displayMetrics.density
    private val radio = (RADIO_DP * densidad).roundToInt()
    private val atenuadoNormal = ventana.attributes.dimAmount
    private val opaco = ColorStateList.valueOf(ContextCompat.getColor(contexto, R.color.surface_card))
    private val translucido = ColorStateList.valueOf(ContextCompat.getColor(contexto, R.color.vidrio_panel))
    private val filo = ContextCompat.getColorStateList(contexto, R.color.vidrio_filo_tarjeta)

    fun empezar() {
        ventana.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        ventana.decorView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = escuchar()

            override fun onViewDetachedFromWindow(v: View) =
                ventana.windowManager.removeCrossWindowBlurEnabledListener(this@DesenfoqueDetras)
        })
        if (ventana.decorView.isAttachedToWindow) escuchar()
    }

    // Avisa enseguida con el estado actual y luego con cada cambio.
    private fun escuchar() = ventana.windowManager.addCrossWindowBlurEnabledListener(contexto.mainExecutor, this)

    override fun accept(activo: Boolean) {
        ventana.attributes = ventana.attributes.apply { blurBehindRadius = if (activo) radio else 0 }
        ventana.setDimAmount(if (activo) ATENUADO_CON_DESENFOQUE else atenuadoNormal)
        if (flotante) ventana.setBackgroundBlurRadius(if (activo) radio else 0)
        val superficie = hoja ?: ventana.decorView
        superficie.backgroundTintList = if (activo) translucido else opaco
        // La forma la pone Material al colocar la hoja: el filo se pone cuando ya existe.
        superficie.post { formaDe(superficie.background)?.setStroke(if (activo) densidad else 0f, filo) }
    }

    private fun formaDe(fondo: Drawable?): MaterialShapeDrawable? = when (fondo) {
        is MaterialShapeDrawable -> fondo
        is InsetDrawable -> fondo.drawable as? MaterialShapeDrawable
        else -> null
    }

    private companion object {
        const val RADIO_DP = 24f
        const val ATENUADO_CON_DESENFOQUE = 0.12f
    }
}

/** Muestra el diálogo y, desde Android 12, lo vuelve vidrio esmerilado (ver [VidrioVentana]). */
fun MaterialAlertDialogBuilder.mostrarConVidrio(): AlertDialog = show().also(VidrioVentana::aplicarDialogo)
