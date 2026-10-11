package com.julen.socios

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.julen.socios.data.BackupRepository
import com.julen.socios.data.BonoRegaloRepository
import com.julen.socios.data.IrpfRepository
import com.julen.socios.data.MigracionBonosLegacy
import com.julen.socios.data.SocioRepository
import com.julen.socios.data.local.AppDatabase
import com.julen.socios.data.local.RoomTransactionRunner
import com.julen.socios.export.ExportService
import com.julen.socios.export.ExportadorArchivos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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

    val migracionBonosLegacy = MigracionBonosLegacy(context, database.bonoRegaloDao())
}

class SociosApp : Application() {

    lateinit var container: AppContainer
        private set

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // La app solo tiene tema claro.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        container = AppContainer(this)

        applicationScope.launch {
            runCatching { container.migracionBonosLegacy.ejecutar() }
                .onFailure { Log.w("SociosApp", "No se pudieron migrar los bonos antiguos", it) }
        }
    }
}
