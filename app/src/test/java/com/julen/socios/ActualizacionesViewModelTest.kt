package com.julen.socios

import com.julen.socios.actualizaciones.ComprobadorActualizaciones
import com.julen.socios.actualizaciones.FuenteVersiones
import com.julen.socios.actualizaciones.RegistroComprobaciones
import com.julen.socios.actualizaciones.Version
import com.julen.socios.actualizaciones.VersionPublicada
import com.julen.socios.util.UiText
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ActualizacionesViewModelTest {

    private val clock = Clock.fixed(Instant.parse("2026-10-11T12:00:00Z"), ZoneOffset.UTC)
    private var publicada = "1.4.0"
    private var sinConexion = false
    private val fuente = FuenteVersiones {
        if (sinConexion) throw IOException("sin conexión")
        VersionPublicada(Version.parse(publicada)!!, "https://github.com/Julenn06/SOCIOS/releases/tag/v$publicada")
    }
    private val registro = object : RegistroComprobaciones {
        override var ultimaComprobacion = 0L
    }

    private fun viewModel(instalada: String) =
        ActualizacionesViewModel(ComprobadorActualizaciones(fuente, registro, instalada, clock))

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun alAbrirLaAppAvisaDeLaVersionNueva() = runTest {
        val evento = viewModel("1.3.0").events.first()

        evento as ActualizacionesViewModel.Evento.NuevaVersion
        assertEquals("1.4.0", evento.publicada.version.toString())
        assertEquals("1.3.0", evento.instalada)
    }

    @Test
    fun laBusquedaManualDiceSiYaEstaAlDia() = runTest {
        publicada = "1.3.0"
        val vm = viewModel("1.3.0")
        vm.buscarActualizaciones()

        assertEquals(
            ActualizacionesViewModel.Evento.Mensaje(UiText.Res(R.string.actualizacion_al_dia, listOf("1.3.0"))),
            vm.events.first()
        )
    }

    @Test
    fun enUnaBuildDeDesarrolloMuestraLaUltimaPublicada() = runTest {
        val vm = viewModel("dev-12")
        vm.buscarActualizaciones()

        assertEquals(
            ActualizacionesViewModel.Evento.Mensaje(UiText.Res(R.string.actualizacion_ultima_publicada, listOf("1.4.0"))),
            vm.events.first()
        )
    }

    @Test
    fun sinConexionLaBusquedaManualAvisaDelError() = runTest {
        sinConexion = true
        val vm = viewModel("1.3.0")
        vm.buscarActualizaciones()

        assertEquals(
            ActualizacionesViewModel.Evento.Mensaje(UiText.Res(R.string.actualizacion_error)),
            vm.events.first()
        )
    }
}
