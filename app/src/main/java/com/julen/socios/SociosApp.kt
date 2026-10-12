package com.julen.socios

import android.app.Application
import android.content.Context
import com.julen.socios.actualizaciones.ComprobadorActualizaciones
import com.julen.socios.actualizaciones.DescargadorGitHub
import com.julen.socios.actualizaciones.GitHubReleases
import com.julen.socios.actualizaciones.RegistroEnPreferencias
import com.julen.socios.ajustes.PreferenciasTema
import com.julen.socios.data.BackupRepository
import com.julen.socios.data.BonoRegaloRepository
import com.julen.socios.data.IrpfRepository
import com.julen.socios.data.SocioRepository
import com.julen.socios.data.local.AppDatabase
import com.julen.socios.data.local.RoomTransactionRunner
import com.julen.socios.export.ExportService
import com.julen.socios.export.ExportadorArchivos
import java.time.Clock

/** Dependencias compartidas por toda la app, creadas una sola vez. */
class AppContainer(context: Context) {
    val clock: Clock = Clock.systemDefaultZone()

    private val database = AppDatabase.crear(context)

    val socioRepository = SocioRepository(database.socioDao())
    val bonoRegaloRepository = BonoRegaloRepository(database.bonoRegaloDao())
    val irpfRepository = IrpfRepository(database.irpfVigenciaDao())
    val backupRepository = BackupRepository(
        database.socioDao(), database.bonoRegaloDao(), database.irpfVigenciaDao(), RoomTransactionRunner(database)
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
}

class SociosApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Antes de abrir ninguna pantalla, para que no se vea un instante con el tema del sistema.
        container.preferenciasTema.tema.aplicar()
    }
}
