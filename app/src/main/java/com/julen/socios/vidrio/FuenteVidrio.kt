package com.julen.socios.vidrio

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RenderNode
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import androidx.core.widget.NestedScrollView

/**
 * Lo que se ve a través del vidrio. Guarda lo que pintan sus hijos en un RenderNode que los vidrios de encima
 * vuelven a pintar desenfocado y refractado, sin copiar píxeles: todo se queda en la GPU.
 *
 * Solo desde Android 12 (RenderEffect); antes se pinta como un FrameLayout cualquiera y no hay vidrio. Los vidrios
 * tienen que pintarse después que ella (hermanos que van detrás en el layout), nunca dentro: se verían a sí mismos.
 */
class FuenteVidrio @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val guardado: RenderNode? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) RenderNode("FuenteVidrio") else null

    /**
     * El fondo va dentro del nodo (sin él, donde no hay contenido el vidrio vería transparente y se oscurecería), así
     * que no se pinta también como fondo de la vista: sería pintar la pantalla entera dos veces.
     */
    private val fondo: Drawable? = background?.also { if (guardado != null) background = null }

    private val vidrios = mutableSetOf<View>()

    /**
     * Lo pintado en el último fotograma con aceleración, o null si no hay (Android 11 o anterior). Un dibujo por
     * software (una captura) no lo borra: el nodo sigue siendo lo que se ve en pantalla.
     */
    internal var nodo: RenderNode? = null
        private set

    internal fun registrar(vidrio: View) {
        vidrios += vidrio
    }

    internal fun olvidar(vidrio: View) {
        vidrios -= vidrio
    }

    /**
     * Ha cambiado algo de dentro: los vidrios se marcan ya, antes de pintar, para que se vuelvan a grabar en este
     * mismo fotograma con el contenido nuevo (y no un fotograma después).
     */
    override fun onDescendantInvalidated(child: View, target: View) {
        super.onDescendantInvalidated(child, target)
        vidrios.forEach(View::invalidate)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fondo?.setBounds(0, 0, w, h)
        vidrios.forEach(View::invalidate)
    }

    override fun dispatchDraw(canvas: Canvas) {
        val guardado = guardado
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || guardado == null || !canvas.isHardwareAccelerated) {
            if (guardado != null) fondo?.draw(canvas)
            super.dispatchDraw(canvas)
            return
        }
        guardado.setPosition(0, 0, width, height)
        val grabacion = guardado.beginRecording(width, height)
        try {
            fondo?.draw(grabacion)
            super.dispatchDraw(grabacion)
        } finally {
            guardado.endRecording()
        }
        canvas.drawRenderNode(guardado)
        nodo = guardado
    }

    /** El color de fondo, para rellenar lo que un vidrio a medio salir de la pantalla tiene detrás. */
    internal val colorFondo: Int?
        get() = (fondo as? android.graphics.drawable.ColorDrawable)?.color
}

/**
 * El scroll de la pantalla principal, que pasa por debajo de la barra de vidrio (su relleno de arriba es el alto de
 * la barra) y del botón de añadir (el de abajo). Al llevar una vista a la pantalla (el foco de TalkBack, el teclado
 * o scrollTo() de los tests) la deja entre los dos, sin que la tapen.
 */
class ScrollBajoVidrio @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : NestedScrollView(context, attrs) {

    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int =
        super.computeScrollDeltaToGetChildRectOnScreen(
            Rect(rect).apply {
                top -= paddingTop
                bottom += paddingBottom
            }
        )
}
