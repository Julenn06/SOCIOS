package com.julen.socios

import androidx.lifecycle.SavedStateHandle
import com.julen.socios.data.BackupRepository
import com.julen.socios.data.BonoRegaloRepository
import com.julen.socios.data.IrpfRepository
import com.julen.socios.data.SocioRepository
import com.julen.socios.data.local.SocioEntity
import com.julen.socios.export.AlcanceExport
import com.julen.socios.export.ExportFormat
import com.julen.socios.export.ExportRequest
import com.julen.socios.fakes.FakeBonoRegaloDao
import com.julen.socios.fakes.FakeExportService
import com.julen.socios.fakes.FakeIrpfVigenciaDao
import com.julen.socios.fakes.FakeSocioDao
import com.julen.socios.fakes.FakeTransactionRunner
import com.julen.socios.model.DatosBackup
import com.julen.socios.model.DiaSemana
import com.julen.socios.model.ImportPreview
import com.julen.socios.model.ResultadoLectura
import com.julen.socios.model.Socio
import com.julen.socios.util.CalculoComisiones
import com.julen.socios.util.DateUtils
import com.julen.socios.util.UiText
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    private val zona = ZoneId.of("Europe/Madrid")
    private val clock = Clock.fixed(LocalDate.parse("2026-10-14").atTime(12, 0).atZone(zona).toInstant(), zona)
    private val semanaActual = DateUtils.getSemanaKey(0, clock)
    private val semanaSiguiente = DateUtils.getSemanaKey(1, clock)

    private val dispatcher = UnconfinedTestDispatcher()
    private val socioDao = FakeSocioDao()
    private val bonoDao = FakeBonoRegaloDao()
    private val irpfDao = FakeIrpfVigenciaDao()
    private val exportService = FakeExportService()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun crearViewModel(handle: SavedStateHandle = SavedStateHandle()) = MainViewModel(
        socioRepository = SocioRepository(socioDao),
        bonoRegaloRepository = BonoRegaloRepository(bonoDao),
        irpfRepository = IrpfRepository(irpfDao),
        backupRepository = BackupRepository(socioDao, bonoDao, irpfDao, FakeTransactionRunner()),
        exportService = exportService,
        savedStateHandle = handle,
        clock = clock,
        calculoDispatcher = dispatcher
    )

    private fun entidad(
        id: String,
        semana: String,
        dia: DiaSemana = DiaSemana.LUNES,
        hecho: Boolean = true,
        cuota: Double = 20.0
    ) = SocioEntity(id, cuota, hecho, dia.id, semana, "", "", 0)

    /** Mantiene suscritos los StateFlow (WhileSubscribed) y recoge los eventos emitidos. */
    private fun TestScope.observar(viewModel: MainViewModel): MutableList<MainEvent> {
        val eventos = mutableListOf<MainEvent>()
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }
        backgroundScope.launch(dispatcher) { viewModel.historicoGlobal.collect {} }
        backgroundScope.launch(dispatcher) { viewModel.events.toList(eventos) }
        return eventos
    }

    @Test
    fun alCambiarDeSemanaNuncaSeMezclanFechasYDatosDeSemanasDistintas() = runTest(dispatcher) {
        socioDao.socios.value = listOf(entidad("a", semanaActual), entidad("b", semanaSiguiente), entidad("c", semanaSiguiente))
        val viewModel = crearViewModel()
        val estados = mutableListOf<MainUiState>()
        backgroundScope.launch(dispatcher) { viewModel.uiState.toList(estados) }

        viewModel.cambiarSemana(1)

        val ultimo = estados.last()
        assertEquals(semanaSiguiente, ultimo.semanaKey)
        assertEquals(1, ultimo.weekOffset)
        assertEquals(2, ultimo.todosSociosSemana.size)
        estados.forEach { estado ->
            assertTrue(estado.toString(), estado.todosSociosSemana.all { it.semanaKey == estado.semanaKey })
        }
    }

    @Test
    fun elFiltroPorDiaSeAplicaYSeReiniciaAlCambiarDeSemana() = runTest(dispatcher) {
        socioDao.socios.value = listOf(
            entidad("a", semanaActual, DiaSemana.LUNES),
            entidad("b", semanaActual, DiaSemana.MARTES),
            entidad("c", semanaActual, DiaSemana.MARTES)
        )
        val viewModel = crearViewModel()
        observar(viewModel)

        viewModel.setDiaFiltro(DiaSemana.MARTES)

        val estado = viewModel.uiState.value
        assertEquals(listOf("b", "c"), estado.sociosFiltrados.map { it.id }.sorted())
        assertEquals(mapOf(DiaSemana.LUNES to 1, DiaSemana.MARTES to 2), estado.conteoPorDia)

        viewModel.cambiarSemana(1)
        assertNull(viewModel.uiState.value.diaFiltro)
    }

    @Test
    fun semanaYFiltroSobrevivenALaRecreacion() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        crearViewModel(handle).apply {
            cambiarSemana(1)
            setDiaFiltro(DiaSemana.JUEVES)
        }

        val recreado = crearViewModel(handle)
        observar(recreado)

        assertEquals(semanaSiguiente, recreado.uiState.value.semanaKey)
        assertEquals(DiaSemana.JUEVES, recreado.uiState.value.diaFiltro)
    }

    @Test
    fun marcarComoNoHechoActualizaElResumenYAvisa() = runTest(dispatcher) {
        socioDao.socios.value = List(5) { entidad("s$it", semanaActual) }
        val viewModel = crearViewModel()
        val eventos = observar(viewModel)
        assertEquals(50.0, viewModel.uiState.value.resumenSemana!!.bonusSemanal, 0.0)

        viewModel.setSocioHecho("s0", false)

        assertEquals(4, viewModel.uiState.value.resumenSemana!!.totalSociosHechos)
        assertEquals(0.0, viewModel.uiState.value.resumenSemana!!.bonusSemanal, 0.0)
        assertEquals(MainEvent.Mensaje(UiText.Res(R.string.msg_socio_pendiente)), eventos.last())
    }

    @Test
    fun elIrpfSoloCambiaDesdeLaSemanaIndicada() = runTest(dispatcher) {
        socioDao.socios.value = listOf(entidad("a", semanaActual), entidad("b", semanaSiguiente))
        val viewModel = crearViewModel()
        observar(viewModel)

        viewModel.guardarIrpf(semanaSiguiente, 15.0)

        assertEquals(CalculoComisiones.IRPF_POR_DEFECTO, viewModel.uiState.value.resumenSemana!!.porcentajeIrpf, 0.0)
        viewModel.cambiarSemana(1)
        assertEquals(15.0, viewModel.uiState.value.resumenSemana!!.porcentajeIrpf, 0.0)
    }

    @Test
    fun elHistoricoCalculaCadaSemanaPorSeparado() = runTest(dispatcher) {
        socioDao.socios.value = List(5) { entidad("a$it", semanaActual) } + List(5) { entidad("b$it", semanaSiguiente) }
        val viewModel = crearViewModel()
        observar(viewModel)

        val historico = viewModel.historicoGlobal.value!!
        assertEquals(2, historico.semanasHistoricas.size)
        assertEquals(100.0, historico.totalBonusAcumulado, 0.0)
    }

    @Test
    fun laImportacionMuestraUnaVistaPreviaYSoloGuardaAlConfirmar() = runTest(dispatcher) {
        socioDao.socios.value = listOf(entidad("existente", semanaActual, cuota = 10.0))
        val viewModel = crearViewModel()
        val eventos = observar(viewModel)
        val lectura = ResultadoLectura(
            datos = DatosBackup(
                socios = listOf(
                    Socio(id = "existente", colaboracion = 30.0, dia = DiaSemana.LUNES, semanaKey = semanaActual),
                    Socio(id = "nuevo", colaboracion = 20.0, dia = DiaSemana.MARTES, semanaKey = semanaActual)
                )
            ),
            descartados = 2
        )

        viewModel.prepararImportacion(lectura, ExportFormat.JSON)

        assertEquals(ImportPreview(1, 1, 0, 0, 2), viewModel.importPendiente.value)
        assertEquals(1, socioDao.socios.value.size)

        viewModel.confirmarImport()

        assertNull(viewModel.importPendiente.value)
        assertEquals(setOf("existente", "nuevo"), socioDao.socios.value.map { it.id }.toSet())
        assertEquals(30.0, socioDao.socios.value.first { it.id == "existente" }.colaboracion, 0.0)
        assertEquals(
            MainEvent.Mensaje(UiText.Plural(R.plurals.msg_import_resultado, 2, listOf(2, 0, 0)), larga = true),
            eventos.last()
        )
    }

    @Test
    fun cancelarLaImportacionNoGuardaNada() = runTest(dispatcher) {
        val viewModel = crearViewModel()
        observar(viewModel)
        val lectura = ResultadoLectura(
            DatosBackup(socios = listOf(Socio(colaboracion = 20.0, dia = DiaSemana.LUNES, semanaKey = semanaActual)))
        )

        viewModel.prepararImportacion(lectura, ExportFormat.CSV)
        viewModel.cancelarImport()
        viewModel.confirmarImport()

        assertTrue(socioDao.socios.value.isEmpty())
    }

    @Test
    fun exportarSinSociosAvisaYNoDejaNadaPendiente() = runTest(dispatcher) {
        val viewModel = crearViewModel()
        val eventos = observar(viewModel)

        viewModel.solicitarExport(ExportFormat.PDF, AlcanceExport.Semanas(setOf(semanaActual)))

        assertNull(viewModel.exportPendienteDeDestino.value)
        assertEquals(MainEvent.Mensaje(UiText.Res(R.string.msg_export_vacio), larga = true), eventos.last())
    }

    @Test
    fun laExportacionPendienteSobreviveALaRecreacionYSeGuardaEnElDocumentoElegido() = runTest(dispatcher) {
        socioDao.socios.value = listOf(entidad("a", semanaActual))
        val handle = SavedStateHandle()
        val viewModel = crearViewModel(handle)
        observar(viewModel)
        val request = ExportRequest(ExportFormat.CSV, AlcanceExport.Semanas(setOf(semanaActual)))

        viewModel.solicitarExport(request.formato, request.alcance)
        assertEquals(request, viewModel.exportPendienteDeDestino.value)

        // La Activity se recrea mientras el usuario elige destino: la petición sigue ahí.
        val recreado = crearViewModel(handle)
        val eventos = observar(recreado)
        assertEquals(request, recreado.exportPendienteDeDestino.value)

        recreado.exportarGuardando()
        assertNull(recreado.exportPendienteDeDestino.value)
        assertEquals(MainEvent.CrearDocumento(ExportFormat.CSV, "socios_test.csv"), eventos.last())

        // El usuario cancela el selector de archivos: no se escribe nada y no queda nada pendiente.
        recreado.onDocumentoCreado(null)
        assertTrue(exportService.escritos.isEmpty())
        recreado.onDocumentoCreado(null)
        assertTrue(exportService.escritos.isEmpty())
    }
}
