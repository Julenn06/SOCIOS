package com.julen.socios.export

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.julen.socios.model.BonoRegaloInfo
import com.julen.socios.model.CobroInfo
import com.julen.socios.model.DatosBackup
import com.julen.socios.model.DiaSemana
import com.julen.socios.model.Socio
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfReportGeneratorTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val clock = Clock.fixed(Instant.parse("2026-10-29T10:00:00Z"), ZoneId.of("Europe/Madrid"))
    private val archivo = File(context.cacheDir, "informe-test.pdf")

    @After
    fun borrar() {
        archivo.delete()
    }

    private fun semana(n: Int) = "2026-W%02d".format(n)

    /** [semanas] semanas seguidas con [porSemana] socios cada una, de todos los días y con nombres de todo tipo. */
    private fun datos(semanas: Int, porSemana: Int) = DatosBackup(
        socios = (1..semanas).flatMap { n ->
            List(porSemana) { i ->
                Socio(
                    id = "s$n-$i",
                    colaboracion = 10.0 + i,
                    hecho = i % 4 != 0,
                    dia = DiaSemana.entries[i % DiaSemana.entries.size],
                    semanaKey = semana(n),
                    nombreSocio = if (i == 0) "Nombre muy largo que no cabe en la columna, con tildes: Íñigo Ñúñez" else "",
                    notas = if (i == 1) "Nota con\nvarias líneas" else ""
                )
            }
        },
        bonosRegalo = listOf(BonoRegaloInfo(semana(2), activo = true, esMontoFijo = true, monto = 140.0)),
        cobros = listOf(CobroInfo(semana(3), netoCobrado = 250.0, nota = "1.ª cuota devuelta"))
    )

    /** Genera el informe y lo abre: cuántas páginas tiene y si todas son A4 y tienen algo dibujado. */
    private fun generarYAbrir(datos: DatosBackup, alcance: AlcanceExport): List<Pagina> {
        archivo.outputStream().use { PdfReportGenerator(context).generar(datos, alcance, it, clock) }
        return ParcelFileDescriptor.open(archivo, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { pdf ->
                List(pdf.pageCount) { i ->
                    pdf.openPage(i).use { pagina ->
                        val bitmap = Bitmap.createBitmap(pagina.width, pagina.height, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)
                        pagina.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        Pagina(pagina.width, pagina.height, tieneContenido(bitmap)).also { bitmap.recycle() }
                    }
                }
            }
        }
    }

    private data class Pagina(val ancho: Int, val alto: Int, val conContenido: Boolean)

    private fun tieneContenido(bitmap: Bitmap): Boolean {
        val pixeles = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixeles, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixeles.any { it != Color.WHITE }
    }

    @Test
    fun unaSemanaCabeEnUnaPaginaA4() {
        val paginas = generarYAbrir(datos(semanas = 1, porSemana = 5), AlcanceExport.Semanas(setOf(semana(1))))

        assertEquals(listOf(Pagina(595, 842, conContenido = true)), paginas)
    }

    @Test
    fun muchasSemanasSiguenEnPaginasNuevasSinNingunaEnBlanco() {
        val paginas = generarYAbrir(datos(semanas = 30, porSemana = 8), AlcanceExport.Historico)

        assertTrue("Solo ${paginas.size} páginas para 240 socios", paginas.size > 3)
        assertTrue(paginas.all { it.ancho == 595 && it.alto == 842 })
        assertTrue("Hay páginas en blanco", paginas.all { it.conContenido })
    }

    @Test
    fun unInformeSinSociosTambienSeGenera() {
        val paginas = generarYAbrir(DatosBackup(), AlcanceExport.Historico)

        assertEquals(1, paginas.size)
        assertTrue(paginas.single().conContenido)
    }
}
