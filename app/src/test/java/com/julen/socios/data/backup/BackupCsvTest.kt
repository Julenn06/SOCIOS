package com.julen.socios.data.backup

import com.julen.socios.model.BonoRegaloInfo
import com.julen.socios.model.CobroInfo
import com.julen.socios.model.DatosBackup
import com.julen.socios.model.DiaSemana
import com.julen.socios.model.IrpfVigencia
import com.julen.socios.model.Socio
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCsvTest {

    private fun escribirYLeer(datos: DatosBackup): DatosBackup {
        val out = ByteArrayOutputStream()
        BackupCsv.escribir(datos, out)
        return BackupCsv.leer(out.toByteArray().inputStream()).datos
    }

    private fun leer(csv: String) = BackupCsv.leer(csv.byteInputStream(Charsets.UTF_8))

    @Test
    fun idaYVueltaConservaCamposConComasComillasYSaltosDeLinea() {
        val datos = DatosBackup(
            socios = listOf(
                Socio(
                    id = "s1",
                    colaboracion = 10.5,
                    hecho = false,
                    dia = DiaSemana.MIERCOLES,
                    semanaKey = "2026-W10",
                    nombreSocio = " Ana, \"la de la esquina\" ",
                    notas = "Primera línea\nSegunda línea, con coma\r\nTercera",
                    timestamp = 1_772_000_000_000
                ),
                Socio(id = "s2", colaboracion = 20.0, dia = DiaSemana.LUNES, semanaKey = "2026-W11", timestamp = 1)
            ),
            bonosRegalo = listOf(
                BonoRegaloInfo("2026-W10", activo = true, esMontoFijo = false, monto = 50.0, nota = "Extra, por ventas")
            ),
            irpfVigencias = listOf(IrpfVigencia("2026-W01", 15.5)),
            cobros = listOf(CobroInfo("2026-W11", 284.69, "2.ª cuota de Ana, \"devuelta\""))
        )

        assertEquals(datos, escribirYLeer(datos))
    }

    @Test
    fun descartaCobrosSinImporteOConClaveInvalida() {
        val resultado = leer("# COBROS\nSemanaKey,NetoCobrado,Nota\n2026-W11,284.69,ok\n2026-W11\nx,5,\n2026-W12,abc,\n")

        assertEquals(listOf(CobroInfo("2026-W11", 284.69, "ok")), resultado.datos.cobros)
        assertEquals(3, resultado.descartados)
    }

    @Test
    fun lasDevolucionesDeUnaHojaV3SePasanALoCobrado() {
        val socios = (1..10).joinToString("\n") { "s$it,20,true,1,2026-W10,,,0" }
        val resultado = leer("# VERSION 3\n# SOCIOS\n$socios\n# DEVOLUCIONES\nSemanaKey,Monto,Nota\n2026-W10,40,1.ª cuota\n")

        assertEquals(listOf(CobroInfo("2026-W10", 392.60, "1.ª cuota")), resultado.datos.cobros)
    }

    @Test
    fun leeElFormatoDeLaVersionAnterior() {
        val csv = """
            # SOCIOS
            ID,Colaboracion,Hecho,DiaSemanaId,SemanaKey,NombreSocio,Notas,Timestamp
            a1,20.0,true,1,2026-W10,Juan,,1700000000000
            a2,15.0,false,5,2026-W10,,"Nota, con coma",1700000000001
            # BONOS_REGALO
            SemanaKey,Activo,EsMontoFijo,Monto,Nota
            2026-W10,true,true,140.0,Ajustado a mano
        """.trimIndent()

        val resultado = leer(csv)

        assertEquals(0, resultado.descartados)
        assertEquals(listOf("a1", "a2"), resultado.datos.socios.map { it.id })
        assertEquals(DiaSemana.VIERNES, resultado.datos.socios[1].dia)
        assertEquals("Nota, con coma", resultado.datos.socios[1].notas)
        assertEquals(140.0, resultado.datos.bonosRegalo.single().monto, 0.0)
    }

    @Test
    fun aceptaBomYFinesDeLineaDeWindows() {
        val csv = Char(0xFEFF) + "# SOCIOS\r\nID,Colaboracion,Hecho,DiaSemanaId,SemanaKey\r\nx,12,1,2,2026-W10\r\n"

        val resultado = leer(csv)
        val socio = resultado.datos.socios.single()

        // Si el BOM no se quitase, "# SOCIOS" no se reconocería como sección y contaría como descartado.
        assertEquals(0, resultado.descartados)
        assertEquals("x", socio.id)
        assertEquals(12.0, socio.colaboracion, 0.0)
        assertEquals(true, socio.hecho)
        assertEquals(DiaSemana.MARTES, socio.dia)
    }

    @Test
    fun descartaFilasInvalidasSinAbortarLaImportacion() {
        val csv = """
            # SOCIOS
            ok,20,true,1,2026-W10
            sin-importe,abc,true,1,2026-W10
            dia-raro,20,true,9,2026-W10
            semana-rara,20,true,1,2026-10
            corta,20
        """.trimIndent()

        val resultado = leer(csv)

        assertEquals(listOf("ok"), resultado.datos.socios.map { it.id })
        assertEquals(4, resultado.descartados)
    }

    @Test
    fun corrigeClavesDeSemanaDeCopiasAntiguas() {
        val zona = ZoneId.systemDefault()
        val ts = LocalDate.parse("2025-12-30").atTime(10, 0).atZone(zona).toInstant().toEpochMilli()
        val csv = "# SOCIOS\nviejo,20,true,2,2025-W01,,,$ts\n"

        assertEquals("2026-W01", leer(csv).datos.socios.single().semanaKey)
    }

    @Test
    fun corrigeClavesImposiblesEnLugarDeDescartarlas() {
        val zona = ZoneId.systemDefault()
        val ts = LocalDate.parse("2027-01-01").atTime(10, 0).atZone(zona).toInstant().toEpochMilli()
        val csv = "# SOCIOS\nviejo,20,true,5,2027-W53,,,$ts\n# BONOS_REGALO\n2027-W53,true,true,50,\n"

        val resultado = leer(csv)

        assertEquals(0, resultado.descartados)
        assertEquals("2026-W53", resultado.datos.socios.single().semanaKey)
        assertEquals("2026-W53", resultado.datos.bonosRegalo.single().semanaKey)
    }

    @Test
    fun noMueveLasSemanasDeArchivosDeLaVersionActual() {
        val zona = ZoneId.systemDefault()
        // Socio de la semana 1 de 2025 (30 dic 2024 - 5 ene 2025) apuntado tarde, en diciembre de 2025.
        val ts = LocalDate.parse("2025-12-30").atTime(10, 0).atZone(zona).toInstant().toEpochMilli()
        val csv = "# VERSION 2\n# SOCIOS\ntarde,20,true,2,2025-W01,,,$ts\n"

        val resultado = leer(csv)

        assertEquals(0, resultado.descartados)
        assertEquals("2025-W01", resultado.datos.socios.single().semanaKey)
    }

    @Test
    fun unaFilaSinFechaNoSeMueveDeSemana() {
        // Sin fecha se tomaría la de hoy, que no dice nada de cuándo se apuntó el socio.
        assertEquals("2025-W01", leer("# SOCIOS\nsin-fecha,20,true,2,2025-W01\n").datos.socios.single().semanaKey)
    }

    @Test
    fun escribeBomYVersionParaExcelYParaLaImportacion() {
        val out = ByteArrayOutputStream()
        BackupCsv.escribir(DatosBackup(), out)
        val texto = out.toString(Charsets.UTF_8.name())

        assertTrue(texto.startsWith(Char(0xFEFF) + "# VERSION ${ConversionBackup.VERSION_ACTUAL}\n# SOCIOS\n"))
    }

    @Test
    fun parserRespetaComillasEntreLineas() {
        val registros = BackupCsv.parsearRegistros("a,\"b\nc\",\"d\"\"e\"\nf,g")

        assertEquals(listOf(listOf("a", "b\nc", "d\"e"), listOf("f", "g")), registros)
    }
}
