package com.julen.socios

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.julen.socios.actualizaciones.ComprobadorActualizaciones
import com.julen.socios.actualizaciones.VersionPublicada
import com.julen.socios.util.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** Aviso de versiones nuevas: al abrir la app (como mucho una vez al día) y cuando el usuario lo pide. */
class ActualizacionesViewModel(private val comprobador: ComprobadorActualizaciones) : ViewModel() {

    sealed interface Evento {
        data class NuevaVersion(val publicada: VersionPublicada, val instalada: String) : Evento
        data class Mensaje(val texto: UiText) : Evento
    }

    private val eventos = Channel<Evento>(Channel.BUFFERED)
    val events: Flow<Evento> = eventos.receiveAsFlow()

    val versionInstalada: String get() = comprobador.versionInstalada

    private var busqueda: Job? = null

    init {
        viewModelScope.launch {
            comprobador.comprobarSiToca()?.let { eventos.send(Evento.NuevaVersion(it, versionInstalada)) }
        }
    }

    fun buscarActualizaciones() {
        if (busqueda?.isActive == true) return
        busqueda = viewModelScope.launch {
            val evento = try {
                val resultado = comprobador.comprobar()
                when {
                    resultado.hayNueva -> Evento.NuevaVersion(resultado.publicada, versionInstalada)
                    comprobador.instalada != null ->
                        Evento.Mensaje(UiText.Res(R.string.actualizacion_al_dia, listOf(versionInstalada)))
                    else -> Evento.Mensaje(
                        UiText.Res(R.string.actualizacion_ultima_publicada, listOf(resultado.publicada.version.toString()))
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Evento.Mensaje(UiText.Res(R.string.actualizacion_error))
            }
            eventos.send(evento)
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as SociosApp
                ActualizacionesViewModel(app.container.comprobadorActualizaciones)
            }
        }
    }
}
