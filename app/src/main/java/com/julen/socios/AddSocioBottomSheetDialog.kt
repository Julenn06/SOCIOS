package com.julen.socios

import android.animation.ValueAnimator
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.activityViewModels
import com.google.android.material.button.MaterialButton
import com.julen.socios.databinding.BottomSheetAddSocioBinding
import com.julen.socios.model.DiaSemana
import com.julen.socios.model.ImportesPredefinidos
import com.julen.socios.model.Socio
import com.julen.socios.util.AnimationExtensions
import com.julen.socios.util.CalculoComisiones
import com.julen.socios.util.DateUtils
import com.julen.socios.util.HapticUtils
import com.julen.socios.util.Movimiento
import com.julen.socios.util.NumberAnimators
import com.julen.socios.util.rangoSemanaConAnio
import com.julen.socios.util.semanaRelativa
import com.julen.socios.util.setTextoAccesible
import com.julen.socios.util.toEurosCompacto

/**
 * Alta o edición de un socio. Recibe sus datos por argumentos (no por constructor) para que el sistema
 * pueda recrearlo tras un giro de pantalla o la muerte del proceso, y guarda a través del ViewModel.
 */
class AddSocioBottomSheetDialog :
    PanelInferior<BottomSheetAddSocioBinding>(BottomSheetAddSocioBinding::inflate, pantallaCompleta = true) {

    private val viewModel: MainViewModel by activityViewModels { MainViewModel.Factory }

    private val socioToEdit: Socio? by lazy { requireArguments().getBundle(ARG_SOCIO)?.toSocio() }

    /** La semana en la que se guardará: la del socio (o la visible al abrirlo), y se puede cambiar. */
    private lateinit var semanaKey: String

    /** Socios guardados con "Guardar y otro" sin cerrar el formulario. */
    private var anadidos = 0
    private val defaultDia: DiaSemana by lazy { DiaSemana.fromId(requireArguments().getInt(ARG_DIA)) }

    /** Las cuotas predefinidas y, al final, "Otro". */
    private lateinit var cuotas: OpcionesImporte

    private val botonesDia: Map<DiaSemana, MaterialButton> by lazy {
        DiaSemana.entries.zip(
            listOf(binding.btnDiaLun, binding.btnDiaMar, binding.btnDiaMie, binding.btnDiaJue, binding.btnDiaVie)
        ).toMap()
    }

    private var esHecho = true
    private var detallesAbiertos = false

    private val fondoHero = GradientDrawable().apply { orientation = GradientDrawable.Orientation.TL_BR }
    private var animacionFondo: ValueAnimator? = null

    private var dialogoEliminar: AlertDialog? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.fondoHero.background = LayerDrawable(
            arrayOf(fondoHero, requireNotNull(ContextCompat.getDrawable(requireContext(), R.drawable.bg_adorno_circulos)))
        )
        semanaKey = savedInstanceState?.getString(STATE_SEMANA) ?: requireNotNull(requireArguments().getString(ARG_SEMANA))
        anadidos = savedInstanceState?.getInt(STATE_ANADIDOS) ?: 0
        mostrarSemana()
        cuotas = crearOpcionesImporte(
            binding.gridCuotas, ImportesPredefinidos.CUOTAS, binding.tilCustomAmount, binding.contenidoAddSocio,
            alElegir = ::actualizarVistaPrevia
        )
        configurarDias()

        val socio = socioToEdit
        binding.tvTitleBottomSheet.setText(if (socio != null) R.string.title_edit_socio else R.string.title_add_socio)
        binding.btnGuardarSocio.setText(if (socio != null) R.string.action_guardar_cambios else R.string.action_save_socio)
        // Al añadir, el segundo botón guarda y prepara otro; al editar, lo elimina. Para cancelar están la ✕
        // y deslizar hacia abajo.
        binding.btnGuardarYOtro.isVisible = socio == null
        binding.btnEliminarSocio.isVisible = socio != null
        // Los botones de Material van en una línea y cortan "Guardar y otro" (o, con la letra grande,
        // "Guardar cambios"): mejor partirlo en dos.
        val botones = listOf(binding.btnGuardarYOtro, binding.btnEliminarSocio, binding.btnGuardarSocio)
        botones.forEach {
            it.isSingleLine = false
            it.maxLines = 2
        }
        // Con la letra muy grande ni en dos líneas caben uno al lado del otro: se apilan a lo ancho.
        if (resources.configuration.fontScale >= ESCALA_LETRA_BOTONES_APILADOS) {
            binding.barraBotones.orientation = LinearLayout.VERTICAL
            val separacion = (8 * resources.displayMetrics.density).toInt()
            botones.forEach { boton ->
                boton.updateLayoutParams<LinearLayout.LayoutParams> {
                    width = ViewGroup.LayoutParams.MATCH_PARENT
                    height = ViewGroup.LayoutParams.WRAP_CONTENT
                    weight = 0f
                    marginEnd = 0
                    bottomMargin = if (boton == binding.btnGuardarSocio) 0 else separacion
                }
            }
        }

        if (savedInstanceState == null) {
            if (socio != null) {
                esHecho = socio.hecho
                binding.etNombreSocio.setText(socio.nombreSocio)
                binding.etNotas.setText(socio.notas)
                detallesAbiertos = socio.nombreSocio.isNotBlank() || socio.notas.isNotBlank()
                cuotas.elegir(socio.colaboracion)
                botonesDia[socio.dia]?.isChecked = true
            } else {
                // La cuota del último socio añadido: casi siempre se repite.
                cuotas.elegir(viewModel.cuotaPorDefecto)
                botonesDia[defaultDia]?.isChecked = true
            }
        } else {
            esHecho = savedInstanceState.getBoolean(STATE_HECHO, true)
            detallesAbiertos = savedInstanceState.getBoolean(STATE_DETALLES)
            cuotas.restaurar(savedInstanceState.getInt(STATE_CUOTA_INDEX, -1))
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

        binding.btnEliminarSocio.setOnClickListener {
            HapticUtils.performClick(it)
            val socioGuardado = socioToEdit ?: return@setOnClickListener
            if (dialogoEliminar?.isShowing == true) return@setOnClickListener
            // Se borra el socio tal como está guardado, no con lo que se haya cambiado en el formulario:
            // así "Deshacer" lo deja exactamente como estaba.
            dialogoEliminar = confirmarEliminarSocio(requireContext(), socioGuardado) {
                viewModel.eliminarSocio(socioGuardado)
                dismiss()
            }.apply { setOnDismissListener { dialogoEliminar = null } }
        }
        binding.btnCerrarAddSocio.setOnClickListener { dismiss() }
        binding.btnGuardarSocio.setOnClickListener {
            HapticUtils.performConfirm(it)
            saveSocio(otro = false)
        }
        binding.btnGuardarYOtro.setOnClickListener {
            HapticUtils.performConfirm(it)
            saveSocio(otro = true)
        }
        binding.btnFormSemanaAnterior.setOnClickListener { moverSemana(-1) }
        binding.btnFormSemanaSiguiente.setOnClickListener { moverSemana(1) }
        listOf(
            binding.cardEstadoHecho, binding.cardEstadoPendiente, binding.btnGuardarSocio, binding.btnEliminarSocio,
            binding.btnGuardarYOtro
        ).forEach(AnimationExtensions::setupPressScaleAnimation)
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        cuotas.ajustarCampo()
        mostrarEstado(animar = false)
        mostrarDetalles()
        actualizarVistaPrevia()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        bindingSiExiste?.let { outState.putInt(STATE_CUOTA_INDEX, cuotas.indiceMarcado) }
        outState.putBoolean(STATE_HECHO, esHecho)
        outState.putBoolean(STATE_DETALLES, detallesAbiertos)
        outState.putString(STATE_SEMANA, semanaKey)
        outState.putInt(STATE_ANADIDOS, anadidos)
    }

    override fun onDestroyView() {
        animacionFondo?.cancel()
        dialogoEliminar?.dismiss()
        super.onDestroyView()
    }

    private fun moverSemana(delta: Int) {
        val nueva = DateUtils.semanaKeyDesplazada(semanaKey, delta) ?: return
        HapticUtils.performClick(binding.contenedorFormSemana)
        semanaKey = nueva
        mostrarSemana()
    }

    /** La semana elegida, cuánto dista de la actual y, en el subtítulo, cuántos se llevan añadidos. */
    private fun mostrarSemana() {
        val rango = DateUtils.rangoCorto(semanaKey)
        binding.tvSubtituloSemana.text = if (anadidos > 0) {
            resources.getQuantityString(R.plurals.add_subtitulo_anadidos, anadidos, anadidos, rango)
        } else {
            getString(R.string.add_subtitulo_semana, rango)
        }
        binding.tvFormSemana.text = requireContext().rangoSemanaConAnio(semanaKey)
        val distancia = DateUtils.semanasEntre(DateUtils.getSemanaKey(), semanaKey) ?: 0
        binding.tvFormSemanaRelativa.text = requireContext().semanaRelativa(distancia, R.string.add_semana_actual)
        binding.contenedorFormSemana.contentDescription = getString(
            R.string.socio_dia_semana, DateUtils.getRangoSemanaTextoFromKey(semanaKey), binding.tvFormSemanaRelativa.text
        )
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
        animacionFondo?.cancel()
        animacionFondo = fondoHero.fundirA(coloresDegradadoSocio(contexto, esHecho), animar) {
            duration = Movimiento.LARGA_MS
            interpolator = Movimiento.curva(contexto)
        }
    }

    private fun actualizarVistaPrevia() {
        val cuota = cuotas.elegido() ?: 0.0
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
        Movimiento.animarCambios(binding.contenidoAddSocio)
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
        binding.ivDetallesFlecha.animate().rotation(if (detallesAbiertos) 180f else 0f)
            .setDuration(Movimiento.MEDIA_MS).setInterpolator(Movimiento.curva(requireContext())).start()
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

    /** [otro]: tras guardar, deja el formulario listo para el siguiente en lugar de cerrarlo. */
    private fun saveSocio(otro: Boolean) {
        val cuota = cuotas.elegido()
        if (cuota == null || cuota <= 0) {
            cuotas.marcar(cuotas.otro)
            binding.tilCustomAmount.isVisible = true
            binding.tilCustomAmount.error = getString(R.string.msg_cuota_invalida)
            mostrarTeclado(binding.etCustomAmount)
            return
        }

        val dia = botonesDia.entries.firstOrNull { it.value.isChecked }?.key ?: defaultDia
        val nombre = binding.etNombreSocio.text?.toString()?.trim().orEmpty()
        val notas = binding.etNotas.text?.toString()?.trim().orEmpty()

        val original = socioToEdit
        // Al editar se conservan el id y la fecha de alta; uno nuevo los estrena.
        val socio = Socio(
            colaboracion = cuota,
            hecho = esHecho,
            dia = dia,
            semanaKey = semanaKey,
            nombreSocio = nombre,
            notas = notas
        ).let { if (original != null) it.copy(id = original.id, timestamp = original.timestamp) else it }

        viewModel.guardarSocio(socio, esNuevo = original == null, semanaOriginal = original?.semanaKey)
        if (otro && original == null) prepararSiguiente() else dismiss()
    }

    /** Vacía nombre y notas para el siguiente socio; la cuota, el día, el estado y la semana se mantienen. */
    private fun prepararSiguiente() {
        anadidos++
        ocultarTeclado()
        binding.etNombreSocio.text = null
        binding.etNotas.text = null
        Movimiento.animarCambios(binding.contenidoAddSocio)
        detallesAbiertos = false
        mostrarDetalles()
        mostrarSemana()
        binding.scrollAddSocio.smoothScrollTo(0, 0)
        // Un latido de la cabecera (se encoge un instante y rebota) para que se note que se ha guardado.
        Movimiento.latido(binding.fondoHero, hacia = -1f)
    }

    companion object {
        const val TAG = "AddSocioDialog"

        private const val ARG_SOCIO = "socio"
        private const val ARG_SEMANA = "semanaKey"
        private const val ARG_DIA = "diaId"
        private const val STATE_CUOTA_INDEX = "cuotaIndex"
        private const val STATE_HECHO = "hecho"
        private const val STATE_DETALLES = "detallesAbiertos"
        private const val STATE_SEMANA = "semana"
        private const val STATE_ANADIDOS = "anadidos"

        /** A partir de esta letra del sistema los botones de abajo no caben uno al lado del otro. */
        private const val ESCALA_LETRA_BOTONES_APILADOS = 1.6f

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
