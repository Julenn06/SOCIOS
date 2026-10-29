package com.julen.socios

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.view.GestureDetector
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.julen.socios.actualizaciones.VersionPublicada
import com.julen.socios.databinding.ActivityMainBinding
import com.julen.socios.export.ExportFormat
import com.julen.socios.export.ExportRequest
import com.julen.socios.export.ImportFormat
import com.julen.socios.model.ImportPreview
import com.julen.socios.model.Socio
import com.julen.socios.util.AnimationExtensions
import com.julen.socios.util.DateUtils
import com.julen.socios.util.HapticUtils
import com.julen.socios.util.nombreVisible
import com.julen.socios.util.rangoSemanaConAnio
import com.julen.socios.util.semanaRelativa
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cabecera: CabeceraResumen
    private lateinit var filtroDias: FiltroDias
    private lateinit var lista: ListaSemana
    private lateinit var deslizarEntreSemanas: DeslizarEntreSemanas

    private val viewModel: MainViewModel by viewModels { MainViewModel.Factory }
    private val actualizaciones: ActualizacionesViewModel by viewModels { ActualizacionesViewModel.Factory }

    private var dialogoDestinoExport: AlertDialog? = null
    private var dialogoEliminar: AlertDialog? = null

    // --- EXPORT & IMPORT LAUNCHERS ---
    // El resultado vuelve al ViewModel, que guarda la petición pendiente en su SavedStateHandle.
    private val crearDocumentoLaunchers = ExportFormat.entries.associateWith { formato ->
        registerForActivityResult(ActivityResultContracts.CreateDocument(formato.mimeType)) { uri ->
            viewModel.onDocumentoCreado(uri)
        }
    }

    private val abrirArchivoLaunchers = ImportFormat.entries.associateWith { formato ->
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            viewModel.onArchivoImportSeleccionado(uri, formato)
        }
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
        if (resources.configuration.fontScale >= ESCALA_LETRA_TITULO_CORTO) {
            binding.toolbar.setTitle(R.string.title_app_bar_corto)
        }

        cabecera = CabeceraResumen(binding)
        filtroDias = FiltroDias(binding, viewModel::setDiaFiltro)
        lista = ListaSemana(
            binding,
            alCambiarHecho = { socio, hecho -> viewModel.setSocioHecho(socio.id, hecho) },
            alEditar = ::openAddEditDialog,
            alEliminar = ::confirmarEliminar,
            alMantenerPulsado = { AccionesSocioBottomSheetDialog.mostrar(supportFragmentManager, it) }
        )
        AccionesSocioBottomSheetDialog.escuchar(supportFragmentManager, this, ::openAddEditDialog, ::confirmarEliminar)
        setupWeekNavigation()
        setupFab()
        setupHeaderClickListeners()
        deslizarEntreSemanas = DeslizarEntreSemanas(this, binding.root) { delta ->
            HapticUtils.performClick(if (delta > 0) binding.btnSemanaSiguiente else binding.btnSemanaAnterior)
            viewModel.cambiarSemana(delta)
        }

        // Micro-animaciones táctiles
        listOf(
            binding.fabAddSocio, binding.containerBonusHeader, binding.containerCobroHeader,
            binding.cardAvisoPendientes
        ).forEach(AnimationExtensions::setupPressScaleAnimation)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.uiState.collect(::renderUiState) }
                launch { viewModel.events.collect(::manejarEvento) }
                launch { viewModel.exportPendienteDeDestino.collect(::mostrarDialogoDestinoExport) }
                launch { viewModel.importPendiente.collect(::mostrarRevisionImport) }
                launch { viewModel.avisoPendientes.collect(::mostrarAvisoPendientes) }
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
        dialogoDestinoExport?.cerrarSinAvisar()
        super.onDestroy()
    }

    /** Lo cierra sin que su listener tome el cierre por una cancelación del usuario. */
    private fun AlertDialog.cerrarSinAvisar() {
        setOnDismissListener(null)
        dismiss()
    }

    private fun renderUiState(state: MainUiState) {
        // Rango corto de la semana y cuánto queda de la actual
        binding.tvRangoSemana.text = rangoSemanaConAnio(state.semanaKey)
        binding.tvSemanaSubtitulo.text = semanaRelativa(state.weekOffset, R.string.subtitle_current_week)

        // Botón Hoy dinámico
        binding.btnHoyLeft.isVisible = state.weekOffset > 0
        binding.btnHoyRight.isVisible = state.weekOffset < 0

        state.resumenSemana?.let { cabecera.mostrar(state.semanaKey, it, state.cobroSemana) }

        filtroDias.mostrar(state)
        lista.mostrar(state)
    }

    private fun mostrarAvisoPendientes(pendientes: List<Socio>) {
        binding.cardAvisoPendientes.isVisible = pendientes.isNotEmpty()
        binding.tvAvisoPendientes.text =
            resources.getQuantityString(R.plurals.pendientes_aviso, pendientes.size, pendientes.size)
    }

    private fun manejarEvento(evento: MainEvent) {
        when (evento) {
            is MainEvent.Mensaje -> aviso(evento.titulo.resolver(this), evento.tipo, evento.detalle?.resolver(this))

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

            // Cualquier tipo: muchos gestores de archivos marcan los .json y .csv como "octet-stream" y,
            // filtrando por tipo, saldrían en gris. Si no es una copia válida, la importación lo dice.
            is MainEvent.SeleccionarArchivoImport -> abrirArchivoLaunchers.getValue(evento.formato).launch("*/*")

            is MainEvent.SocioEliminado -> aviso(
                getString(R.string.msg_socio_deleted),
                TipoAviso.BORRADO,
                evento.socio.nombreVisible().resolver(this),
                accion = R.string.action_deshacer
            ) { viewModel.restaurarSocio(evento.socio) }
        }
    }

    /**
     * Aviso con el diseño de la app, encima del botón de "Nuevo socio" para no taparlo. Si hay un panel
     * abierto (por ejemplo, el de un socio nuevo con "Guardar y otro"), va dentro de él: el panel es otra ventana y
     * en la pantalla principal quedaría tapado.
     */
    private fun aviso(
        titulo: CharSequence,
        tipo: TipoAviso,
        detalle: CharSequence? = null,
        @StringRes accion: Int? = null,
        alPulsarAccion: (() -> Unit)? = null
    ) {
        val panel = supportFragmentManager.fragments.asReversed().firstNotNullOfOrNull { fragmento ->
            (fragmento as? DialogFragment)?.dialog?.takeIf { it.isShowing }?.window
                ?.decorView?.findViewById<View>(android.R.id.content)
        }
        Avisos.mostrar(
            panel ?: binding.root, titulo, tipo, detalle,
            anclaje = binding.fabAddSocio.takeIf { panel == null }, accion = accion, alPulsarAccion = alPulsarAccion
        )
    }

    private fun avisarNuevaVersion(publicada: VersionPublicada) {
        // Dura más que un aviso normal (ver TipoAviso.NOVEDAD): es fácil no verlo al abrir la app.
        aviso(
            getString(R.string.actualizacion_disponible, publicada.version.toString()),
            TipoAviso.NOVEDAD,
            getString(R.string.actualizacion_disponible_detalle, actualizaciones.versionInstalada),
            R.string.action_actualizar
        ) {
            if (!ActualizacionBottomSheetDialog.empezar(this, actualizaciones, publicada)) {
                aviso(getString(R.string.msg_sin_navegador), TipoAviso.ERROR)
            }
        }
    }

    private fun mostrarDialogoDestinoExport(request: ExportRequest?) {
        if (request == null) {
            dialogoDestinoExport?.cerrarSinAvisar()
            dialogoDestinoExport = null
            return
        }
        if (dialogoDestinoExport?.isShowing == true) return

        // Al elegir, el ViewModel deja de tener la petición pendiente y el diálogo se cierra solo.
        dialogoDestinoExport = preguntarDestinoExport(
            this,
            request.formato,
            alCompartir = viewModel::exportarCompartiendo,
            alGuardar = viewModel::exportarGuardando
        ) {
            dialogoDestinoExport = null
            viewModel.cancelarExport()
        }
    }

    /** Pide confirmación antes de borrar un socio; después aún se puede deshacer desde el aviso. */
    private fun confirmarEliminar(socio: Socio) {
        if (dialogoEliminar?.isShowing == true) return
        dialogoEliminar = confirmarEliminarSocio(this, socio) { viewModel.eliminarSocio(socio) }.apply {
            setOnDismissListener { dialogoEliminar = null }
        }
    }

    /** La revisión de lo leído antes de importarlo; el panel se cierra solo cuando deja de haber importación pendiente. */
    private fun mostrarRevisionImport(preview: ImportPreview?) {
        if (preview != null) supportFragmentManager.mostrarSiNoEsta(ImportarBottomSheetDialog.TAG, ::ImportarBottomSheetDialog)
    }

    // --- Gestos ---

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (deslizarEntreSemanas.onTouchEvent(ev)) {
            // El gesto ha cambiado de semana: la tarjeta que había debajo no debe tomarlo también por un toque.
            val cancelar = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
            super.dispatchTouchEvent(cancelar)
            cancelar.recycle()
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    // --- Configuración de vistas ---

    private fun setupFab() {
        binding.fabAddSocio.setOnClickListener { openAddEditDialog(null) }
        binding.containerEmptyState.setOnClickListener { openAddEditDialog(null) }
        // Se encoge al bajar por la lista y vuelve a mostrar su texto al subir.
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

    private fun setupHeaderClickListeners() {
        binding.containerBonusHeader.setOnClickListener { mostrarDialogoBonoRegalo() }
        setupDobleToqueNeto()
        binding.containerCobroHeader.setOnClickListener {
            HapticUtils.performClick(it)
            mostrarCobro()
        }
        binding.cardAvisoPendientes.setOnClickListener {
            HapticUtils.performClick(it)
            mostrarPendientes()
        }
        ViewCompat.replaceAccessibilityAction(
            binding.cardAvisoPendientes,
            AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
            getString(R.string.pendientes_accion_revisar)
        ) { _, _ ->
            mostrarPendientes()
            true
        }
        binding.btnOcultarAvisoPendientes.setOnClickListener {
            HapticUtils.performClick(it)
            viewModel.ocultarAvisoPendientes()
        }
    }

    // --- Diálogos ---

    private fun openAddEditDialog(socioToEdit: Socio?) {
        val state = viewModel.uiState.value
        supportFragmentManager.mostrarSiNoEsta(AddSocioBottomSheetDialog.TAG) {
            if (socioToEdit != null) {
                AddSocioBottomSheetDialog.editar(socioToEdit)
            } else {
                AddSocioBottomSheetDialog.nuevo(state.semanaKey, state.diaFiltro ?: DateUtils.getDiaSemanaActual())
            }
        }
    }

    private fun mostrarHistoricoGlobal() =
        supportFragmentManager.mostrarSiNoEsta(HistoricoGlobalBottomSheetDialog.TAG, ::HistoricoGlobalBottomSheetDialog)

    private fun mostrarDialogoBonoRegalo() {
        val state = viewModel.uiState.value
        supportFragmentManager.mostrarSiNoEsta(BonoRegaloBottomSheetDialog.TAG) {
            BonoRegaloBottomSheetDialog.newInstance(state.semanaKey, state.bonoInfoSemana)
        }
    }

    /**
     * Doble toque en el neto: ajustarlo a lo cobrado de verdad. Un toque solo no hace nada (se ve el pulsado),
     * para no abrirlo sin querer. Con TalkBack, su doble toque de siempre hace lo mismo.
     */
    @SuppressLint("ClickableViewAccessibility") // TalkBack lo abre con la acción de clic que se sustituye abajo.
    private fun setupDobleToqueNeto() {
        val neto = binding.containerNetoHeader
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                HapticUtils.performConfirm(neto)
                mostrarCobro()
                return true
            }
        })
        // El detector solo mira; la vista sigue recibiendo el toque para dibujar el pulsado.
        neto.setOnTouchListener { _, evento ->
            detector.onTouchEvent(evento)
            false
        }
        ViewCompat.replaceAccessibilityAction(
            neto, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK, getString(R.string.cobro_accion_a11y)
        ) { _, _ ->
            mostrarCobro()
            true
        }
    }

    private fun mostrarCobro() {
        val state = viewModel.uiState.value
        if (state.resumenSemana == null) return
        CobroBottomSheetDialog.mostrar(supportFragmentManager, state)
    }

    private fun mostrarLista(crear: () -> ListaSociosBottomSheetDialog) =
        supportFragmentManager.mostrarSiNoEsta(ListaSociosBottomSheetDialog.TAG, crear)

    private fun mostrarPendientes() = mostrarLista { ListaSociosBottomSheetDialog.pendientes() }

    private fun mostrarMas() = supportFragmentManager.mostrarSiNoEsta(MasBottomSheetDialog.TAG, ::MasBottomSheetDialog)

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_buscar -> {
                mostrarLista { ListaSociosBottomSheetDialog.buscar() }
                true
            }

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

    companion object {
        // Deben coincidir con res/xml/shortcuts.xml. El botón "+" del widget usa la misma acción.
        const val ACTION_NUEVO_SOCIO = "com.julen.socios.action.NUEVO_SOCIO"
        private const val ATAJO_NUEVO_SOCIO = "nuevo_socio"

        /** A partir de esta letra del sistema, "Gestión de Socios" no cabe junto a los tres iconos de la barra. */
        private const val ESCALA_LETRA_TITULO_CORTO = 1.3f
    }
}
