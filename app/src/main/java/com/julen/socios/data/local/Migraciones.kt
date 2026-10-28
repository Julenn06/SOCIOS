package com.julen.socios.data.local

import android.database.Cursor
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
        val socios = db.filas("SELECT id, semanaKey, timestamp FROM socios") { cursor ->
            ReparacionSemanaKeys.SocioRef(cursor.getString(0), cursor.getString(1), cursor.getLong(2))
        }
        val bonoKeys = db.filas("SELECT semanaKey FROM bonos_regalo") { it.getString(0) }.toSet()

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
        val vigencias = db.filas("SELECT desdeSemanaKey, porcentaje FROM irpf_vigencias") {
            IrpfVigencia(it.getString(0), it.getDouble(1))
        }
        val devoluciones = db.filas("SELECT semanaKey, monto, nota FROM devoluciones") {
            Triple(it.getString(0), it.getDouble(1), it.getString(2))
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
        db.filas("SELECT id, colaboracion, hecho, diaSemanaId FROM socios WHERE semanaKey = ?", semanaKey) { c ->
            Socio(
                id = c.getString(0),
                colaboracion = c.getDouble(1),
                hecho = c.getInt(2) != 0,
                dia = DiaSemana.fromId(c.getInt(3)),
                semanaKey = semanaKey
            )
        }

    // La semana es la clave primaria de los bonos: como mucho hay uno.
    private fun bonoDe(db: SupportSQLiteDatabase, semanaKey: String): BonoRegaloInfo? =
        db.filas("SELECT activo, esMontoFijo, monto FROM bonos_regalo WHERE semanaKey = ?", semanaKey) { c ->
            BonoRegaloInfo(semanaKey, activo = c.getInt(0) != 0, esMontoFijo = c.getInt(1) != 0, monto = c.getDouble(2))
        }.firstOrNull()
}

/** Cada fila que devuelve [sql] (con [argumentos] en lugar de sus ?), convertida con [leer]. */
private fun <T> SupportSQLiteDatabase.filas(sql: String, vararg argumentos: Any?, leer: (Cursor) -> T): List<T> =
    query(sql, argumentos).use { cursor -> buildList { while (cursor.moveToNext()) add(leer(cursor)) } }
