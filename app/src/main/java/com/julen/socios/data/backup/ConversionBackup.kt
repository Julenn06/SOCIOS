package com.julen.socios.data.backup

import com.julen.socios.model.BonoRegaloInfo
import com.julen.socios.model.CobroInfo
import com.julen.socios.model.DatosBackup
import com.julen.socios.model.DiaSemana
import com.julen.socios.model.IrpfVigencia
import com.julen.socios.model.ResultadoLectura
import com.julen.socios.model.Socio
import com.julen.socios.util.DateUtils
import java.util.UUID

/** Conversión y validación entre el formato de copia de seguridad y el modelo de dominio. */
object ConversionBackup {

    /**
     * v1: socios y bonos. v2: añade las vigencias de IRPF y calcula las claves de semana con el año ISO.
     * v3: añade las devoluciones (lo descontado). v4: las sustituye por lo cobrado de verdad cada semana.
     *
     * Las copias se leen tal cual: las claves de semana de la v1 no se corrigen y las devoluciones de la v3 no
     * se convierten (cuentan como registros que no se importan, así el resumen avisa de ellas).
     */
    const val VERSION_ACTUAL = 4

    fun aDto(datos: DatosBackup, timestamp: Long = System.currentTimeMillis()): BackupDto = BackupDto(
        version = VERSION_ACTUAL,
        timestamp = timestamp,
        socios = datos.socios.map { socio ->
            SocioDto(
                id = socio.id,
                colaboracion = socio.colaboracion,
                hecho = socio.hecho,
                diaSemanaId = socio.dia.id,
                semanaKey = socio.semanaKey,
                nombreSocio = socio.nombreSocio,
                notas = socio.notas,
                timestamp = socio.timestamp
            )
        },
        bonosRegalo = datos.bonosRegalo.map { bono ->
            BonoRegaloDto(bono.semanaKey, bono.activo, bono.esMontoFijo, bono.monto, bono.nota)
        },
        irpfVigencias = datos.irpfVigencias.map { IrpfVigenciaDto(it.desdeSemanaKey, it.porcentaje) },
        cobros = datos.cobros.map { CobroDto(it.semanaKey, it.netoCobrado, it.nota) }
    )

    /** Convierte un DTO leído de un archivo en datos válidos, descartando los registros incompletos o incoherentes. */
    fun desdeDto(dto: BackupDto, ahora: Long = System.currentTimeMillis()): ResultadoLectura {
        var descartados = dto.devoluciones?.size ?: 0

        // Los registros válidos; los nulos o incompletos se descartan y se cuentan.
        fun <D : Any, T : Any> List<D?>?.validos(convertir: (D) -> T?): List<T> =
            orEmpty().mapNotNull { registro -> registro?.let(convertir) ?: run { descartados++; null } }

        val socios = dto.socios.validos { aSocio(it, ahora) }
        val bonos = dto.bonosRegalo.validos(::aBono)
        val vigencias = dto.irpfVigencias.validos(::aVigencia)
        val cobros = dto.cobros.validos(::aCobro)

        // Si un archivo repite un registro prevalece el último, igual que al guardarlo en la BD.
        return ResultadoLectura(
            datos = DatosBackup(
                socios = socios.associateBy { it.id }.values.toList(),
                bonosRegalo = bonos.associateBy { it.semanaKey }.values.toList(),
                irpfVigencias = vigencias.associateBy { it.desdeSemanaKey }.values.toList(),
                cobros = cobros.associateBy { it.semanaKey }.values.toList()
            ),
            descartados = descartados,
            fechaCopia = dto.timestamp
        )
    }

    /** La clave de semana sin espacios, o null si no es una semana ISO válida. */
    private fun claveValida(texto: String?): String? = texto?.trim()?.takeIf(DateUtils::esSemanaKeyValida)

    private fun aSocio(dto: SocioDto, ahora: Long): Socio? {
        val colaboracion = dto.colaboracion?.takeIf { it.isFinite() && it > 0 } ?: return null
        val dia = dto.diaSemanaId?.let(DiaSemana::fromIdOrNull) ?: return null
        val semanaKey = claveValida(dto.semanaKey) ?: return null
        return Socio(
            id = dto.id?.trim()?.takeIf { it.isNotEmpty() } ?: UUID.randomUUID().toString(),
            colaboracion = colaboracion,
            hecho = dto.hecho ?: true,
            dia = dia,
            semanaKey = semanaKey,
            nombreSocio = dto.nombreSocio.orEmpty(),
            notas = dto.notas.orEmpty(),
            timestamp = dto.timestamp ?: ahora
        )
    }

    private fun aBono(dto: BonoRegaloDto): BonoRegaloInfo? {
        val semanaKey = claveValida(dto.semanaKey) ?: return null
        val monto = dto.monto?.takeIf { it.isFinite() && it >= 0 } ?: return null
        return BonoRegaloInfo(
            semanaKey = semanaKey,
            activo = dto.activo ?: true,
            esMontoFijo = dto.esMontoFijo ?: true,
            monto = monto,
            nota = dto.nota.orEmpty()
        )
    }

    private fun aVigencia(dto: IrpfVigenciaDto): IrpfVigencia? {
        val desde = claveValida(dto.desdeSemanaKey) ?: return null
        val porcentaje = dto.porcentaje?.takeIf { it.isFinite() && it in 0.0..100.0 } ?: return null
        return IrpfVigencia(desde, porcentaje)
    }

    private fun aCobro(dto: CobroDto): CobroInfo? {
        val semanaKey = claveValida(dto.semanaKey) ?: return null
        // Puede ser negativo: así quedaban las semanas de la 1.9.0 con más devoluciones que ganancias.
        val neto = dto.netoCobrado?.takeIf { it.isFinite() } ?: return null
        return CobroInfo(semanaKey = semanaKey, netoCobrado = neto, nota = dto.nota.orEmpty())
    }
}
