package com.julen.socios.data

import android.content.Context
import androidx.core.content.edit
import com.google.gson.Gson
import com.julen.socios.data.backup.BackupDto
import com.julen.socios.data.backup.BonoRegaloDto
import com.julen.socios.data.backup.ConversionBackup
import com.julen.socios.data.local.BonoRegaloDao
import com.julen.socios.data.local.BonoRegaloEntity

/**
 * Las primeras versiones guardaban los bonos en SharedPreferences ("bono_<semana>" → JSON).
 * Esta migración los pasa a Room una sola vez al arrancar y borra las preferencias, de modo que el
 * resto de la app solo lee de la base de datos.
 */
class MigracionBonosLegacy(
    private val context: Context,
    private val bonoDao: BonoRegaloDao
) {

    suspend fun ejecutar() {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pendientes = prefs.all.filter { (key, value) -> key.startsWith(PREFIJO) && value is String }
        if (pendientes.isEmpty()) return

        val gson = Gson()
        val dtos = pendientes.mapNotNull { (key, value) ->
            runCatching { gson.fromJson(value as String, BonoRegaloDto::class.java) }.getOrNull()
                ?.let { it.copy(semanaKey = it.semanaKey ?: key.removePrefix(PREFIJO)) }
        }
        val bonos = ConversionBackup.desdeDto(BackupDto(bonosRegalo = dtos)).datos.bonosRegalo
            .filter { it.activo }

        for (bono in bonos) {
            // Lo ya guardado en Room es más reciente que lo que quedara en las preferencias.
            if (bonoDao.getBonoRegalo(bono.semanaKey) == null) {
                bonoDao.saveBonoRegalo(BonoRegaloEntity.fromBonoRegaloInfo(bono))
            }
        }
        prefs.edit(commit = true) { clear() }
        context.deleteSharedPreferences(PREFS)
    }

    private companion object {
        const val PREFS = "bonos_regalo_prefs"
        const val PREFIJO = "bono_"
    }
}
