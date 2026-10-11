package com.julen.socios.data.backup

import com.julen.socios.model.BonoRegaloInfo
import com.julen.socios.model.DatosBackup
import com.julen.socios.model.DiaSemana
import com.julen.socios.model.IrpfVigencia
import com.julen.socios.model.ResultadoLectura
import com.julen.socios.model.Socio
import com.julen.socios.util.DateUtils
import com.julen.socios.util.ReparacionSemanaKeys
import java.time.ZoneId
import java.util.UUID

/** Conversión y validación entre el formato de copia de seguridad y el modelo de dominio. */
object ConversionBackup {

    /** v1: socios y bonos. v2: añade las vigencias de IRPF. */
    const val VERSION_ACTUAL = 2

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
        irpfVigencias = datos.irpfVigencias.map { IrpfVigenciaDto(it.desdeSemanaKey, it.porcentaje) }
    )

    /**
     * Convierte un DTO leído de un archivo en datos válidos, descartando los registros incompletos o
     * incoherentes y corrigiendo las claves de semana generadas por versiones antiguas de la app.
     */
    fun desdeDto(
        dto: BackupDto,
        ahora: Long = System.currentTimeMillis(),
        zona: ZoneId = ZoneId.systemDefault()
    ): ResultadoLectura {
        var descartados = 0

        val socios = dto.socios.orEmpty().mapNotNull { socioDto ->
            socioDto?.let { aSocio(it, ahora) } ?: run { descartados++; null }
        }
        val bonos = dto.bonosRegalo.orEmpty().mapNotNull { bonoDto ->
            bonoDto?.let(::aBono) ?: run { descartados++; null }
        }
        val vigencias = dto.irpfVigencias.orEmpty().mapNotNull { vigenciaDto ->
            vigenciaDto?.let(::aVigencia) ?: run { descartados++; null }
        }

        val reparacion = ReparacionSemanaKeys.calcular(
            socios = socios.map { ReparacionSemanaKeys.SocioRef(it.id, it.semanaKey, it.timestamp) },
            bonoKeys = bonos.map { it.semanaKey }.toSet(),
            zona = zona
        )
        val sociosReparados = socios.map { socio ->
            reparacion.socios[socio.id]?.let { socio.copy(semanaKey = it) } ?: socio
        }
        val bonosReparados = bonos.map { bono ->
            reparacion.bonos[bono.semanaKey]?.let { bono.copy(semanaKey = it) } ?: bono
        }

        return ResultadoLectura(
            datos = DatosBackup(
                // Si un archivo repite un registro prevalece el último, igual que al guardarlo en la BD.
                socios = sociosReparados.associateBy { it.id }.values.toList(),
                bonosRegalo = bonosReparados.associateBy { it.semanaKey }.values.toList(),
                irpfVigencias = vigencias.associateBy { it.desdeSemanaKey }.values.toList()
            ),
            descartados = descartados
        )
    }

    private fun aSocio(dto: SocioDto, ahora: Long): Socio? {
        val colaboracion = dto.colaboracion?.takeIf { it.isFinite() && it > 0 } ?: return null
        val dia = dto.diaSemanaId?.let(DiaSemana::fromIdOrNull) ?: return null
        val semanaKey = dto.semanaKey?.trim()?.takeIf(DateUtils::esSemanaKeyValida) ?: return null
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
        val semanaKey = dto.semanaKey?.trim()?.takeIf(DateUtils::esSemanaKeyValida) ?: return null
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
        val desde = dto.desdeSemanaKey?.trim()?.takeIf(DateUtils::esSemanaKeyValida) ?: return null
        val porcentaje = dto.porcentaje?.takeIf { it.isFinite() && it in 0.0..100.0 } ?: return null
        return IrpfVigencia(desde, porcentaje)
    }
}
