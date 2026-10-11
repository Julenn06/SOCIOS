package com.julen.socios.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.julen.socios.util.ReparacionSemanaKeys
import java.time.ZoneId

/**
 * v3 → v4:
 * - Índice por semanaKey (todas las consultas de la pantalla principal filtran por semana).
 * - Tabla de vigencias de IRPF.
 * - Corrección de las claves de semana guardadas con el cálculo antiguo en el cambio de año.
 */
class Migracion3a4(private val zona: ZoneId = ZoneId.systemDefault()) : Migration(3, 4) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_socios_semanaKey` ON `socios` (`semanaKey`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `irpf_vigencias` (`desdeSemanaKey` TEXT NOT NULL, " +
                "`porcentaje` REAL NOT NULL, PRIMARY KEY(`desdeSemanaKey`))"
        )
        repararSemanaKeys(db)
    }

    private fun repararSemanaKeys(db: SupportSQLiteDatabase) {
        val socios = db.query("SELECT id, semanaKey, timestamp FROM socios").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(ReparacionSemanaKeys.SocioRef(cursor.getString(0), cursor.getString(1), cursor.getLong(2)))
                }
            }
        }
        val bonoKeys = db.query("SELECT semanaKey FROM bonos_regalo").use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

        val reparacion = ReparacionSemanaKeys.calcular(socios, bonoKeys, zona)
        reparacion.socios.forEach { (id, semanaKey) ->
            db.execSQL("UPDATE socios SET semanaKey = ? WHERE id = ?", arrayOf(semanaKey, id))
        }
        reparacion.bonos.forEach { (antigua, nueva) ->
            db.execSQL("UPDATE bonos_regalo SET semanaKey = ? WHERE semanaKey = ?", arrayOf(nueva, antigua))
        }
    }
}
