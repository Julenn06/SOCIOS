package com.julen.socios

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.viewbinding.ViewBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

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
}

/** Abre el panel o diálogo que crea [crear], salvo que ya haya uno abierto con [tag] (por ejemplo, tras un doble toque). */
fun FragmentManager.mostrarSiNoEsta(tag: String, crear: () -> DialogFragment) {
    if (findFragmentByTag(tag) == null) crear().show(this, tag)
}
