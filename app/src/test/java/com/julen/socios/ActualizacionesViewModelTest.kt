package com.julen.socios

import com.julen.socios.ActualizacionesViewModel.Descarga
import com.julen.socios.ActualizacionesViewModel.EstadoVersion
import com.julen.socios.actualizaciones.ApkPublicado
import com.julen.socios.actualizaciones.ComprobadorActualizaciones
import com.julen.socios.actualizaciones.DescargaDanadaException
import com.julen.socios.actualizaciones.DescargadorActualizaciones
import com.julen.socios.actualizaciones.FuenteVersiones
import com.julen.socios.actualizaciones.Progreso
import com.julen.socios.actualizaciones.RegistroComprobaciones
import com.julen.socios.actualizaciones.Version
import com.julen.socios.actualizaciones.VersionPublicada
import com.julen.socios.util.UiText
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

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

    @get:Rule
    val carpeta = TemporaryFolder()

    private class DescargadorFalso : DescargadorActualizaciones {
        var archivo = File("socios-1.4.0.apk")
        var error: Exception? = null
        var seQuedaAMedias = false
        var descargas = 0
        var limpiezas = 0

        override suspend fun descargar(apk: ApkPublicado, version: Version, alProgresar: (Progreso) -> Unit): File {
            descargas++
            alProgresar(Progreso(apk.tamano / 2, apk.tamano))
            if (seQuedaAMedias) awaitCancellation()
            error?.let { throw it }
            return archivo
        }

        override suspend fun limpiar() {
            limpiezas++
        }
    }

    private val descargador = DescargadorFalso()

    private val nueva = VersionPublicada(
        Version.parse("1.4.0")!!,
        "https://github.com/Julenn06/SOCIOS/releases/tag/v1.4.0",
        ApkPublicado("https://github.com/Julenn06/SOCIOS/releases/download/v1.4.0/socios-1.4.0.apk", 1000, null)
    )

    private fun viewModel(instalada: String) =
        ActualizacionesViewModel(ComprobadorActualizaciones(fuente, registro, instalada, clock), descargador)

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun alAbrirLaAppAvisaDeLaVersionNueva() = runTest {
        val vm = viewModel("1.3.0")

        assertEquals("1.4.0", vm.nuevaVersion.first().version.toString())
        assertEquals("1.4.0", (vm.estadoVersion.value as EstadoVersion.Disponible).publicada.version.toString())
    }

    @Test
    fun laBusquedaManualEncuentraLaVersionNueva() {
        registro.ultimaComprobacion = clock.millis() // la del arranque ya no toca
        val vm = viewModel("1.3.0")
        assertEquals(EstadoVersion.SinComprobar, vm.estadoVersion.value)

        vm.buscarActualizaciones()

        assertEquals("1.4.0", (vm.estadoVersion.value as EstadoVersion.Disponible).publicada.version.toString())
    }

    @Test
    fun laBusquedaManualDiceSiYaEstaAlDia() {
        publicada = "1.3.0"
        val vm = viewModel("1.3.0")
        vm.buscarActualizaciones()

        assertEquals(EstadoVersion.AlDia, vm.estadoVersion.value)
    }

    @Test
    fun enUnaBuildDeDesarrolloMuestraLaUltimaPublicada() {
        val vm = viewModel("dev-12")
        vm.buscarActualizaciones()

        assertEquals(EstadoVersion.UltimaPublicada(Version.parse("1.4.0")!!), vm.estadoVersion.value)
    }

    @Test
    fun sinConexionLaBusquedaManualAvisaDelError() {
        sinConexion = true
        val vm = viewModel("1.3.0")
        vm.buscarActualizaciones()

        assertEquals(EstadoVersion.Error, vm.estadoVersion.value)
    }

    @Test
    fun alAbrirseBorraLasDescargasAnteriores() {
        viewModel("1.3.0")
        assertEquals(1, descargador.limpiezas)
    }

    @Test
    fun descargaElApkDeLaVersionNueva() {
        val vm = viewModel("1.3.0")
        assertEquals(Descarga.Inactiva, vm.descarga.value)

        vm.descargar(nueva)

        assertEquals(Descarga.Completada(nueva, descargador.archivo), vm.descarga.value)
    }

    @Test
    fun muestraElProgresoMientrasDescarga() {
        descargador.seQuedaAMedias = true
        val vm = viewModel("1.3.0")
        vm.descargar(nueva)

        assertEquals(Descarga.EnCurso(nueva, Progreso(500, 1000)), vm.descarga.value)
    }

    @Test
    fun cancelarParaLaDescargaYPermiteEmpezarOtra() {
        descargador.seQuedaAMedias = true
        val vm = viewModel("1.3.0")
        vm.descargar(nueva)
        vm.descargar(nueva)
        assertEquals(1, descargador.descargas)

        vm.cancelarDescarga()
        assertEquals(Descarga.Inactiva, vm.descarga.value)

        descargador.seQuedaAMedias = false
        vm.descargar(nueva)
        assertEquals(2, descargador.descargas)
        assertEquals(Descarga.Completada(nueva, descargador.archivo), vm.descarga.value)
    }

    @Test
    fun noVuelveADescargarUnApkQueYaEstaDescargado() {
        descargador.archivo = carpeta.newFile("socios-1.4.0.apk")
        val vm = viewModel("1.3.0")
        vm.descargar(nueva)
        vm.descargar(nueva)
        assertEquals(1, descargador.descargas)

        // Si Android ha vaciado la caché, sí.
        descargador.archivo.delete()
        vm.descargar(nueva)
        assertEquals(2, descargador.descargas)
    }

    @Test
    fun siLaDescargaFallaDiceElMotivo() {
        descargador.error = IOException("sin conexión")
        val vm = viewModel("1.3.0")
        vm.descargar(nueva)
        assertEquals(Descarga.Fallida(nueva, UiText.Res(R.string.descarga_error)), vm.descarga.value)

        descargador.error = DescargaDanadaException("incompleta")
        vm.descargar(nueva)
        assertEquals(Descarga.Fallida(nueva, UiText.Res(R.string.descarga_danada)), vm.descarga.value)
    }
}
