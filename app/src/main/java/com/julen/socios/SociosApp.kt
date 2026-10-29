package com.julen.socios

import android.app.Application
import android.content.Context
import com.julen.socios.actualizaciones.ComprobadorActualizaciones
import com.julen.socios.actualizaciones.DescargadorGitHub
import com.julen.socios.actualizaciones.GitHubReleases
import com.julen.socios.actualizaciones.RegistroEnPreferencias
import com.julen.socios.ajustes.PreferenciasTema
import com.julen.socios.ajustes.PreferenciasUsuario
import com.julen.socios.ajustes.PreferenciasUsuarioCompartidas
import com.julen.socios.data.BackupRepository
import com.julen.socios.data.BonoRegaloRepository
import com.julen.socios.data.CobroRepository
import com.julen.socios.data.SocioRepository
import com.julen.socios.data.local.AppDatabase
import com.julen.socios.data.local.RoomTransactionRunner
import com.julen.socios.export.ExportService
import com.julen.socios.export.ExportadorArchivos
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** Dependencias compartidas por toda la app, creadas una sola vez. */
class AppContainer(context: Context) {
    val clock: Clock = Clock.systemDefaultZone()

    private val database = AppDatabase.crear(context)

    val socioRepository = SocioRepository(database.socioDao())
    val bonoRegaloRepository = BonoRegaloRepository(database.bonoRegaloDao())
    val cobroRepository = CobroRepository(database.cobroDao())
    val backupRepository = BackupRepository(
        database.socioDao(), database.bonoRegaloDao(), database.cobroDao(),
        RoomTransactionRunner(database)
    )
    val exportService: ExportService = ExportadorArchivos(context, clock)
    val comprobadorActualizaciones = ComprobadorActualizaciones(
        fuente = GitHubReleases(),
        registro = RegistroEnPreferencias(context),
        versionInstalada = BuildConfig.VERSION_NAME,
        clock = clock
    )
    val descargadorActualizaciones = DescargadorGitHub(context)
    val preferenciasTema = PreferenciasTema(context)
    val preferenciasUsuario: PreferenciasUsuario = PreferenciasUsuarioCompartidas(context)

    /** Avisa (al empezar y con cada cambio) de que han cambiado datos, sin leerlos: lo que haga falta se lee después. */
    val cambiosEnDatos: Flow<Set<String>> =
        database.invalidationTracker.createFlow("socios", "bonos_regalo", "cobros")
}

class SociosApp : Application() {

    lateinit var container: AppContainer
        private set

    /** Trabajos que viven lo mismo que el proceso, como mantener el widget al día. */
    private val alcanceApp = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Antes de abrir ninguna pantalla, para que no se vea un instante con el tema del sistema.
        container.preferenciasTema.tema.aplicar()
        mantenerWidgetAlDia()
    }

    /**
     * Cualquier cambio de socios, bonus, IRPF o lo cobrado se ve enseguida en el widget, no a la media hora.
     * Solo se escucha que algo ha cambiado: el widget lee únicamente su semana, y nada si no hay ninguno puesto.
     */
    private fun mantenerWidgetAlDia() {
        alcanceApp.launch {
            container.cambiosEnDatos.collect {
                // Un fallo al pintar el widget no debe cerrar la app.
                try {
                    ResumenWidget.actualizarTodos(this@SociosApp)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Se queda como estaba hasta el próximo cambio o la próxima actualización del sistema.
                }
            }
        }
    }
}
