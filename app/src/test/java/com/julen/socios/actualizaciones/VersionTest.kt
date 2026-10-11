package com.julen.socios.actualizaciones

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionTest {

    private fun v(texto: String) = requireNotNull(Version.parse(texto)) { texto }

    @Test
    fun aceptaLasVersionesDeLosTagsConYSinV() {
        assertEquals("1.3.0", v("1.3.0").toString())
        assertEquals("1.4.0", v("v1.4.0").toString())
        assertEquals("2", v(" v2 ").toString())
    }

    @Test
    fun lasBuildsDeDesarrolloYLosTextosRarosNoSonVersiones() {
        listOf("dev-12", "", "v", "1..2", "1.2.", "1.2-beta", "99999999999.0").forEach {
            assertNull(it, Version.parse(it))
        }
    }

    @Test
    fun comparaPorNumeroYNoComoTexto() {
        assertTrue(v("1.10.0") > v("1.9.0"))
        assertTrue(v("2.0.0") > v("1.99.99"))
        assertTrue(v("1.3.1") > v("1.3"))
        assertEquals(v("1.3"), v("1.3.0"))
        assertEquals(v("1.3").hashCode(), v("1.3.0").hashCode())
    }
}
