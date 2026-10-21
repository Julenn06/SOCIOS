package com.julen.socios.data.local

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigracionesTest {

    private val nombreDb = "migracion-test"
    private val zona = ZoneId.of("Europe/Madrid")

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    private fun ts(fecha: String) = LocalDate.parse(fecha).atTime(10, 0).atZone(zona).toInstant().toEpochMilli()

    @Test
    fun migra3a4ConservandoLosDatosYCorrigiendoLasClavesDeFinDeAnio() {
        helper.createDatabase(nombreDb, 3).apply {
            execSQL("INSERT INTO socios VALUES ('dic', 20.0, 1, 2, '2025-W01', 'Ana', '', ${ts("2025-12-30")})")
            execSQL("INSERT INTO socios VALUES ('ene', 15.0, 0, 4, '2025-W01', '', '', ${ts("2025-01-02")})")
            execSQL("INSERT INTO socios VALUES ('oct', 12.0, 1, 1, '2026-W42', '', 'nota', ${ts("2026-10-12")})")
            execSQL("INSERT INTO bonos_regalo VALUES ('2026-W42', 1, 1, 140.0, 'Ajustado a mano')")
            close()
        }

        val db = helper.runMigrationsAndValidate(nombreDb, 4, true, Migracion3a4(zona))

        val claves = db.query("SELECT id, semanaKey FROM socios").use { cursor ->
            buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1)) }
        }
        assertEquals(mapOf("dic" to "2026-W01", "ene" to "2025-W01", "oct" to "2026-W42"), claves)

        db.query("SELECT nombreSocio, colaboracion FROM socios WHERE id = 'dic'").use { cursor ->
            cursor.moveToFirst()
            assertEquals("Ana", cursor.getString(0))
            assertEquals(20.0, cursor.getDouble(1), 0.0)
        }
        db.query("SELECT semanaKey, monto FROM bonos_regalo").use { cursor ->
            cursor.moveToFirst()
            assertEquals("2026-W42", cursor.getString(0))
            assertEquals(140.0, cursor.getDouble(1), 0.0)
        }
        db.query("SELECT COUNT(*) FROM irpf_vigencias").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0, cursor.getInt(0))
        }
    }

    @Test
    fun migra4a5ConservandoLosDatosYCreandoLaTablaDeDevoluciones() {
        helper.createDatabase(nombreDb, 4).apply {
            execSQL("INSERT INTO socios VALUES ('a', 20.0, 1, 1, '2026-W42', 'Ana', '', 0)")
            execSQL("INSERT INTO irpf_vigencias VALUES ('2026-W01', 15.0)")
            close()
        }

        val db = helper.runMigrationsAndValidate(nombreDb, 5, true, Migracion4a5)

        db.query("SELECT nombreSocio FROM socios WHERE id = 'a'").use { cursor ->
            cursor.moveToFirst()
            assertEquals("Ana", cursor.getString(0))
        }
        db.execSQL("INSERT INTO devoluciones VALUES ('2026-W43', 24.0, '1.ª cuota')")
        db.query("SELECT monto, nota FROM devoluciones").use { cursor ->
            cursor.moveToFirst()
            assertEquals(24.0, cursor.getDouble(0), 0.0)
            assertEquals("1.ª cuota", cursor.getString(1))
        }
    }

    @Test
    fun roomAbreLaBaseDeDatosMigrada() {
        helper.createDatabase(nombreDb, 3).apply {
            execSQL("INSERT INTO socios VALUES ('a', 20.0, 1, 1, '2026-W42', '', '', 0)")
            close()
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val room = Room.databaseBuilder(context, AppDatabase::class.java, nombreDb)
            .addMigrations(Migracion3a4(zona), Migracion4a5)
            .build()
        helper.closeWhenFinished(room)

        val socios = runBlocking { room.socioDao().getAllSocios() }
        assertEquals(listOf("a"), socios.map { it.id })
        assertEquals(emptyList<DevolucionEntity>(), runBlocking { room.devolucionDao().getAll() })
    }
}
