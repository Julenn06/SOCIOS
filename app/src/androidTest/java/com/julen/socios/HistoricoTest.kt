package com.julen.socios

import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.openActionBarOverflowOrOptionsMenu
import androidx.test.espresso.NoMatchingViewException
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.julen.socios.model.DiaSemana
import com.julen.socios.model.Socio
import com.julen.socios.util.DateUtils
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * El histórico: el gráfico, que se desplaza y empieza en lo más reciente, y la mejor semana, que abre su semana.
 * Usa la base de datos real de la app: apunta sus propios socios y los borra al terminar.
 */
@RunWith(AndroidJUnit4::class)
class HistoricoTest {

    private val app = ApplicationProvider.getApplicationContext<SociosApp>()
    private val socios = app.container.socioRepository
    private val cobros = app.container.cobroRepository
    private val apuntados = mutableListOf<Socio>()

    /** Semanas atrás de la que tendrá el socio enorme y será la mejor. */
    private var semanasAtrasMejor = SEMANAS_ATRAS_MEJOR

    /**
     * Un socio en cada una de las 20 semanas anteriores (más de las que caben en el gráfico) y uno enorme hace 3.
     * Si en el dispositivo esa semana ya tiene lo cobrado apuntado, su neto es lo cobrado y el socio enorme no
     * cuenta: entonces va a la primera semana siguiente sin lo cobrado apuntado.
     */
    @Before
    fun apuntarSocios() = runBlocking {
        for (atras in 1..20) apuntar(atras, cuota = 10.0)
        semanasAtrasMejor = (SEMANAS_ATRAS_MEJOR..20).first { atras ->
            cobros.getCobroFlow(DateUtils.getSemanaKey(-atras, app.container.clock)).first() == null
        }
        apuntar(semanasAtrasMejor, cuota = 9_999.0)
    }

    @After
    fun borrarSocios() = runBlocking { apuntados.forEach { socios.deleteSocio(it.id) } }

    private suspend fun apuntar(semanasAtras: Int, cuota: Double) {
        val socio = Socio(
            id = "prueba-${UUID.randomUUID()}",
            colaboracion = cuota,
            dia = DiaSemana.MARTES,
            semanaKey = DateUtils.getSemanaKey(-semanasAtras, app.container.clock),
            nombreSocio = "Prueba del histórico"
        )
        socios.addSocio(socio)
        apuntados += socio
    }

    /** Abre el histórico desde su icono o, si en una pantalla estrecha no cabe en la barra, desde el menú ⋮. */
    private fun abrirHistorico() {
        try {
            onView(withId(R.id.action_ver_historico)).perform(click())
        } catch (e: NoMatchingViewException) {
            openActionBarOverflowOrOptionsMenu(app)
            onView(withText(R.string.menu_historico)).perform(click())
        }
    }

    @Test
    fun elGraficoSeDesplazaYEmpiezaEnLoMasReciente() {
        ActivityScenario.launch(MainActivity::class.java).use {
            abrirHistorico()

            // Con más semanas de las que caben se puede volver atrás, pero no avanzar: ya está en la actual.
            reintentar {
                onView(withId(R.id.graficoNeto)).inRoot(isDialog()).check { vista, error ->
                    if (error != null) throw error
                    val acciones = vista.createAccessibilityNodeInfo().actionList
                    assertTrue(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD in acciones)
                    assertFalse(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD in acciones)
                }
            }
            reintentar {
                onView(withId(R.id.tvGraficoAyuda)).inRoot(isDialog()).check(matches(withText(R.string.grafico_ayuda_desplazar)))
            }
        }
    }

    @Test
    fun tocarLaMejorSemanaAbreEsaSemana() {
        ActivityScenario.launch(MainActivity::class.java).use {
            abrirHistorico()

            reintentar { onView(withId(R.id.filaMejorSemana)).inRoot(isDialog()).perform(scrollTo(), click()) }

            // El panel se cierra (con su animación) y la pantalla principal pasa a esa semana.
            reintentar { onView(withId(R.id.filaMejorSemana)).check(doesNotExist()) }
            val subtitulo = app.resources.getQuantityString(R.plurals.semana_pasada, semanasAtrasMejor, semanasAtrasMejor)
            reintentar { onView(withId(R.id.tvSemanaSubtitulo)).check(matches(withText(subtitulo))) }
        }
    }

    @Test
    fun elHistoricoMuestraLoCobradoEnTotal() {
        ActivityScenario.launch(MainActivity::class.java).use {
            abrirHistorico()

            onView(withText(R.string.historico_cobrado_label)).inRoot(isDialog()).check(matches(isDisplayed()))
            onView(withId(R.id.tvGlobalNetoTotal)).inRoot(isDialog()).check(matches(isDisplayed()))
            reintentar { onView(withId(R.id.tarjetaMejorSemana)).inRoot(isDialog()).perform(scrollTo()).check(matches(isDisplayed())) }
        }
    }

    private companion object {
        const val SEMANAS_ATRAS_MEJOR = 3
    }
}
