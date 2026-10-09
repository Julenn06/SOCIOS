package com.julen.socios.data

import android.content.Context
import androidx.core.content.edit
import com.google.gson.Gson
import com.julen.socios.data.local.AppDatabase
import com.julen.socios.data.local.BonoRegaloDao
import com.julen.socios.data.local.BonoRegaloEntity
import com.julen.socios.model.BonoRegaloInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class BonoRegaloRepository(context: Context) {
    private val bonoDao: BonoRegaloDao = AppDatabase.getDatabase(context).bonoRegaloDao()
    private val prefs = context.getSharedPreferences("bonos_regalo_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    suspend fun getBonoRegalo(semanaKey: String): BonoRegaloInfo = withContext(Dispatchers.IO) {
        val entity = bonoDao.getBonoRegalo(semanaKey)
        if (entity != null) {
            return@withContext entity.toBonoRegaloInfo()
        }
        // Fallback check SharedPreferences for legacy data migration
        val json = prefs.getString("bono_$semanaKey", null)
        if (json != null) {
            try {
                val legacy = gson.fromJson(json, BonoRegaloInfo::class.java)
                if (legacy != null) {
                    saveBonoRegalo(legacy)
                    prefs.edit { remove("bono_$semanaKey") }
                    return@withContext legacy
                }
            } catch (_: Exception) {
            }
        }
        BonoRegaloInfo(semanaKey = semanaKey)
    }

    fun getBonoRegaloFlow(semanaKey: String): Flow<BonoRegaloInfo?> {
        return bonoDao.getBonoRegaloFlow(semanaKey).map { entity ->
            entity?.toBonoRegaloInfo() ?: BonoRegaloInfo(semanaKey = semanaKey)
        }
    }

    suspend fun saveBonoRegalo(bonoInfo: BonoRegaloInfo) = withContext(Dispatchers.IO) {
        bonoDao.saveBonoRegalo(BonoRegaloEntity.fromBonoRegaloInfo(bonoInfo))
    }

    suspend fun deleteBonoRegalo(semanaKey: String) = withContext(Dispatchers.IO) {
        bonoDao.deleteBonoRegalo(semanaKey)
        prefs.edit { remove("bono_$semanaKey") }
    }

    suspend fun getAllBonosRegalo(): Map<String, BonoRegaloInfo> = withContext(Dispatchers.IO) {
        val entities = bonoDao.getAllBonosRegalo()
        val map = entities.associateBy({ it.semanaKey }, { it.toBonoRegaloInfo() }).toMutableMap()

        // Check legacy SharedPreferences
        for ((key, value) in prefs.all) {
            if (key.startsWith("bono_") && value is String) {
                val semanaKey = key.removePrefix("bono_")
                if (!map.containsKey(semanaKey)) {
                    try {
                        val bono = gson.fromJson(value, BonoRegaloInfo::class.java)
                        if (bono != null && bono.activo) {
                            map[semanaKey] = bono
                            saveBonoRegalo(bono)
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        }
        map
    }
}
