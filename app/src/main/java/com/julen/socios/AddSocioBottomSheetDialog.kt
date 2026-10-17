package com.julen.socios

import android.animation.ValueAnimator
import android.app.Dialog
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.activityViewModels
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.julen.socios.databinding.BottomSheetAddSocioBinding
import com.julen.socios.model.DiaSemana
import com.julen.socios.model.ImportesPredefinidos
import com.julen.socios.model.Socio
import com.julen.socios.util.AnimationExtensions
import com.julen.socios.util.CalculoComisiones
import com.julen.socios.util.DateUtils
import com.julen.socios.util.HapticUtils
import com.julen.socios.util.NumberAnimators
import com.julen.socios.util.parseImporte
import com.julen.socios.util.setTextoAccesible
import com.julen.socios.util.toEurosCompacto
import com.julen.socios.util.toImporteInput

/**
 * Alta o edición de un socio. Recibe sus datos por argumentos (no por constructor) para que el sistema
 * pueda recrearlo tras un giro de pantalla o la muerte del proceso, y guarda a través del ViewModel.
 */
class AddSocioBottomSheetDialog : BottomSheetDialogFragment() {

    private val viewModel: MainViewModel by activityViewModels { MainViewModel.Factory }

    private var _binding: BottomSheetAddSocioBinding? = null
    private val binding get() = _binding!!

    private val socioToEdit: Socio? by lazy { requireArguments().getBundle(ARG_SOCIO)?.toSocio() }
    private val semanaKey: String by lazy { requireNotNull(requireArguments().getString(ARG_SEMANA)) }
    private val defaultDia: DiaSemana by lazy { DiaSemana.fromId(requireArguments().getInt(ARG_DIA)) }

    /** Las cuotas predefinidas y, al final, "Otro". */
    private var opcionesCuota: List<MaterialButton> = emptyList()
    private val opcionOtro get() = opcionesCuota.last()

    private val botonesDia: Map<DiaSemana, MaterialButton> by lazy {
        DiaSemana.entries.zip(
            listOf(binding.btnDiaLun, binding.btnDiaMar, binding.btnDiaMie, binding.btnDiaJue, binding.btnDiaVie)
        ).toMap()
    }

    private var esHecho = true
    private var detallesAbiertos = false

    private val fondoHero = GradientDrawable().apply { orientation = GradientDrawable.Orientation.TL_BR }
    private var animacionFondo: ValueAnimator? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        (super.onCreateDialog(savedInstanceState) as BottomSheetDialog).apply {
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
            setOnShowListener {
                // A pantalla completa, para que los botones queden siempre abajo.
                findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { hoja ->
                    hoja.layoutParams = hoja.layoutParams.apply { height = ViewGroup.LayoutParams.MATCH_PARENT }
                }
            }
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetAddSocioBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.fondoHero.background = LayerDrawable(
            arrayOf(fondoHero, requireNotNull(ContextCompat.getDrawable(requireContext(), R.drawable.bg_adorno_circulos)))
        )
        binding.tvSubtituloSemana.text = getString(R.string.add_subtitulo_semana, DateUtils.rangoCorto(semanaKey))
        crearOpcionesCuota()
        configurarDias()

        val socio = socioToEdit
        binding.tvTitleBottomSheet.setText(if (socio != null) R.string.title_edit_socio else R.string.title_add_socio)
        binding.btnGuardarSocio.setText(if (socio != null) R.string.action_guardar_cambios else R.string.action_save_socio)

        if (savedInstanceState == null) {
            if (socio != null) {
                esHecho = socio.hecho
                binding.etNombreSocio.setText(socio.nombreSocio)
                binding.etNotas.setText(socio.notas)
                detallesAbiertos = socio.nombreSocio.isNotBlank() || socio.notas.isNotBlank()
                elegirCuota(socio.colaboracion)
                botonesDia[socio.dia]?.isChecked = true
            } else {
                elegirCuota(ImportesPredefinidos.CUOTA_POR_DEFECTO)
                botonesDia[defaultDia]?.isChecked = true
            }
        } else {
            esHecho = savedInstanceState.getBoolean(STATE_HECHO, true)
            detallesAbiertos = savedInstanceState.getBoolean(STATE_DETALLES)
            // Las cuotas se crean en código con ids nuevos: su selección se restaura a mano.
            opcionesCuota.getOrNull(savedInstanceState.getInt(STATE_CUOTA_INDEX, -1))?.let(::marcarCuota)
        }

        binding.cardEstadoHecho.setOnClickListener { elegirEstado(hecho = true) }
        binding.cardEstadoPendiente.setOnClickListener { elegirEstado(hecho = false) }
        binding.filaDetalles.setOnClickListener { alternarDetalles() }
        binding.etCustomAmount.doOnTextChanged { _, _, _, _ ->
            binding.tilCustomAmount.error = null
            actualizarVistaPrevia()
        }
        binding.etNombreSocio.doOnTextChanged { _, _, _, _ -> actualizarResumenDetalles() }
        binding.etNotas.doOnTextChanged { _, _, _, _ -> actualizarResumenDetalles() }

        binding.btnCancelarAdd.setOnClickListener {
            HapticUtils.performClick(it)
            dismiss()
        }
        binding.btnGuardarSocio.setOnClickListener {
            HapticUtils.performConfirm(it)
            saveSocio()
        }
        listOf(binding.cardEstadoHecho, binding.cardEstadoPendiente, binding.btnGuardarSocio, binding.btnCancelarAdd)
            .forEach(AnimationExtensions::setupPressScaleAnimation)
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        binding.tilCustomAmount.isVisible = opcionOtro.isChecked
        mostrarEstado(animar = false)
        mostrarDetalles()
        actualizarVistaPrevia()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        _binding?.let { outState.putInt(STATE_CUOTA_INDEX, opcionesCuota.indexOfFirst { it.isChecked }) }
        outState.putBoolean(STATE_HECHO, esHecho)
        outState.putBoolean(STATE_DETALLES, detallesAbiertos)
    }

    override fun onDestroyView() {
        animacionFondo?.cancel()
        super.onDestroyView()
        _binding = null
    }

    private fun crearOpcionesCuota() {
        val margen = (4 * resources.displayMetrics.density).toInt()
        val textos = ImportesPredefinidos.CUOTAS.map { it.toEurosCompacto() } + getString(R.string.chip_otro)
        opcionesCuota = textos.mapIndexed { index, texto ->
            (layoutInflater.inflate(R.layout.item_opcion_cuota, binding.gridCuotas, false) as MaterialButton).apply {
                id = View.generateViewId()
                text = texto
                tag = ImportesPredefinidos.CUOTAS.getOrNull(index)
                layoutParams = GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)
                ).apply {
                    width = 0
                    setMargins(margen, margen, margen, margen)
                }
                setOnClickListener { opcion ->
                    HapticUtils.performClick(opcion)
                    marcarCuota(this)
                    TransitionManager.beginDelayedTransition(binding.contenidoAddSocio, AutoTransition().setDuration(DURACION_MS))
                    if (this == opcionOtro) {
                        binding.tilCustomAmount.isVisible = true
                        mostrarTeclado(binding.etCustomAmount)
                    } else {
                        // Si se pulsó "Otro" sin querer, el teclado de la cantidad se va con su campo.
                        if (binding.etCustomAmount.hasFocus()) ocultarTeclado()
                        binding.tilCustomAmount.isVisible = false
                    }
                    actualizarVistaPrevia()
                }
                AnimationExtensions.setupPressScaleAnimation(this)
                binding.gridCuotas.addView(this)
            }
        }
    }

    /** Marca una sola cuota; volver a pulsar la elegida la deja marcada. */
    private fun marcarCuota(elegida: MaterialButton) {
        opcionesCuota.forEach { it.isChecked = it == elegida }
    }

    /** Marca el importe exacto; cualquier otro (p. ej. 10,50 €) va a "Otro". */
    private fun elegirCuota(importe: Double) {
        val opcion = opcionesCuota.firstOrNull { it.tag == importe }
        if (opcion != null) {
            marcarCuota(opcion)
        } else {
            marcarCuota(opcionOtro)
            binding.etCustomAmount.setText(importe.toImporteInput())
        }
    }

    /** La cuota en euros; la escrita a mano, redondeada a céntimos (10,555 → 10,56). */
    private fun cuotaElegida(): Double? {
        if (opcionOtro.isChecked) return parseImporte(binding.etCustomAmount.text)?.let(CalculoComisiones::redondearCentimos)
        return opcionesCuota.firstOrNull { it.isChecked }?.tag as? Double
    }

    private fun configurarDias() {
        for ((dia, boton) in botonesDia) {
            boton.text = dia.nombreCorto
            boton.contentDescription = dia.nombreCompleto
        }
        binding.toggleDias.addOnButtonCheckedListener { grupo, _, marcado ->
            if (marcado) HapticUtils.performClick(grupo)
        }
    }

    private fun elegirEstado(hecho: Boolean) {
        if (hecho == esHecho) return
        HapticUtils.performToggle(binding.cardEstadoHecho)
        esHecho = hecho
        mostrarEstado(animar = true)
        actualizarVistaPrevia()
    }

    private fun mostrarEstado(animar: Boolean) {
        binding.cardEstadoHecho.isChecked = esHecho
        binding.cardEstadoPendiente.isChecked = !esHecho
        pintarFondoHero(animar)
    }

    /** Verde si el socio suma, ámbar si está pendiente, con un fundido entre los dos. */
    private fun pintarFondoHero(animar: Boolean) {
        val contexto = requireContext()
        val destino = if (esHecho) {
            intArrayOf(ContextCompat.getColor(contexto, R.color.badge_gradient_start), ContextCompat.getColor(contexto, R.color.badge_gradient_end))
        } else {
            intArrayOf(ContextCompat.getColor(contexto, R.color.pendiente_gradient_start), ContextCompat.getColor(contexto, R.color.pendiente_gradient_end))
        }
        animacionFondo?.cancel()
        val origen = fondoHero.colors
        if (!animar || origen == null) {
            fondoHero.colors = destino
            return
        }
        animacionFondo = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DURACION_MS * 2
            addUpdateListener { animacion ->
                val f = animacion.animatedValue as Float
                fondoHero.colors = intArrayOf(
                    ColorUtils.blendARGB(origen[0], destino[0], f), ColorUtils.blendARGB(origen[1], destino[1], f)
                )
            }
            start()
        }
    }

    private fun actualizarVistaPrevia() {
        val cuota = cuotaElegida() ?: 0.0
        NumberAnimators.animateCurrency(binding.tvPreviewBaseX2, CalculoComisiones.calcularBaseX2(cuota), prefix = "+")
        binding.tvPreviewDetalle.text = if (esHecho) {
            getString(R.string.preview_detalle, cuota.toEurosCompacto())
        } else {
            getString(R.string.preview_detalle_pendiente)
        }
        binding.tvPreviewEstadoBadge.setTextoAccesible(
            getString(if (esHecho) R.string.socio_status_badge_conseguido else R.string.socio_status_badge_pendiente)
        )
    }

    private fun alternarDetalles() {
        HapticUtils.performClick(binding.filaDetalles)
        detallesAbiertos = !detallesAbiertos
        TransitionManager.beginDelayedTransition(binding.contenidoAddSocio, AutoTransition().setDuration(DURACION_MS))
        if (detallesAbiertos) {
            mostrarDetalles()
            mostrarTeclado(binding.etNombreSocio)
        } else {
            ocultarTeclado()
            mostrarDetalles()
        }
    }

    private fun mostrarDetalles() {
        binding.contenedorDetalles.isVisible = detallesAbiertos
        binding.ivDetallesFlecha.animate().rotation(if (detallesAbiertos) 180f else 0f).setDuration(DURACION_MS).start()
        binding.filaDetalles.contentDescription = getString(
            if (detallesAbiertos) R.string.detalles_ocultar_a11y else R.string.detalles_mostrar_a11y
        )
        actualizarResumenDetalles()
    }

    /** Plegado, muestra lo que se ha escrito para que no quede escondido. */
    private fun actualizarResumenDetalles() {
        val escrito = listOf(binding.etNombreSocio.text, binding.etNotas.text)
            .map { it?.toString()?.trim().orEmpty() }
            .filter { it.isNotEmpty() }
        binding.tvDetallesResumen.text = if (detallesAbiertos || escrito.isEmpty()) {
            getString(R.string.detalles_opcional)
        } else {
            escrito.joinToString(" · ")
        }
    }

    private fun mostrarTeclado(campo: View) {
        campo.requestFocus()
        val ventana = dialog?.window ?: return
        WindowCompat.getInsetsController(ventana, campo).show(WindowInsetsCompat.Type.ime())
    }

    /** Cierra el teclado y quita el foco del campo en el que se estaba escribiendo. */
    private fun ocultarTeclado() {
        val ventana = dialog?.window ?: return
        WindowCompat.getInsetsController(ventana, binding.root).hide(WindowInsetsCompat.Type.ime())
        ventana.currentFocus?.clearFocus()
    }

    private fun saveSocio() {
        val cuota = cuotaElegida()
        if (cuota == null || cuota <= 0) {
            marcarCuota(opcionOtro)
            binding.tilCustomAmount.isVisible = true
            binding.tilCustomAmount.error = getString(R.string.msg_cuota_invalida)
            mostrarTeclado(binding.etCustomAmount)
            return
        }

        val dia = botonesDia.entries.firstOrNull { it.value.isChecked }?.key ?: defaultDia
        val nombre = binding.etNombreSocio.text?.toString()?.trim().orEmpty()
        val notas = binding.etNotas.text?.toString()?.trim().orEmpty()

        val original = socioToEdit
        val socio = original?.copy(
            colaboracion = cuota,
            hecho = esHecho,
            dia = dia,
            nombreSocio = nombre,
            notas = notas
        ) ?: Socio(
            colaboracion = cuota,
            hecho = esHecho,
            dia = dia,
            semanaKey = semanaKey,
            nombreSocio = nombre,
            notas = notas
        )

        viewModel.guardarSocio(socio, esNuevo = original == null)
        dismiss()
    }

    companion object {
        const val TAG = "AddSocioDialog"

        private const val ARG_SOCIO = "socio"
        private const val ARG_SEMANA = "semanaKey"
        private const val ARG_DIA = "diaId"
        private const val STATE_CUOTA_INDEX = "cuotaIndex"
        private const val STATE_HECHO = "hecho"
        private const val STATE_DETALLES = "detallesAbiertos"
        private const val DURACION_MS = 200L

        fun nuevo(semanaKey: String, dia: DiaSemana) = AddSocioBottomSheetDialog().apply {
            arguments = Bundle().apply {
                putString(ARG_SEMANA, semanaKey)
                putInt(ARG_DIA, dia.id)
            }
        }

        fun editar(socio: Socio) = AddSocioBottomSheetDialog().apply {
            arguments = Bundle().apply {
                putString(ARG_SEMANA, socio.semanaKey)
                putInt(ARG_DIA, socio.dia.id)
                putBundle(ARG_SOCIO, socio.toBundle())
            }
        }

        private fun Socio.toBundle() = Bundle().apply {
            putString("id", id)
            putDouble("colaboracion", colaboracion)
            putBoolean("hecho", hecho)
            putInt("diaId", dia.id)
            putString("semanaKey", semanaKey)
            putString("nombreSocio", nombreSocio)
            putString("notas", notas)
            putLong("timestamp", timestamp)
        }

        private fun Bundle.toSocio() = Socio(
            id = requireNotNull(getString("id")),
            colaboracion = getDouble("colaboracion"),
            hecho = getBoolean("hecho"),
            dia = DiaSemana.fromId(getInt("diaId")),
            semanaKey = requireNotNull(getString("semanaKey")),
            nombreSocio = getString("nombreSocio").orEmpty(),
            notas = getString("notas").orEmpty(),
            timestamp = getLong("timestamp")
        )
    }
}
