package com.julen.socios

import android.net.Uri
import android.os.Bundle
import android.view.GestureDetector
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.julen.socios.data.BonoRegaloRepository
import com.julen.socios.data.SocioRepository
import com.julen.socios.databinding.ActivityMainBinding
import com.julen.socios.model.BonoRegaloInfo
import com.julen.socios.model.DiaSemana
import com.julen.socios.model.Socio
import com.julen.socios.util.AnimationExtensions
import com.julen.socios.util.CalculoComisiones
import com.julen.socios.util.DateUtils
import com.julen.socios.util.ExportUtils
import com.julen.socios.util.HapticUtils
import com.julen.socios.util.NumberAnimators
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: SocioAdapter
    private lateinit var gestureDetector: GestureDetector

    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory(
            SocioRepository(applicationContext), BonoRegaloRepository(applicationContext)
        )
    }

    private var previousBonusAmount: Int = -1

    // --- EXPORT & IMPORT LAUNCHERS ---
    private var pendingExportType: String? = null
    private var pendingExportSocios: List<Socio> = emptyList()
    private var pendingExportBonos: List<BonoRegaloInfo> = emptyList()
    private var pendingExportTitle: String = ""

    private val createDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri: Uri? ->
        if (uri != null && pendingExportType != null) {
            lifecycleScope.launch {
                try {
                    contentResolver.openOutputStream(uri)?.use { outputStream ->
                        when (pendingExportType) {
                            "pdf" -> {
                                val state = viewModel.uiState.value
                                val bonoInfo =
                                    if (pendingExportTitle != "Histórico Global") viewModel.getBonoRegalo(
                                        state.semanaKey
                                    ) else null
                                ExportUtils.exportToPdf(
                                    pendingExportSocios, pendingExportTitle, outputStream, bonoInfo
                                )
                            }

                            "csv" -> ExportUtils.exportToCsv(
                                pendingExportSocios, pendingExportBonos, outputStream
                            )

                            "json" -> ExportUtils.exportToJson(
                                pendingExportSocios, pendingExportBonos, outputStream
                            )
                        }
                    }
                    Snackbar.make(
                        binding.root, getString(R.string.msg_file_saved), Snackbar.LENGTH_LONG
                    ).show()
                } catch (e: Exception) {
                    Snackbar.make(
                        binding.root,
                        getString(R.string.msg_error_file_save, e.message.orEmpty()),
                        Snackbar.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private val importJsonLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            lifecycleScope.launch {
                try {
                    contentResolver.openInputStream(uri)?.use { inputStream ->
                        val result = ExportUtils.importFromJson(inputStream)
                        if (result.socios.isNotEmpty() || result.bonosRegalo.isNotEmpty()) {
                            viewModel.importBackup(result.socios, result.bonosRegalo)
                        } else {
                            Snackbar.make(
                                binding.root,
                                "El archivo JSON no contiene datos válidos",
                                Snackbar.LENGTH_LONG
                            ).show()
                        }
                    }
                } catch (e: Exception) {
                    Snackbar.make(
                        binding.root, "Error al importar JSON: ${e.message}", Snackbar.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private val importCsvLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            lifecycleScope.launch {
                try {
                    contentResolver.openInputStream(uri)?.use { inputStream ->
                        val result = ExportUtils.importFromCsv(inputStream)
                        if (result.socios.isNotEmpty() || result.bonosRegalo.isNotEmpty()) {
                            viewModel.importBackup(result.socios, result.bonosRegalo)
                        } else {
                            Snackbar.make(
                                binding.root,
                                "El archivo CSV no contiene datos válidos",
                                Snackbar.LENGTH_LONG
                            ).show()
                        }
                    }
                } catch (e: Exception) {
                    Snackbar.make(
                        binding.root, "Error al importar CSV: ${e.message}", Snackbar.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
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

        // Observar estado del ViewModel reactivamente
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    renderUiState(state)
                }
            }
        }
    }

    private fun renderUiState(state: MainUiState) {
        // Rango de fechas y subtítulo
        binding.tvRangoSemana.text = state.rangoTexto
        binding.tvSemanaSubtitulo.text =
            if (state.weekOffset == 0) "Semana actual (Lunes a Viernes)" else "Semana ${state.semanaKey}"

        // Botón Hoy dinámico
        if (state.weekOffset < 0) {
            binding.btnHoyLeft.visibility = View.GONE
            binding.btnHoyRight.visibility = View.VISIBLE
        } else if (state.weekOffset > 0) {
            binding.btnHoyLeft.visibility = View.VISIBLE
            binding.btnHoyRight.visibility = View.GONE
        } else {
            binding.btnHoyLeft.visibility = View.GONE
            binding.btnHoyRight.visibility = View.GONE
        }

        // Totales financieros
        val resumen = state.resumenSemana
        if (resumen != null) {
            NumberAnimators.animateCurrency(binding.tvNetoHeader, resumen.totalNeto, prefix = "")
            NumberAnimators.animateCurrency(
                binding.tvBaseHeader, resumen.gananciasBaseX2, prefix = "+", decimals = 0
            )
            NumberAnimators.animateCurrency(
                binding.tvBonusHeader, resumen.bonusSemanal, prefix = "+", decimals = 0
            )
            NumberAnimators.animateCurrency(
                binding.tvIrpfHeader, resumen.retencionIrpf, prefix = "-"
            )

            binding.tvBonusHeaderLabel.text =
                if (resumen.esBonoRegaloAplicado) "Bonus (Manual) ✏️" else "Bonus Socios ✏️"

            val bonusActual = resumen.bonusSemanal.toInt()
            val hechosCount = resumen.totalSociosHechos
            val metaSocios = resumen.siguienteMetaSocios
            val metaBonus = resumen.siguienteMetaBonus.toInt()

            binding.tvBonusTitle.text =
                "🏆 Bonus Nivel: +$bonusActual € ($hechosCount socios hechos)"
            binding.tvBonusProgressCount.text = "$hechosCount / $metaSocios socios"

            if (previousBonusAmount in 0 until bonusActual) {
                binding.tvBonusTitle.animate().scaleX(1.12f).scaleY(1.12f).setDuration(200)
                    .setInterpolator(OvershootInterpolator(2.5f)).withEndAction {
                        binding.tvBonusTitle.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150)
                            .start()
                    }.start()
            }
            previousBonusAmount = bonusActual

            val progressPercent =
                ((hechosCount.toFloat() / metaSocios.toFloat()) * 100).toInt().coerceAtMost(100)
            AnimationExtensions.animateProgressSmooth(binding.progressBonus, progressPercent)

            if (resumen.sociosFaltantesParaSiguienteMeta > 0) {
                val faltan = resumen.sociosFaltantesParaSiguienteMeta
                binding.tvBonusSiguienteMeta.text =
                    "¡Haz $faltan ${if (faltan == 1) "socio más" else "socios más"} para alcanzar el Bonus de +$metaBonus €!"
            } else {
                binding.tvBonusSiguienteMeta.text =
                    "¡Enhorabuena! Has alcanzado el nivel de bonus máximo para este tramo."
            }
        }

        // Chips de filtro por día
        val countLun = state.todosSociosSemana.count { it.diaSemanaId == 1 }
        val countMar = state.todosSociosSemana.count { it.diaSemanaId == 2 }
        val countMie = state.todosSociosSemana.count { it.diaSemanaId == 3 }
        val countJue = state.todosSociosSemana.count { it.diaSemanaId == 4 }
        val countVie = state.todosSociosSemana.count { it.diaSemanaId == 5 }
        val countTodos = state.todosSociosSemana.size

        binding.chipFiltroTodos.text = "Todos ($countTodos)"
        binding.chipFiltroLun.text = "Lun ($countLun)"
        binding.chipFiltroMar.text = "Mar ($countMar)"
        binding.chipFiltroMie.text = "Mié ($countMie)"
        binding.chipFiltroJue.text = "Jue ($countJue)"
        binding.chipFiltroVie.text = "Vie ($countVie)"

        // Lista de socios
        adapter.submitList(state.sociosFiltrados)

        if (state.sociosFiltrados.isEmpty()) {
            binding.containerEmptyState.visibility = View.VISIBLE
            binding.rvSocios.visibility = View.GONE
        } else {
            binding.containerEmptyState.visibility = View.GONE
            binding.rvSocios.visibility = View.VISIBLE
        }

        val diaNombre = state.selectedDiaFiltroId?.let { DiaSemana.fromId(it).nombreCompleto }
        binding.tvListaTitulo.text =
            if (diaNombre != null) "Socios del $diaNombre" else "Socios de la semana"

        val hechosFiltrados = state.sociosFiltrados.count { it.hecho }
        binding.tvListaResumenDia.text =
            "$hechosFiltrados hechos / ${state.sociosFiltrados.size} reg."

        // Mostrar mensajes
        state.messageEvent?.let { message ->
            Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
            viewModel.clearMessageEvent()
        }
    }

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

                if (abs(diffX) > abs(diffY) * 1.3f) {
                    if (abs(diffX) > swipeThreshold && abs(velocityX) > swipeVelocityThreshold) {
                        if (diffX < 0) {
                            HapticUtils.performClick(binding.btnSemanaSiguiente)
                            viewModel.changeWeekOffset(1)
                        } else {
                            HapticUtils.performClick(binding.btnSemanaAnterior)
                            viewModel.changeWeekOffset(-1)
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    private var isTouchStartedOnDayChips = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            isTouchStartedOnDayChips = isTouchInsideView(binding.scrollDiasFiltro, ev)
        }

        if (!isTouchStartedOnDayChips) {
            gestureDetector.onTouchEvent(ev)
        }

        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            isTouchStartedOnDayChips = false
        }

        return super.dispatchTouchEvent(ev)
    }

    private fun isTouchInsideView(view: View, ev: MotionEvent): Boolean {
        if (view.visibility != View.VISIBLE) return false
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val x = location[0]
        val y = location[1]
        val width = view.width
        val height = view.height

        return ev.rawX >= x && ev.rawX <= (x + width) && ev.rawY >= y && ev.rawY <= (y + height)
    }

    private fun setupRecyclerView() {
        adapter = SocioAdapter(onToggleHecho = { socio ->
            viewModel.toggleSocioHecho(socio.id)
        }, onEdit = { socio ->
            openAddEditDialog(socio)
        }, onDelete = { socio ->
            confirmDeleteSocio(socio)
        })
        binding.rvSocios.layoutManager = LinearLayoutManager(this)
        binding.rvSocios.adapter = adapter

        binding.rvSocios.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy > 8 && binding.fabAddSocio.isExtended) {
                    binding.fabAddSocio.shrink()
                } else if (dy < -8 && !binding.fabAddSocio.isExtended) {
                    binding.fabAddSocio.extend()
                }
            }
        })
    }

    private fun setupWeekNavigation() {
        binding.btnSemanaAnterior.setOnClickListener {
            viewModel.changeWeekOffset(-1)
        }

        binding.btnSemanaSiguiente.setOnClickListener {
            viewModel.changeWeekOffset(1)
        }

        val onHoyClick = View.OnClickListener {
            viewModel.setWeekOffset(0)
        }

        binding.btnHoyLeft.setOnClickListener(onHoyClick)
        binding.btnHoyRight.setOnClickListener(onHoyClick)
    }

    private fun setupDayFilterChips() {
        binding.chipGroupDiasFiltro.setOnCheckedStateChangeListener { chipGroup, checkedIds ->
            HapticUtils.performClick(chipGroup)
            val checkedChipId = checkedIds.firstOrNull()
            if (checkedChipId != null) {
                val chip = chipGroup.findViewById<View>(checkedChipId)
                chip?.animate()?.scaleX(1.08f)?.scaleY(1.08f)?.setDuration(100)?.withEndAction {
                    chip.animate()?.scaleX(1.0f)?.scaleY(1.0f)?.setDuration(100)?.start()
                }?.start()
            }
            val diaId = when {
                checkedIds.contains(R.id.chipFiltroLun) -> 1
                checkedIds.contains(R.id.chipFiltroMar) -> 2
                checkedIds.contains(R.id.chipFiltroMie) -> 3
                checkedIds.contains(R.id.chipFiltroJue) -> 4
                checkedIds.contains(R.id.chipFiltroVie) -> 5
                else -> null
            }
            viewModel.setSelectedDiaFiltro(diaId)
        }
    }

    private fun setupFab() {
        binding.fabAddSocio.setOnClickListener {
            openAddEditDialog(null)
        }
        binding.btnAddSocioEmpty.setOnClickListener {
            openAddEditDialog(null)
        }
    }

    private fun setupHeaderClickListeners() {
        binding.btnVerDesglose.setOnClickListener {
            mostrarDesgloseIrpf()
        }
        binding.containerBonusHeader.setOnClickListener {
            mostrarDialogoBonoRegalo()
        }
    }

    private fun openAddEditDialog(socioToEdit: Socio?) {
        if (supportFragmentManager.findFragmentByTag("AddSocioDialog") != null) return
        val state = viewModel.uiState.value
        val defaultDia = state.selectedDiaFiltroId ?: DateUtils.getDiaSemanaActualId()

        val dialog = AddSocioBottomSheetDialog(
            semanaKey = state.semanaKey,
            defaultDiaId = defaultDia,
            socioToEdit = socioToEdit,
            onSave = { socio ->
                if (socioToEdit == null) {
                    viewModel.addSocio(socio)
                } else {
                    viewModel.updateSocio(socio)
                }
            })
        dialog.show(supportFragmentManager, "AddSocioDialog")
    }

    private fun confirmDeleteSocio(socio: Socio) {
        AlertDialog.Builder(this).setTitle(getString(R.string.dialog_delete_socio_title))
            .setMessage(getString(R.string.dialog_delete_socio_msg, socio.colaboracion.toInt()))
            .setPositiveButton(getString(R.string.action_delete)) { _, _ ->
                viewModel.deleteSocio(socio.id)
            }.setNegativeButton(getString(R.string.action_cancel), null).show()
    }

    private fun mostrarDesgloseIrpf() {
        if (supportFragmentManager.findFragmentByTag("IrpfBreakdownDialog") != null) return
        val state = viewModel.uiState.value
        val resumen = state.resumenSemana ?: return

        val dialog = IrpfBreakdownBottomSheetDialog(state.rangoTexto, resumen)
        dialog.show(supportFragmentManager, "IrpfBreakdownDialog")
    }

    private fun mostrarHistoricoGlobal() {
        if (supportFragmentManager.findFragmentByTag("HistoricoGlobalDialog") != null) return
        lifecycleScope.launch {
            val todosLosSocios = viewModel.getAllSocios()
            val bonosMap = viewModel.getAllBonosRegalo()
            val resumenGlobal =
                CalculoComisiones.calcularResumenHistoricoGlobal(todosLosSocios, bonosMap)

            val dialog = HistoricoGlobalBottomSheetDialog(
                resumenGlobal = resumenGlobal, onSelectSemana = { semanaKey ->
                    val offset = DateUtils.getWeekOffsetFromKey(semanaKey)
                    viewModel.setWeekOffset(offset)
                })
            dialog.show(supportFragmentManager, "HistoricoGlobalDialog")
        }
    }

    private fun mostrarDialogoBonoRegalo() {
        if (supportFragmentManager.findFragmentByTag("BonoRegaloDialog") != null) return
        val state = viewModel.uiState.value
        val currentBono = state.bonoInfoSemana ?: BonoRegaloInfo(semanaKey = state.semanaKey)

        val dialog = BonoRegaloBottomSheetDialog(
            semanaTexto = state.rangoTexto,
            currentBono = currentBono,
            onSave = { nuevoBono ->
                viewModel.saveBonoRegalo(nuevoBono)
            },
            onDelete = {
                viewModel.deleteBonoRegalo(state.semanaKey)
            })
        dialog.show(supportFragmentManager, "BonoRegaloDialog")
    }

    private fun mostrarDialogoExportImport() {
        if (supportFragmentManager.findFragmentByTag("ExportImportDialog") != null) return
        lifecycleScope.launch {
            val state = viewModel.uiState.value
            val todosLosSocios = viewModel.getAllSocios()
            val bonosMap = viewModel.getAllBonosRegalo()
            val todasLasSemanasKeys =
                (todosLosSocios.map { it.semanaKey } + bonosMap.keys).distinct().sortedDescending()

            val dialog = ExportImportBottomSheetDialog(
                semanaKeyActiva = state.semanaKey,
                todasLasSemanasKeys = todasLasSemanasKeys,
                onExportPdf = { scope ->
                    ejecutarExportacion("pdf", scope)
                },
                onExportCsv = { scope ->
                    ejecutarExportacion("csv", scope)
                },
                onExportJson = { scope ->
                    ejecutarExportacion("json", scope)
                },
                onImportJson = {
                    importJsonLauncher.launch("application/json")
                },
                onImportCsv = {
                    importCsvLauncher.launch("*/*")
                })
            dialog.show(supportFragmentManager, "ExportImportDialog")
        }
    }

    private fun ejecutarExportacion(tipo: String, scope: ExportScope) {
        lifecycleScope.launch {
            val state = viewModel.uiState.value
            val allSocios = viewModel.getAllSocios()
            val allBonosMap = viewModel.getAllBonosRegalo()

            val (socios, titulo, bonoInfoForPdf, bonosInScope) = when (scope) {
                is ExportScope.SemanaActiva -> {
                    val sociosSemana = viewModel.getSociosPorSemana(state.semanaKey)
                    val bono = viewModel.getBonoRegalo(state.semanaKey)
                    val listBonos = listOfNotNull(bono).filter { it.activo }
                    Tuple4(sociosSemana, state.rangoTexto, bono, listBonos)
                }

                is ExportScope.SemanasEspecificas -> {
                    val keys = scope.semanaKeys
                    val sociosFiltrados = allSocios.filter { it.semanaKey in keys }
                    val tituloTexto = if (keys.size == 1) {
                        DateUtils.getRangoSemanaTextoFromKey(keys.first())
                    } else {
                        "${keys.size} semanas seleccionadas"
                    }
                    val bono = if (keys.size == 1) viewModel.getBonoRegalo(keys.first()) else null
                    val listBonos = keys.mapNotNull { allBonosMap[it] }.filter { it.activo }
                    Tuple4(sociosFiltrados, tituloTexto, bono, listBonos)
                }

                is ExportScope.HistoricoGlobal -> {
                    val listBonos = allBonosMap.values.filter { it.activo }
                    Tuple4(allSocios, "Histórico Global", null, listBonos)
                }
            }

            if (socios.isEmpty()) {
                Snackbar.make(
                    binding.root,
                    "No hay socios para exportar en el rango seleccionado",
                    Snackbar.LENGTH_LONG
                ).show()
                return@launch
            }

            val opciones = arrayOf("Compartir con otra app", "Guardar en el dispositivo")
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Exportar ${tipo.uppercase(Locale.getDefault())}")
                .setItems(opciones) { _, which ->
                    if (which == 0) {
                        lifecycleScope.launch {
                            val file = when (tipo) {
                                "pdf" -> ExportUtils.exportToPdfFile(
                                    this@MainActivity, socios, titulo, bonoInfoForPdf
                                )

                                "csv" -> ExportUtils.exportToCsvFile(
                                    this@MainActivity, socios, bonosInScope
                                )

                                else -> ExportUtils.exportToJsonFile(
                                    this@MainActivity, socios, bonosInScope
                                )
                            }
                            if (file != null) {
                                val mime = when (tipo) {
                                    "pdf" -> "application/pdf"
                                    "csv" -> "text/csv"
                                    else -> "application/json"
                                }
                                ExportUtils.shareFile(
                                    this@MainActivity, file, mime, "Compartir reporte $tipo"
                                )
                            } else {
                                Snackbar.make(
                                    binding.root,
                                    "Error al generar archivo temporal",
                                    Snackbar.LENGTH_LONG
                                ).show()
                            }
                        }
                    } else {
                        pendingExportType = tipo
                        pendingExportSocios = socios
                        pendingExportBonos = bonosInScope
                        pendingExportTitle = titulo

                        val timestamp =
                            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                        val defaultFileName = "socios_${state.semanaKey}_$timestamp.$tipo"
                        createDocumentLauncher.launch(defaultFileName)
                    }
                }.setNegativeButton("Cancelar", null).show()
        }
    }

    private data class Tuple4<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

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
