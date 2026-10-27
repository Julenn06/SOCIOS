package com.julen.socios

/**
 * De qué va un aviso, para su icono, su color y cuánto tiempo se queda (ver [Avisos]). Lo que solo
 * confirma algo que se ve en pantalla dura poco; lo que se puede deshacer o hay que leer, más.
 */
enum class TipoAviso(val duracionMs: Int) {
    EXITO(2500),
    INFO(3500),
    PENDIENTE(2500),
    BORRADO(5000),
    ERROR(5500),
    NOVEDAD(10000)
}
