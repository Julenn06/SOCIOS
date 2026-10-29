package com.julen.socios

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.julen.socios.export.ImportFormat
import com.julen.socios.model.DatosBackup
import com.julen.socios.model.DiaSemana
import com.julen.socios.model.ResultadoLectura
import com.julen.socios.model.Socio
import com.julen.socios.util.DateUtils
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La revisión antes de importar: muestra lo que entra y solo guarda al confirmar. Se le da lo leído de un archivo
 * directamente (elegir uno pasa por el selector del sistema, que es otra app). Usa la base de datos real de la
 * app y borra al terminar los socios que haya podido importar.
 */
@RunWith(AndroidJUnit4::class)
class ImportarTest {

    private val app = ApplicationProvider.getApplicationContext<SociosApp>()
    private val socios = app.container.socioRepository
    private val semana = DateUtils.getSemanaKey(0, app.container.clock)

    private val leidos = List(2) {
        Socio(
            id = "prueba-${UUID.randomUUID()}",
            colaboracion = 20.0,
            dia = DiaSemana.JUEVES,
            semanaKey = semana,
            nombreSocio = "Prueba de importar $it"
        )
    }

    @After
    fun borrarSocios() = runBlocking { leidos.forEach { socios.deleteSocio(it.id) } }

    /** Abre la revisión con dos socios nuevos y un registro que no se pudo leer. */
    private fun revisar(escenario: ActivityScenario<MainActivity>) {
        lateinit var viewModel: MainViewModel
        escenario.onActivity { viewModel = ViewModelProvider(it, MainViewModel.Factory)[MainViewModel::class.java] }
        runBlocking {
            viewModel.prepararImportacion(
                ResultadoLectura(DatosBackup(socios = leidos), descartados = 1, nombreArchivo = "copia_prueba.json"),
                ImportFormat.JSON
            )
        }
    }

    /**
     * Espera a que el panel esté abierto del todo: mientras sube con su animación, un toque puede caer donde ya no
     * está el botón. Su estado pasa a abierto al empezar a subir, así que después se espera a que acabe la animación
     * de la ventana; sin esperarla, con las animaciones activadas fallaba uno de cada tres intentos.
     */
    private fun esperarPanelAbierto(escenario: ActivityScenario<MainActivity>) {
        var dialogo: BottomSheetDialog? = null
        reintentar {
            escenario.onActivity { activity ->
                val panel = activity.supportFragmentManager.findFragmentByTag(ImportarBottomSheetDialog.TAG) as? BottomSheetDialogFragment
                dialogo = (panel?.dialog as? BottomSheetDialog)?.takeIf { it.behavior.state == BottomSheetBehavior.STATE_EXPANDED }
            }
            assertTrue("El panel de importar no está abierto", dialogo != null)
        }
        Thread.sleep(dialogo?.let(::duracionEntrada) ?: 0)
    }

    private fun importados(): Int = runBlocking {
        val ids = leidos.map { it.id }.toSet()
        socios.getSociosPorSemanaFlow(semana).first().count { it.id in ids }
    }

    @Test
    fun laRevisionMuestraLoQueEntraYCancelarNoGuardaNada() {
        ActivityScenario.launch(MainActivity::class.java).use { escenario ->
            revisar(escenario)
            esperarPanelAbierto(escenario)

            reintentar { onView(withText("copia_prueba.json")).inRoot(isDialog()).check(matches(isDisplayed())) }
            onView(withText(app.resources.getQuantityString(R.plurals.import_total_socios, 2, "2")))
                .inRoot(isDialog()).check(matches(isDisplayed()))
            onView(withText(R.string.import_fila_nuevos)).inRoot(isDialog()).check(matches(isDisplayed()))
            onView(withText(R.string.import_fila_sustituidos)).inRoot(isDialog()).check(doesNotExist())
            onView(withId(R.id.tarjetaDescartadosImport)).inRoot(isDialog()).perform(scrollTo()).check(matches(isDisplayed()))

            onView(withId(R.id.btnCancelarImport)).inRoot(isDialog()).perform(scrollTo(), click())

            reintentar { onView(withId(R.id.btnConfirmarImport)).check(doesNotExist()) }
            assertEquals(0, importados())
        }
    }

    @Test
    fun importarGuardaLosSociosLeidos() {
        ActivityScenario.launch(MainActivity::class.java).use { escenario ->
            revisar(escenario)
            esperarPanelAbierto(escenario)

            onView(withId(R.id.btnConfirmarImport)).inRoot(isDialog()).perform(scrollTo(), click())

            reintentar { onView(withId(R.id.btnConfirmarImport)).check(doesNotExist()) }
            reintentar { assertEquals(2, importados()) }
        }
    }
}
