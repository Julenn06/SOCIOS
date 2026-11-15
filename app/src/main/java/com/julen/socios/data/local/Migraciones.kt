package com.julen.socios.data.local

import android.database.Cursor
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.julen.socios.model.BonoRegaloInfo
import com.julen.socios.model.DiaSemana
import com.julen.socios.model.Socio
import com.julen.socios.util.CalculoComisiones
import com.julen.socios.util.Centimos
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
        val devoluciones = db.filas("SELECT semanaKey, monto, nota FROM devoluciones") {
            Triple(it.getString(0), it.getDouble(1), it.getString(2))
        }
        for ((semanaKey, monto, nota) in devoluciones) {
            val neto = DevolucionesAntiguas.netoCobrado(
                socios = sociosDe(db, semanaKey),
                bono = bonoDe(db, semanaKey),
                porcentajeIrpf = irpfDe(db, semanaKey),
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

    // El IRPF con el que la v5 calculaba esa semana: el del último cambio que empezase en ella o antes.
    private fun irpfDe(db: SupportSQLiteDatabase, semanaKey: String): Double =
        db.filas(
            "SELECT porcentaje FROM irpf_vigencias WHERE desdeSemanaKey <= ? ORDER BY desdeSemanaKey DESC LIMIT 1", semanaKey
        ) { it.getDouble(0) }.firstOrNull() ?: CalculoComisiones.IRPF

    // La semana es la clave primaria de los bonos: como mucho hay uno.
    private fun bonoDe(db: SupportSQLiteDatabase, semanaKey: String): BonoRegaloInfo? =
        db.filas("SELECT activo, esMontoFijo, monto FROM bonos_regalo WHERE semanaKey = ?", semanaKey) { c ->
            BonoRegaloInfo(semanaKey, activo = c.getInt(0) != 0, esMontoFijo = c.getInt(1) != 0, monto = c.getDouble(2))
        }.firstOrNull()
}

/**
 * v6 → v7: el IRPF es siempre el mismo ([CalculoComisiones.IRPF]). Fuera la tabla de cambios de IRPF, que solo
 * se llenaba al importar copias antiguas y podía dejar semanas con otro porcentaje.
 */
object Migracion6a7 : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS `irpf_vigencias`")
    }
}

/**
 * v7 → v8: los importes (cuota, bono y lo cobrado) se guardan en céntimos enteros ([Centimos]) en lugar de en euros
 * con decimales. SQLite no deja cambiar el tipo de una columna: cada tabla se rehace con la columna nueva y sus filas
 * se copian convirtiendo el importe aquí, con el mismo redondeo que usa la app (`ROUND` de SQLite redondea el valor
 * binario y 12,345 € daría 1234 céntimos en lugar de 1235).
 */
object Migracion7a8 : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        rehacer(
            db, "socios",
            crear = "CREATE TABLE IF NOT EXISTS `socios_v8` (`id` TEXT NOT NULL, `colaboracionCentimos` INTEGER NOT NULL, " +
                "`hecho` INTEGER NOT NULL, `diaSemanaId` INTEGER NOT NULL, `semanaKey` TEXT NOT NULL, " +
                "`nombreSocio` TEXT NOT NULL, `notas` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, PRIMARY KEY(`id`))",
            columnas = listOf("id", "colaboracion", "hecho", "diaSemanaId", "semanaKey", "nombreSocio", "notas", "timestamp"),
            importe = "colaboracion"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_socios_semanaKey` ON `socios` (`semanaKey`)")
        rehacer(
            db, "bonos_regalo",
            crear = "CREATE TABLE IF NOT EXISTS `bonos_regalo_v8` (`semanaKey` TEXT NOT NULL, `activo` INTEGER NOT NULL, " +
                "`esMontoFijo` INTEGER NOT NULL, `montoCentimos` INTEGER NOT NULL, `nota` TEXT NOT NULL, PRIMARY KEY(`semanaKey`))",
            columnas = listOf("semanaKey", "activo", "esMontoFijo", "monto", "nota"),
            importe = "monto"
        )
        rehacer(
            db, "cobros",
            crear = "CREATE TABLE IF NOT EXISTS `cobros_v8` (`semanaKey` TEXT NOT NULL, `netoCobradoCentimos` INTEGER NOT NULL, " +
                "`nota` TEXT NOT NULL, PRIMARY KEY(`semanaKey`))",
            columnas = listOf("semanaKey", "netoCobrado", "nota"),
            importe = "netoCobrado"
        )
    }

    /**
     * Crea `[tabla]_v8` con [crear], copia en ella cada fila de [tabla] (sus [columnas], en el mismo orden que en la
     * tabla nueva) pasando a céntimos la columna [importe], y la deja en lugar de la antigua.
     */
    private fun rehacer(db: SupportSQLiteDatabase, tabla: String, crear: String, columnas: List<String>, importe: String) {
        db.execSQL(crear)
        val posicionImporte = columnas.indexOf(importe)
        val filas = db.filas("SELECT ${columnas.joinToString { "`$it`" }} FROM `$tabla`") { cursor ->
            Array<Any?>(columnas.size) { i ->
                when {
                    i == posicionImporte -> Centimos.de(cursor.getDouble(i))
                    cursor.getType(i) == Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                    else -> cursor.getString(i)
                }
            }
        }
        val huecos = columnas.joinToString { "?" }
        filas.forEach { db.execSQL("INSERT INTO `${tabla}_v8` VALUES ($huecos)", it) }
        db.execSQL("DROP TABLE `$tabla`")
        db.execSQL("ALTER TABLE `${tabla}_v8` RENAME TO `$tabla`")
    }
}

/** Cada fila que devuelve [sql] (con [argumentos] en lugar de sus ?), convertida con [leer]. */
private fun <T> SupportSQLiteDatabase.filas(sql: String, vararg argumentos: Any?, leer: (Cursor) -> T): List<T> =
    query(sql, argumentos).use { cursor -> buildList { while (cursor.moveToNext()) add(leer(cursor)) } }
