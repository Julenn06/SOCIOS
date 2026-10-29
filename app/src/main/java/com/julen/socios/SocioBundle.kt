package com.julen.socios

import android.os.Bundle
import com.julen.socios.model.DiaSemana
import com.julen.socios.model.Socio

/** Un socio en los argumentos o el resultado de un panel, que sobreviven a que la Activity se recree. */
internal fun Socio.toBundle() = Bundle().apply {
    putString("id", id)
    putDouble("colaboracion", colaboracion)
    putBoolean("hecho", hecho)
    putInt("diaId", dia.id)
    putString("semanaKey", semanaKey)
    putString("nombreSocio", nombreSocio)
    putString("notas", notas)
    putLong("timestamp", timestamp)
}

internal fun Bundle.toSocio() = Socio(
    id = requireNotNull(getString("id")),
    colaboracion = getDouble("colaboracion"),
    hecho = getBoolean("hecho"),
    dia = DiaSemana.fromId(getInt("diaId")),
    semanaKey = requireNotNull(getString("semanaKey")),
    nombreSocio = getString("nombreSocio").orEmpty(),
    notas = getString("notas").orEmpty(),
    timestamp = getLong("timestamp")
)
