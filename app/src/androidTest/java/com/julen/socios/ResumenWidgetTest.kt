package com.julen.socios

import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.julen.socios.model.CobroInfo
import com.julen.socios.model.DiaSemana
import com.julen.socios.model.Socio
import com.julen.socios.util.CalculoComisiones
import com.julen.socios.util.toCurrencyString
import com.julen.socios.util.toEurosCompacto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** El widget enseña las mismas cuentas que la cabecera de la pantalla principal. */
@RunWith(AndroidJUnit4::class)
class ResumenWidgetTest {

    private val instrumentacion = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentacion.targetContext
    private val semanaKey = "2026-W44"

    private fun socios(hechos: Int, pendientes: Int = 0) =
        List(hechos + pendientes) { i -> Socio(colaboracion = 20.0, hecho = i < hechos, dia = DiaSemana.LUNES, semanaKey = semanaKey) }

    /** Dibuja el widget con [resumen] como lo haría el lanzador. */
    private fun dibujar(resumen: CalculoComisiones.ResumenCalculo): View {
        lateinit var vista: View
        instrumentacion.runOnMainSync {
            vista = ResumenWidget.vistas(context, semanaKey, resumen).apply(context, FrameLayout(context))
        }
        return vista
    }

    private fun View.texto(id: Int) = findViewById<TextView>(id).text.toString()

    @Test
    fun muestraElNetoYCuantoFaltaParaElSiguienteBonus() {
        // 8 socios de 20 €: 320 € de base + 100 € de bonus = 420 €; menos el 21,48 % de IRPF, 329,78 €.
        val resumen = CalculoComisiones.calcularResumenSemana(socios(hechos = 8, pendientes = 2), null)
        val vista = dibujar(resumen)

        assertEquals(329.78, resumen.totalNeto, 0.0)
        assertEquals(context.getString(R.string.header_neto_semanal), vista.texto(R.id.tvWidgetEtiqueta))
        assertEquals(329.78.toCurrencyString(), vista.texto(R.id.tvWidgetNeto))
        assertTrue(vista.texto(R.id.tvWidgetSocios).startsWith("8 socios hechos"))
        assertEquals("Faltan 2 para ${140.0.toEurosCompacto(prefix = "+")}", vista.texto(R.id.tvWidgetMeta))
        // La barra va del tramo anterior (7) al siguiente (10), como la de la app.
        val progreso = vista.findViewById<ProgressBar>(R.id.progresoWidget)
        assertEquals(3, progreso.max)
        assertEquals(1, progreso.progress)
    }

    @Test
    fun conLoCobradoApuntadoMuestraLoCobrado() {
        val resumen = CalculoComisiones.calcularResumenSemana(
            socios(hechos = 8), null, cobro = CobroInfo(semanaKey, netoCobrado = 300.0)
        )
        val vista = dibujar(resumen)

        assertEquals(context.getString(R.string.header_neto_cobrado), vista.texto(R.id.tvWidgetEtiqueta))
        assertEquals(300.0.toCurrencyString(), vista.texto(R.id.tvWidgetNeto))
    }
}
