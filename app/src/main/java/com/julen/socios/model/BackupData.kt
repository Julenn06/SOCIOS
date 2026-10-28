package com.julen.socios.model

/** Conjunto de datos que se exporta o importa en una copia de seguridad. */
data class DatosBackup(
    val socios: List<Socio> = emptyList(),
    val bonosRegalo: List<BonoRegaloInfo> = emptyList(),
    val irpfVigencias: List<IrpfVigencia> = emptyList(),
    val cobros: List<CobroInfo> = emptyList()
) {
    val estaVacio: Boolean
        get() = socios.isEmpty() && bonosRegalo.isEmpty() && irpfVigencias.isEmpty() && cobros.isEmpty()
}

/**
 * Resultado de leer un archivo de importación: los datos válidos, cuántos registros se descartaron, el nombre
 * del archivo (si el sistema lo da) y cuándo se hizo la copia (solo las JSON lo guardan).
 */
data class ResultadoLectura(
    val datos: DatosBackup,
    val descartados: Int = 0,
    val nombreArchivo: String? = null,
    val fechaCopia: Long? = null
)

/**
 * Resumen de lo que hará una importación, para que el usuario lo confirme antes de aplicarla. [semanas] son las
 * que trae el archivo con algo (socios, bonus o lo cobrado), de [primeraSemana] a [ultimaSemana].
 */
data class ImportPreview(
    val sociosNuevos: Int,
    val sociosSobrescritos: Int,
    val bonos: Int,
    val irpfVigencias: Int,
    val cobros: Int,
    val descartados: Int,
    val semanas: Int = 0,
    val primeraSemana: String? = null,
    val ultimaSemana: String? = null,
    val nombreArchivo: String? = null,
    val fechaCopia: Long? = null
) {
    val socios: Int get() = sociosNuevos + sociosSobrescritos
}
