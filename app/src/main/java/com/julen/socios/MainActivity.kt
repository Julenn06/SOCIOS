package com.julen.socios

import android.content.ClipData
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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.julen.socios.databinding.ActivityMainBinding
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

    private val chipsFiltroDia: Map<DiaSemana, Chip> by lazy {
        DiaSemana.entries.zip(
            listOf(
                binding.chipFiltroLun, binding.chipFiltroMar, binding.chipFiltroMie,
                binding.chipFiltroJue, binding.chipFiltroVie
            )
        ).toMap()
    }
    private var sincronizandoFiltro = false

    private var bonusAnterior: Pair<String, Double>? = null

    private var dialogoDestinoExport: AlertDialog? = null
    private var dialogoConfirmarImport: AlertDialog? = null

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
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val density = resources.displayMetrics.density

            binding.fabAddSocio.updateLayoutParams<CoordinatorLayout.LayoutParams> {
                bottomMargin = (20 * density).toInt() + systemBars.bottom
            }
            binding.nestedScrollView.updatePadding(bottom = systemBars.bottom + (80 * density).toInt())
            insets
        }

        setSupportActionBar(binding.toolbar)

        setupRecyclerView()
        setupWeekNavigation()
        setupDayFilterChips()
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
            }
        }
    }

    override fun onDestroy() {
        // Los diálogos se vuelven a mostrar desde el estado del ViewModel si la Activity se recrea.
        listOfNotNull(dialogoDestinoExport, dialogoConfirmarImport).forEach {
            it.setOnDismissListener(null)
            it.dismiss()
        }
        super.onDestroy()
    }

    private fun renderUiState(state: MainUiState) {
        // Rango de fechas y subtítulo
        binding.tvRangoSemana.text = state.rangoTexto
        binding.tvSemanaSubtitulo.text = if (state.weekOffset == 0) {
            getString(R.string.subtitle_current_week)
        } else {
            getString(R.string.title_week_format, state.semanaKey)
        }

        // Botón Hoy dinámico
        binding.btnHoyLeft.isVisible = state.weekOffset > 0
        binding.btnHoyRight.isVisible = state.weekOffset < 0

        state.resumenSemana?.let { renderResumen(state.semanaKey, it) }

        // Chips de filtro por día
        binding.chipFiltroTodos.text = getString(R.string.chip_todos_format, state.todosSociosSemana.size)
        chipsFiltroDia.forEach { (dia, chip) ->
            chip.text = getString(R.string.chip_day_format, dia.nombreCorto, state.conteoPorDia[dia] ?: 0)
        }
        sincronizarChipFiltro(state.diaFiltro)

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

        binding.tvBonusTitle.setTextoAccesible(
            resources.getQuantityString(
                R.plurals.bonus_level_title, hechos, hechos, resumen.bonusSemanal.toEurosCompacto(prefix = "+")
            )
        )
        binding.tvBonusProgressCount.text = getString(R.string.bonus_progress_format, hechos, metaSocios)

        // Pequeño rebote al subir de nivel dentro de la misma semana.
        val anterior = bonusAnterior
        if (anterior != null && anterior.first == semanaKey && anterior.second < resumen.bonusSemanal) {
            binding.tvBonusTitle.animate().scaleX(1.12f).scaleY(1.12f).setDuration(200)
                .setInterpolator(OvershootInterpolator(2.5f)).withEndAction {
                    binding.tvBonusTitle.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                }.start()
        }
        bonusAnterior = semanaKey to resumen.bonusSemanal

        val progressPercent = ((hechos.toFloat() / metaSocios.toFloat()) * 100).toInt().coerceAtMost(100)
        AnimationExtensions.animateProgressSmooth(binding.progressBonus, progressPercent)

        val faltan = resumen.sociosFaltantesParaSiguienteMeta
        binding.tvBonusSiguienteMeta.text = if (faltan > 0) {
            resources.getQuantityString(
                R.plurals.bonus_next_goal, faltan, faltan, resumen.siguienteMetaBonus.toEurosCompacto(prefix = "+")
            )
        } else {
            getString(R.string.bonus_max_goal_reached)
        }
    }

    private fun decimalesDe(importe: Double) = if (importe % 1.0 == 0.0) 0 else 2

    private fun sincronizarChipFiltro(dia: DiaSemana?) {
        val chipId = dia?.let { chipsFiltroDia[it]?.id } ?: R.id.chipFiltroTodos
        if (binding.chipGroupDiasFiltro.checkedChipId != chipId) {
            sincronizandoFiltro = true
            binding.chipGroupDiasFiltro.check(chipId)
            sincronizandoFiltro = false
        }
    }

    private fun manejarEvento(evento: MainEvent) {
        when (evento) {
            is MainEvent.Mensaje -> Snackbar.make(
                binding.root,
                evento.texto.resolver(this),
                if (evento.larga) Snackbar.LENGTH_LONG else Snackbar.LENGTH_SHORT
            ).show()

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

            is MainEvent.SocioEliminado ->
                Snackbar.make(binding.root, R.string.msg_socio_deleted, Snackbar.LENGTH_LONG)
                    .setAction(R.string.action_deshacer) { viewModel.restaurarSocio(evento.socio) }
                    .show()
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

        val opciones = arrayOf(getString(R.string.export_destino_compartir), getString(R.string.export_destino_guardar))
        dialogoDestinoExport = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.export_destino_titulo, request.formato.name))
            .setItems(opciones) { _, which ->
                if (which == 0) viewModel.exportarCompartiendo() else viewModel.exportarGuardando()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .setOnDismissListener {
                dialogoDestinoExport = null
                viewModel.cancelarExport()
            }
            .show()
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

        val mensaje = buildString {
            append(getString(R.string.import_confirmar_cabecera))
            listOf(
                R.plurals.import_linea_nuevos to preview.sociosNuevos,
                R.plurals.import_linea_sobrescritos to preview.sociosSobrescritos,
                R.plurals.import_linea_bonus to preview.bonos,
                R.plurals.import_linea_irpf to preview.irpfVigencias
            ).forEach { (plural, cantidad) ->
                append("\n• ").append(resources.getQuantityString(plural, cantidad, cantidad))
            }
            if (preview.descartados > 0) {
                append(resources.getQuantityString(R.plurals.import_descartados, preview.descartados, preview.descartados))
            }
        }
        dialogoConfirmarImport = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.import_confirmar_titulo)
            .setMessage(mensaje)
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
            onDelete = { socio -> viewModel.eliminarSocio(socio) }
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

    private fun setupDayFilterChips() {
        binding.chipGroupDiasFiltro.setOnCheckedStateChangeListener { chipGroup, checkedIds ->
            if (sincronizandoFiltro) return@setOnCheckedStateChangeListener
            HapticUtils.performClick(chipGroup)
            checkedIds.firstOrNull()?.let { chipGroup.findViewById<View>(it) }?.let { chip ->
                chip.animate().scaleX(1.08f).scaleY(1.08f).setDuration(100).withEndAction {
                    chip.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                }.start()
            }
            val dia = chipsFiltroDia.entries.firstOrNull { it.value.id in checkedIds }?.key
            viewModel.setDiaFiltro(dia)
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
        BonoRegaloBottomSheetDialog.newInstance(state.semanaKey, state.rangoTexto, state.bonoInfoSemana)
            .show(supportFragmentManager, BonoRegaloBottomSheetDialog.TAG)
    }

    private fun mostrarDialogoExportImport() {
        if (supportFragmentManager.findFragmentByTag(ExportImportBottomSheetDialog.TAG) != null) return
        ExportImportBottomSheetDialog().show(supportFragmentManager, ExportImportBottomSheetDialog.TAG)
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

            R.id.action_export_import -> {
                mostrarDialogoExportImport()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }
}
