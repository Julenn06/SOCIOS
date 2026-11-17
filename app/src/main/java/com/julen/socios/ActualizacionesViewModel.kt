package com.julen.socios

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.julen.socios.actualizaciones.ComprobadorActualizaciones
import com.julen.socios.actualizaciones.DescargaDanadaException
import com.julen.socios.actualizaciones.DescargadorActualizaciones
import com.julen.socios.actualizaciones.Progreso
import com.julen.socios.actualizaciones.Version
import com.julen.socios.actualizaciones.VersionPublicada
import com.julen.socios.util.UiText
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Versiones nuevas: aviso al abrir la app (como mucho una vez al día), búsqueda desde el panel "Más" y
 * descarga de su APK para instalarla.
 */
class ActualizacionesViewModel(
    private val comprobador: ComprobadorActualizaciones,
    private val descargador: DescargadorActualizaciones
) : ViewModel() {

    /** Si hay una versión nueva, como lo muestra el panel "Más". */
    sealed interface EstadoVersion {
        /** Aún no se ha buscado en esta sesión, o la búsqueda al abrir no tocaba o no encontró nada. */
        data object SinComprobar : EstadoVersion
        data object Buscando : EstadoVersion
        data object AlDia : EstadoVersion
        data class Disponible(val publicada: VersionPublicada) : EstadoVersion

        /** En las builds de desarrollo no se puede comparar: solo se dice cuál es la última publicada. */
        data class UltimaPublicada(val version: Version) : EstadoVersion
        data object Error : EstadoVersion
    }

    /** Descarga del APK de una versión nueva, que muestra [ActualizacionBottomSheetDialog]. */
    sealed interface Descarga {
        data object Inactiva : Descarga
        data class EnCurso(val publicada: VersionPublicada, val progreso: Progreso) : Descarga
        data class Completada(val publicada: VersionPublicada, val apk: File) : Descarga
        data class Fallida(val publicada: VersionPublicada, val motivo: UiText) : Descarga
    }

    private val avisos = Channel<VersionPublicada>(Channel.BUFFERED)

    /** Versión nueva encontrada al abrir la app, para avisar de ella una vez. */
    val nuevaVersion: Flow<VersionPublicada> = avisos.receiveAsFlow()

    val estadoVersion: StateFlow<EstadoVersion>
        field = MutableStateFlow<EstadoVersion>(EstadoVersion.SinComprobar)

    val versionInstalada: String get() = comprobador.versionInstalada

    /** Cuándo se consultó GitHub por última vez (0 si nunca). */
    val ultimaComprobacion: Long get() = comprobador.ultimaComprobacion

    val descarga: StateFlow<Descarga>
        field = MutableStateFlow<Descarga>(Descarga.Inactiva)

    private var trabajoDescarga: Job? = null

    init {
        viewModelScope.launch { descargador.limpiar() }
        viewModelScope.launch {
            comprobador.comprobarSiToca()?.let {
                estadoVersion.value = EstadoVersion.Disponible(it)
                avisos.send(it)
            }
        }
    }

    fun buscarActualizaciones() {
        if (estadoVersion.value == EstadoVersion.Buscando) return
        estadoVersion.value = EstadoVersion.Buscando
        viewModelScope.launch {
            estadoVersion.value = try {
                val resultado = comprobador.comprobar()
                when {
                    resultado.hayNueva -> EstadoVersion.Disponible(resultado.publicada)
                    comprobador.instalada != null -> EstadoVersion.AlDia
                    else -> EstadoVersion.UltimaPublicada(resultado.publicada.version)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                EstadoVersion.Error
            }
        }
    }

    /** Descarga el APK de [publicada], salvo que ya se esté descargando o esté descargado. */
    fun descargar(publicada: VersionPublicada) {
        val apk = publicada.apk ?: return
        when (val actual = descarga.value) {
            is Descarga.EnCurso -> return
            is Descarga.Completada -> if (actual.publicada.version == publicada.version && actual.apk.exists()) return
            else -> Unit
        }
        trabajoDescarga = viewModelScope.launch {
            val progreso = MutableStateFlow(Progreso(0, apk.tamano))
            descarga.value = Descarga.EnCurso(publicada, progreso.value)
            // El progreso llega desde el hilo de la descarga; se publica desde aquí para que, al cancelar,
            // no pueda llegar tarde y pisar el estado.
            val seguimiento = launch { progreso.collect { descarga.value = Descarga.EnCurso(publicada, it) } }
            val resultado = try {
                Descarga.Completada(publicada, descargador.descargar(apk, publicada.version) { progreso.value = it })
            } catch (e: CancellationException) {
                throw e
            } catch (e: DescargaDanadaException) {
                Descarga.Fallida(publicada, UiText.Res(R.string.descarga_danada))
            } catch (e: Exception) {
                Descarga.Fallida(publicada, UiText.Res(R.string.descarga_error))
            } finally {
                seguimiento.cancel()
            }
            descarga.value = resultado
        }
    }

    fun cancelarDescarga() {
        trabajoDescarga?.cancel()
        descarga.value = Descarga.Inactiva
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as SociosApp
                ActualizacionesViewModel(app.container.comprobadorActualizaciones, app.container.descargadorActualizaciones)
            }
        }
    }
}
