package com.julen.socios

import androidx.lifecycle.SavedStateHandle
import com.julen.socios.data.BackupRepository
import com.julen.socios.data.BonoRegaloRepository
import com.julen.socios.data.CobroRepository
import com.julen.socios.data.IrpfRepository
import com.julen.socios.data.SocioRepository
import com.julen.socios.data.local.BonoRegaloEntity
import com.julen.socios.data.local.SocioEntity
import com.julen.socios.export.AlcanceExport
import com.julen.socios.export.ExportFormat
import com.julen.socios.export.ExportRequest
import com.julen.socios.export.ImportFormat
import com.julen.socios.fakes.FakeBonoRegaloDao
import com.julen.socios.fakes.FakeCobroDao
import com.julen.socios.fakes.FakePreferenciasUsuario
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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
    private val cobroDao = FakeCobroDao()
    private val preferencias = FakePreferenciasUsuario()
    private val exportService = FakeExportService()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun crearViewModel(
        handle: SavedStateHandle = SavedStateHandle(),
        relojSemana: Flow<String> = flowOf(semanaActual)
    ) = MainViewModel(
        socioRepository = SocioRepository(socioDao),
        bonoRegaloRepository = BonoRegaloRepository(bonoDao),
        irpfRepository = IrpfRepository(irpfDao),
        cobroRepository = CobroRepository(cobroDao),
        backupRepository = BackupRepository(socioDao, bonoDao, irpfDao, cobroDao, FakeTransactionRunner()),
        exportService = exportService,
        preferencias = preferencias,
        savedStateHandle = handle,
        clock = clock,
        calculoDispatcher = dispatcher,
        relojSemana = relojSemana
    )

    private fun entidad(
        id: String,
        semana: String,
        dia: DiaSemana = DiaSemana.LUNES,
        hecho: Boolean = true,
        cuota: Double = 20.0,
        timestamp: Long = 0
    ) = SocioEntity(id, cuota, hecho, dia.id, semana, "", "", timestamp)

    /** Mantiene suscritos los StateFlow (WhileSubscribed) y recoge los eventos emitidos. */
    private fun TestScope.observar(viewModel: MainViewModel): MutableList<MainEvent> {
        val eventos = mutableListOf<MainEvent>()
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }
        backgroundScope.launch(dispatcher) { viewModel.historicoGlobal.collect {} }
        backgroundScope.launch(dispatcher) { viewModel.avisoPendientes.collect {} }
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
    fun laSemanaActualPasaSolaALaSiguienteSinMoverLaElegida() = runTest(dispatcher) {
        val reloj = MutableStateFlow(semanaActual)
        val viewModel = crearViewModel(relojSemana = reloj)
        observar(viewModel)

        // Llega el lunes con la app abierta: se pasa a la semana nueva.
        reloj.value = semanaSiguiente
        assertEquals(semanaSiguiente, viewModel.uiState.value.semanaKey)
        assertEquals(0, viewModel.uiState.value.weekOffset)

        // Una semana elegida a mano no se mueve, solo cambia su distancia a la actual.
        viewModel.cambiarSemana(-1)
        assertEquals(semanaActual, viewModel.uiState.value.semanaKey)
        reloj.value = DateUtils.semanaKeyDesplazada(semanaSiguiente, 1)!!
        assertEquals(semanaActual, viewModel.uiState.value.semanaKey)
        assertEquals(-2, viewModel.uiState.value.weekOffset)

        // "Hoy" vuelve a la actual aunque antes ya se hubiera estado en ella.
        viewModel.irASemanaActual()
        assertEquals(reloj.value, viewModel.uiState.value.semanaKey)
    }

    @Test
    fun exportarUnaSemanaSinSociosPeroConBonusManualSeDeja() = runTest(dispatcher) {
        bonoDao.saveBonoRegalo(BonoRegaloEntity(semanaActual, activo = true, esMontoFijo = true, monto = 50.0, nota = ""))
        val viewModel = crearViewModel()
        observar(viewModel)
        val request = ExportRequest(ExportFormat.PDF, AlcanceExport.Semanas(setOf(semanaActual)))

        viewModel.solicitarExport(request.formato, request.alcance)

        assertEquals(request, viewModel.exportPendienteDeDestino.value)
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
        assertEquals(MainEvent.Mensaje(UiText.Res(R.string.msg_socio_pendiente), TipoAviso.PENDIENTE), eventos.last())
    }

    @Test
    fun borrarUnSocioSePuedeDeshacerConSusMismosDatos() = runTest(dispatcher) {
        socioDao.socios.value = listOf(entidad("a", semanaActual, DiaSemana.JUEVES, hecho = false, cuota = 18.0))
        val viewModel = crearViewModel()
        val eventos = observar(viewModel)
        val socio = viewModel.uiState.value.todosSociosSemana.single()

        viewModel.eliminarSocio(socio)

        assertTrue(viewModel.uiState.value.todosSociosSemana.isEmpty())
        assertEquals(MainEvent.SocioEliminado(socio), eventos.last())

        viewModel.restaurarSocio((eventos.last() as MainEvent.SocioEliminado).socio)

        assertEquals(listOf(socio), viewModel.uiState.value.todosSociosSemana)
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
    fun alEliminarUnCambioDeIrpfSeVuelveAlPorcentajeAnterior() = runTest(dispatcher) {
        socioDao.socios.value = listOf(entidad("a", semanaActual))
        val viewModel = crearViewModel()
        backgroundScope.launch(dispatcher) { viewModel.irpfVigencias.collect {} }
        observar(viewModel)
        val semanaAnterior = DateUtils.getSemanaKey(-1, clock)

        viewModel.guardarIrpf(semanaAnterior, 15.0)
        viewModel.guardarIrpf(semanaActual, 19.0)
        assertEquals(19.0, viewModel.uiState.value.resumenSemana!!.porcentajeIrpf, 0.0)
        assertEquals(listOf(semanaAnterior, semanaActual), viewModel.irpfVigencias.value.map { it.desdeSemanaKey })

        viewModel.eliminarIrpf(semanaActual)

        assertEquals(15.0, viewModel.uiState.value.resumenSemana!!.porcentajeIrpf, 0.0)
        assertEquals(listOf(semanaAnterior), viewModel.irpfVigencias.value.map { it.desdeSemanaKey })
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

        viewModel.prepararImportacion(lectura, ImportFormat.JSON)

        assertEquals(ImportPreview(1, 1, 0, 0, 0, 2), viewModel.importPendiente.value)
        assertEquals(1, socioDao.socios.value.size)

        viewModel.confirmarImport()

        assertNull(viewModel.importPendiente.value)
        assertEquals(setOf("existente", "nuevo"), socioDao.socios.value.map { it.id }.toSet())
        assertEquals(30.0, socioDao.socios.value.first { it.id == "existente" }.colaboracion, 0.0)
        assertEquals(
            MainEvent.Mensaje(UiText.Plural(R.plurals.msg_import_resultado, 2, listOf(2, 0, 0, 0)), TipoAviso.EXITO, larga = true),
            eventos.last()
        )
    }

    @Test
    fun loCobradoPasaASerElNetoDeLaSemanaYDiceCuantoSeHaDescontado() = runTest(dispatcher) {
        socioDao.socios.value = List(10) { entidad("s$it", semanaActual) }
        val viewModel = crearViewModel()
        val eventos = observar(viewModel)
        assertEquals(424.01, viewModel.uiState.value.resumenSemana!!.totalNeto, 0.0)

        viewModel.guardarCobro(semanaActual, 408.70, "  2.ª cuota de Ana ")

        val resumen = viewModel.uiState.value.resumenSemana!!
        assertEquals(408.70, resumen.totalNeto, 0.0)
        assertEquals(15.31, resumen.descontado, 0.0)
        assertEquals("2.ª cuota de Ana", viewModel.uiState.value.cobroSemana?.nota)
        assertEquals(15.31, viewModel.historicoGlobal.value!!.totalDescontadoAcumulado, 0.0)
        assertEquals(
            MainEvent.Mensaje(UiText.Res(R.string.msg_cobro_guardado, listOf("408,70 €")), TipoAviso.EXITO),
            eventos.last()
        )

        // Quitarlo vuelve al neto calculado.
        viewModel.eliminarCobro(semanaActual)

        assertNull(viewModel.uiState.value.cobroSemana)
        assertEquals(424.01, viewModel.uiState.value.resumenSemana!!.totalNeto, 0.0)
        assertEquals(MainEvent.Mensaje(UiText.Res(R.string.msg_cobro_eliminado), TipoAviso.INFO), eventos.last())
    }

    @Test
    fun unaSemanaConSoloLoCobradoSePuedeExportar() = runTest(dispatcher) {
        val viewModel = crearViewModel()
        observar(viewModel)
        viewModel.guardarCobro(semanaActual, 12.0, "")

        viewModel.solicitarExport(ExportFormat.JSON, AlcanceExport.Semanas(setOf(semanaActual)))

        assertEquals(ExportRequest(ExportFormat.JSON, AlcanceExport.Semanas(setOf(semanaActual))), viewModel.exportPendienteDeDestino.value)
    }

    @Test
    fun avisaDeLosPendientesDeSemanasAnterioresHastaQueSeCierraElAviso() = runTest(dispatcher) {
        val semanaAnterior = DateUtils.getSemanaKey(-1, clock)
        val haceDos = DateUtils.getSemanaKey(-2, clock)
        socioDao.socios.value = listOf(
            entidad("viejo", haceDos, hecho = false),
            entidad("anterior", semanaAnterior, hecho = false),
            entidad("hecho", semanaAnterior),
            entidad("actual", semanaActual, hecho = false)
        )
        val viewModel = crearViewModel()
        observar(viewModel)

        assertEquals(listOf("anterior", "viejo"), viewModel.avisoPendientes.value.map { it.id })

        viewModel.ocultarAvisoPendientes()
        assertTrue(viewModel.avisoPendientes.value.isEmpty())
        // Siguen ahí para revisarlos aunque el aviso esté cerrado.
        assertEquals(2, viewModel.pendientesAnteriores.value.size)

        // Al conseguir uno no vuelve el aviso; si otro pasa a pendiente, sí.
        viewModel.setSocioHecho("viejo", true)
        assertTrue(viewModel.avisoPendientes.value.isEmpty())
        viewModel.setSocioHecho("hecho", false)
        assertEquals(listOf("anterior", "hecho"), viewModel.avisoPendientes.value.map { it.id }.sorted())
    }

    @Test
    fun elFormularioProponeLaCuotaDelUltimoSocioAnadido() = runTest(dispatcher) {
        val viewModel = crearViewModel()
        observar(viewModel)
        assertEquals(20.0, viewModel.cuotaPorDefecto, 0.0)

        viewModel.guardarSocio(Socio(colaboracion = 15.0, dia = DiaSemana.LUNES, semanaKey = semanaActual), esNuevo = true)

        assertEquals(15.0, viewModel.cuotaPorDefecto, 0.0)
    }

    @Test
    fun moverUnSocioDeSemanaLoSacaDeLaVisibleYAvisa() = runTest(dispatcher) {
        socioDao.socios.value = listOf(entidad("a", semanaActual))
        val viewModel = crearViewModel()
        val eventos = observar(viewModel)
        val socio = viewModel.uiState.value.todosSociosSemana.single()

        viewModel.guardarSocio(socio.copy(semanaKey = semanaSiguiente), esNuevo = false, semanaOriginal = semanaActual)

        assertTrue(viewModel.uiState.value.todosSociosSemana.isEmpty())
        assertEquals(semanaSiguiente, socioDao.socios.value.single().semanaKey)
        assertEquals(
            MainEvent.Mensaje(UiText.Res(R.string.msg_socio_movido, listOf(DateUtils.rangoCorto(semanaSiguiente))), TipoAviso.EXITO),
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

        viewModel.prepararImportacion(lectura, ImportFormat.CSV)
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
        assertEquals(MainEvent.Mensaje(UiText.Res(R.string.msg_export_vacio), TipoAviso.INFO, larga = true), eventos.last())
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
