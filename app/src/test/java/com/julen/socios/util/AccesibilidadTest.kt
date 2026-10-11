package com.julen.socios.util

import org.junit.Assert.assertEquals
import org.junit.Test

class AccesibilidadTest {

    @Test
    fun quitaEmojisYSimbolosDecorativos() {
        assertEquals("Socio Conseguido (+40 € base)", "✓ Socio Conseguido (+40 € base)".sinSimbolosDecorativos())
        assertEquals("No Hecho (Pendiente)", "⏳ No Hecho (Pendiente)".sinSimbolosDecorativos())
        assertEquals("Bonus Nivel: +50 € (5 socios hechos)", "🏆 Bonus Nivel: +50 € (5 socios hechos)".sinSimbolosDecorativos())
        assertEquals("Bonus Socios", "Bonus Socios ✏️".sinSimbolosDecorativos())
        assertEquals("3 socios hechos (+50 € bonus)", "3 socios hechos (+50 € bonus 🎁)".sinSimbolosDecorativos())
    }

    @Test
    fun conservaMonedaYPuntuacion() {
        val texto = "Bruto 64,00 € · 2 socios hechos «motivo»"
        assertEquals(texto, texto.sinSimbolosDecorativos())
    }
}
