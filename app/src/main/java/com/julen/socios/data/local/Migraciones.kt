package com.julen.socios.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.julen.socios.model.BonoRegaloInfo
import com.julen.socios.model.DiaSemana
import com.julen.socios.model.IrpfVigencia
import com.julen.socios.model.Socio
import com.julen.socios.util.CalculoComisiones
import com.julen.socios.util.DevolucionesAntiguas
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

/** v4 → v5: tabla de devoluciones (lo descontado cada semana por cuotas devueltas). La v6 la sustituye. */
object Migracion4a5 : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `devoluciones` (`semanaKey` TEXT NOT NULL, `monto` REAL NOT NULL, " +
                "`nota` TEXT NOT NULL, PRIMARY KEY(`semanaKey`))"
        )
    }
}

/**
 * v5 → v6: en lugar de lo descontado por devoluciones se guarda lo cobrado de verdad. Cada devolución se
 * pasa al neto que daba con las cuentas de la v5, así ninguna semana cambia de neto al actualizar.
 */
object Migracion5a6 : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `cobros` (`semanaKey` TEXT NOT NULL, `netoCobrado` REAL NOT NULL, " +
                "`nota` TEXT NOT NULL, PRIMARY KEY(`semanaKey`))"
        )
        val vigencias = db.query("SELECT desdeSemanaKey, porcentaje FROM irpf_vigencias").use { cursor ->
            buildList { while (cursor.moveToNext()) add(IrpfVigencia(cursor.getString(0), cursor.getDouble(1))) }
        }
        val devoluciones = db.query("SELECT semanaKey, monto, nota FROM devoluciones").use { cursor ->
            buildList { while (cursor.moveToNext()) add(Triple(cursor.getString(0), cursor.getDouble(1), cursor.getString(2))) }
        }
        for ((semanaKey, monto, nota) in devoluciones) {
            val neto = DevolucionesAntiguas.netoCobrado(
                socios = sociosDe(db, semanaKey),
                bono = bonoDe(db, semanaKey),
                porcentajeIrpf = CalculoComisiones.irpfParaSemana(semanaKey, vigencias),
                devolucion = monto
            )
            db.execSQL("INSERT OR REPLACE INTO cobros VALUES (?, ?, ?)", arrayOf(semanaKey, neto, nota))
        }
        db.execSQL("DROP TABLE IF EXISTS `devoluciones`")
    }

    private fun sociosDe(db: SupportSQLiteDatabase, semanaKey: String): List<Socio> =
        db.query("SELECT id, colaboracion, hecho, diaSemanaId FROM socios WHERE semanaKey = ?", arrayOf(semanaKey)).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        Socio(
                            id = c.getString(0),
                            colaboracion = c.getDouble(1),
                            hecho = c.getInt(2) != 0,
                            dia = DiaSemana.fromId(c.getInt(3)),
                            semanaKey = semanaKey
                        )
                    )
                }
            }
        }

    private fun bonoDe(db: SupportSQLiteDatabase, semanaKey: String): BonoRegaloInfo? =
        db.query("SELECT activo, esMontoFijo, monto FROM bonos_regalo WHERE semanaKey = ?", arrayOf(semanaKey)).use { c ->
            if (c.moveToFirst()) {
                BonoRegaloInfo(semanaKey, activo = c.getInt(0) != 0, esMontoFijo = c.getInt(1) != 0, monto = c.getDouble(2))
            } else {
                null
            }
        }
}
