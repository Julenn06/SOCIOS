package com.julen.socios.util

import com.julen.socios.model.BonoRegaloInfo
import com.julen.socios.model.Socio

/**
 * La versión 1.9.0 guardaba lo descontado por devoluciones (en bruto, antes del IRPF) en lugar de lo cobrado.
 * Esto lo pasa al neto que salía con esas cuentas, para que al actualizar la base de datos (Migracion5a6) cada
 * semana siga mostrando el mismo neto. Las devoluciones de una copia de esa versión no se importan: se cuentan
 * como descartadas.
 */
object DevolucionesAntiguas {

    fun netoCobrado(socios: List<Socio>, bono: BonoRegaloInfo?, porcentajeIrpf: Double, devolucion: Double): Double {
        val bruto = CalculoComisiones.calcularResumenSemana(socios, bono, porcentajeIrpf).totalBruto
        val brutoTrasDevolucion = CalculoComisiones.redondearCentimos(bruto - devolucion.coerceAtLeast(0.0))
        val irpf = CalculoComisiones.redondearCentimos(brutoTrasDevolucion.coerceAtLeast(0.0) * porcentajeIrpf / 100.0)
        return CalculoComisiones.redondearCentimos(brutoTrasDevolucion - irpf)
    }
}
