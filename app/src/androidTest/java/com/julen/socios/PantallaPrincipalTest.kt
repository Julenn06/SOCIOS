package com.julen.socios

import android.view.View
import androidx.core.widget.NestedScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.longClick
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.action.ViewActions.swipeLeft
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.julen.socios.model.DiaSemana
import com.julen.socios.model.Socio
import com.julen.socios.util.DateUtils
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matcher
import org.hamcrest.Matchers.not
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Lo que se hace con un socio desde la lista de la semana. Usa la base de datos real de la app: apunta su
 * propio socio en la semana actual y lo borra al terminar, así no toca los demás datos.
 */
@RunWith(AndroidJUnit4::class)
class PantallaPrincipalTest {

    private val app = ApplicationProvider.getApplicationContext<SociosApp>()
    private val socios = app.container.socioRepository

    private val socio = Socio(
        id = "prueba-${UUID.randomUUID()}",
        colaboracion = 17.0,
        hecho = true,
        dia = DiaSemana.LUNES,
        semanaKey = DateUtils.getSemanaKey(0, app.container.clock),
        nombreSocio = "Prueba de interfaz",
        notas = "",
        timestamp = System.currentTimeMillis()
    )

    @Before
    fun apuntarSocio() = runBlocking { socios.addSocio(socio) }

    @After
    fun borrarSocio() = runBlocking { socios.deleteSocio(socio.id) }

    /**
     * Sube la vista hasta la parte de arriba de la pantalla. scrollTo() se conforma con dejarla en el borde de
     * abajo, que en pantallas pequeñas queda detrás de la barra de navegación: allí el sistema se queda con los
     * gestos y no llegan a la app.
     */
    private fun subirArriba() = object : ViewAction {
        override fun getConstraints(): Matcher<View> = isDescendantOfA(isAssignableFrom(NestedScrollView::class.java))

        override fun getDescription() = "subir la vista hasta arriba de la pantalla"

        override fun perform(uiController: UiController, view: View) {
            var padre = view.parent
            while (padre !is NestedScrollView) padre = (padre as View).parent
            val posVista = IntArray(2).also(view::getLocationInWindow)
            val posScroll = IntArray(2).also(padre::getLocationInWindow)
            padre.scrollBy(0, posVista[1] - posScroll[1])
            uiController.loopMainThreadUntilIdle()
        }
    }

    @Test
    fun tocarUnSocioAbreSuEdicion() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText(socio.nombreSocio)).perform(subirArriba(), click())

            onView(withText(R.string.title_edit_socio)).inRoot(isDialog()).check(matches(isDisplayed()))
            onView(withId(R.id.btnEliminarSocio)).inRoot(isDialog()).check(matches(isDisplayed()))
        }
    }

    @Test
    fun mantenerPulsadoUnSocioAbreSuPanelConEditarYEliminar() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText(socio.nombreSocio)).perform(subirArriba(), longClick())

            reintentar { onView(withText(socio.nombreSocio)).inRoot(isDialog()).check(matches(isDisplayed())) }
            onView(withText(R.string.cd_editar_socio)).inRoot(isDialog()).check(matches(isDisplayed()))
            onView(withText(R.string.cd_eliminar_socio)).inRoot(isDialog()).perform(scrollTo()).check(matches(isDisplayed()))
        }
    }

    @Test
    fun deslizarSobreUnSocioCambiaDeSemanaSinAbrirlo() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText(socio.nombreSocio)).perform(subirArriba(), swipeLeft())

            onView(withId(R.id.tvSemanaSubtitulo)).check(matches(not(withText(R.string.subtitle_current_week))))
            onView(withText(R.string.title_edit_socio)).check(doesNotExist())
        }
    }
}
