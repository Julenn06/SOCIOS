package com.julen.socios.data.local

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SocioDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: SocioDao

    private fun socio(id: String, semana: String, hecho: Boolean = true, timestamp: Long = 0) =
        SocioEntity(id, 20.0, hecho, 1, semana, "", "", timestamp)

    @Before
    fun crear() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = db.socioDao()
    }

    @After
    fun cerrar() = db.close()

    @Test
    fun setHechoFijaElEstadoSoloDelSocioIndicado() = runTest {
        dao.insertSocios(listOf(socio("a", "2026-W42"), socio("b", "2026-W42")))

        assertEquals(1, dao.setHecho("a", false))
        assertEquals(1, dao.setHecho("a", false)) // idempotente: repetirlo no lo vuelve a cambiar
        assertEquals(0, dao.setHecho("no-existe", false))

        val porId = dao.getAllSocios().associateBy { it.id }
        assertEquals(false, porId.getValue("a").hecho)
        assertEquals(true, porId.getValue("b").hecho)
    }

    @Test
    fun consultasPorSemana() = runTest {
        dao.insertSocios(
            listOf(
                socio("a", "2026-W41", timestamp = 1),
                socio("b", "2026-W42", timestamp = 2),
                socio("c", "2026-W42", timestamp = 3)
            )
        )

        assertEquals(listOf("c", "b"), dao.getSociosPorSemanaFlow("2026-W42").first().map { it.id })
        assertEquals(listOf("c", "b", "a"), dao.getSociosPorSemanas(listOf("2026-W41", "2026-W42")).map { it.id })
        assertEquals(setOf("2026-W41", "2026-W42"), dao.getSemanaKeysFlow().first().toSet())
    }

    @Test
    fun unaTransaccionFallidaNoDejaDatosAMedias() = runTest {
        val runner = RoomTransactionRunner(db)

        runCatching {
            runner.run {
                dao.insertSocio(socio("a", "2026-W42"))
                error("fallo a mitad de la importación")
            }
        }

        assertTrue(dao.getAllSocios().isEmpty())
    }
}
