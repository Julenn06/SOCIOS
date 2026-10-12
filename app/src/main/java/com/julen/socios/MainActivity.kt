package com.julen.socios

import android.content.ClipData
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.view.GestureDetector
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.CompoundButton
import android.widget.HorizontalScrollView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.text.buildSpannedString
import androidx.core.text.scale
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.julen.socios.actualizaciones.VersionPublicada
import com.julen.socios.databinding.ActivityMainBinding
import com.julen.socios.databinding.DialogoConfirmarImportBinding
import com.julen.socios.databinding.DialogoDestinoExportBinding
import com.julen.socios.databinding.ItemResumenImportBinding
import com.julen.socios.export.ExportFormat
import com.julen.socios.export.ExportRequest
import com.julen.socios.export.ImportFormat
import com.julen.socios.model.DiaSemana
import com.julen.socios.model.ImportPreview
import com.julen.socios.model.Socio
import com.julen.socios.util.AnimationExtensions
import com.julen.socios.util.CalculoComisiones
import com.julen.socios.util.DateUtils
import com.julen.socios.util.HapticUtils
import com.julen.socios.util.NumberAnimators
import com.julen.socios.util.toEurosCompacto
import com.julen.socios.util.toPorcentajeString
import com.julen.socios.util.setTextoAccesible
import kotlin.math.abs
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: SocioAdapter
    private lateinit var gestureDetector: GestureDetector

    private val viewModel: MainViewModel by viewModels { MainViewModel.Factory }
    private val actualizaciones: ActualizacionesViewModel by viewModels { ActualizacionesViewModel.Factory }

    private val botonesFiltroDia: Map<DiaSemana, MaterialButton> by lazy {
        DiaSemana.entries.zip(
            listOf(
                binding.btnFiltroLun, binding.btnFiltroMar, binding.btnFiltroMie,
                binding.btnFiltroJue, binding.btnFiltroVie
            )
        ).toMap()
    }
    private var sincronizandoFiltro = false

    private var bonusAnterior: Pair<String, Double>? = null

    private var dialogoDestinoExport: AlertDialog? = null
    private var dialogoConfirmarImport: AlertDialog? = null
    private var dialogoEliminar: AlertDialog? = null

    // --- EXPORT & IMPORT LAUNCHERS ---
    // El resultado vuelve al ViewModel, que guarda la petición pendiente en su SavedStateHandle.
    private val crearDocumentoLaunchers = ExportFormat.entries.associateWith { formato ->
        registerForActivityResult(ActivityResultContracts.CreateDocument(formato.mimeType)) { uri ->
            viewModel.onDocumentoCreado(uri)
        }
    }

    private val importJsonLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        viewModel.onArchivoImportSeleccionado(uri, ImportFormat.JSON)
    }

    private val importCsvLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        viewModel.onArchivoImportSeleccionado(uri, ImportFormat.CSV)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { _, insets ->
            val systemBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val density = resources.displayMetrics.density

            // La barra superior empieza debajo de la de estado: si no, sus botones quedan en la franja
            // donde el sistema recoge el gesto de bajar las notificaciones y cuesta pulsarlos.
            binding.appBar.updatePadding(top = systemBars.top)
            binding.fabAddSocio.updateLayoutParams<CoordinatorLayout.LayoutParams> {
                bottomMargin = (20 * density).toInt() + systemBars.bottom
            }
            binding.nestedScrollView.updatePadding(bottom = systemBars.bottom + (80 * density).toInt())
            insets
        }

        setSupportActionBar(binding.toolbar)

        setupRecyclerView()
        setupWeekNavigation()
        setupFiltroDias()
        setupFab()
        setupHeaderClickListeners()
        setupGestureDetector()

        // Micro-animaciones táctiles
        AnimationExtensions.setupPressScaleAnimation(binding.fabAddSocio)
        AnimationExtensions.setupPressScaleAnimation(binding.containerBonusHeader)
        AnimationExtensions.setupPressScaleAnimation(binding.btnVerDesglose)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.uiState.collect(::renderUiState) }
                launch { viewModel.events.collect(::manejarEvento) }
                launch { viewModel.exportPendienteDeDestino.collect(::mostrarDialogoDestinoExport) }
                launch { viewModel.importPendiente.collect(::mostrarDialogoConfirmarImport) }
                launch { actualizaciones.nuevaVersion.collect(::avisarNuevaVersion) }
            }
        }

        // Atajo "Nuevo socio" del icono de la app. Solo en un arranque nuevo: tras girar la pantalla o
        // volver a la app, el formulario ya se restaura solo si seguía abierto.
        if (savedInstanceState == null && intent?.action == ACTION_NUEVO_SOCIO) {
            ShortcutManagerCompat.reportShortcutUsed(this, ATAJO_NUEVO_SOCIO)
            openAddEditDialog(null)
        }
    }

    override fun onDestroy() {
        // Los diálogos se vuelven a mostrar desde el estado del ViewModel si la Activity se recrea.
        // El de eliminar no se restaura: tras girar la pantalla basta con volver a pulsar la papelera.
        dialogoEliminar?.dismiss()
        listOfNotNull(dialogoDestinoExport, dialogoConfirmarImport).forEach {
            it.setOnDismissListener(null)
            it.dismiss()
        }
        super.onDestroy()
    }

    private fun renderUiState(state: MainUiState) {
        // Rango corto de la semana y cuánto queda de la actual
        binding.tvRangoSemana.text =
            getString(R.string.rango_semana_anio, DateUtils.rangoCorto(state.semanaKey), state.semanaKey.take(4))
        binding.tvSemanaSubtitulo.text = when {
            state.weekOffset == 0 -> getString(R.string.subtitle_current_week)
            state.weekOffset < 0 -> resources.getQuantityString(R.plurals.semana_pasada, -state.weekOffset, -state.weekOffset)
            else -> resources.getQuantityString(R.plurals.semana_futura, state.weekOffset, state.weekOffset)
        }

        // Botón Hoy dinámico
        binding.btnHoyLeft.isVisible = state.weekOffset > 0
        binding.btnHoyRight.isVisible = state.weekOffset < 0

        state.resumenSemana?.let { renderResumen(state.semanaKey, it) }

        // Filtro por día, con cuántos socios hay en cada uno
        mostrarFiltro(binding.btnFiltroTodos, getString(R.string.filtro_todos), getString(R.string.filtro_todos_a11y), state.todosSociosSemana.size)
        botonesFiltroDia.forEach { (dia, boton) ->
            mostrarFiltro(boton, dia.nombreCorto, dia.nombreCompleto, state.conteoPorDia[dia] ?: 0)
        }
        sincronizarFiltro(state.diaFiltro)

        // Lista de socios
        adapter.submitList(state.sociosFiltrados)
        binding.containerEmptyState.isVisible = state.sociosFiltrados.isEmpty()
        binding.rvSocios.isVisible = state.sociosFiltrados.isNotEmpty()

        binding.tvListaTitulo.text = state.diaFiltro
            ?.let { getString(R.string.title_socios_day_format, it.nombreCompleto) }
            ?: getString(R.string.title_socios_week)
        binding.tvListaResumenDia.text = getString(
            R.string.list_summary_day_format, state.sociosFiltrados.count { it.hecho }, state.sociosFiltrados.size
        )
    }

    private fun renderResumen(semanaKey: String, resumen: CalculoComisiones.ResumenCalculo) {
        NumberAnimators.animateCurrency(binding.tvNetoHeader, resumen.totalNeto, prefix = "")
        NumberAnimators.animateCurrency(
            binding.tvBaseHeader, resumen.gananciasBaseX2, prefix = "+", decimals = decimalesDe(resumen.gananciasBaseX2)
        )
        NumberAnimators.animateCurrency(
            binding.tvBonusHeader, resumen.bonusSemanal, prefix = "+", decimals = decimalesDe(resumen.bonusSemanal)
        )
        NumberAnimators.animateCurrency(binding.tvIrpfHeader, resumen.retencionIrpf, prefix = "-")

        binding.tvBonusHeaderLabel.setTextoAccesible(
            getString(if (resumen.esBonoRegaloAplicado) R.string.label_bonus_manual else R.string.label_bonus_auto)
        )
        binding.tvIrpfHeaderLabel.text = getString(R.string.label_irpf_header, resumen.porcentajeIrpf.toPorcentajeString())

        val hechos = resumen.totalSociosHechos
        val metaSocios = resumen.siguienteMetaSocios

        binding.tvBonusTitle.text = getString(R.string.bonus_titulo, resumen.bonusSemanal.toEurosCompacto(prefix = "+"))
        binding.tvBonusProgressCount.text = getString(R.string.bonus_progress_format, hechos, metaSocios)
        binding.tvBonusProgressCount.contentDescription = getString(R.string.bonus_progress_a11y, hechos, metaSocios)

        // La barra avanza dentro de la misma semana; al cambiar de semana se coloca sin animar.
        val anterior = bonusAnterior
        val mismaSemana = anterior != null && anterior.first == semanaKey
        binding.progressBonus.mostrar(hechos, animar = mismaSemana)
        // Pequeño rebote al subir de nivel dentro de la misma semana.
        if (anterior != null && mismaSemana && anterior.second < resumen.bonusSemanal) {
            binding.tvBonusTitle.animate().scaleX(1.12f).scaleY(1.12f).setDuration(200)
                .setInterpolator(OvershootInterpolator(2.5f)).withEndAction {
                    binding.tvBonusTitle.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                }.start()
        }
        bonusAnterior = semanaKey to resumen.bonusSemanal

        // Siempre hay un tramo más: después de 10 socios, uno cada 3.
        val faltan = resumen.sociosFaltantesParaSiguienteMeta
        binding.tvBonusSiguienteMeta.text = resources.getQuantityString(
            R.plurals.bonus_next_goal, faltan, faltan, resumen.siguienteMetaBonus.toEurosCompacto(prefix = "+")
        )
    }

    private fun decimalesDe(importe: Double) = if (importe % 1.0 == 0.0) 0 else 2

    /** El nombre en una línea y, debajo, cuántos socios hay. */
    private fun mostrarFiltro(boton: MaterialButton, nombre: String, nombreLeido: String, socios: Int) {
        boton.text = buildSpannedString {
            append(nombre)
            append('\n')
            scale(0.9f) { append(socios.toString()) }
        }
        boton.contentDescription = resources.getQuantityString(R.plurals.filtro_dia_a11y, socios, nombreLeido, socios)
    }

    private fun sincronizarFiltro(dia: DiaSemana?) {
        val id = dia?.let { botonesFiltroDia[it]?.id } ?: R.id.btnFiltroTodos
        if (binding.toggleFiltroDias.checkedButtonId != id) {
            sincronizandoFiltro = true
            binding.toggleFiltroDias.check(id)
            sincronizandoFiltro = false
        }
    }

    private fun manejarEvento(evento: MainEvent) {
        when (evento) {
            is MainEvent.Mensaje -> aviso(
                evento.texto.resolver(this),
                evento.tipo,
                if (evento.larga) Snackbar.LENGTH_LONG else Snackbar.LENGTH_SHORT
            )

            is MainEvent.CrearDocumento ->
                crearDocumentoLaunchers.getValue(evento.formato).launch(evento.nombreSugerido)

            is MainEvent.Compartir -> {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = evento.mimeType
                    putExtra(Intent.EXTRA_STREAM, evento.uri)
                    clipData = ClipData.newRawUri(null, evento.uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val titulo = getString(R.string.export_compartir_titulo, evento.formato.name)
                startActivity(Intent.createChooser(intent, titulo))
            }

            is MainEvent.SeleccionarArchivoImport -> when (evento.formato) {
                ImportFormat.JSON -> importJsonLauncher.launch("application/json")
                ImportFormat.CSV -> importCsvLauncher.launch("*/*")
            }

            is MainEvent.SocioEliminado -> aviso(
                getString(R.string.msg_socio_deleted), TipoAviso.BORRADO, accion = R.string.action_deshacer
            ) { viewModel.restaurarSocio(evento.socio) }
        }
    }

    /** Aviso con el diseño de la app, encima del botón de "Nuevo socio" para no taparlo. */
    private fun aviso(
        texto: CharSequence,
        tipo: TipoAviso,
        duracion: Int = Snackbar.LENGTH_LONG,
        @StringRes accion: Int? = null,
        alPulsarAccion: (() -> Unit)? = null
    ) {
        Avisos.mostrar(binding.root, texto, tipo, duracion, binding.fabAddSocio, accion, alPulsarAccion)
    }

    private fun avisarNuevaVersion(publicada: VersionPublicada) {
        val texto = getString(
            R.string.actualizacion_disponible, publicada.version.toString(), actualizaciones.versionInstalada
        )
        // Más tiempo que un aviso normal: es fácil no verlo al abrir la app.
        aviso(texto, TipoAviso.NOVEDAD, DURACION_AVISO_VERSION_MS, R.string.action_actualizar) {
            if (!ActualizacionBottomSheetDialog.empezar(this, actualizaciones, publicada)) {
                aviso(getString(R.string.msg_sin_navegador), TipoAviso.ERROR)
            }
        }
    }

    private fun mostrarDialogoDestinoExport(request: ExportRequest?) {
        if (request == null) {
            dialogoDestinoExport?.let {
                it.setOnDismissListener(null)
                it.dismiss()
            }
            dialogoDestinoExport = null
            return
        }
        if (dialogoDestinoExport?.isShowing == true) return

        // Al elegir, el ViewModel deja de tener la petición pendiente y el diálogo se cierra solo.
        val vista = DialogoDestinoExportBinding.inflate(layoutInflater)
        vista.opcionCompartir.configurar(
            R.drawable.ic_share, R.color.blue_container, R.color.blue_text_soft,
            R.string.export_destino_compartir, R.string.export_destino_compartir_detalle
        ) { viewModel.exportarCompartiendo() }
        vista.opcionGuardar.configurar(
            R.drawable.ic_import, R.color.green_container, R.color.green_text_soft,
            R.string.export_destino_guardar, R.string.export_destino_guardar_detalle
        ) { viewModel.exportarGuardando() }
        dialogoDestinoExport = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.export_destino_titulo, request.formato.name))
            .setView(vista.root)
            .setNegativeButton(R.string.action_cancel, null)
            .setOnDismissListener {
                dialogoDestinoExport = null
                viewModel.cancelarExport()
            }
            .show()
    }

    /** Pide confirmación antes de borrar un socio; después aún se puede deshacer desde el aviso. */
    private fun confirmarEliminar(socio: Socio) {
        if (dialogoEliminar?.isShowing == true) return
        val nombre = socio.nombreSocio.ifBlank { getString(R.string.socio_default_name, socio.colaboracion.toEurosCompacto()) }
        val icono = ContextCompat.getDrawable(this, R.drawable.ic_delete)?.mutate()?.apply {
            setTint(ContextCompat.getColor(this@MainActivity, R.color.red_text))
        }
        val dialogo = MaterialAlertDialogBuilder(this)
            .setIcon(icono)
            .setTitle(getString(R.string.eliminar_socio_titulo, nombre))
            .setMessage(R.string.eliminar_socio_mensaje)
            .setPositiveButton(R.string.action_delete) { _, _ -> viewModel.eliminarSocio(socio) }
            .setNegativeButton(R.string.action_cancel, null)
            .setOnDismissListener { dialogoEliminar = null }
            .show()
        dialogo.getButton(DialogInterface.BUTTON_POSITIVE).setTextColor(ContextCompat.getColor(this, R.color.red_text))
        dialogoEliminar = dialogo
    }

    private fun mostrarDialogoConfirmarImport(preview: ImportPreview?) {
        if (preview == null) {
            dialogoConfirmarImport?.let {
                it.setOnDismissListener(null)
                it.dismiss()
            }
            dialogoConfirmarImport = null
            return
        }
        if (dialogoConfirmarImport?.isShowing == true) return

        val vista = DialogoConfirmarImportBinding.inflate(layoutInflater)
        listOf(
            FilaImport(R.plurals.import_linea_nuevos, preview.sociosNuevos, R.drawable.ic_person, R.color.green_container, R.color.green_text_soft),
            FilaImport(R.plurals.import_linea_sobrescritos, preview.sociosSobrescritos, R.drawable.ic_edit, R.color.orange_container, R.color.orange_text),
            FilaImport(R.plurals.import_linea_bonus, preview.bonos, R.drawable.ic_gift, R.color.amber_container, R.color.amber_text),
            FilaImport(R.plurals.import_linea_irpf, preview.irpfVigencias, R.drawable.ic_receipt, R.color.blue_container, R.color.blue_text_soft)
        ).forEach { fila ->
            val linea = ItemResumenImportBinding.inflate(layoutInflater, vista.filasImport, true)
            linea.placaResumen.backgroundTintList = ContextCompat.getColorStateList(this, fila.fondo)
            linea.ivResumen.setImageResource(fila.icono)
            linea.ivResumen.imageTintList = ContextCompat.getColorStateList(this, fila.color)
            linea.tvResumen.text = resources.getQuantityString(fila.plural, fila.cantidad, fila.cantidad)
        }
        vista.tvDescartadosImport.isVisible = preview.descartados > 0
        vista.tvDescartadosImport.text =
            resources.getQuantityString(R.plurals.import_descartados, preview.descartados, preview.descartados)
        dialogoConfirmarImport = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.import_confirmar_titulo)
            .setView(vista.root)
            .setPositiveButton(R.string.action_importar) { _, _ -> viewModel.confirmarImport() }
            .setNegativeButton(R.string.action_cancel, null)
            .setOnDismissListener {
                dialogoConfirmarImport = null
                viewModel.cancelarImport()
            }
            .show()
    }

    // --- Gestos ---

    private fun setupGestureDetector() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            private val swipeThreshold = 80
            private val swipeVelocityThreshold = 80

            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y

                if (abs(diffX) > abs(diffY) * 1.3f &&
                    abs(diffX) > swipeThreshold && abs(velocityX) > swipeVelocityThreshold
                ) {
                    if (diffX < 0) {
                        HapticUtils.performClick(binding.btnSemanaSiguiente)
                        viewModel.cambiarSemana(1)
                    } else {
                        HapticUtils.performClick(binding.btnSemanaAnterior)
                        viewModel.cambiarSemana(-1)
                    }
                    return true
                }
                return false
            }
        })
    }

    private var gestoSobreControlHorizontal = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            // Un deslizamiento que empieza en un control que ya se arrastra en horizontal (switch,
            // chip, fila desplazable) es para ese control, no para cambiar de semana.
            gestoSobreControlHorizontal = hayControlHorizontalEn(binding.root, ev.rawX, ev.rawY)
        }

        if (!gestoSobreControlHorizontal) {
            gestureDetector.onTouchEvent(ev)
        }

        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            gestoSobreControlHorizontal = false
        }

        return super.dispatchTouchEvent(ev)
    }

    private fun hayControlHorizontalEn(view: View, rawX: Float, rawY: Float): Boolean {
        if (!view.isShown) return false
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val dentro = rawX >= location[0] && rawX <= location[0] + view.width &&
            rawY >= location[1] && rawY <= location[1] + view.height
        if (!dentro) return false
        if (view is HorizontalScrollView || view is CompoundButton) return true
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                if (hayControlHorizontalEn(view.getChildAt(i), rawX, rawY)) return true
            }
        }
        return false
    }

    // --- Configuración de vistas ---

    private fun setupRecyclerView() {
        adapter = SocioAdapter(
            onCambiarHecho = { socio, hecho -> viewModel.setSocioHecho(socio.id, hecho) },
            onEdit = { socio -> openAddEditDialog(socio) },
            onDelete = ::confirmarEliminar
        )
        binding.rvSocios.layoutManager = LinearLayoutManager(this)
        binding.rvSocios.adapter = adapter

        binding.nestedScrollView.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            val dy = scrollY - oldScrollY
            if (dy > 12 && binding.fabAddSocio.isExtended) {
                binding.fabAddSocio.shrink()
            } else if ((dy < -12 || scrollY == 0) && !binding.fabAddSocio.isExtended) {
                binding.fabAddSocio.extend()
            }
        }
    }

    private fun setupWeekNavigation() {
        binding.btnSemanaAnterior.setOnClickListener { viewModel.cambiarSemana(-1) }
        binding.btnSemanaSiguiente.setOnClickListener { viewModel.cambiarSemana(1) }

        val onHoyClick = View.OnClickListener { viewModel.irASemanaActual() }
        binding.btnHoyLeft.setOnClickListener(onHoyClick)
        binding.btnHoyRight.setOnClickListener(onHoyClick)
    }

    private fun setupFiltroDias() {
        // Dos líneas: el día y, debajo, cuántos socios hay (los botones de Material traen una sola).
        (botonesFiltroDia.values + binding.btnFiltroTodos).forEach {
            it.isSingleLine = false
            it.maxLines = 2
        }
        binding.toggleFiltroDias.addOnButtonCheckedListener { grupo, id, marcado ->
            if (!marcado || sincronizandoFiltro) return@addOnButtonCheckedListener
            HapticUtils.performClick(grupo)
            grupo.findViewById<View>(id)?.let { boton ->
                boton.animate().scaleX(1.08f).scaleY(1.08f).setDuration(100).withEndAction {
                    boton.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                }.start()
            }
            viewModel.setDiaFiltro(botonesFiltroDia.entries.firstOrNull { it.value.id == id }?.key)
        }
    }

    private fun setupFab() {
        binding.fabAddSocio.setOnClickListener { openAddEditDialog(null) }
        binding.btnAddSocioEmpty.setOnClickListener { openAddEditDialog(null) }
    }

    private fun setupHeaderClickListeners() {
        binding.btnVerDesglose.setOnClickListener { mostrarDesgloseIrpf() }
        binding.containerBonusHeader.setOnClickListener { mostrarDialogoBonoRegalo() }
    }

    // --- Diálogos ---

    private fun openAddEditDialog(socioToEdit: Socio?) {
        if (supportFragmentManager.findFragmentByTag(AddSocioBottomSheetDialog.TAG) != null) return
        val state = viewModel.uiState.value
        val dialog = if (socioToEdit != null) {
            AddSocioBottomSheetDialog.editar(socioToEdit)
        } else {
            AddSocioBottomSheetDialog.nuevo(state.semanaKey, state.diaFiltro ?: DateUtils.getDiaSemanaActual())
        }
        dialog.show(supportFragmentManager, AddSocioBottomSheetDialog.TAG)
    }

    private fun mostrarDesgloseIrpf() {
        if (supportFragmentManager.findFragmentByTag(IrpfBreakdownBottomSheetDialog.TAG) != null) return
        if (viewModel.uiState.value.resumenSemana == null) return
        IrpfBreakdownBottomSheetDialog().show(supportFragmentManager, IrpfBreakdownBottomSheetDialog.TAG)
    }

    private fun mostrarHistoricoGlobal() {
        if (supportFragmentManager.findFragmentByTag(HistoricoGlobalBottomSheetDialog.TAG) != null) return
        HistoricoGlobalBottomSheetDialog().show(supportFragmentManager, HistoricoGlobalBottomSheetDialog.TAG)
    }

    private fun mostrarDialogoBonoRegalo() {
        if (supportFragmentManager.findFragmentByTag(BonoRegaloBottomSheetDialog.TAG) != null) return
        val state = viewModel.uiState.value
        BonoRegaloBottomSheetDialog.newInstance(state.semanaKey, state.bonoInfoSemana)
            .show(supportFragmentManager, BonoRegaloBottomSheetDialog.TAG)
    }

    private fun mostrarMas() {
        if (supportFragmentManager.findFragmentByTag(MasBottomSheetDialog.TAG) != null) return
        MasBottomSheetDialog().show(supportFragmentManager, MasBottomSheetDialog.TAG)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_ver_historico -> {
                mostrarHistoricoGlobal()
                true
            }

            R.id.action_mas -> {
                mostrarMas()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    /** Una línea del resumen de importación. */
    private class FilaImport(
        @PluralsRes val plural: Int,
        val cantidad: Int,
        @DrawableRes val icono: Int,
        @ColorRes val fondo: Int,
        @ColorRes val color: Int
    )

    private companion object {
        // Deben coincidir con res/xml/shortcuts.xml.
        const val ACTION_NUEVO_SOCIO = "com.julen.socios.action.NUEVO_SOCIO"
        const val ATAJO_NUEVO_SOCIO = "nuevo_socio"

        const val DURACION_AVISO_VERSION_MS = 10_000
    }
}
