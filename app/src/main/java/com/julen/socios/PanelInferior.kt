package com.julen.socios

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.GridLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewbinding.ViewBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout
import com.julen.socios.util.AnimationExtensions
import com.julen.socios.util.HapticUtils
import com.julen.socios.util.Movimiento
import com.julen.socios.util.toEurosCompacto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Panel inferior de la app. Se abre ya desplegado, sin quedarse a media altura, y guarda su ViewBinding
 * solo mientras existe la vista. Con [pantallaCompleta] ocupa toda la altura: para paneles con los botones
 * abajo o con contenido que cambia de tamaño, que así no dan saltos.
 */
abstract class PanelInferior<B : ViewBinding>(
    private val inflar: (LayoutInflater, ViewGroup?, Boolean) -> B,
    private val pantallaCompleta: Boolean = false
) : BottomSheetDialogFragment() {

    private var _binding: B? = null

    protected val binding: B get() = checkNotNull(_binding) { "La vista del panel ya no existe" }

    /** El binding, o null si la vista ya no existe: para respuestas que pueden llegar con el panel cerrado. */
    protected val bindingSiExiste: B? get() = _binding

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        (super.onCreateDialog(savedInstanceState) as BottomSheetDialog).apply {
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
            if (pantallaCompleta) {
                setOnShowListener {
                    findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { hoja ->
                        hoja.layoutParams = hoja.layoutParams.apply { height = ViewGroup.LayoutParams.MATCH_PARENT }
                    }
                }
            }
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflar(inflater, container, false).also { _binding = it }.root

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /** Ejecuta [bloque] mientras el panel se ve: se para al pasar a segundo plano y vuelve a empezar al volver. */
    protected fun mientrasSeVea(bloque: suspend CoroutineScope.() -> Unit) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED, bloque)
        }
    }

    /**
     * Tarjeta con un interruptor que muestra u oculta [opciones]: tocar la tarjeta lo cambia, el cambio se anima
     * en [contenido] y, al apagarlo, se cierra el teclado. [alCambiar] hace lo propio de cada panel.
     */
    protected fun configurarInterruptor(
        tarjeta: View,
        interruptor: CompoundButton,
        contenido: ViewGroup,
        opciones: View,
        alCambiar: (activado: Boolean) -> Unit = {}
    ) {
        tarjeta.setOnClickListener {
            HapticUtils.performToggle(it)
            interruptor.toggle()
        }
        interruptor.setOnCheckedChangeListener { _, activado ->
            Movimiento.animarCambios(contenido)
            opciones.isVisible = activado
            alCambiar(activado)
            if (!activado) ocultarTeclado()
        }
    }

    protected fun mostrarTeclado(campo: View) {
        campo.requestFocus()
        val ventana = dialog?.window ?: return
        WindowCompat.getInsetsController(ventana, campo).show(WindowInsetsCompat.Type.ime())
    }

    /** Cierra el teclado y quita el foco del campo en el que se estaba escribiendo. */
    protected fun ocultarTeclado() {
        val ventana = dialog?.window ?: return
        WindowCompat.getInsetsController(ventana, binding.root).hide(WindowInsetsCompat.Type.ime())
        ventana.currentFocus?.clearFocus()
    }

    /**
     * Rellena [rejilla] con un botón por cada importe de [importes] y, al final, "Otro", que muestra [campo]
     * para escribirlo. Al pulsar uno se marca solo ese, [contenido] anima el cambio y se llama a [alElegir].
     */
    protected fun crearOpcionesImporte(
        rejilla: GridLayout,
        importes: List<Double>,
        campo: TextInputLayout,
        contenido: ViewGroup,
        alElegir: () -> Unit = {}
    ): OpcionesImporte {
        val margen = (4 * resources.displayMetrics.density).toInt()
        val textos = importes.map { it.toEurosCompacto() } + getString(R.string.chip_otro)
        val botones = textos.mapIndexed { index, texto ->
            (layoutInflater.inflate(R.layout.item_opcion_cuota, rejilla, false) as MaterialButton).apply {
                id = View.generateViewId()
                text = texto
                tag = importes.getOrNull(index)
                layoutParams = GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)
                ).apply {
                    width = 0
                    setMargins(margen, margen, margen, margen)
                }
                rejilla.addView(this)
            }
        }
        val opciones = OpcionesImporte(botones, campo)
        val escrito = requireNotNull(campo.editText)
        for (boton in botones) {
            boton.setOnClickListener {
                HapticUtils.performClick(it)
                opciones.marcar(boton)
                Movimiento.animarCambios(contenido)
                if (boton == opciones.otro) {
                    campo.isVisible = true
                    mostrarTeclado(escrito)
                } else {
                    // Si se pulsó "Otro" sin querer, el teclado del importe se va con su campo.
                    if (escrito.hasFocus()) ocultarTeclado()
                    campo.isVisible = false
                }
                alElegir()
            }
            AnimationExtensions.setupPressScaleAnimation(boton)
        }
        return opciones
    }
}

/** Abre el panel o diálogo que crea [crear], salvo que ya haya uno abierto con [tag] (por ejemplo, tras un doble toque). */
fun FragmentManager.mostrarSiNoEsta(tag: String, crear: () -> DialogFragment) {
    if (findFragmentByTag(tag) == null) crear().show(this, tag)
}
