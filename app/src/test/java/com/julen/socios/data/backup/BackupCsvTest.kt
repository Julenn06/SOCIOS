package com.julen.socios.data.backup

import com.julen.socios.model.BonoRegaloInfo
import com.julen.socios.model.DatosBackup
import com.julen.socios.model.DiaSemana
import com.julen.socios.model.IrpfVigencia
import com.julen.socios.model.Socio
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
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
            irpfVigencias = listOf(IrpfVigencia("2026-W01", 15.5))
        )

        assertEquals(datos, escribirYLeer(datos))
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
    fun parserRespetaComillasEntreLineas() {
        val registros = BackupCsv.parsearRegistros("a,\"b\nc\",\"d\"\"e\"\nf,g")

        assertEquals(listOf(listOf("a", "b\nc", "d\"e"), listOf("f", "g")), registros)
    }
}
