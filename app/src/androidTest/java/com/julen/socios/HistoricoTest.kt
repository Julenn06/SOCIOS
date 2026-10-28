package com.julen.socios

import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
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
    private val apuntados = mutableListOf<Socio>()

    /** Un socio en cada una de las 20 semanas anteriores (más de las que caben en el gráfico) y uno enorme hace 3. */
    @Before
    fun apuntarSocios() = runBlocking {
        for (atras in 1..20) apuntar(atras, cuota = 10.0)
        apuntar(SEMANAS_ATRAS_MEJOR, cuota = 9_999.0)
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

    private fun abrirHistorico() = onView(withId(R.id.action_ver_historico)).perform(click())

    @Test
    fun elGraficoSeDesplazaYEmpiezaEnLoMasReciente() {
        ActivityScenario.launch(MainActivity::class.java).use {
            abrirHistorico()

            // Con más semanas de las que caben se puede volver atrás, pero no avanzar: ya está en la actual.
            reintentar {
                onView(withId(R.id.graficoNeto)).inRoot(isDialog()).check { vista, error ->
                    if (error != null) throw error
                    val acciones = AccessibilityNodeInfo.obtain(vista).also(vista::onInitializeAccessibilityNodeInfo).actionList
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

            reintentar { onView(withId(R.id.filaMejorSemana)).inRoot(isDialog()).perform(click()) }

            // El panel se cierra (con su animación) y la pantalla principal pasa a esa semana.
            reintentar { onView(withId(R.id.filaMejorSemana)).check(doesNotExist()) }
            val haceTres = app.resources.getQuantityString(R.plurals.semana_pasada, SEMANAS_ATRAS_MEJOR, SEMANAS_ATRAS_MEJOR)
            reintentar { onView(withId(R.id.tvSemanaSubtitulo)).check(matches(withText(haceTres))) }
        }
    }

    @Test
    fun elHistoricoMuestraLoCobradoEnTotal() {
        ActivityScenario.launch(MainActivity::class.java).use {
            abrirHistorico()

            onView(withText(R.string.historico_cobrado_label)).inRoot(isDialog()).check(matches(isDisplayed()))
            onView(withId(R.id.tvGlobalNetoTotal)).inRoot(isDialog()).check(matches(isDisplayed()))
            reintentar { onView(withId(R.id.tarjetaMejorSemana)).inRoot(isDialog()).check(matches(isDisplayed())) }
        }
    }

    private companion object {
        const val SEMANAS_ATRAS_MEJOR = 3
    }
}
