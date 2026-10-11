package com.julen.socios

import android.net.Uri
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.julen.socios.data.BackupRepository
import com.julen.socios.data.BonoRegaloRepository
import com.julen.socios.data.IrpfRepository
import com.julen.socios.data.SocioRepository
import com.julen.socios.export.AlcanceExport
import com.julen.socios.export.ExportFormat
import com.julen.socios.export.ExportRequest
import com.julen.socios.export.ExportService
import com.julen.socios.export.ImportFormat
import com.julen.socios.model.BonoRegaloInfo
import com.julen.socios.model.DiaSemana
import com.julen.socios.model.ImportPreview
import com.julen.socios.model.ResultadoLectura
import com.julen.socios.model.Socio
import com.julen.socios.util.CalculoComisiones
import com.julen.socios.util.DateUtils
import com.julen.socios.util.UiText
import com.julen.socios.util.toPorcentajeString
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MainUiState(
    val weekOffset: Int,
    val semanaKey: String,
    val rangoTexto: String,
    val diaFiltro: DiaSemana? = null,
    val todosSociosSemana: List<Socio> = emptyList(),
    val sociosFiltrados: List<Socio> = emptyList(),
    val conteoPorDia: Map<DiaSemana, Int> = emptyMap(),
    val bonoInfoSemana: BonoRegaloInfo? = null,
    val resumenSemana: CalculoComisiones.ResumenCalculo? = null
)

/** Acciones puntuales que la Activity debe ejecutar una sola vez. */
sealed interface MainEvent {
    data class Mensaje(val texto: UiText, val larga: Boolean = false) : MainEvent
    data class CrearDocumento(val formato: ExportFormat, val nombreSugerido: String) : MainEvent
    data class Compartir(val uri: Uri, val formato: ExportFormat) : MainEvent {
        val mimeType: String get() = formato.mimeType
    }
    data class SeleccionarArchivoImport(val formato: ImportFormat) : MainEvent

    /** Se ha borrado un socio: la vista ofrece deshacerlo con [MainViewModel.restaurarSocio]. */
    data class SocioEliminado(val socio: Socio) : MainEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(
    private val socioRepository: SocioRepository,
    private val bonoRegaloRepository: BonoRegaloRepository,
    private val irpfRepository: IrpfRepository,
    private val backupRepository: BackupRepository,
    private val exportService: ExportService,
    private val savedStateHandle: SavedStateHandle,
    private val clock: Clock = Clock.systemDefaultZone(),
    calculoDispatcher: CoroutineDispatcher = Dispatchers.Default
) : ViewModel() {

    // Semana y filtro sobreviven a la recreación de la Activity y a la muerte del proceso.
    private val weekOffset = savedStateHandle.getStateFlow(KEY_WEEK_OFFSET, 0)
    private val diaFiltroId = savedStateHandle.getStateFlow<Int?>(KEY_DIA_FILTRO, null)

    private val eventos = Channel<MainEvent>(Channel.BUFFERED)

    /** Eventos de un solo uso. A diferencia de un SharedFlow sin buffer, no se pierden si la UI está en segundo plano. */
    val events: Flow<MainEvent> = eventos.receiveAsFlow()

    private data class DatosSemana(
        val offset: Int,
        val semanaKey: String,
        val socios: List<Socio>,
        val bono: BonoRegaloInfo?,
        val porcentajeIrpf: Double
    )

    // Un único flatMapLatest: la clave, los socios y el bono de la semana llegan siempre juntos, así la
    // UI nunca muestra las fechas de una semana con los datos de la anterior.
    private val datosSemana: Flow<DatosSemana> = weekOffset.flatMapLatest { offset ->
        val semanaKey = DateUtils.getSemanaKey(offset, clock)
        combine(
            socioRepository.getSociosPorSemanaFlow(semanaKey),
            bonoRegaloRepository.getBonoRegaloFlow(semanaKey),
            irpfRepository.getVigenciasFlow()
        ) { socios, bono, vigencias ->
            DatosSemana(offset, semanaKey, socios, bono, CalculoComisiones.irpfParaSemana(semanaKey, vigencias))
        }
    }

    val uiState: StateFlow<MainUiState> = combine(datosSemana, diaFiltroId) { datos, diaId ->
        val dia = diaId?.let(DiaSemana::fromIdOrNull)
        MainUiState(
            weekOffset = datos.offset,
            semanaKey = datos.semanaKey,
            rangoTexto = DateUtils.getRangoSemanaTexto(datos.offset, clock),
            diaFiltro = dia,
            todosSociosSemana = datos.socios,
            sociosFiltrados = if (dia == null) datos.socios else datos.socios.filter { it.dia == dia },
            conteoPorDia = datos.socios.groupingBy { it.dia }.eachCount(),
            bonoInfoSemana = datos.bono,
            resumenSemana = CalculoComisiones.calcularResumenSemana(datos.socios, datos.bono, datos.porcentajeIrpf)
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = MainUiState(
            weekOffset = weekOffset.value,
            semanaKey = DateUtils.getSemanaKey(weekOffset.value, clock),
            rangoTexto = DateUtils.getRangoSemanaTexto(weekOffset.value, clock)
        )
    )

    val historicoGlobal: StateFlow<CalculoComisiones.ResumenHistoricoGlobal?> = combine(
        socioRepository.getAllSociosFlow(),
        bonoRegaloRepository.getAllBonosRegaloFlow(),
        irpfRepository.getVigenciasFlow()
    ) { socios, bonos, vigencias ->
        CalculoComisiones.calcularResumenHistoricoGlobal(socios, bonos, vigencias)
    }.flowOn(calculoDispatcher)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Semanas con socios o con un bono manual, de la más reciente a la más antigua. */
    val semanasDisponibles: StateFlow<List<String>> = combine(
        socioRepository.getSemanaKeysFlow(),
        bonoRegaloRepository.getSemanaKeysFlow()
    ) { deSocios, deBonos ->
        (deSocios + deBonos).distinct().sortedDescending()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Exportación preparada a la espera de que el usuario elija entre compartir o guardar. */
    val exportPendienteDeDestino: StateFlow<ExportRequest?> =
        savedStateHandle.getStateFlow<String?>(KEY_EXPORT_DESTINO + SUFIJO_FORMATO, null)
            .map { leerExportRequest(KEY_EXPORT_DESTINO) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, leerExportRequest(KEY_EXPORT_DESTINO))

    private var lecturaPendiente: ResultadoLectura? = null
    private val _importPendiente = MutableStateFlow<ImportPreview?>(null)

    /** Importación leída y validada, a la espera de que el usuario la confirme. */
    val importPendiente: StateFlow<ImportPreview?> = _importPendiente.asStateFlow()

    // --- Navegación y filtros ---

    fun cambiarSemana(delta: Int) = irAOffset(weekOffset.value + delta)

    fun irASemanaActual() = irAOffset(0)

    fun irASemana(semanaKey: String) {
        DateUtils.getWeekOffsetFromKey(semanaKey, clock)?.let(::irAOffset)
    }

    private fun irAOffset(offset: Int) {
        savedStateHandle[KEY_DIA_FILTRO] = null
        savedStateHandle[KEY_WEEK_OFFSET] = offset
    }

    fun setDiaFiltro(dia: DiaSemana?) {
        savedStateHandle[KEY_DIA_FILTRO] = dia?.id
    }

    // --- Socios, bonus e IRPF ---

    fun setSocioHecho(socioId: String, hecho: Boolean) = lanzar {
        if (socioRepository.setHecho(socioId, hecho)) {
            mensaje(if (hecho) R.string.msg_socio_hecho else R.string.msg_socio_pendiente)
        }
    }

    fun guardarSocio(socio: Socio, esNuevo: Boolean) = lanzar {
        if (esNuevo) {
            socioRepository.addSocio(socio)
            mensaje(R.string.msg_socio_saved)
        } else {
            socioRepository.updateSocio(socio)
            mensaje(R.string.msg_socio_updated)
        }
    }

    fun eliminarSocio(socio: Socio) = lanzar {
        socioRepository.deleteSocio(socio.id)
        enviar(MainEvent.SocioEliminado(socio))
    }

    /** Vuelve a guardar un socio recién borrado, con el mismo id y fecha de alta. */
    fun restaurarSocio(socio: Socio) = lanzar {
        socioRepository.addSocio(socio)
        mensaje(R.string.msg_socio_restaurado)
    }

    fun guardarBono(bono: BonoRegaloInfo) = lanzar {
        bonoRegaloRepository.saveBonoRegalo(bono)
        mensaje(R.string.msg_bonus_updated)
    }

    fun eliminarBono(semanaKey: String) = lanzar {
        bonoRegaloRepository.deleteBonoRegalo(semanaKey)
        mensaje(R.string.msg_bonus_reset)
    }

    fun guardarIrpf(desdeSemanaKey: String, porcentaje: Double) = lanzar {
        irpfRepository.guardarVigencia(desdeSemanaKey, porcentaje)
        mensaje(R.string.msg_irpf_actualizado, porcentaje.toPorcentajeString(), desdeSemanaKey)
    }

    // --- Exportación ---

    fun solicitarExport(formato: ExportFormat, alcance: AlcanceExport) = lanzar(R.string.msg_error_export) {
        if (backupRepository.cargarDatos(alcance).socios.isEmpty()) {
            mensajeLargo(R.string.msg_export_vacio)
        } else {
            guardarExportRequest(KEY_EXPORT_DESTINO, ExportRequest(formato, alcance))
        }
    }

    fun cancelarExport() = guardarExportRequest(KEY_EXPORT_DESTINO, null)

    fun exportarCompartiendo() {
        val request = tomarExportRequest(KEY_EXPORT_DESTINO) ?: return
        lanzar(R.string.msg_error_export) {
            val uri = exportService.crearArchivoCompartible(request, backupRepository.cargarDatos(request.alcance))
            enviar(MainEvent.Compartir(uri, request.formato))
        }
    }

    fun exportarGuardando() {
        val request = tomarExportRequest(KEY_EXPORT_DESTINO) ?: return
        // Se guarda en el SavedStateHandle: el selector de archivos es otra app y la nuestra puede
        // recrearse (o morir) mientras está abierto.
        guardarExportRequest(KEY_EXPORT_GUARDAR, request)
        enviar(MainEvent.CrearDocumento(request.formato, exportService.nombreArchivo(request)))
    }

    fun onDocumentoCreado(uri: Uri?) {
        val request = tomarExportRequest(KEY_EXPORT_GUARDAR) ?: return
        if (uri == null) return
        lanzar(R.string.msg_error_file_save) {
            exportService.escribirEnUri(request, backupRepository.cargarDatos(request.alcance), uri)
            mensajeLargo(R.string.msg_file_saved)
        }
    }

    // --- Importación ---

    fun solicitarImport(formato: ImportFormat) = enviar(MainEvent.SeleccionarArchivoImport(formato))

    fun onArchivoImportSeleccionado(uri: Uri?, formato: ImportFormat) {
        if (uri == null) return
        lanzar(R.string.msg_error_import) {
            prepararImportacion(exportService.leerImportacion(uri, formato), formato)
        }
    }

    /** Valida lo leído y deja la importación pendiente de confirmar por el usuario. */
    @VisibleForTesting
    internal suspend fun prepararImportacion(lectura: ResultadoLectura, formato: ImportFormat) {
        if (lectura.datos.estaVacio) {
            mensajeLargo(R.string.msg_import_vacio, formato.name)
            return
        }
        lecturaPendiente = lectura
        _importPendiente.value = backupRepository.previsualizar(lectura)
    }

    fun confirmarImport() {
        val datos = lecturaPendiente?.datos ?: return
        cancelarImport()
        lanzar(R.string.msg_error_import) {
            backupRepository.importar(datos)
            enviar(
                MainEvent.Mensaje(
                    UiText.Plural(
                        R.plurals.msg_import_resultado,
                        datos.socios.size,
                        listOf(datos.socios.size, datos.bonosRegalo.size, datos.irpfVigencias.size)
                    ),
                    larga = true
                )
            )
        }
    }

    fun cancelarImport() {
        lecturaPendiente = null
        _importPendiente.value = null
    }

    // --- Utilidades ---

    private fun guardarExportRequest(prefijo: String, request: ExportRequest?) {
        // El formato es la clave que observa exportPendienteDeDestino: se vacía primero y se escribe
        // al final para que cualquier cambio de la petición se emita aunque el formato se repita.
        savedStateHandle[prefijo + SUFIJO_FORMATO] = null
        savedStateHandle[prefijo + SUFIJO_SEMANAS] =
            (request?.alcance as? AlcanceExport.Semanas)?.semanaKeys?.let { ArrayList(it) }
        savedStateHandle[prefijo + SUFIJO_FORMATO] = request?.formato?.name
    }

    private fun leerExportRequest(prefijo: String): ExportRequest? {
        val formato = savedStateHandle.get<String>(prefijo + SUFIJO_FORMATO)
            ?.let { nombre -> ExportFormat.entries.find { it.name == nombre } }
            ?: return null
        val semanas = savedStateHandle.get<ArrayList<String>>(prefijo + SUFIJO_SEMANAS)
        val alcance = if (semanas != null) AlcanceExport.Semanas(semanas.toSet()) else AlcanceExport.Historico
        return ExportRequest(formato, alcance)
    }

    private fun tomarExportRequest(prefijo: String): ExportRequest? =
        leerExportRequest(prefijo).also { guardarExportRequest(prefijo, null) }

    private fun enviar(evento: MainEvent) {
        eventos.trySend(evento)
    }

    private fun mensaje(@StringRes id: Int, vararg args: Any) =
        enviar(MainEvent.Mensaje(UiText.Res(id, args.toList())))

    private fun mensajeLargo(@StringRes id: Int, vararg args: Any) =
        enviar(MainEvent.Mensaje(UiText.Res(id, args.toList()), larga = true))

    /** Ejecuta una operación y convierte cualquier error en un mensaje, en lugar de cerrar la app. */
    private fun lanzar(@StringRes errorRes: Int = R.string.msg_error_generico, bloque: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                bloque()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mensajeLargo(errorRes, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    companion object {
        private const val KEY_WEEK_OFFSET = "weekOffset"
        private const val KEY_DIA_FILTRO = "diaFiltro"
        private const val KEY_EXPORT_DESTINO = "exportDestino"
        private const val KEY_EXPORT_GUARDAR = "exportGuardar"
        private const val SUFIJO_FORMATO = ".formato"
        private const val SUFIJO_SEMANAS = ".semanas"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as SociosApp
                val container = app.container
                MainViewModel(
                    socioRepository = container.socioRepository,
                    bonoRegaloRepository = container.bonoRegaloRepository,
                    irpfRepository = container.irpfRepository,
                    backupRepository = container.backupRepository,
                    exportService = container.exportService,
                    savedStateHandle = createSavedStateHandle(),
                    clock = container.clock
                )
            }
        }
    }
}
