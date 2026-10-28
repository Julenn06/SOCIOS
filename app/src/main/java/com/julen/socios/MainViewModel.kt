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
import com.julen.socios.ajustes.PreferenciasUsuario
import com.julen.socios.data.BackupRepository
import com.julen.socios.data.BonoRegaloRepository
import com.julen.socios.data.CobroRepository
import com.julen.socios.data.IrpfRepository
import com.julen.socios.data.SocioRepository
import com.julen.socios.export.AlcanceExport
import com.julen.socios.export.ExportFormat
import com.julen.socios.export.ExportRequest
import com.julen.socios.export.ExportService
import com.julen.socios.export.ImportFormat
import com.julen.socios.model.BonoRegaloInfo
import com.julen.socios.model.CobroInfo
import com.julen.socios.model.DatosBackup
import com.julen.socios.model.DiaSemana
import com.julen.socios.model.ImportesPredefinidos
import com.julen.socios.model.ImportPreview
import com.julen.socios.model.IrpfVigencia
import com.julen.socios.model.ResultadoLectura
import com.julen.socios.model.Socio
import com.julen.socios.util.CalculoComisiones
import com.julen.socios.util.DateUtils
import com.julen.socios.util.UiText
import com.julen.socios.util.motivoDeError
import com.julen.socios.util.nombreVisible
import com.julen.socios.util.toCurrencyString
import com.julen.socios.util.toEurosCompacto
import com.julen.socios.util.toPorcentajeString
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MainUiState(
    val weekOffset: Int,
    val semanaKey: String,
    val diaFiltro: DiaSemana? = null,
    val todosSociosSemana: List<Socio> = emptyList(),
    val sociosFiltrados: List<Socio> = emptyList(),
    val conteoPorDia: Map<DiaSemana, Int> = emptyMap(),
    val bonoInfoSemana: BonoRegaloInfo? = null,
    val cobroSemana: CobroInfo? = null,
    val resumenSemana: CalculoComisiones.ResumenCalculo? = null
)

/** Acciones puntuales que la Activity debe ejecutar una sola vez. */
sealed interface MainEvent {
    /** Un aviso: [titulo] dice qué ha pasado y [detalle], si hay, a qué o por qué. */
    data class Mensaje(val titulo: UiText, val tipo: TipoAviso, val detalle: UiText? = null) : MainEvent
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
    private val cobroRepository: CobroRepository,
    private val backupRepository: BackupRepository,
    private val exportService: ExportService,
    private val preferencias: PreferenciasUsuario,
    private val savedStateHandle: SavedStateHandle,
    private val clock: Clock = Clock.systemDefaultZone(),
    calculoDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** Clave de la semana actual, que cambia sola cada lunes aunque la app siga abierta. */
    relojSemana: Flow<String> = semanaActualCadaMinuto(clock)
) : ViewModel() {

    // Semana y filtro sobreviven a la recreación de la Activity y a la muerte del proceso. Se guarda la
    // clave de la semana elegida (null = la actual) y no su distancia a hoy: si el proceso se restaura días
    // después, se sigue viendo la misma semana.
    private val semanaElegida = savedStateHandle.getStateFlow<String?>(KEY_SEMANA, null)
    private val diaFiltroId = savedStateHandle.getStateFlow<Int?>(KEY_DIA_FILTRO, null)

    // La navegación también la usa: sin nadie observando conserva el último valor, y al volver la app a
    // primer plano se actualiza antes de que el usuario pueda tocar nada.
    val semanaActual: StateFlow<String> =
        relojSemana.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DateUtils.getSemanaKey(0, clock))

    private data class SemanaVisible(val semanaKey: String, val offset: Int)

    private val semanaVisible: Flow<SemanaVisible> = combine(semanaElegida, semanaActual) { elegida, actual ->
        semanaVisibleDe(elegida, actual)
    }.distinctUntilChanged()

    private val eventos = Channel<MainEvent>(Channel.BUFFERED)

    /** Eventos de un solo uso. A diferencia de un SharedFlow sin buffer, no se pierden si la UI está en segundo plano. */
    val events: Flow<MainEvent> = eventos.receiveAsFlow()

    private data class DatosSemana(
        val offset: Int,
        val semanaKey: String,
        val socios: List<Socio>,
        val bono: BonoRegaloInfo?,
        val porcentajeIrpf: Double,
        val cobro: CobroInfo?
    )

    // Un único flatMapLatest: la clave, los socios y el bono de la semana llegan siempre juntos, así la
    // UI nunca muestra las fechas de una semana con los datos de la anterior.
    private val datosSemana: Flow<DatosSemana> = semanaVisible.flatMapLatest { (semanaKey, offset) ->
        combine(
            socioRepository.getSociosPorSemanaFlow(semanaKey),
            bonoRegaloRepository.getBonoRegaloFlow(semanaKey),
            irpfRepository.getVigenciasFlow(),
            cobroRepository.getCobroFlow(semanaKey)
        ) { socios, bono, vigencias, cobro ->
            DatosSemana(offset, semanaKey, socios, bono, CalculoComisiones.irpfParaSemana(semanaKey, vigencias), cobro)
        }
    }

    val uiState: StateFlow<MainUiState> = combine(datosSemana, diaFiltroId) { datos, diaId ->
        val dia = diaId?.let(DiaSemana::fromIdOrNull)
        MainUiState(
            weekOffset = datos.offset,
            semanaKey = datos.semanaKey,
            diaFiltro = dia,
            todosSociosSemana = datos.socios,
            sociosFiltrados = if (dia == null) datos.socios else datos.socios.filter { it.dia == dia },
            conteoPorDia = datos.socios.groupingBy { it.dia }.eachCount(),
            bonoInfoSemana = datos.bono,
            cobroSemana = datos.cobro,
            resumenSemana = CalculoComisiones.calcularResumenSemana(
                datos.socios, datos.bono, datos.porcentajeIrpf, datos.cobro
            )
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = semanaVisibleDe(semanaElegida.value, semanaActual.value).let {
            MainUiState(weekOffset = it.offset, semanaKey = it.semanaKey)
        }
    )

    val historicoGlobal: StateFlow<CalculoComisiones.ResumenHistoricoGlobal?> = combine(
        socioRepository.getAllSociosFlow(),
        bonoRegaloRepository.getAllBonosRegaloFlow(),
        irpfRepository.getVigenciasFlow(),
        cobroRepository.getAllFlow()
    ) { socios, bonos, vigencias, cobros ->
        CalculoComisiones.calcularResumenHistoricoGlobal(socios, bonos, vigencias, cobros)
    }.flowOn(calculoDispatcher)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Cambios de porcentaje de IRPF, del más antiguo al más reciente. */
    val irpfVigencias: StateFlow<List<IrpfVigencia>> = irpfRepository.getVigenciasFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Semanas con socios, con un bono manual o con lo cobrado apuntado, de la más reciente a la más antigua. */
    val semanasDisponibles: StateFlow<List<String>> = combine(
        socioRepository.getSemanaKeysFlow(),
        bonoRegaloRepository.getSemanaKeysFlow(),
        cobroRepository.getSemanaKeysFlow()
    ) { deSocios, deBonos, deCobros ->
        (deSocios + deBonos + deCobros).distinct().sortedDescending()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Todos los socios, del más reciente al más antiguo, para buscar en el histórico. */
    val todosLosSocios: StateFlow<List<Socio>> = socioRepository.getAllSociosFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Socios que siguen pendientes en semanas anteriores a la actual, de la más reciente a la más antigua. */
    val pendientesAnteriores: StateFlow<List<Socio>> = semanaActual
        .flatMapLatest { socioRepository.getPendientesAnterioresFlow(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Los pendientes anteriores mientras haya alguno que el usuario no haya descartado; vacío si ya cerró el
     * aviso con estos mismos. Un pendiente nuevo (o que vuelve a estar pendiente) hace que salga otra vez.
     */
    val avisoPendientes: StateFlow<List<Socio>> =
        combine(pendientesAnteriores, preferencias.pendientesOcultos) { pendientes, ocultos ->
            pendientes.takeIf { lista -> lista.any { it.id !in ocultos } }.orEmpty()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Cuota con la que empieza el formulario de un socio nuevo: la del último que se añadió. */
    val cuotaPorDefecto: Double
        get() = preferencias.ultimaCuota ?: ImportesPredefinidos.CUOTA_POR_DEFECTO

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

    fun cambiarSemana(delta: Int) {
        val visible = semanaElegida.value ?: semanaActual.value
        DateUtils.semanaKeyDesplazada(visible, delta)?.let(::irASemana)
    }

    fun irASemanaActual() = elegirSemana(null)

    fun irASemana(semanaKey: String) {
        if (!DateUtils.esSemanaKeyValida(semanaKey)) return
        // Volver a la semana actual la deja de fijar: así pasa sola a la siguiente cuando llegue el lunes.
        elegirSemana(semanaKey.takeIf { it != semanaActual.value })
    }

    private fun elegirSemana(semanaKey: String?) {
        savedStateHandle[KEY_DIA_FILTRO] = null
        savedStateHandle[KEY_SEMANA] = semanaKey
    }

    fun setDiaFiltro(dia: DiaSemana?) {
        savedStateHandle[KEY_DIA_FILTRO] = dia?.id
    }

    // --- Socios, bonus e IRPF ---

    fun setSocioHecho(socioId: String, hecho: Boolean) = lanzar {
        if (socioRepository.setHecho(socioId, hecho)) {
            if (hecho) avisar(R.string.msg_socio_hecho, TipoAviso.EXITO) else avisar(R.string.msg_socio_pendiente, TipoAviso.PENDIENTE)
        }
    }

    /** [semanaOriginal]: la semana en la que estaba el socio editado, para avisar si se ha movido a otra. */
    fun guardarSocio(socio: Socio, esNuevo: Boolean, semanaOriginal: String? = null) = lanzar {
        when {
            esNuevo -> {
                socioRepository.addSocio(socio)
                preferencias.ultimaCuota = socio.colaboracion
                avisar(R.string.msg_socio_saved, TipoAviso.EXITO, UiText.Res(R.string.socio_dia_semana, listOf(socio.nombreVisible(), socio.dia.nombreCompleto)))
            }

            semanaOriginal != null && semanaOriginal != socio.semanaKey -> {
                socioRepository.updateSocio(socio)
                avisar(
                    R.string.msg_socio_movido, TipoAviso.EXITO,
                    UiText.Res(R.string.msg_socio_movido_detalle, listOf(socio.nombreVisible(), DateUtils.rangoCorto(socio.semanaKey)))
                )
            }

            else -> {
                socioRepository.updateSocio(socio)
                avisar(R.string.msg_socio_updated, TipoAviso.EXITO, socio.nombreVisible())
            }
        }
    }

    /** Cierra el aviso de pendientes de semanas pasadas hasta que aparezca uno nuevo. */
    fun ocultarAvisoPendientes() = preferencias.ocultarPendientes(pendientesAnteriores.value.map { it.id }.toSet())

    fun eliminarSocio(socio: Socio) = lanzar {
        socioRepository.deleteSocio(socio.id)
        enviar(MainEvent.SocioEliminado(socio))
    }

    /** Vuelve a guardar un socio recién borrado, con el mismo id y fecha de alta. */
    fun restaurarSocio(socio: Socio) = lanzar {
        socioRepository.addSocio(socio)
        avisar(R.string.msg_socio_restaurado, TipoAviso.EXITO, socio.nombreVisible())
    }

    fun guardarBono(bono: BonoRegaloInfo) = lanzar {
        bonoRegaloRepository.saveBonoRegalo(bono)
        val detalle = if (bono.esMontoFijo) R.string.msg_bonus_detalle_fijo else R.string.msg_bonus_detalle_suma
        avisar(R.string.msg_bonus_updated, TipoAviso.EXITO, UiText.Res(detalle, listOf(bono.monto.toEurosCompacto())))
    }

    fun eliminarBono(semanaKey: String) = lanzar {
        bonoRegaloRepository.deleteBonoRegalo(semanaKey)
        avisar(R.string.msg_bonus_reset, TipoAviso.INFO, UiText.Res(R.string.msg_bonus_reset_detalle))
    }

    /** Lo cobrado de verdad en la semana: pasa a ser su neto, y la diferencia con el calculado, lo descontado. */
    fun guardarCobro(semanaKey: String, netoCobrado: Double, nota: String) = lanzar {
        val importe = CalculoComisiones.redondearCentimos(netoCobrado)
        cobroRepository.guardar(CobroInfo(semanaKey, importe, nota.trim()))
        avisar(R.string.msg_cobro_guardado, TipoAviso.EXITO, UiText.Res(R.string.msg_cobro_guardado_detalle, listOf(importe.toCurrencyString())))
    }

    /** La semana vuelve a usar el neto calculado. */
    fun eliminarCobro(semanaKey: String) = lanzar {
        cobroRepository.eliminar(semanaKey)
        avisar(R.string.msg_cobro_eliminado, TipoAviso.INFO, UiText.Res(R.string.msg_cobro_eliminado_detalle))
    }

    fun guardarIrpf(desdeSemanaKey: String, porcentaje: Double) = lanzar {
        irpfRepository.guardarVigencia(desdeSemanaKey, porcentaje)
        avisar(
            R.string.msg_irpf_actualizado, TipoAviso.EXITO,
            UiText.Res(R.string.msg_irpf_actualizado_detalle, listOf(porcentaje.toPorcentajeString(), DateUtils.rangoCorto(desdeSemanaKey)))
        )
    }

    fun eliminarIrpf(desdeSemanaKey: String) = lanzar {
        irpfRepository.eliminarVigencia(desdeSemanaKey)
        avisar(R.string.msg_irpf_cambio_eliminado, TipoAviso.INFO)
    }

    // --- Exportación ---

    fun solicitarExport(formato: ExportFormat, alcance: AlcanceExport) = lanzar(R.string.msg_error_export) {
        val datos = backupRepository.cargarDatos(alcance)
        // Una semana sin socios pero con el bonus ajustado a mano también se puede exportar.
        if (datos.socios.isEmpty() && datos.bonosRegalo.isEmpty() && datos.cobros.isEmpty()) {
            avisar(R.string.msg_export_vacio, TipoAviso.INFO, UiText.Res(R.string.msg_export_vacio_detalle))
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
            avisar(R.string.msg_file_saved, TipoAviso.EXITO, UiText.Res(R.string.msg_file_saved_detalle, listOf(request.formato.name)))
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
            avisar(R.string.msg_import_vacio, TipoAviso.ERROR, UiText.Res(R.string.msg_import_vacio_detalle, listOf(formato.name)))
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
            avisar(R.string.msg_import_hecho, TipoAviso.EXITO, resumenImportado(datos))
        }
    }

    fun cancelarImport() {
        lecturaPendiente = null
        _importPendiente.value = null
    }

    // --- Utilidades ---

    private fun semanaVisibleDe(elegida: String?, actual: String): SemanaVisible {
        val semanaKey = elegida ?: actual
        return SemanaVisible(semanaKey, DateUtils.semanasEntre(actual, semanaKey) ?: 0)
    }

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

    private fun avisar(@StringRes titulo: Int, tipo: TipoAviso, detalle: UiText? = null) =
        enviar(MainEvent.Mensaje(UiText.Res(titulo), tipo, detalle))

    /** "12 socios, 2 bonus y 3 semanas con lo cobrado", sin lo que no venía en la copia. */
    private fun resumenImportado(datos: DatosBackup): UiText = UiText.Lista(
        listOf(
            R.plurals.msg_import_socios to datos.socios.size,
            R.plurals.msg_import_bonus to datos.bonosRegalo.size,
            R.plurals.msg_import_irpf to datos.irpfVigencias.size,
            R.plurals.msg_import_cobros to datos.cobros.size
        ).filter { (_, cantidad) -> cantidad > 0 }
            .map { (plural, cantidad) -> UiText.Plural(plural, cantidad, listOf(cantidad)) }
    )

    /** Ejecuta una operación y convierte cualquier error en un mensaje, en lugar de cerrar la app. */
    private fun lanzar(@StringRes errorRes: Int = R.string.msg_error_generico, bloque: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                bloque()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                enviar(MainEvent.Mensaje(UiText.Res(errorRes), TipoAviso.ERROR, motivoDeError(e)))
            }
        }
    }

    companion object {
        private const val KEY_SEMANA = "semanaElegida"
        private const val KEY_DIA_FILTRO = "diaFiltro"
        private const val KEY_EXPORT_DESTINO = "exportDestino"
        private const val KEY_EXPORT_GUARDAR = "exportGuardar"
        private const val SUFIJO_FORMATO = ".formato"
        private const val SUFIJO_SEMANAS = ".semanas"
        private const val INTERVALO_SEMANA_ACTUAL_MS = 60_000L

        /**
         * Semana actual, comprobada cada minuto mientras alguien la observa. Al pasar a segundo plano se deja
         * de observar, y al volver se calcula de nuevo, así que tampoco se queda atrás tras días sin abrir la app.
         */
        private fun semanaActualCadaMinuto(clock: Clock): Flow<String> = flow {
            while (true) {
                emit(DateUtils.getSemanaKey(0, clock))
                delay(INTERVALO_SEMANA_ACTUAL_MS)
            }
        }.distinctUntilChanged()

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as SociosApp
                val container = app.container
                MainViewModel(
                    socioRepository = container.socioRepository,
                    bonoRegaloRepository = container.bonoRegaloRepository,
                    irpfRepository = container.irpfRepository,
                    cobroRepository = container.cobroRepository,
                    backupRepository = container.backupRepository,
                    exportService = container.exportService,
                    preferencias = container.preferenciasUsuario,
                    savedStateHandle = createSavedStateHandle(),
                    clock = container.clock
                )
            }
        }
    }
}
