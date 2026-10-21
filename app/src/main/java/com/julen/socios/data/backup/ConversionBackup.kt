package com.julen.socios.data.backup

import com.julen.socios.model.BonoRegaloInfo
import com.julen.socios.model.DatosBackup
import com.julen.socios.model.DevolucionInfo
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

    /**
     * v1: socios y bonos. v2: añade las vigencias de IRPF y calcula las claves de semana con el año ISO.
     * v3: añade las devoluciones.
     */
    const val VERSION_ACTUAL = 3

    /** Primera versión cuyas claves de semana son siempre correctas: las de antes pueden venir con el error del cambio de año. */
    private const val VERSION_CLAVES_ISO = 2

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
        devoluciones = datos.devoluciones.map { DevolucionDto(it.semanaKey, it.monto, it.nota) }
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
        val clavesFiables = (dto.version ?: 0) >= VERSION_CLAVES_ISO

        // Cada socio con la fecha con la que decidir si su clave de semana es del cálculo antiguo. Sin ella
        // (archivo nuevo, o fila sin fecha que tomaría la de hoy) solo se corrigen las claves imposibles.
        val leidos = dto.socios.orEmpty().mapNotNull { socioDto ->
            socioDto?.let { leido -> aSocio(leido, ahora)?.let { it to leido.timestamp.takeUnless { clavesFiables } } }
                ?: run { descartados++; null }
        }
        val socios = leidos.map { it.first }
        val bonos = dto.bonosRegalo.orEmpty().mapNotNull { bonoDto ->
            bonoDto?.let(::aBono) ?: run { descartados++; null }
        }
        val vigencias = dto.irpfVigencias.orEmpty().mapNotNull { vigenciaDto ->
            vigenciaDto?.let(::aVigencia) ?: run { descartados++; null }
        }
        // Las devoluciones llegaron en la v3, con las claves ya bien calculadas: no hace falta repararlas.
        val devoluciones = dto.devoluciones.orEmpty().mapNotNull { devolucionDto ->
            devolucionDto?.let(::aDevolucion) ?: run { descartados++; null }
        }

        val reparacion = ReparacionSemanaKeys.calcular(
            socios = leidos.map { (socio, creadoEl) -> ReparacionSemanaKeys.SocioRef(socio.id, socio.semanaKey, creadoEl) },
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
                irpfVigencias = vigencias.associateBy { it.desdeSemanaKey }.values.toList(),
                devoluciones = devoluciones.associateBy { it.semanaKey }.values.toList()
            ),
            descartados = descartados
        )
    }

    /**
     * La clave de semana ya validada. Una "AAAA-W53" imposible de las versiones antiguas se corrige antes de
     * validarla: si no, se descartaría el registro en lugar de moverlo a su semana.
     */
    private fun claveValida(texto: String?): String? =
        texto?.trim()
            ?.let { ReparacionSemanaKeys.claveImposibleCorregida(it) ?: it }
            ?.takeIf(DateUtils::esSemanaKeyValida)

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

    private fun aDevolucion(dto: DevolucionDto): DevolucionInfo? {
        val semanaKey = claveValida(dto.semanaKey) ?: return null
        val monto = dto.monto?.takeIf { it.isFinite() && it > 0 } ?: return null
        return DevolucionInfo(semanaKey = semanaKey, monto = monto, nota = dto.nota.orEmpty())
    }
}
