package com.julen.socios.data.backup

import com.julen.socios.model.BonoRegaloInfo
import com.julen.socios.model.DatosBackup
import com.julen.socios.model.DiaSemana
import com.julen.socios.model.IrpfVigencia
import com.julen.socios.model.Socio
import java.io.ByteArrayOutputStream
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupJsonTest {

    private fun leer(json: String) = BackupJson.leer(json.byteInputStream(Charsets.UTF_8))

    @Test
    fun idaYVuelta() {
        val datos = DatosBackup(
            socios = listOf(
                Socio(
                    id = "s1", colaboracion = 18.0, hecho = false, dia = DiaSemana.JUEVES,
                    semanaKey = "2026-W10", nombreSocio = "Ana", notas = "Línea 1\nLínea 2", timestamp = 42
                )
            ),
            bonosRegalo = listOf(BonoRegaloInfo("2026-W10", activo = true, esMontoFijo = true, monto = 100.0)),
            irpfVigencias = listOf(IrpfVigencia("2026-W01", 18.0))
        )
        val out = ByteArrayOutputStream()
        BackupJson.escribir(datos, out, timestamp = 0)

        assertEquals(datos, BackupJson.leer(out.toByteArray().inputStream()).datos)
    }

    @Test
    fun losCamposAusentesTomanValoresPorDefectoEnVezDeRomperLaApp() {
        // Con Gson, nombreSocio/notas/id llegaban a null y la app se cerraba al guardar.
        val resultado = leer("""{"socios":[{"colaboracion":20.0,"diaSemanaId":2,"semanaKey":"2026-W10"}]}""")

        val socio = resultado.datos.socios.single()
        assertEquals("", socio.nombreSocio)
        assertEquals("", socio.notas)
        assertTrue(socio.hecho)
        assertTrue(socio.id.isNotBlank())
        assertEquals(0, resultado.descartados)
    }

    @Test
    fun leeElFormatoAntiguoDeListaDeSocios() {
        val resultado = leer(
            """[{"id":"a","colaboracion":20.0,"hecho":true,"diaSemanaId":1,"semanaKey":"2026-W10","nombreSocio":"","notas":"","timestamp":1}]"""
        )

        assertEquals("a", resultado.datos.socios.single().id)
    }

    @Test
    fun descartaRegistrosInvalidosONulos() {
        val resultado = leer(
            """
            {
              "socios": [
                {"id":"ok","colaboracion":20.0,"diaSemanaId":1,"semanaKey":"2026-W10"},
                {"id":"sin-dia","colaboracion":20.0,"semanaKey":"2026-W10"},
                {"id":"negativo","colaboracion":-5.0,"diaSemanaId":1,"semanaKey":"2026-W10"},
                null
              ],
              "bonosRegalo": [{"semanaKey":"2026-W10","monto":50.0}, {"semanaKey":"x","monto":50.0}],
              "irpfVigencias": [{"desdeSemanaKey":"2026-W01","porcentaje":150.0}]
            }
            """.trimIndent()
        )

        assertEquals(listOf("ok"), resultado.datos.socios.map { it.id })
        assertEquals(1, resultado.datos.bonosRegalo.size)
        assertTrue(resultado.datos.irpfVigencias.isEmpty())
        assertEquals(5, resultado.descartados)
    }

    @Test
    fun leeUnaCopiaExportadaPorLasVersionesQueUsabanGson() {
        // Salida literal de la v1.3.0: Gson escapa ' = < > & como \u00XX y sangra con dos espacios.
        val resultado = leer(
            """
            {
              "version": 2,
              "timestamp": 1760000000002,
              "socios": [
                {
                  "id": "s1",
                  "colaboracion": 18.5,
                  "hecho": false,
                  "diaSemanaId": 4,
                  "semanaKey": "2026-W10",
                  "nombreSocio": "Íñigo O'Neil",
                  "notas": "a=b <c> & \"d\"\nLínea 2 €",
                  "timestamp": 1760000000000
                }
              ],
              "bonosRegalo": [
                {
                  "semanaKey": "2026-W10",
                  "activo": true,
                  "esMontoFijo": false,
                  "monto": 25.0,
                  "nota": "Campaña"
                }
              ],
              "irpfVigencias": [
                {
                  "desdeSemanaKey": "2026-W01",
                  "porcentaje": 15.5
                }
              ]
            }
            """.trimIndent()
        )

        val esperado = DatosBackup(
            socios = listOf(
                Socio(
                    id = "s1", colaboracion = 18.5, hecho = false, dia = DiaSemana.JUEVES, semanaKey = "2026-W10",
                    nombreSocio = "Íñigo O'Neil", notas = "a=b <c> & \"d\"\nLínea 2 €", timestamp = 1760000000000
                )
            ),
            bonosRegalo = listOf(
                BonoRegaloInfo("2026-W10", activo = true, esMontoFijo = false, monto = 25.0, nota = "Campaña")
            ),
            irpfVigencias = listOf(IrpfVigencia("2026-W01", 15.5))
        )
        assertEquals(esperado, resultado.datos)
        assertEquals(0, resultado.descartados)
    }

    @Test
    fun toleraLoMismoQueGson() {
        // Números y booleanos entre comillas, un número donde se espera texto y campos desconocidos.
        val resultado = leer(
            """{"socios":[{"id":"x","colaboracion":"12.5","hecho":"false","diaSemanaId":"3",""" +
                """"semanaKey":"2026-W10","nombreSocio":123,"campoNuevo":{"a":1}}],"otraCosa":true}"""
        )

        val socio = resultado.datos.socios.single()
        assertEquals(12.5, socio.colaboracion, 0.0)
        assertEquals(false, socio.hecho)
        assertEquals(DiaSemana.MIERCOLES, socio.dia)
        assertEquals("123", socio.nombreSocio)
    }

    @Test
    fun unJsonMalFormadoLanzaUnErrorControlado() {
        assertThrows(SerializationException::class.java) { leer("{esto no es json") }
        assertThrows(SerializationException::class.java) { leer("42") }
        assertThrows(SerializationException::class.java) { leer("""{"socios":{"no":"es una lista"}}""") }
    }
}
