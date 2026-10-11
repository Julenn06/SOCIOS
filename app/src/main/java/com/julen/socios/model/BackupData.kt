package com.julen.socios.model

/** Conjunto de datos que se exporta o importa en una copia de seguridad. */
data class DatosBackup(
    val socios: List<Socio> = emptyList(),
    val bonosRegalo: List<BonoRegaloInfo> = emptyList(),
    val irpfVigencias: List<IrpfVigencia> = emptyList()
) {
    val estaVacio: Boolean
        get() = socios.isEmpty() && bonosRegalo.isEmpty() && irpfVigencias.isEmpty()
}

/** Resultado de leer un archivo de importación: los datos válidos y cuántos registros se descartaron. */
data class ResultadoLectura(
    val datos: DatosBackup,
    val descartados: Int = 0
)

/** Resumen de lo que hará una importación, para que el usuario lo confirme antes de aplicarla. */
data class ImportPreview(
    val sociosNuevos: Int,
    val sociosSobrescritos: Int,
    val bonos: Int,
    val irpfVigencias: Int,
    val descartados: Int
)
