package com.julen.socios.data

import androidx.lifecycle.SavedStateHandle
import com.julen.socios.data.local.CobroEntity
import com.julen.socios.data.local.SocioEntity
import com.julen.socios.export.AlcanceExport
import com.julen.socios.export.ExportFormat
import com.julen.socios.export.ExportGuardada
import com.julen.socios.export.ExportRequest
import com.julen.socios.fakes.FakeBonoRegaloDao
import com.julen.socios.fakes.FakeCobroDao
import com.julen.socios.fakes.FakeSocioDao
import com.julen.socios.fakes.FakeTransactionRunner
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportarAnioTest {

    @Test
    fun unaSemanaEsDelAnioDeSuClave() {
        val anio = AlcanceExport.Anio(2026)
        assertTrue(anio.incluye("2026-W01"))
        assertTrue(anio.incluye("2026-W53"))
        // La semana 1 de 2027 empieza el 4 de enero de 2027; la del 29-dic-2025 es la 2026-W01, no de 2025.
        assertFalse(anio.incluye("2027-W01"))
        assertFalse(anio.incluye("2025-W52"))
    }

    @Test
    fun cargaSoloLoDeEseAnio() = runTest {
        val socios = FakeSocioDao()
        val cobros = FakeCobroDao()
        socios.insertSocios(
            listOf(
                SocioEntity("a", 2000, true, 1, "2025-W52", "", "", 1),
                SocioEntity("b", 1500, true, 1, "2026-W01", "", "", 2),
                SocioEntity("c", 1000, false, 2, "2026-W40", "", "", 3)
            )
        )
        cobros.upsert(CobroEntity("2025-W52", 3000, ""))
        cobros.upsert(CobroEntity("2026-W40", 1000, ""))
        val repositorio = BackupRepository(socios, FakeBonoRegaloDao(), cobros, FakeTransactionRunner())

        val datos = repositorio.cargarDatos(AlcanceExport.Anio(2026))

        assertEquals(setOf("b", "c"), datos.socios.map { it.id }.toSet())
        assertEquals(listOf("2026-W40"), datos.cobros.map { it.semanaKey })
    }

    @Test
    fun laExportacionDeUnAnioSobreviveALaRecreacion() {
        val guardada = ExportGuardada(SavedStateHandle(), "export")
        guardada.guardar(ExportRequest(ExportFormat.PDF, AlcanceExport.Anio(2026)))

        assertEquals(ExportRequest(ExportFormat.PDF, AlcanceExport.Anio(2026)), guardada.tomar())
        assertEquals(null, guardada.leer())
    }
}
